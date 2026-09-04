package com.example.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import com.example.data.repository.SettingsRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderDataFlowTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = SettingsRepository(context)
    }

    @Test
    fun testLastReadChapterPersistence() {
        assertNull(repository.getLastReadChapterId("book_123"))

        repository.setLastReadChapterId("book_123", "ch_456")
        assertEquals("ch_456", repository.getLastReadChapterId("book_123"))

        repository.setLastReadChapterId("book_123", "ch_789")
        assertEquals("ch_789", repository.getLastReadChapterId("book_123"))
    }

    @Test
    fun testLastReadParagraphPersistence() {
        assertEquals(0, repository.getLastReadParagraphIndex("book_123", "ch_456"))

        repository.setLastReadParagraphIndex("book_123", "ch_456", 14)
        assertEquals(14, repository.getLastReadParagraphIndex("book_123", "ch_456"))

        // Boundary check: negative values should coerce to 0
        repository.setLastReadParagraphIndex("book_123", "ch_456", -5)
        assertEquals(0, repository.getLastReadParagraphIndex("book_123", "ch_456"))
    }

    @Test
    fun testOnlyTranslatedEnglishTextDisplayed() {
        // Chunk 1 translated, Chunk 2 untranslated (has only Chinese sourceText)
        val chunk1 = TranslationChunkEntity(
            id = "c1",
            jobId = "j1",
            bookId = "b1",
            chapterId = "ch1",
            chapterOrder = 0,
            chunkOrder = 0,
            chunkType = "CHAPTER_BODY",
            sourceText = "这是第一段中文测试。",
            translatedText = "<p>This is the first translated English paragraph.</p>\n<p>And here is the second sentence.</p>",
            status = "COMPLETED"
        )
        val chunk2 = TranslationChunkEntity(
            id = "c2",
            jobId = "j1",
            bookId = "b1",
            chapterId = "ch1",
            chapterOrder = 0,
            chunkOrder = 1,
            chunkType = "CHAPTER_BODY",
            sourceText = "这是未翻译的第二段中文。",
            translatedText = null, // Untranslated
            status = "PENDING"
        )

        val bodyChunks = listOf(chunk1, chunk2).filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
        val extractedParagraphs = mutableListOf<String>()
        for (chunk in bodyChunks) {
            val text = chunk.translatedText?.takeIf { it.isNotBlank() } ?: continue
            val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
            for (p in rawParas) {
                val clean = p.replace(Regex("<[^>]+>"), "").trim()
                if (clean.isNotBlank()) {
                    extractedParagraphs.add(clean)
                }
            }
        }

        // Verify only translated English paragraphs are present
        assertEquals(2, extractedParagraphs.size)
        assertEquals("This is the first translated English paragraph.", extractedParagraphs[0])
        assertEquals("And here is the second sentence.", extractedParagraphs[1])

        // Verify zero Chinese characters leaked into the paragraphs
        for (para in extractedParagraphs) {
            assertFalse(para.any { it.code in 0x4e00..0x9fff })
        }
    }

    @Test
    fun testUntranslatedChapterFallbackEnglishMessage() {
        val chunk = TranslationChunkEntity(
            id = "c1",
            jobId = "j1",
            bookId = "b1",
            chapterId = "ch1",
            chapterOrder = 0,
            chunkOrder = 0,
            chunkType = "CHAPTER_BODY",
            sourceText = "第一章未翻译内容。",
            translatedText = null,
            status = "PENDING"
        )

        val bodyChunks = listOf(chunk)
        val extractedParagraphs = mutableListOf<String>()
        for (c in bodyChunks) {
            val text = c.translatedText?.takeIf { it.isNotBlank() } ?: continue
            val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
            for (p in rawParas) {
                val clean = p.replace(Regex("<[^>]+>"), "").trim()
                if (clean.isNotBlank()) extractedParagraphs.add(clean)
            }
        }

        val finalParagraphs = if (extractedParagraphs.isNotEmpty()) {
            extractedParagraphs
        } else {
            listOf("This chapter has not been translated yet. Please wait for translation to complete.")
        }

        assertEquals(1, finalParagraphs.size)
        assertEquals("This chapter has not been translated yet. Please wait for translation to complete.", finalParagraphs[0])
        assertFalse(finalParagraphs[0].any { it.code in 0x4e00..0x9fff })
    }

    @Test
    fun testChapterTitleChineseSuppression() {
        val rawChineseTitle = "第1章 宗门风云"
        val resolvedWithoutTranslation = if (rawChineseTitle.any { it.code in 0x4e00..0x9fff }) {
            "Chapter 1"
        } else {
            rawChineseTitle
        }
        assertEquals("Chapter 1", resolvedWithoutTranslation)

        val englishTitle = "Chapter 1 - The Sect"
        val resolvedWithEnglish = if (englishTitle.any { it.code in 0x4e00..0x9fff }) {
            "Chapter 1"
        } else {
            englishTitle
        }
        assertEquals("Chapter 1 - The Sect", resolvedWithEnglish)
    }

    @Test
    fun testChapterNavigationBoundaryDeterminism() {
        val chapters = listOf(
            ChapterEntity(id = "ch1", bookId = "b1", chapterOrder = 0, title = "Chapter 1", originalHref = "ch1.html"),
            ChapterEntity(id = "ch2", bookId = "b1", chapterOrder = 1, title = "Chapter 2", originalHref = "ch2.html"),
            ChapterEntity(id = "ch3", bookId = "b1", chapterOrder = 2, title = "Chapter 3", originalHref = "ch3.html")
        )

        // Case 1: First Chapter
        val idx0 = chapters.indexOfFirst { it.id == "ch1" }
        val prev0 = if (idx0 > 0) chapters[idx0 - 1] else null
        val next0 = if (idx0 >= 0 && idx0 < chapters.size - 1) chapters[idx0 + 1] else null
        assertNull("First chapter should not have a previous chapter", prev0)
        assertNotNull("First chapter should have a next chapter", next0)
        assertEquals("ch2", next0?.id)

        // Case 2: Middle Chapter
        val idx1 = chapters.indexOfFirst { it.id == "ch2" }
        val prev1 = if (idx1 > 0) chapters[idx1 - 1] else null
        val next1 = if (idx1 >= 0 && idx1 < chapters.size - 1) chapters[idx1 + 1] else null
        assertNotNull(prev1)
        assertNotNull(next1)
        assertEquals("ch1", prev1?.id)
        assertEquals("ch3", next1?.id)

        // Case 3: Last Chapter
        val idx2 = chapters.indexOfFirst { it.id == "ch3" }
        val prev2 = if (idx2 > 0) chapters[idx2 - 1] else null
        val next2 = if (idx2 >= 0 && idx2 < chapters.size - 1) chapters[idx2 + 1] else null
        assertNotNull(prev2)
        assertEquals("ch2", prev2?.id)
        assertNull("Last chapter should not have a next chapter", next2)

        // Case 4: Non-existent Chapter
        val idxNone = chapters.indexOfFirst { it.id == "unknown" }
        val prevNone = if (idxNone > 0) chapters[idxNone - 1] else null
        val nextNone = if (idxNone >= 0 && idxNone < chapters.size - 1) chapters[idxNone + 1] else null
        assertNull(prevNone)
        assertNull(nextNone)
    }
}
