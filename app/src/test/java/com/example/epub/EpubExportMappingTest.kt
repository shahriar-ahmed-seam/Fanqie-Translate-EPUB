package com.example.epub

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.BookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import com.example.data.db.TranslationJobEntity
import com.example.translation.TranslationQuality
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Exports of books whose chapter files have non-ASCII names (hrefs URL-encoded in the OPF). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EpubExportMappingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun writeEpub(file: File) {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="id">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>夜书</dc:title><dc:language>zh</dc:language></metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="c1" href="Text/%E7%AC%AC1%E7%AB%A0.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="c1"/></spine>
            </package>"""
        val ncx = """<?xml version="1.0" encoding="utf-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><docTitle><text>夜书</text></docTitle>
              <navMap><navPoint id="n1" playOrder="1"><navLabel><text>第1章 夜</text></navLabel><content src="Text/第1章.xhtml"/></navPoint></navMap>
            </ncx>"""
        val chapter = """<?xml version="1.0" encoding="utf-8"?><html xmlns="http://www.w3.org/1999/xhtml"><head><title>第1章 夜</title></head>
            <body><h1>第1章 夜</h1><p>他推开门走了进去。</p><p>屋里一片漆黑。</p></body></html>"""
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", container)
            put("OEBPS/content.opf", opf)
            put("OEBPS/toc.ncx", ncx)
            // Like the Fanqie engine's: the book title as the page title and heading.
            put(
                "OEBPS/nav.xhtml",
                """<?xml version="1.0" encoding="UTF-8"?><html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
                <head><title>夜书</title></head><body><nav epub:type="toc" id="toc"><h1>夜书</h1><h2>目录</h2>
                <ol><li><a href="Text/第1章.xhtml">第1章 夜</a></li></ol></nav></body></html>"""
            )
            put("OEBPS/Text/第1章.xhtml", chapter)
        }
    }

    @Test
    fun chaptersWithEncodedHrefsAreTranslatedInTheExport() = runBlocking {
        val source = temp.newFile("source.epub").also(::writeEpub)
        val info = EpubParser.parseQuickInfo(source)
        val parsed = ZipFile(source).use { EpubParser.extractChapter(it, info.opfDirectory, info.spine[0], 0) }
        assertEquals(listOf("第1章 夜", "他推开门走了进去。", "屋里一片漆黑。"), parsed.translatableParagraphs)

        db.bookDao().insertBook(
            BookEntity(id = "b", title = "夜书", author = "", description = "", coverPath = null, originalUri = "x",
                originalFileName = "x.epub", chapterCount = 1, totalChunks = 3)
        )
        db.jobDao().insertJob(TranslationJobEntity(id = "j", bookId = "b", status = "COMPLETED", totalChunks = 3))
        db.chapterDao().insertChapters(
            listOf(ChapterEntity(id = "c1", bookId = "b", chapterOrder = 0, originalHref = info.spine[0].href, title = parsed.title, chunkCount = 2))
        )
        db.chunkDao().insertChunks(
            listOf(
                TranslationChunkEntity(id = "t", jobId = "j", bookId = "b", chapterId = "METADATA_TITLE", chapterOrder = -1, chunkOrder = 0,
                    chunkType = "TITLE", sourceText = "夜书", translatedText = "Night Book", status = "COMPLETED"),
                TranslationChunkEntity(id = "ct", jobId = "j", bookId = "b", chapterId = "c1", chapterOrder = 0, chunkOrder = 0,
                    chunkType = "CHAPTER_TITLE", sourceText = "第1章 夜", translatedText = "Chapter 1 Night", status = "COMPLETED"),
                TranslationChunkEntity(id = "cb", jobId = "j", bookId = "b", chapterId = "c1", chapterOrder = 0, chunkOrder = 1,
                    chunkType = "CHAPTER_BODY", sourceText = parsed.translatableParagraphs.joinToString("\n\n"),
                    translatedText = "Chapter 1 Night\n\nHe pushed the door open and went in.\n\nThe room was pitch dark.", status = "COMPLETED")
            )
        )

        val out = File(temp.root, "out.epub")
        EpubRebuilder.rebuild(source, "b", "j", db, out)

        ZipFile(out).use { zip ->
            val chapter = zip.getInputStream(zip.getEntry("OEBPS/Text/第1章.xhtml")).bufferedReader().readText()
            val doc = Jsoup.parse(chapter)
            assertEquals("Chapter 1 Night", doc.select("h1").text())
            assertEquals(listOf("He pushed the door open and went in.", "The room was pitch dark."), doc.select("p").map { it.text() })
            assertEquals("no Chinese left in the chapter", 0, TranslationQuality.hanCount(doc.body().text()))

            val ncx = Jsoup.parse(zip.getInputStream(zip.getEntry("OEBPS/toc.ncx")).bufferedReader().readText(), "", Parser.xmlParser())
            assertEquals("Chapter 1 Night", ncx.select("navLabel > text").text())
            assertEquals("Night Book", ncx.select("docTitle > text").text())

            val nav = Jsoup.parse(zip.getInputStream(zip.getEntry("OEBPS/nav.xhtml")).bufferedReader().readText())
            assertEquals("Night Book", nav.title())
            assertEquals("Night Book", nav.select("h1").text())
            assertEquals("Contents", nav.select("h2").text())
            assertEquals("Chapter 1 Night", nav.select("nav a").text())
            assertEquals(0, TranslationQuality.hanCount(nav.title() + nav.body().text()))
        }
    }

    @Test
    fun hrefsAreDecodedWithoutTurningPlusIntoSpaces() {
        assertEquals("Text/第1章.xhtml", EpubRebuilder.hrefKey("Text/%E7%AC%AC1%E7%AB%A0.xhtml#top"))
        assertEquals("a+b.xhtml", EpubRebuilder.percentDecode("a+b.xhtml"))
        assertEquals("100%.xhtml", EpubRebuilder.percentDecode("100%.xhtml"))
        assertTrue(EpubRebuilder.percentDecode("%F0%A0%80%80.xhtml").startsWith("\uD840\uDC00"))
    }
}
