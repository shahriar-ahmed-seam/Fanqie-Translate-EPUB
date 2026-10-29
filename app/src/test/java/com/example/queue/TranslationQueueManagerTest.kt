package com.example.queue

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.BookEntity
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationChunkEntity
import com.example.data.db.TranslationJobEntity
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import com.example.translation.TranslationException
import com.example.translation.TranslationProvider
import com.example.translation.TranslationQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

/** The real queue (workers, retries, automatic rounds, start-up repair) with a fake translation service. */
@RunWith(RobolectricTestRunner::class)
// A plain Application: the app's own (queue, TTS) isn't needed and would keep running after the test.
@Config(sdk = [34], application = android.app.Application::class)
class TranslationQueueManagerTest {
    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private var manager: TranslationQueueManager? = null

    private val bodyZh = listOf("他推开门走了进去，屋里一片漆黑。", "桌子上放着一封信，信封已经发黄了。")
    private val bodyEn = listOf("He pushed the door open and went in; the room was pitch dark.", "A letter lay on the table; its envelope had yellowed.")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(context)
        settings.updateSettings(AppSettings(workerCount = 2, maxActiveBooks = 2, maxRetries = 2))
    }

    @After
    fun tearDown() {
        manager?.close()
        db.close()
    }

    /** Answers per source text; anything else is translated by [default]. */
    private class FakeProvider(private val default: (String) -> Result<String>) : TranslationProvider {
        val calls = ConcurrentHashMap<String, Int>()
        override val name = "fake"
        override suspend fun translate(text: String, sourceLang: String, targetLang: String): Result<String> {
            calls.merge(text, 1, Int::plus)
            return default(text)
        }
    }

    private fun maintenance(repairDone: Boolean, improveDone: Boolean = true) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("quality_repair_v1_done", repairDone)
            .putBoolean("quality_improve_v1_done", improveDone)
            .commit()
    }

    private fun start(provider: TranslationProvider, online: Boolean = true) {
        manager = TranslationQueueManager(
            context, db, settings,
            providerFactory = { _, _ -> provider },
            retrySleep = {},
            isOnline = { online },
            maintenancePrefsName = PREFS
        )
    }

    private suspend fun book(status: String, chunks: List<TranslationChunkEntity>) {
        db.bookDao().insertBook(
            BookEntity(id = "b", title = "书", author = "", description = "", coverPath = null, originalUri = "x",
                originalFileName = "x.epub", chapterCount = 1, totalChunks = chunks.size)
        )
        db.chapterDao().insertChapters(listOf(ChapterEntity(id = "ch", bookId = "b", chapterOrder = 0, originalHref = "c.xhtml", title = "第1章 空屋", chunkCount = chunks.size)))
        db.chunkDao().insertChunks(chunks)
        db.jobDao().insertJob(TranslationJobEntity(id = "j", bookId = "b", status = status, totalChunks = chunks.size))
    }

    private fun chunk(id: String, type: String, order: Int, source: String, translated: String? = null, status: String = "PENDING") =
        TranslationChunkEntity(
            id = id, jobId = "j", bookId = "b", chapterId = "ch", chapterOrder = 0, chunkOrder = order,
            chunkType = type, sourceText = source, translatedText = translated, status = status
        )

    private val title = chunk("t", "CHAPTER_TITLE", 0, "第1章 空屋")
    private val body = chunk("c", "CHAPTER_BODY", 1, bodyZh.joinToString("\n\n"))

    private suspend fun awaitJob(vararg statuses: String): TranslationJobEntity = withTimeout(20_000) {
        while (true) {
            val job = db.jobDao().getJobById("j")
            if (job != null && job.status in statuses) return@withTimeout job
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun english(text: String): Result<String> = when (text) {
        "第1章 空屋" -> Result.success("Chapter 1 Empty House")
        bodyZh.joinToString("\n\n") -> Result.success(bodyEn.joinToString("\n\n"))
        else -> bodyZh.indexOf(text).takeIf { it >= 0 }?.let { Result.success(bodyEn[it]) }
            ?: Result.failure(TranslationException("unexpected: $text"))
    }

    @Test
    fun aBookIsTranslatedAndCompleted() = runBlocking {
        maintenance(repairDone = true)
        book("QUEUED", listOf(title, body))
        start(FakeProvider(::english))
        awaitJob("COMPLETED")
        assertEquals("Chapter 1 Empty House", db.chunkDao().getChunkById("t")!!.translatedText)
        assertEquals(bodyEn.joinToString("\n\n"), db.chunkDao().getChunkById("c")!!.translatedText)
    }

    @Test
    fun anEmptyAnswerIsNeverSavedAndIsTriedAgain() = runBlocking {
        maintenance(repairDone = true)
        book("QUEUED", listOf(title, body))
        var emptyAnswers = 2
        start(FakeProvider { text ->
            if (text == body.sourceText && emptyAnswers-- > 0) Result.success("[\"\"]") else english(text)
        })
        awaitJob("COMPLETED")
        assertEquals(bodyEn.joinToString("\n\n"), db.chunkDao().getChunkById("c")!!.translatedText)
    }

    @Test
    fun aTitleGoogleAlwaysSendsBackDoesNotFailTheBook() = runBlocking {
        maintenance(repairDone = true)
        book("QUEUED", listOf(title, body))
        // The provider reports an echoed title as a soft failure, as TomatoMTLProvider does.
        start(FakeProvider { text ->
            if (text == "第1章 空屋") Result.failure(TranslationException("Google returned an empty or untranslated answer", isSoftFailure = true))
            else english(text)
        })
        val job = awaitJob("COMPLETED", "FAILED")
        assertEquals("COMPLETED", job.status)
        // The original is kept; the reader shows "Chapter N" for it.
        assertEquals("第1章 空屋", db.chunkDao().getChunkById("t")!!.translatedText)
        assertNull(TranslationQuality.usableTitle(db.chunkDao().getChunkById("t")!!.translatedText, "第1章 空屋"))
    }

    @Test
    fun bodyTextGoogleNeverTranslatesIsKeptAfterTheAutomaticRounds() = runBlocking {
        maintenance(repairDone = true)
        book("QUEUED", listOf(title, body))
        val provider = FakeProvider { text ->
            if (text in bodyZh || text == body.sourceText) Result.success("[\"\"]") else english(text)
        }
        start(provider)
        val job = awaitJob("COMPLETED", "FAILED")
        assertEquals("a book whose text Google won't translate still completes, as before", "COMPLETED", job.status)
        assertEquals(body.sourceText, db.chunkDao().getChunkById("c")!!.translatedText)
        // Two tries per round (maxRetries = 2): the first claim plus two automatic rounds.
        assertEquals(6, provider.calls[body.sourceText])
    }

    @Test
    fun networkFailuresStillFailTheBookAfterTheAutomaticRounds() = runBlocking {
        maintenance(repairDone = true)
        book("QUEUED", listOf(title, body))
        start(FakeProvider { text ->
            if (text == body.sourceText) Result.failure(TranslationException("DNS failure (cannot resolve host)")) else english(text)
        })
        val job = awaitJob("FAILED")
        assertTrue(job.errorMessage!!.contains("1 chunks failed"))
        assertEquals("FAILED", db.chunkDao().getChunkById("c")!!.status)
        assertNull("nothing made up is stored", db.chunkDao().getChunkById("c")!!.translatedText)
    }

    @Test
    fun emptyChunksFromOlderVersionsAreTranslatedAgainAtStartUp() = runBlocking {
        maintenance(repairDone = false)
        book(
            "COMPLETED",
            listOf(
                title.copy(translatedText = "Chapter 1 Empty House", status = "COMPLETED"),
                body.copy(translatedText = "[\"\"]", status = "COMPLETED")
            )
        )
        start(FakeProvider(::english))
        withTimeout(20_000) {
            while (db.chunkDao().getChunkById("c")!!.translatedText != bodyEn.joinToString("\n\n")) delay(20)
        }
        awaitJob("COMPLETED")
        assertTrue(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("quality_repair_v1_done", false))
    }

    @Test
    fun theStartUpRepairWaitsForAConnection() = runBlocking {
        maintenance(repairDone = false)
        book("COMPLETED", listOf(title.copy(translatedText = "Chapter 1 Empty House", status = "COMPLETED"), body.copy(translatedText = "[\"\"]", status = "COMPLETED")))
        start(FakeProvider(::english), online = false)
        delay(500)
        assertEquals("the book isn't touched while offline", "COMPLETED", db.jobDao().getJobById("j")!!.status)
        assertEquals("COMPLETED", db.chunkDao().getChunkById("c")!!.status)
        assertTrue(!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("quality_repair_v1_done", false))
    }

    @Test
    fun aFinishedBookWithChunksStillWaitingIsQueuedAgain() = runBlocking {
        maintenance(repairDone = true)
        // As if the app stopped in the middle of "Retry failed".
        book("FAILED", listOf(title.copy(translatedText = "Chapter 1 Empty House", status = "COMPLETED"), body))
        start(FakeProvider(::english))
        awaitJob("COMPLETED")
        assertEquals(bodyEn.joinToString("\n\n"), db.chunkDao().getChunkById("c")!!.translatedText)
    }

    private companion object {
        /** Not the app's own flags (Robolectric also starts the app, with its own queue). */
        const val PREFS = "translation_maintenance_test"
    }
}
