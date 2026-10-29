package com.example.translation

import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.TranslatorApplication
import com.example.epub.EpubRebuilder
import com.example.queue.ChunkTranslator
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Real translation against Google's endpoints. Needs network; run with `-e live true`.
 * Checks both endpoints' answers are parsed cleanly, then translates a small book through the
 * app's real queue and exports it: no empty `[""]` chunks, no Chinese left, titles translated.
 */
@RunWith(AndroidJUnit4::class)
class TranslationLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val live get() = InstrumentationRegistry.getArguments().getString("live") == "true"

    @Test
    fun bothEndpointsGiveCleanTranslations() = runBlocking<Unit> {
        assumeTrue(live)
        val provider = TomatoMTLProvider()
        val primary = provider.executeTomatoMtlGoogleEngine("第1章 空屋", "zh-CN", "en")
        val fallback = provider.executeFallbackEngine("第1章 空屋", "zh-CN", "en")
        Log.i(TAG, "primary='$primary' fallback='$fallback'")
        for (text in listOf(primary, fallback)) {
            assertEquals(text, 0, TranslationQuality.hanCount(text))
            assertFalse("no language codes glued on: $text", text.contains("zh-CN"))
            assertTrue(text, text.contains("Chapter 1", ignoreCase = true))
        }
        val chunk = CHAPTER_ONE.drop(1).joinToString("\n\n")
        val translated = ChunkTranslator { provider }.translate("CHAPTER_BODY", chunk).getOrThrow()
        Log.i(TAG, "chunk -> $translated")
        assertTrue(TranslationQuality.check(chunk, translated).isClean)
    }

    @Test
    fun aBookTranslatesAndExportsWithoutJunkOrChinese() = runBlocking<Unit> {
        assumeTrue(live)
        val app = context.applicationContext as TranslatorApplication
        val source = File(context.cacheDir, "live_translation_test.epub").also(::writeBook)
        val (book, job) = app.queueManager.enqueueEpub(Uri.fromFile(source), "live_translation_test.epub")
        try {
            val finished = withTimeout(240_000) {
                while (true) {
                    val current = app.database.jobDao().getJobById(job.id)!!
                    if (current.status == "COMPLETED" || current.status == "FAILED") return@withTimeout current
                    delay(1_000)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            val chunks = app.database.chunkDao().getChunksByJob(job.id)
            chunks.forEach { Log.i(TAG, "${it.chunkType} ${it.status}: ${it.translatedText?.take(300)}") }
            assertEquals(finished.errorMessage, "COMPLETED", finished.status)
            for (chunk in chunks) {
                val text = chunk.translatedText
                assertTrue("usable: $text", TranslationQuality.usable(text, chunk.sourceText) == text?.trim())
                if (chunk.chunkType == "CHAPTER_TITLE" || chunk.chunkType == "TITLE") {
                    assertEquals("title translated: $text", 0, TranslationQuality.hanCount(text!!))
                } else {
                    assertTrue("clean: $text", TranslationQuality.check(chunk.sourceText, text!!).isClean)
                }
            }

            val out = File(context.cacheDir, "live_translation_test_en.epub")
            EpubRebuilder.rebuild(File(context.filesDir, "books/${book.id}/source.epub"), book.id, job.id, app.database, out)
            ZipFile(out).use { zip ->
                for (name in listOf("OEBPS/ch1.xhtml", "OEBPS/第2章.xhtml", "OEBPS/ch3.xhtml")) {
                    val html = zip.getInputStream(zip.getEntry(name)).bufferedReader().readText()
                    val doc = Jsoup.parse(html)
                    Log.i(TAG, "$name: ${doc.body().text().take(400)}")
                    assertEquals("$name has no Chinese left: ${doc.body().text()}", 0, TranslationQuality.hanCount(doc.body().text()))
                    assertEquals(0, TranslationQuality.hanCount(doc.title()))
                }
                val ncx = Jsoup.parse(zip.getInputStream(zip.getEntry("OEBPS/toc.ncx")).bufferedReader().readText(), "", Parser.xmlParser())
                val labels = ncx.select("navLabel > text").map { it.text() }
                Log.i(TAG, "toc: $labels")
                assertTrue(labels.toString(), labels.all { TranslationQuality.hanCount(it) == 0 })
            }
            out.delete()
        } finally {
            app.queueManager.deleteBookAndJob(book.id)
            source.delete()
        }
    }

    /** Exports a book already in the library (`-e bookId <id>`) and checks every chapter file. */
    @Test
    fun exportOfALibraryBookHasNoChineseOrJunk() = runBlocking<Unit> {
        val bookId = InstrumentationRegistry.getArguments().getString("bookId")
        assumeTrue(live && bookId != null)
        val app = context.applicationContext as TranslatorApplication
        val job = app.database.jobDao().getJobByBookId(bookId!!)!!
        val out = File(context.cacheDir, "export_scan.epub")
        EpubRebuilder.rebuild(File(context.filesDir, "books/$bookId/source.epub"), bookId, job.id, app.database, out)
        var chapters = 0
        val problems = mutableListOf<String>()
        ZipFile(out).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.name.endsWith(".xhtml") && !entry.name.endsWith(".html") && !entry.name.endsWith(".ncx")) continue
                val text = zip.getInputStream(entry).bufferedReader().readText()
                // The author's name is kept as it is (the library shows it that way too).
                val visible = if (entry.name.endsWith(".ncx")) {
                    Jsoup.parse(text, "", Parser.xmlParser()).apply { select("docAuthor").remove() }.text()
                } else {
                    Jsoup.parse(text).let { it.title() + " " + it.body().text() }
                }
                chapters++
                val han = TranslationQuality.hanCount(visible)
                if (han > 0 || visible.contains("[\"\"]") || visible.contains("zh-CN")) problems += "${entry.name}: $han Chinese characters: ${visible.take(200)}"
            }
        }
        Log.i(TAG, "export of $bookId: $chapters files, ${problems.size} with problems, ${out.length()} bytes")
        problems.take(20).forEach { Log.w(TAG, it) }
        out.delete()
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun writeBook(file: File) {
        fun chapter(title: String, paragraphs: List<String>) =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>$title</title></head><body>" +
                "<h1>$title</h1>" + paragraphs.joinToString("") { "<p>$it</p>" } + "</body></html>"
        val lineChapter = "<?xml version=\"1.0\" encoding=\"utf-8\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>第3章 青石镇</title></head><body>" +
            "<h2>第3章 青石镇</h2><div>" + CHAPTER_THREE_LINES.joinToString("<br/>") + "</div></body></html>"
        val opf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>雨夜来客</dc:title><dc:creator>测试</dc:creator>
                <dc:description>一个关于等待与寻找的故事。</dc:description><dc:language>zh</dc:language></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="%E7%AC%AC2%E7%AB%A0.xhtml" media-type="application/xhtml+xml"/>
                <item id="c3" href="ch3.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/><itemref idref="c2"/><itemref idref="c3"/></spine>
            </package>"""
        val ncx = """<?xml version="1.0" encoding="utf-8"?><ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
            <docTitle><text>雨夜来客</text></docTitle><navMap>
            <navPoint id="n1" playOrder="1"><navLabel><text>${CHAPTER_ONE[0]}</text></navLabel><content src="ch1.xhtml"/></navPoint>
            <navPoint id="n2" playOrder="2"><navLabel><text>${CHAPTER_TWO[0]}</text></navLabel><content src="第2章.xhtml"/></navPoint>
            <navPoint id="n3" playOrder="3"><navLabel><text>第3章 青石镇</text></navLabel><content src="ch3.xhtml"/></navPoint>
            </navMap></ncx>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", opf)
            put("OEBPS/toc.ncx", ncx)
            put("OEBPS/ch1.xhtml", chapter(CHAPTER_ONE[0], CHAPTER_ONE.drop(1)))
            put("OEBPS/第2章.xhtml", chapter(CHAPTER_TWO[0], CHAPTER_TWO.drop(1)))
            put("OEBPS/ch3.xhtml", lineChapter)
        }
    }

    private companion object {
        const val TAG = "TranslationLiveTest"

        val CHAPTER_ONE = listOf(
            "第1章 雨夜来客",
            "雨下了一整夜，街上几乎看不到行人。",
            "林川坐在窗边，手里捧着一杯已经凉掉的茶。",
            "“你还在等他吗？”母亲从厨房里探出头来问道。",
            "“嗯，他说过今晚一定会回来。”林川轻声回答。",
            "墙上的老钟敲了十一下，声音在空荡荡的屋子里回响。",
            "【系统提示：检测到附近有陌生人靠近。】",
            "他猛地站起身，推开门，冷风夹着雨点扑面而来。",
            "门外站着一个穿着黑色雨衣的男人，帽檐压得很低，看不清他的脸。",
            "“请问，这里是林家吗？”男人的声音沙哑而低沉。",
            "林川犹豫了一下，点了点头。",
            "男人从怀里掏出一封信，信封上写着三个字：林川收。",
            "……",
            "雨还在下，远处传来了几声狗叫。"
        )

        val CHAPTER_TWO = listOf(
            "第2章 旧信",
            "信纸已经发黄，边角有些破损，看得出来已经放了很多年。",
            "林川小心翼翼地展开信纸，借着昏暗的灯光读了起来。",
            "“川儿，当你看到这封信的时候，我可能已经不在了。”",
            "他的手开始微微发抖，眼眶也渐渐湿润了。",
            "信的最后写着一个地址：青石镇，东街七十二号。",
            "第二天一早，他收拾好行李，踏上了前往青石镇的长途汽车。",
            "车窗外的风景飞快地后退，田野、村庄、河流，一切都显得那么陌生。",
            "坐在他旁边的老人忽然开口：“年轻人，你也是去青石镇的？”",
            "“是的，大爷，您也去那里？”",
            "老人笑了笑，没有回答，只是望向窗外。"
        )

        val CHAPTER_THREE_LINES = listOf(
            "青石镇比他想象中还要小，一条主街从镇口一直延伸到河边。",
            "街道两旁是低矮的老房子，屋檐下挂着褪色的红灯笼。",
            "他按照信上的地址，一路打听，终于找到了东街七十二号。",
            "那是一间破旧的木屋，门上的锁已经生锈了。",
            "他推开门，一股潮湿的霉味扑面而来，屋里的家具都盖着厚厚的灰尘。",
            "桌上放着一张泛黄的照片，照片里的人正对着他微笑。"
        )
    }
}
