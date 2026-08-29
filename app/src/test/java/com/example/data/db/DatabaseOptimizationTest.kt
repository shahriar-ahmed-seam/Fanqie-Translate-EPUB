package com.example.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseOptimizationTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testTargetedChunkQueriesForLargeNovels() = runBlocking {
        val bookId = "book-large-1"
        val jobId = "job-large-1"

        // Insert book and job
        db.bookDao().insertBook(
            BookEntity(
                id = bookId,
                title = "A 5000 Chapter Novel",
                author = "Author",
                description = "Long description",
                coverPath = null,
                originalUri = "content://sample",
                originalFileName = "sample.epub",
                chapterCount = 5000,
                totalChunks = 15000
            )
        )

        db.jobDao().insertJob(
            TranslationJobEntity(
                id = jobId,
                bookId = bookId,
                status = "RUNNING",
                totalChunks = 15000
            )
        )

        // Insert chunks for chapter 0 and chapter 1
        val chunks = listOf(
            TranslationChunkEntity(
                id = "c-title",
                jobId = jobId,
                bookId = bookId,
                chapterId = "meta",
                chapterOrder = -1,
                chunkOrder = 0,
                chunkType = "TITLE",
                sourceText = "大主宰",
                translatedText = "The Great Ruler",
                status = "COMPLETED"
            ),
            TranslationChunkEntity(
                id = "c-desc",
                jobId = jobId,
                bookId = bookId,
                chapterId = "meta",
                chapterOrder = -1,
                chunkOrder = 1,
                chunkType = "DESCRIPTION",
                sourceText = "大千世界",
                translatedText = "The Great World",
                status = "COMPLETED"
            ),
            TranslationChunkEntity(
                id = "c-ch0-title",
                jobId = jobId,
                bookId = bookId,
                chapterId = "ch-0",
                chapterOrder = 0,
                chunkOrder = 0,
                chunkType = "CHAPTER_TITLE",
                sourceText = "第一章 北灵院",
                translatedText = "Chapter 1 Northern Spiritual Academy",
                status = "COMPLETED"
            ),
            TranslationChunkEntity(
                id = "c-ch0-body-1",
                jobId = jobId,
                bookId = bookId,
                chapterId = "ch-0",
                chapterOrder = 0,
                chunkOrder = 1,
                chunkType = "CHAPTER_BODY",
                sourceText = "清晨的阳光洒在大地上。",
                translatedText = "The morning sun shone on the ground.",
                status = "COMPLETED"
            ),
            TranslationChunkEntity(
                id = "c-ch1-title",
                jobId = jobId,
                bookId = bookId,
                chapterId = "ch-1",
                chapterOrder = 1,
                chunkOrder = 0,
                chunkType = "CHAPTER_TITLE",
                sourceText = "第二章 牧尘",
                translatedText = null,
                status = "PENDING"
            ),
            TranslationChunkEntity(
                id = "c-ch1-body-1",
                jobId = jobId,
                bookId = bookId,
                chapterId = "ch-1",
                chapterOrder = 1,
                chunkOrder = 1,
                chunkType = "CHAPTER_BODY",
                sourceText = "少年站立于山巅。",
                translatedText = null,
                status = "PENDING"
            )
        )

        db.chunkDao().insertChunks(chunks)

        // 1. Test targeted title & description
        val titleChunk = db.chunkDao().getTitleChunkByBook(bookId)
        assertEquals("The Great Ruler", titleChunk?.translatedText)

        val descChunk = db.chunkDao().getDescriptionChunkByBook(bookId)
        assertEquals("The Great World", descChunk?.translatedText)

        // 2. Test lightweight chapter titles projection
        val chapterTitles = db.chunkDao().getChapterTitlesByBook(bookId)
        assertEquals(2, chapterTitles.size)
        assertTrue(chapterTitles.any { it.chapterId == "ch-0" && it.translatedText == "Chapter 1 Northern Spiritual Academy" })

        // 3. Test chapter body progress aggregation
        val progress = db.chunkDao().getChapterBodyProgressByBook(bookId)
        assertEquals(2, progress.size)
        val ch0Prog = progress.first { it.chapterId == "ch-0" }
        assertEquals(1, ch0Prog.totalChunks)
        assertEquals(1, ch0Prog.completedChunks)

        // 4. Test bounded pending chunks
        val pending = db.chunkDao().getPendingChunksForJob(jobId, limit = 1)
        assertEquals(1, pending.size)
        assertEquals("c-ch1-title", pending[0].id)

        // 5. Test single chapter chunks
        val ch0Chunks = db.chunkDao().getChunksByJobAndChapter(jobId, "ch-0")
        assertEquals(2, ch0Chunks.size)

        // 6. Test chapter range chunks
        val rangeChunks = db.chunkDao().getChunksByJobAndChapterRange(jobId, fromChapterOrder = 0, toChapterOrder = 0)
        assertEquals(2, rangeChunks.size)

        // 7. Test atomic claim chunk
        val claimed = db.chunkDao().claimNextPendingChunkForJob(jobId)
        assertNotNull(claimed)
        assertEquals("c-ch1-title", claimed?.id)
        assertEquals("TRANSLATING", claimed?.status)

        // 8. Test count queries (no full list loading)
        assertEquals(6, db.chunkDao().getTotalChunkCount(jobId))
        assertEquals(4, db.chunkDao().getCompletedChunkCount(jobId))
        assertEquals(1, db.chunkDao().getTranslatingChunkCount(jobId))
        assertEquals(0, db.chunkDao().getFailedChunkCount(jobId))
        assertEquals(2, db.chunkDao().getIncompleteChunkCount(jobId))
    }
}
