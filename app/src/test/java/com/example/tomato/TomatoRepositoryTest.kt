package com.example.tomato

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.tomato.data.TomatoBookEntity
import com.example.tomato.data.TomatoDatabase
import com.example.tomato.data.TomatoLibraryKind
import com.example.tomato.data.TomatoState
import com.example.tomato.engine.EngineCallException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TomatoRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: TomatoDatabase
    private lateinit var engine: FakeEngine
    private lateinit var library: FakeLibrary
    private lateinit var scope: CoroutineScope
    private lateinit var repository: TomatoRepository

    private val book = TomatoBook(
        bookId = BOOK_ID, title = "十日终焉", author = "杀虫队队员", abstractText = "简介", coverUrl = "",
        category = "悬疑", serialStatus = "已完结", wordCount = 3_201_288, chapterCount = 3, readCount = 0, lastChapterTime = 0
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "tomato").deleteRecursively()
        database = TomatoDatabase.inMemory(context)
        engine = FakeEngine()
        library = FakeLibrary()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repository = TomatoRepository(context, database, engine, installer = null, library = library, scope = scope, pollIntervalMs = 20L)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun lookupByIdLinkAndTitle() = runBlocking {
        val byId = repository.lookup("https://fanqienovel.com/page/$BOOK_ID") as LookupResult.Found
        assertTrue(byId.byId)
        assertEquals("十日终焉", byId.books.single().title)

        val unknown = repository.lookup("7000000000000000001")
        assertTrue(unknown is LookupResult.NotFound)

        val byTitle = repository.lookup("十日终焉") as LookupResult.Found
        assertFalse(byTitle.byId)

        val reader = repository.lookup("https://fanqienovel.com/reader/7143039000000000001")
        assertTrue((reader as LookupResult.NotFound).message.contains("doesn't contain a novel ID"))
    }

    @Test
    fun laterListAddsAndRemoves() = runBlocking {
        repository.saveForLater(book)
        assertEquals(TomatoState.LATER, database.books().get(BOOK_ID)!!.state)
        repository.removeFromLater(BOOK_ID)
        assertNull(database.books().get(BOOK_ID))
    }

    @Test
    fun downloadRunsToAnEpubInAppStorage() = runBlocking {
        repository.saveForLater(book)
        assertTrue(repository.download(book).isSuccess)
        assertEquals("epub", engine.lastCreate!!.getString("file_format"))
        val done = awaitState(TomatoState.DOWNLOADED)
        val epub = File(done.epubPath!!)
        assertTrue(epub.isFile)
        assertEquals("$BOOK_ID.epub", epub.name)
        // The engine's working folder is cleaned up.
        assertTrue(epub.parentFile!!.listFiles()!!.none { it.isDirectory && it.name.startsWith("download-") })
        assertEquals(3, done.progressTotal)
        assertEquals(TomatoLibraryKind.NONE, done.libraryKind)
    }

    @Test
    fun failedRedownloadKeepsTheEarlierFile() = runBlocking {
        repository.download(book)
        val first = awaitState(TomatoState.DOWNLOADED)
        engine.failNextJob = true
        repository.redownload(BOOK_ID)
        val after = awaitMessage { it.message?.startsWith("Update failed") == true }
        assertEquals(TomatoState.DOWNLOADED, after.state)
        assertEquals(first.epubPath, after.epubPath)
        assertTrue(File(after.epubPath!!).isFile)
    }

    @Test
    fun startFailureIsShownAndCanBeRetried() = runBlocking {
        engine.rejectCreate = true
        assertTrue(repository.download(book).isFailure)
        val failed = database.books().get(BOOK_ID)!!
        assertEquals(TomatoState.FAILED, failed.state)
        assertTrue(failed.message!!.contains("network down"))
        engine.rejectCreate = false
        assertTrue(repository.redownload(BOOK_ID).isSuccess)
        awaitState(TomatoState.DOWNLOADED)
        Unit
    }

    @Test
    fun cancelStopsTheEngineJob() = runBlocking {
        engine.holdRunning = true
        repository.download(book)
        awaitState(TomatoState.DOWNLOADING)
        repository.cancel(BOOK_ID)
        assertEquals(TomatoState.CANCELED, database.books().get(BOOK_ID)!!.state)
        assertTrue(engine.canceled.isNotEmpty())
    }

    @Test
    fun downloadedBookGoesToTranslationOrBookshelf() = runBlocking {
        repository.download(book)
        awaitState(TomatoState.DOWNLOADED)
        assertTrue(repository.translate(BOOK_ID).isSuccess)
        val translated = database.books().get(BOOK_ID)!!
        assertEquals(TomatoLibraryKind.TRANSLATION, translated.libraryKind)
        assertEquals("十日终焉.epub", library.translated.single().second)
        // Deleting the library book clears the link.
        library.existing.clear()
        repository.verifyLibraryLinks()
        assertEquals(TomatoLibraryKind.NONE, database.books().get(BOOK_ID)!!.libraryKind)
        assertTrue(repository.addToBookshelf(BOOK_ID).isSuccess)
        assertEquals(TomatoLibraryKind.LOCAL, database.books().get(BOOK_ID)!!.libraryKind)
    }

    @Test
    fun deleteRemovesFileAndEntry() = runBlocking {
        repository.download(book)
        val done = awaitState(TomatoState.DOWNLOADED)
        repository.delete(BOOK_ID)
        assertNull(database.books().get(BOOK_ID))
        assertFalse(File(done.epubPath!!).exists())
        Unit
    }

    private suspend fun awaitState(state: String): TomatoBookEntity = awaitMessage { it.state == state }

    private suspend fun awaitMessage(predicate: (TomatoBookEntity) -> Boolean): TomatoBookEntity = withTimeout(10_000) {
        while (true) {
            val current = database.books().get(BOOK_ID)
            if (current != null && predicate(current)) return@withTimeout current
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private class FakeEngine : TomatoEngineGateway {
        var lastCreate: JSONObject? = null
        var rejectCreate = false
        var failNextJob = false
        var holdRunning = false
        val canceled = CopyOnWriteArrayList<String>()
        private val jobs = mutableMapOf<String, JSONObject>()
        private var polls = 0

        override suspend fun call(action: String, payload: JSONObject, timeoutMs: Long): String = synchronized(this) {
            when (action) {
                "book_detail" -> {
                    if (payload.getString("book_id") != BOOK_ID) throw EngineCallException("书籍不存在")
                    """{"book_id":"$BOOK_ID","book_name":"十日终焉","author":"杀虫队队员","chapter_count":3,"status":"已完结"}"""
                }
                "search" -> """{"items":[{"book_id":"$BOOK_ID","book_name":"十日终焉","author":"杀虫队队员"}]}"""
                "create_download" -> {
                    if (rejectCreate) throw EngineCallException("network down")
                    lastCreate = payload
                    val id = "job-${jobs.size + 1}"
                    val job = JSONObject().put("id", id).put("book_id", payload.getString("book_id")).put("status", "queued")
                        .put("save_dir", payload.getString("save_dir")).put("fail", failNextJob)
                    failNextJob = false
                    jobs[id] = job
                    job.toString()
                }
                "list_jobs" -> {
                    polls++
                    val items = JSONArray()
                    jobs.values.forEach { job ->
                        when {
                            job.optString("status") in setOf("canceled", "done", "failed") -> Unit
                            holdRunning || polls % 2 == 1 -> job.put("status", "running").put("progress", 33).put("progress_done", 1).put("progress_total", 3)
                            job.optBoolean("fail") -> job.put("status", "failed").put("message", "章节下载失败")
                            else -> {
                                val out = File(job.getString("save_dir"), "十日终焉 - 杀虫队队员.epub")
                                writeEpub(out)
                                job.put("status", "done").put("progress", 100).put("progress_done", 3).put("progress_total", 3)
                                    .put("output_path", out.absolutePath)
                            }
                        }
                        items.put(job)
                    }
                    JSONObject().put("items", items).toString()
                }
                "cancel_job" -> {
                    val id = payload.getString("job_id")
                    canceled.add(id)
                    jobs[id]?.put("status", "canceled")
                    "{}"
                }
                else -> "{}"
            }
        }

        override suspend fun shutdown() {}

        private fun writeEpub(file: File) {
            file.parentFile?.mkdirs()
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("mimetype")); zip.write("application/epub+zip".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("META-INF/container.xml")); zip.write("<container/>".toByteArray()); zip.closeEntry()
            }
        }
    }

    private class FakeLibrary : TomatoLibraryActions {
        val translated = CopyOnWriteArrayList<Pair<File, String>>()
        val existing = mutableSetOf<String>()
        override suspend fun addToBookshelf(epub: File, fileName: String): String = "local-1".also { existing.add(it) }
        override suspend fun translate(epub: File, fileName: String): String {
            translated.add(epub to fileName)
            return "translated-1".also { existing.add(it) }
        }
        override suspend fun exists(libraryBookId: String): Boolean = libraryBookId in existing
    }

    private companion object {
        const val BOOK_ID = "7143038691944959011"
    }
}
