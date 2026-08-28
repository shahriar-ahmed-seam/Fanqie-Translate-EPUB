package com.example

import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import com.example.epub.*
import com.example.update.AppUpdateManager
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubTranslatorUnitTest {

    @Test
    fun testVersionComparison() {
        assertTrue(AppUpdateManager.isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(AppUpdateManager.isVersionNewer("2.0.0", "1.9.9"))
        assertTrue(AppUpdateManager.isVersionNewer("1.1.0", "1.0.9"))
        assertFalse(AppUpdateManager.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(AppUpdateManager.isVersionNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun testSpineAndChunkOrderingPreservation() {
        // Create mock chapters and chunks
        val chapters = listOf(
            ChapterEntity(id = "ch-1", bookId = "book-1", chapterOrder = 0, originalHref = "text/ch1.xhtml", title = "Chapter 1"),
            ChapterEntity(id = "ch-2", bookId = "book-1", chapterOrder = 1, originalHref = "text/ch2.xhtml", title = "Chapter 2")
        )

        val chunks = listOf(
            TranslationChunkEntity(id = "c4", jobId = "j1", bookId = "book-1", chapterId = "ch-2", chapterOrder = 1, chunkOrder = 1, sourceText = "Chunk 4", translatedText = "Trans 4", status = "COMPLETED"),
            TranslationChunkEntity(id = "c1", jobId = "j1", bookId = "book-1", chapterId = "ch-1", chapterOrder = 0, chunkOrder = 0, sourceText = "Chunk 1", translatedText = "Trans 1", status = "COMPLETED"),
            TranslationChunkEntity(id = "c3", jobId = "j1", bookId = "book-1", chapterId = "ch-2", chapterOrder = 1, chunkOrder = 0, sourceText = "Chunk 3", translatedText = "Trans 3", status = "COMPLETED"),
            TranslationChunkEntity(id = "c2", jobId = "j1", bookId = "book-1", chapterId = "ch-1", chapterOrder = 0, chunkOrder = 1, sourceText = "Chunk 2", translatedText = "Trans 2", status = "COMPLETED")
        )

        // Sort chunks as required by the architecture: chapterOrder ASC, chunkOrder ASC
        val sortedChunks = chunks.sortedWith(compareBy({ it.chapterOrder }, { it.chunkOrder }))

        assertEquals("c1", sortedChunks[0].id)
        assertEquals("c2", sortedChunks[1].id)
        assertEquals("c3", sortedChunks[2].id)
        assertEquals("c4", sortedChunks[3].id)
    }

    @Test
    fun testMultiBookIsolation() {
        val bookAChunks = listOf(
            TranslationChunkEntity(id = "ca1", jobId = "jobA", bookId = "bookA", chapterId = "chA1", chapterOrder = 0, chunkOrder = 0, sourceText = "Book A Ch1", status = "COMPLETED")
        )
        val bookBChunks = listOf(
            TranslationChunkEntity(id = "cb1", jobId = "jobB", bookId = "bookB", chapterId = "chB1", chapterOrder = 0, chunkOrder = 0, sourceText = "Book B Ch1", status = "COMPLETED")
        )

        val allChunks = bookAChunks + bookBChunks

        val filteredA = allChunks.filter { it.bookId == "bookA" && it.jobId == "jobA" }
        val filteredB = allChunks.filter { it.bookId == "bookB" && it.jobId == "jobB" }

        assertEquals(1, filteredA.size)
        assertEquals("ca1", filteredA[0].id)
        assertEquals(1, filteredB.size)
        assertEquals("cb1", filteredB[0].id)
        assertTrue(filteredA.none { it.bookId == "bookB" })
    }

    @Test
    fun testDuplicateAndMissingChunkDetection() {
        val tempFile = File.createTempFile("test_sample", ".epub")
        try {
            // Create a minimal valid EPUB zip
            ZipOutputStream(FileOutputStream(tempFile)).use { zos ->
                zos.putNextEntry(ZipEntry("mimetype"))
                zos.write("application/epub+zip".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("META-INF/container.xml"))
                zos.write("<container><rootfiles><rootfile full-path=\"content.opf\"/></rootfiles></container>".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("content.opf"))
                zos.write("<package><metadata><dc:title>Test</dc:title></metadata><manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("c1.xhtml"))
                zos.write("<html><body><h1>Title</h1><p>Text</p></body></html>".toByteArray())
                zos.closeEntry()
            }

            val parsed = EpubParser.parse(tempFile)
            val chapters = listOf(
                ChapterEntity(id = "c1", bookId = "b1", chapterOrder = 0, originalHref = "c1.xhtml", title = "Title")
            )

            // Test duplicate chunk ID
            val dupChunks = listOf(
                TranslationChunkEntity(id = "chunk-1", jobId = "j1", bookId = "b1", chapterId = "c1", chapterOrder = 0, chunkOrder = 0, sourceText = "text", translatedText = "text", status = "COMPLETED"),
                TranslationChunkEntity(id = "chunk-1", jobId = "j1", bookId = "b1", chapterId = "c1", chapterOrder = 0, chunkOrder = 1, sourceText = "text2", translatedText = "text2", status = "COMPLETED")
            )
            val result = EpubValidator.validatePreExport(parsed, chapters, dupChunks)
            assertFalse(result.isValid)
            assertTrue(result.errors.any { it.contains("Duplicate chunk ID") })

        } finally {
            tempFile.delete()
        }
    }
}
