package com.example.queue

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.BookEntity
import com.example.data.db.TranslationChunkEntity
import com.example.data.db.TranslationJobEntity
import com.example.translation.TranslationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own (queue, TTS) isn't needed and would keep running after the test.
@Config(sdk = [34], application = android.app.Application::class)
class StoredTranslationRepairTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun book(id: String, jobStatus: String, startedAt: Long = 1L) {
        db.bookDao().insertBook(
            BookEntity(
                id = id, title = "书", author = "作者", description = "", coverPath = null, originalUri = "x",
                originalFileName = "x.epub", chapterCount = 2, totalChunks = 0
            )
        )
        db.jobDao().insertJob(TranslationJobEntity(id = "job-$id", bookId = id, status = jobStatus, progress = 1f, startedAt = startedAt, exportedUri = "/cache/$id.epub"))
    }

    private fun chunk(id: String, book: String, order: Int, type: String, source: String, translated: String?) = TranslationChunkEntity(
        id = id, jobId = "job-$book", bookId = book, chapterId = "ch$order", chapterOrder = order, chunkOrder = if (type == "CHAPTER_TITLE") 0 else 1,
        chunkType = type, sourceText = source, translatedText = translated, status = "COMPLETED"
    )

    private val good = "He looked at the rain outside the window, wondering what to do tomorrow."
    private val partial = "The first paragraph is long.\n\n第二段也是很长的文字。"

    @Test
    fun emptyChunksAreSentBackAndTheirBookIsQueuedBehindTheOthers() = runBlocking {
        book("a", "COMPLETED")
        db.chunkDao().insertChunks(
            listOf(
                chunk("empty", "a", 0, "CHAPTER_BODY", "他看着窗外的雨，心里想着明天该怎么办。", "[\"\"]"),
                chunk("title-code", "a", 1, "CHAPTER_TITLE", "第2章 风起", "Chapter 2 The Wind Riseszh-CN"),
                chunk("partial", "a", 1, "CHAPTER_BODY", "第一段很长的文字。\n\n第二段也是很长的文字。", partial),
                chunk("title-zh", "a", 2, "CHAPTER_TITLE", "第3章 夜", "第3章 夜"),
                chunk("good", "a", 2, "CHAPTER_BODY", "他看着窗外的雨，心里想着明天该怎么办。", good)
            )
        )

        val summary = StoredTranslationRepair(db).run()

        assertEquals(5, summary.scanned)
        assertEquals(1, summary.requeued)
        assertEquals(1, summary.cleaned)
        val empty = db.chunkDao().getChunkById("empty")!!
        assertEquals("PENDING", empty.status)
        assertNull(empty.translatedText)
        assertEquals("Chapter 2 The Wind Rises", db.chunkDao().getChunkById("title-code")!!.translatedText)
        // Chinese left in usable text is fixed in place later (StoredTranslationImprover), not here.
        assertEquals("COMPLETED", db.chunkDao().getChunkById("partial")!!.status)
        assertEquals(partial, db.chunkDao().getChunkById("partial")!!.translatedText)
        assertEquals("COMPLETED", db.chunkDao().getChunkById("title-zh")!!.status)
        assertEquals(good, db.chunkDao().getChunkById("good")!!.translatedText)

        val job = db.jobDao().getJobById("job-a")!!
        assertEquals("QUEUED", job.status)
        assertTrue("queued behind the books queued now", job.startedAt > 1L)
        assertNull("the next export is built again", job.exportedUri)

        // Running it again finds nothing more to do.
        val again = StoredTranslationRepair(db).run()
        assertEquals(0, again.requeued)
        assertEquals(0, again.cleaned)
    }

    @Test
    fun booksWithoutEmptyChunksKeepTheirState() = runBlocking {
        book("ok", "COMPLETED")
        db.chunkDao().insertChunks(listOf(chunk("p", "ok", 0, "CHAPTER_BODY", "第一段很长的文字。\n\n第二段也是很长的文字。", partial)))
        val summary = StoredTranslationRepair(db).run()
        assertEquals(0, summary.requeued)
        val job = db.jobDao().getJobById("job-ok")!!
        assertEquals("COMPLETED", job.status)
        assertEquals("/cache/ok.epub", job.exportedUri)
    }

    @Test
    fun pausedAndCancelledBooksKeepTheirState() = runBlocking {
        book("p", "PAUSED")
        book("c", "CANCELLED")
        db.chunkDao().insertChunks(
            listOf(
                chunk("p1", "p", 0, "CHAPTER_BODY", "他看着窗外的雨。", "[\"\"]"),
                chunk("c1", "c", 0, "CHAPTER_BODY", "他看着窗外的雨。", "[\"\"]")
            )
        )
        val summary = StoredTranslationRepair(db).run()
        assertEquals(setOf("job-p"), summary.requeuedJobs)
        assertEquals("PAUSED", db.jobDao().getJobById("job-p")!!.status)
        assertEquals("PENDING", db.chunkDao().getChunkById("p1")!!.status)
        assertEquals("COMPLETED", db.chunkDao().getChunkById("c1")!!.status)
        assertEquals("CANCELLED", db.jobDao().getJobById("job-c")!!.status)
    }

    @Test
    fun verdicts() {
        val ok = chunk("x", "a", 0, "CHAPTER_BODY", "他走了。", "He left.")
        assertEquals(StoredTranslationRepair.Verdict.Keep, StoredTranslationRepair.verdict(ok))
        val symbols = chunk("y", "a", 0, "CHAPTER_BODY", "……", "[\"\"]")
        assertEquals(StoredTranslationRepair.Verdict.Clean("……"), StoredTranslationRepair.verdict(symbols))
        val word = chunk("z", "a", 0, "CHAPTER_BODY", "石碑上写着三生石。", "The tablet reads \"三生石\".")
        assertEquals("fixed in place by the improver instead", StoredTranslationRepair.Verdict.Keep, StoredTranslationRepair.verdict(word))
    }

    @Test
    fun chineseLeftInSavedTranslationsIsFixedInPlace() = runBlocking {
        book("i", "COMPLETED")
        db.chunkDao().insertChunks(
            listOf(
                chunk("w", "i", 0, "CHAPTER_BODY", "石碑上写着三生石，他看了很久很久。", "The tablet reads \"三生石\"; he looked at it for a long time."),
                chunk("t", "i", 0, "CHAPTER_TITLE", "第1章 空屋", "Chapter 1 空屋"),
                chunk("stubborn", "i", 1, "CHAPTER_BODY", "他念了一句咒语：阿弥陀佛。", "He recited a mantra: 阿弥陀佛.")
            )
        )
        val provider = object : TranslationProvider {
            override val name = "fake"
            override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> = when (text) {
                "三生石" -> Result.success("Three Life Stones")
                "空屋" -> Result.success("Empty House")
                else -> Result.success(text) // never translates the rest
            }
        }
        var waits = 0
        val summary = StoredTranslationImprover(db, ChunkTranslator { provider }, waitUntilAllowed = { waits++ }).run()

        assertTrue(summary.finished)
        assertEquals(3, summary.checked)
        assertEquals(2, summary.improved)
        assertEquals(3, waits)
        assertEquals("The tablet reads \"Three Life Stones\"; he looked at it for a long time.", db.chunkDao().getChunkById("w")!!.translatedText)
        assertEquals("Chapter 1 Empty House", db.chunkDao().getChunkById("t")!!.translatedText)
        assertEquals("unchanged when nothing better comes back", "He recited a mantra: 阿弥陀佛.", db.chunkDao().getChunkById("stubborn")!!.translatedText)
        assertEquals("the book stays completed", "COMPLETED", db.jobDao().getJobById("job-i")!!.status)
        assertNull(db.jobDao().getJobById("job-i")!!.exportedUri)
    }
}
