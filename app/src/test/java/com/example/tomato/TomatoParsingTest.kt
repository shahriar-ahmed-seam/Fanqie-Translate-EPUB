package com.example.tomato

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Robolectric provides the real org.json implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TomatoParsingTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun bookIdsAreFoundInIdsLinksAndShareTexts() {
        assertEquals("7143038691944959011", TomatoIds.extract(" 7143038691944959011 "))
        assertEquals("7143038691944959011", TomatoIds.extract("https://fanqienovel.com/page/7143038691944959011?enter_from=search"))
        assertEquals("7143038691944959011", TomatoIds.extract("https://changdunovel.com/wap/share-v2.html?book_id=7143038691944959011&share_id=1"))
        assertEquals("7143038691944959011", TomatoIds.extract("《十日终焉》番茄小说 7143038691944959011"))
        // Reader links point at a chapter, not the book.
        assertNull(TomatoIds.extract("https://fanqienovel.com/reader/7143039000000000001"))
        assertNull(TomatoIds.extract("十日终焉"))
        assertNull(TomatoIds.extract("12345"))
        assertTrue(TomatoIds.looksLikeLink("https://fanqienovel.com/"))
    }

    @Test
    fun engineBookDetailIsParsed() {
        val json = JSONObject(
            """{"abstract":"简介","author":"杀虫队队员","book_id":"7143038691944959011","book_name":"十日终焉","category":"",
               "chapter_count":1496,"chapters":[],"cover_url":"https://p3.example/c.jpg","last_chapter_first_pass_time":1761919823,
               "read_count":"1711683","status":"已完结","title":"十日终焉","word_count":"3201288"}"""
        )
        val book = TomatoJson.parseBook(json)!!
        assertEquals("十日终焉", book.title)
        assertEquals(1496, book.chapterCount)
        assertEquals(3_201_288L, book.wordCount)
        assertEquals(1_711_683L, book.readCount)
        assertTrue(book.isCompleted)
        assertEquals("Completed", book.statusLabel())
        assertNull(TomatoJson.parseBook(JSONObject("""{"book_id":"abc","book_name":"x"}""")))
    }

    @Test
    fun engineJobsAreParsed() {
        val jobs = TomatoJson.parseJobs(
            JSONObject(
                """{"items":[{"id":"job-1","book_id":"7143038691944959011","status":"running","progress":10,"progress_total":3},
                   {"id":"job-2","book_id":"7143038691944959011","status":"partial","progress":100,"progress_done":2,"progress_total":3,
                    "output_path":"/x/a.epub","missing_chapters":[5]}]}"""
            )
        )
        assertEquals(2, jobs.size)
        assertFalse(jobs[0].isTerminal)
        assertTrue(jobs[1].isPartial && jobs[1].isTerminal)
        assertEquals(1, jobs[1].missingChapters)
        assertEquals("/x/a.epub", jobs[1].outputPath)
    }

    @Test
    fun engineTagsCompareByBuildDateAndRun() {
        assertTrue(TomatoEngineInstaller.isNewerTag("unsigned-v2026.10.26-0905-r761", "unsigned-v2026.10.25-1124-r760"))
        assertFalse(TomatoEngineInstaller.isNewerTag("unsigned-v2026.10.25-1124-r760", "unsigned-v2026.10.25-1124-r760"))
        assertFalse(TomatoEngineInstaller.isNewerTag("unsigned-v2026.10.24-1638-r759", "unsigned-v2026.10.25-1124-r760"))
        assertEquals("r760 · 2026-10-25", TomatoEngineInstaller.describeTag("unsigned-v2026.10.25-1124-r760"))
    }

    @Test
    fun releaseAssetsAreMatchedToProcessors() {
        val release = TomatoEngineInstaller.parseRelease(
            JSONObject(
                """{"tag_name":"unsigned-v2026.10.25-1124-r760","assets":[
                    {"name":"FanqieNovelDownloader-2026.10.25-1124-android-arm64-v8a.apk","browser_download_url":"https://x/a.apk","size":10,"digest":"sha256:${"a".repeat(64)}"},
                    {"name":"FanqieNovelDownloader-2026.10.25-1124-android-universal.apk","browser_download_url":"https://x/u.apk","size":20},
                    {"name":"latest.json","browser_download_url":"https://x/l.json","size":1}]}"""
            )
        )
        assertEquals(setOf("arm64-v8a"), release.assets.keys)
        assertEquals("a".repeat(64), release.assets.getValue("arm64-v8a").sha256)
    }

    @Test
    fun pinnedEngineCoversEveryPhoneProcessor() {
        assertEquals(TomatoEngineInstaller.SUPPORTED_ABIS.toSet(), TomatoEngineInstaller.PINNED_RELEASE.assets.keys)
        TomatoEngineInstaller.PINNED_RELEASE.assets.values.forEach { asset ->
            assertEquals(64, asset.sha256!!.length)
            assertTrue(asset.url.startsWith("https://github.com/POf-L/Fanqie-novel-Downloader/releases/download/"))
        }
    }

    @Test
    fun elfDynamicSymbolsAreRead() {
        val file = temp.newFile("libtest.so")
        writeMiniElf(file, listOf("Java_com_pofl_fanqienoveldownloader_Rust_ipc", "JNI_OnLoad"))
        val names = ElfSymbols.dynamicSymbolNames(file)
        assertNotNull(names)
        assertTrue("Java_com_pofl_fanqienoveldownloader_Rust_ipc" in names!!)
        assertTrue("JNI_OnLoad" in names)
        assertNull(ElfSymbols.dynamicSymbolNames(temp.newFile("not-elf.so").apply { writeText("hello") }))
    }

    /** Smallest ELF64 with a .dynsym section linked to a .dynstr string table. */
    private fun writeMiniElf(file: File, symbols: List<String>) {
        val strtab = ByteArray(1) + symbols.flatMap { (it.toByteArray() + 0).toList() }.toByteArray()
        val strtabOffset = 64L
        val dynsymOffset = strtabOffset + strtab.size
        val dynsymSize = 24L
        val shOffset = dynsymOffset + dynsymSize
        val buffer = ByteBuffer.allocate((shOffset + 3 * 64).toInt()).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        buffer.position(0x28); buffer.putLong(shOffset)
        buffer.position(0x3A); buffer.putShort(64); buffer.putShort(3)
        buffer.position(strtabOffset.toInt()); buffer.put(strtab)
        // Section 1: .dynstr (SHT_STRTAB = 3)
        buffer.position((shOffset + 64).toInt())
        buffer.putInt(0); buffer.putInt(3); buffer.putLong(0); buffer.putLong(0)
        buffer.putLong(strtabOffset); buffer.putLong(strtab.size.toLong()); buffer.putInt(0); buffer.putInt(0); buffer.putLong(1); buffer.putLong(0)
        // Section 2: .dynsym (SHT_DYNSYM = 11), link -> 1
        buffer.position((shOffset + 128).toInt())
        buffer.putInt(0); buffer.putInt(11); buffer.putLong(0); buffer.putLong(0)
        buffer.putLong(dynsymOffset); buffer.putLong(dynsymSize); buffer.putInt(1); buffer.putInt(0); buffer.putLong(8); buffer.putLong(24)
        file.writeBytes(buffer.array())
    }
}
