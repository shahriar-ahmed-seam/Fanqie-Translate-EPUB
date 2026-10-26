package com.example.tomato

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.diagnostics.AppDiagnostics
import com.example.tomato.data.TomatoBookEntity
import com.example.tomato.data.TomatoDatabase
import com.example.tomato.data.TomatoLibraryKind
import com.example.tomato.data.TomatoState
import com.example.tomato.engine.EngineCallException
import com.example.tomato.engine.EngineUnavailableException
import com.example.tomato.engine.TomatoEngineClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/** The engine, as the repository uses it (replaced by a fake in tests). */
interface TomatoEngineGateway {
    suspend fun call(action: String, payload: JSONObject = JSONObject(), timeoutMs: Long = 60_000L): String
    suspend fun shutdown()
}

/** What the Tomato tab can do with the app's library (replaced by a fake in tests). */
interface TomatoLibraryActions {
    /** Adds the EPUB as a local (Chinese) book; returns the library book id. */
    suspend fun addToBookshelf(epub: File, fileName: String): String
    /** Queues the EPUB for translation (this also adds it to the bookshelf); returns the library book id. */
    suspend fun translate(epub: File, fileName: String): String
    suspend fun exists(libraryBookId: String): Boolean
}

sealed interface EngineSetup {
    data object Checking : EngineSetup
    data class NotInstalled(val unsupportedAbi: Boolean) : EngineSetup
    data class Installing(val progress: Float?, val message: String) : EngineSetup
    data class Ready(val engine: InstalledEngine) : EngineSetup
    data class Failed(val message: String) : EngineSetup
}

sealed interface LookupResult {
    data class Found(val books: List<TomatoBook>, val byId: Boolean) : LookupResult
    data class NotFound(val message: String) : LookupResult
}

/**
 * Tomato (番茄小说) downloads: looks novels up through the engine, keeps the "download later" list,
 * runs downloads (raw Chinese EPUBs in app storage, not on the bookshelf), and hands finished books
 * to the bookshelf or the translation queue on request.
 */
class TomatoRepository(
    private val context: Context,
    private val database: TomatoDatabase,
    private val engine: TomatoEngineGateway,
    private val installer: TomatoEngineInstaller?,
    private val library: TomatoLibraryActions,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val pollIntervalMs: Long = 1_500L,
    /** Called when a download starts (the app starts its foreground service here). */
    private val onDownloadStarted: () -> Unit = {},
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val dao = database.books()
    private val root = File(context.filesDir, "tomato")
    private val pollLock = Mutex()
    private var pollJob: Job? = null

    private val _engineSetup = MutableStateFlow<EngineSetup>(EngineSetup.Checking)
    val engineSetup: StateFlow<EngineSetup> = _engineSetup.asStateFlow()

    val books: Flow<List<TomatoBookEntity>> = dao.observeAll()
    val activeDownloads: Flow<List<TomatoBookEntity>> = dao.observeActive()
    fun observe(bookId: String): Flow<TomatoBookEntity?> = dao.observe(bookId)
    suspend fun activeDownloadsOnce(): List<TomatoBookEntity> = dao.getActive()
    suspend fun observeOnce(bookId: String): TomatoBookEntity? = dao.get(bookId)

    init {
        refreshEngineSetup()
    }

    // ── Engine ────────────────────────────────────────────────────────────

    fun refreshEngineSetup() {
        val installer = installer ?: run {
            _engineSetup.value = EngineSetup.Ready(InstalledEngine("test", "test", "", "", 0))
            return
        }
        if (_engineSetup.value is EngineSetup.Installing) return
        val installed = installer.installed()
        _engineSetup.value = when {
            installed != null -> EngineSetup.Ready(installed)
            installer.deviceAbi() == null -> EngineSetup.NotInstalled(unsupportedAbi = true)
            else -> EngineSetup.NotInstalled(unsupportedAbi = false)
        }
    }

    /** Downloads and checks the pinned engine (first-time setup). */
    fun installEngine() {
        val installer = installer ?: return
        if (_engineSetup.value is EngineSetup.Installing) return
        _engineSetup.value = EngineSetup.Installing(null, "Downloading the engine…")
        scope.launch {
            try {
                val engine = installer.download(TomatoEngineInstaller.PINNED_RELEASE) { progress ->
                    _engineSetup.value = EngineSetup.Installing(progress, "Downloading the engine…")
                }
                _engineSetup.value = EngineSetup.Installing(null, "Starting the engine…")
                installer.makeCurrent(engine)
                this@TomatoRepository.engine.shutdown()
                // Start it once, so problems show up here and not on the first search.
                this@TomatoRepository.engine.call("list_jobs", JSONObject(), 30_000L)
                _engineSetup.value = EngineSetup.Ready(engine)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Engine install failed", e)
                AppDiagnostics.log("tomato", "Engine install failed: ${e.message}")
                val installedNow = installer.installed()
                _engineSetup.value = if (installedNow != null && e !is EngineInstallException) {
                    EngineSetup.Failed("The engine was installed but didn't start: ${e.message}")
                } else {
                    EngineSetup.Failed(e.message ?: "The engine couldn't be installed")
                }
            }
        }
    }

    /**
     * Looks for a newer upstream engine. A new engine is only kept if this app's runtime can start it;
     * otherwise the current one stays. Returns a message for the user.
     */
    suspend fun updateEngine(onProgress: (Float?) -> Unit): String = withContext(Dispatchers.IO) {
        val installer = installer ?: return@withContext "Not available"
        val current = installer.installed() ?: return@withContext "Install the engine first."
        if (dao.getActive().isNotEmpty()) return@withContext "Wait for the running downloads to finish first."
        val latest = installer.latestRelease()
        if (!TomatoEngineInstaller.isNewerTag(latest.tag, current.tag)) {
            return@withContext "The engine is up to date (${current.label})."
        }
        val candidate = installer.download(latest, onProgress)
        engine.shutdown()
        installer.makeCurrent(candidate)
        val works = try {
            engine.call("list_jobs", JSONObject(), 45_000L)
            true
        } catch (e: Exception) {
            Log.w(TAG, "New engine ${candidate.tag} didn't start", e)
            false
        }
        if (works) {
            _engineSetup.value = EngineSetup.Ready(candidate)
            "Updated the engine to ${candidate.label}."
        } else {
            engine.shutdown()
            installer.makeCurrent(current)
            installer.discard(candidate)
            _engineSetup.value = EngineSetup.Ready(current)
            AppDiagnostics.log("tomato", "Engine ${candidate.tag} rejected after a failed start; kept ${current.tag}")
            "The new engine (${candidate.label}) didn't work on this phone, so ${current.label} is still in use."
        }
    }

    suspend fun removeEngine() {
        engine.shutdown()
        installer?.uninstall()
        refreshEngineSetup()
    }

    // ── Lookup ────────────────────────────────────────────────────────────

    /** A novel ID or book link looks the book up directly; anything else searches by title. */
    suspend fun lookup(input: String): LookupResult = withContext(Dispatchers.IO) {
        val text = input.trim()
        if (text.isEmpty()) return@withContext LookupResult.NotFound("Enter a novel ID, link or title.")
        val id = TomatoIds.extract(text)
        if (id != null) {
            val book = try {
                fetchBook(id)
            } catch (e: EngineCallException) {
                null
            }
            return@withContext if (book != null) {
                LookupResult.Found(listOf(book), byId = true)
            } else {
                LookupResult.NotFound("No Fanqie novel has the ID $id.")
            }
        }
        if (TomatoIds.looksLikeLink(text)) {
            return@withContext LookupResult.NotFound("That link doesn't contain a novel ID. Open the book's page and copy that link.")
        }
        val results = TomatoJson.parseSearch(JSONObject(engine.call("search", JSONObject().put("q", text))))
        if (results.isEmpty()) LookupResult.NotFound("No novels found for \"$text\".") else LookupResult.Found(results, byId = false)
    }

    /** Full book info; null when Fanqie doesn't know the ID. */
    suspend fun fetchBook(bookId: String): TomatoBook? = withContext(Dispatchers.IO) {
        val reply = engine.call("book_detail", JSONObject().put("book_id", bookId).put("include_chapters", false))
        TomatoJson.parseBook(JSONObject(reply))?.takeIf { it.bookId == bookId && it.title.isNotBlank() }
    }

    /** Refreshes the stored info of a saved or downloaded book (title, chapter count...). */
    suspend fun refreshBookInfo(bookId: String) {
        val existing = dao.get(bookId) ?: return
        val book = try {
            fetchBook(bookId)
        } catch (e: Exception) {
            null
        } ?: return
        dao.upsert(existing.withBook(book))
    }

    // ── Later list ────────────────────────────────────────────────────────

    suspend fun saveForLater(book: TomatoBook) {
        val existing = dao.get(book.bookId)
        if (existing != null && existing.state != TomatoState.CANCELED && existing.state != TomatoState.FAILED) return
        dao.upsert(newEntity(book, TomatoState.LATER))
        cacheCover(book.bookId, book.coverUrl)
    }

    suspend fun removeFromLater(bookId: String) {
        val existing = dao.get(bookId) ?: return
        if (existing.state == TomatoState.LATER) dao.delete(bookId)
    }

    // ── Downloads ─────────────────────────────────────────────────────────

    /** Starts (or restarts) the download of [book]; returns false with a message when it couldn't. */
    suspend fun download(book: TomatoBook): Result<Unit> = startDownload(book.bookId, book)

    suspend fun redownload(bookId: String): Result<Unit> = startDownload(bookId, null)

    private suspend fun startDownload(bookId: String, book: TomatoBook?): Result<Unit> = withContext(Dispatchers.IO) {
        val existing = dao.get(bookId)
        if (existing != null && existing.state in TomatoState.ACTIVE) return@withContext Result.success(Unit)
        val base = when {
            book != null && existing != null -> existing.withBook(book)
            book != null -> newEntity(book, TomatoState.QUEUED)
            existing != null -> existing
            else -> return@withContext Result.failure(IllegalStateException("Unknown book"))
        }
        val queued = base.copy(
            state = TomatoState.QUEUED,
            jobId = null,
            progressPercent = 0,
            progressDone = 0,
            progressTotal = 0,
            message = "Starting…",
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(queued)
        cacheCover(bookId, queued.coverUrl)
        val saveDir = File(bookDir(bookId), "download-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val reply = engine.call(
                "create_download",
                JSONObject()
                    .put("book_id", bookId)
                    .put("book_name", queued.title)
                    .put("author", queued.author)
                    .put("book_input", bookId)
                    .put("save_dir", saveDir.absolutePath)
                    .put("file_format", "epub")
                    .put("overwrite_existing", true),
                90_000L
            )
            val job = TomatoJson.parseJob(JSONObject(reply))
                ?: throw EngineCallException("The engine didn't start the download")
            dao.upsert(
                (dao.get(bookId) ?: queued).copy(
                    state = TomatoState.DOWNLOADING,
                    jobId = job.id,
                    progressPercent = job.percent,
                    message = "Downloading…",
                    updatedAt = System.currentTimeMillis()
                )
            )
            AppDiagnostics.log("tomato", "Download started for $bookId (job ${job.id})")
            onDownloadStarted()
            ensurePolling()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            saveDir.deleteRecursively()
            settleFailure(bookId, readable(e))
            Result.failure(e)
        }
    }

    suspend fun cancel(bookId: String) = withContext(Dispatchers.IO) {
        val existing = dao.get(bookId) ?: return@withContext
        if (existing.state !in TomatoState.ACTIVE) return@withContext
        existing.jobId?.let { jobId ->
            try {
                engine.call("cancel_job", JSONObject().put("job_id", jobId), 20_000L)
            } catch (e: Exception) {
                Log.w(TAG, "cancel_job failed for $jobId", e)
            }
        }
        settleAfterStop(existing, TomatoState.CANCELED, "Download canceled")
    }

    /** Removes the book from the Tomato tab and deletes its downloaded file (the library copy stays). */
    suspend fun delete(bookId: String) = withContext(Dispatchers.IO) {
        val existing = dao.get(bookId) ?: return@withContext
        if (existing.state in TomatoState.ACTIVE) cancel(bookId)
        existing.jobId?.let { jobId ->
            try {
                engine.call("remove_job", JSONObject().put("job_id", jobId), 15_000L)
            } catch (e: Exception) {
                // The engine keeps its own history; that's fine.
            }
        }
        bookDir(bookId).deleteRecursively()
        dao.delete(bookId)
    }

    /** Resumes progress tracking after the app restarted mid-download. */
    fun resumeTracking() {
        scope.launch {
            if (dao.getActive().isNotEmpty()) {
                onDownloadStarted()
                ensurePolling()
            }
        }
    }

    private fun ensurePolling() {
        scope.launch {
            pollLock.withLock {
                if (pollJob?.isActive == true) return@withLock
                pollJob = scope.launch { pollLoop() }
            }
        }
    }

    private suspend fun pollLoop() {
        var failures = 0
        var missingRounds = 0
        while (scope.isActive) {
            val active = dao.getActive()
            if (active.isEmpty()) return
            try {
                val jobs = TomatoJson.parseJobs(JSONObject(engine.call("list_jobs", JSONObject(), 30_000L)))
                failures = 0
                var anyMissing = false
                for (book in active) {
                    val job = jobs.firstOrNull { it.id == book.jobId }
                        ?: jobs.filter { it.bookId == book.bookId }.lastOrNull().takeIf { book.jobId == null }
                    if (job == null) {
                        if (book.state == TomatoState.DOWNLOADING) anyMissing = true
                        continue
                    }
                    applyJob(book, job)
                }
                missingRounds = if (anyMissing) missingRounds + 1 else 0
                if (missingRounds >= MISSING_JOB_ROUNDS) {
                    dao.getActive().filter { it.state == TomatoState.DOWNLOADING }.forEach { book ->
                        if (jobs.none { it.id == book.jobId }) settleAfterStop(book, TomatoState.FAILED, "The download was interrupted. Try again.")
                    }
                    missingRounds = 0
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failures++
                Log.w(TAG, "Progress check failed ($failures)", e)
                if (failures >= MAX_POLL_FAILURES) {
                    dao.getActive().forEach { settleAfterStop(it, TomatoState.FAILED, "The Tomato engine stopped: ${readable(e)}") }
                    return
                }
            }
            delay(if (failures == 0) pollIntervalMs else pollIntervalMs * 2)
        }
    }

    private suspend fun applyJob(book: TomatoBookEntity, job: TomatoJob) {
        when {
            job.isFinishedOk || job.isPartial -> finishDownload(book, job)
            job.isFailed -> settleAfterStop(book, TomatoState.FAILED, job.message.ifBlank { "The download failed" })
            job.isCanceled -> settleAfterStop(book, TomatoState.CANCELED, "Download canceled")
            else -> {
                val total = if (job.total > 0) job.total else book.chapterCount
                dao.upsert(
                    book.copy(
                        state = TomatoState.DOWNLOADING,
                        jobId = job.id,
                        progressPercent = job.percent,
                        progressDone = job.done,
                        progressTotal = total,
                        message = if (job.isPaused) "Paused" else progressMessage(job.done, total),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private suspend fun finishDownload(book: TomatoBookEntity, job: TomatoJob) {
        val produced = job.outputPath?.let(::File)?.takeIf { it.isFile }
            ?: newestEpub(bookDir(book.bookId))
        if (produced == null || !isEpub(produced)) {
            settleAfterStop(book, TomatoState.FAILED, "The engine finished but no EPUB was saved")
            return
        }
        val target = File(bookDir(book.bookId), "${book.bookId}.epub")
        if (produced.absolutePath != target.absolutePath) {
            target.delete()
            if (!produced.renameTo(target)) produced.copyTo(target, overwrite = true)
        }
        // Old download folders (including a previous file) aren't needed any more.
        bookDir(book.bookId).listFiles()?.filter { it.isDirectory && it.name.startsWith("download-") }?.forEach { it.deleteRecursively() }
        val partial = job.isPartial || job.missingChapters > 0
        dao.upsert(
            book.copy(
                state = if (partial) TomatoState.PARTIAL else TomatoState.DOWNLOADED,
                jobId = job.id,
                progressPercent = 100,
                progressDone = job.done.takeIf { it > 0 } ?: book.progressDone,
                progressTotal = job.total.takeIf { it > 0 } ?: book.progressTotal,
                message = if (partial) "${job.missingChapters} chapters couldn't be downloaded" else null,
                epubPath = target.absolutePath,
                epubSizeBytes = target.length(),
                missingChapters = job.missingChapters,
                downloadedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
        AppDiagnostics.log("tomato", "Download finished for ${book.bookId} (${target.length()} bytes, missing ${job.missingChapters})")
    }

    /** A stopped download keeps an earlier file if there was one. */
    private suspend fun settleAfterStop(book: TomatoBookEntity, state: String, message: String) {
        val current = dao.get(book.bookId) ?: book
        bookDir(book.bookId).listFiles()?.filter { it.isDirectory && it.name.startsWith("download-") }?.forEach { it.deleteRecursively() }
        val keptFile = current.epubPath?.let(::File)?.takeIf { it.isFile }
        dao.upsert(
            if (keptFile != null) {
                current.copy(
                    state = if (current.missingChapters > 0) TomatoState.PARTIAL else TomatoState.DOWNLOADED,
                    message = if (state == TomatoState.CANCELED) null else "Update failed: $message",
                    updatedAt = System.currentTimeMillis()
                )
            } else {
                current.copy(state = state, message = message, updatedAt = System.currentTimeMillis())
            }
        )
    }

    private suspend fun settleFailure(bookId: String, message: String) {
        val current = dao.get(bookId) ?: return
        settleAfterStop(current, TomatoState.FAILED, message)
    }

    // ── Library ───────────────────────────────────────────────────────────

    suspend fun addToBookshelf(bookId: String): Result<String> = toLibrary(bookId, TomatoLibraryKind.LOCAL)

    suspend fun translate(bookId: String): Result<String> = toLibrary(bookId, TomatoLibraryKind.TRANSLATION)

    private suspend fun toLibrary(bookId: String, kind: String): Result<String> = withContext(Dispatchers.IO) {
        val book = dao.get(bookId) ?: return@withContext Result.failure(IllegalStateException("Unknown book"))
        val file = book.epubPath?.let(::File)?.takeIf { it.isFile }
            ?: return@withContext Result.failure(IllegalStateException("Download the book first"))
        try {
            val fileName = libraryFileName(book)
            val libraryId = if (kind == TomatoLibraryKind.TRANSLATION) library.translate(file, fileName) else library.addToBookshelf(file, fileName)
            dao.upsert(book.copy(libraryBookId = libraryId, libraryKind = kind, updatedAt = System.currentTimeMillis()))
            Result.success(libraryId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't move $bookId to the library", e)
            Result.failure(e)
        }
    }

    /** Clears library links whose library book was deleted. */
    suspend fun verifyLibraryLinks() = withContext(Dispatchers.IO) {
        dao.getAllOnce().filter { it.libraryBookId != null }.forEach { book ->
            if (!library.exists(book.libraryBookId!!)) {
                dao.upsert(book.copy(libraryBookId = null, libraryKind = TomatoLibraryKind.NONE))
            }
        }
    }

    suspend fun exportTo(bookId: String, destination: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        val file = dao.get(bookId)?.epubPath?.let(::File)?.takeIf { it.isFile }
            ?: return@withContext Result.failure(IllegalStateException("Download the book first"))
        try {
            context.contentResolver.openOutputStream(destination)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("Couldn't open the destination")
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun coverFile(bookId: String): File = File(bookDir(bookId), "cover.jpg")

    private fun bookDir(bookId: String) = File(root, "books/$bookId")

    private fun cacheCover(bookId: String, url: String) {
        if (url.isBlank()) return
        val target = coverFile(bookId)
        if (target.isFile && target.length() > 0) return
        scope.launch {
            try {
                val request = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body ?: return@use
                    if (!response.isSuccessful) return@use
                    target.parentFile?.mkdirs()
                    val tmp = File(target.parentFile, "cover.tmp")
                    tmp.outputStream().use { body.byteStream().copyTo(it) }
                    if (tmp.length() > 0) tmp.renameTo(target) else tmp.delete()
                }
            } catch (e: Exception) {
                Log.d(TAG, "Cover not cached for $bookId: ${e.message}")
            }
        }
    }

    private fun newEntity(book: TomatoBook, state: String) = TomatoBookEntity(
        bookId = book.bookId,
        title = book.title,
        author = book.author,
        abstractText = book.abstractText,
        coverUrl = book.coverUrl,
        category = book.category,
        serialStatus = book.serialStatus,
        wordCount = book.wordCount,
        chapterCount = book.chapterCount,
        lastChapterTime = book.lastChapterTime,
        state = state
    )

    private fun TomatoBookEntity.withBook(book: TomatoBook) = copy(
        title = book.title.ifBlank { title },
        author = book.author.ifBlank { author },
        abstractText = book.abstractText.ifBlank { abstractText },
        coverUrl = book.coverUrl.ifBlank { coverUrl },
        category = book.category.ifBlank { category },
        serialStatus = book.serialStatus.ifBlank { serialStatus },
        wordCount = if (book.wordCount > 0) book.wordCount else wordCount,
        chapterCount = if (book.chapterCount > 0) book.chapterCount else chapterCount,
        lastChapterTime = if (book.lastChapterTime > 0) book.lastChapterTime else lastChapterTime,
        updatedAt = System.currentTimeMillis()
    )

    private fun libraryFileName(book: TomatoBookEntity): String {
        val name = book.title.ifBlank { "Fanqie ${book.bookId}" }.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return "$name.epub"
    }

    private fun newestEpub(dir: File): File? =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".epub", ignoreCase = true) }.maxByOrNull { it.lastModified() }

    private fun isEpub(file: File): Boolean = try {
        ZipFile(file).use { zip -> zip.getEntry("mimetype") != null || zip.getEntry("META-INF/container.xml") != null }
    } catch (e: Exception) {
        false
    }

    private fun progressMessage(done: Int, total: Int): String =
        if (total > 0) "Downloading chapters ($done of $total)" else "Downloading…"

    private fun readable(e: Exception): String = when (e) {
        is EngineUnavailableException, is EngineCallException -> e.message ?: "The Tomato engine failed"
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        const val TAG = "TomatoRepository"
        const val MAX_POLL_FAILURES = 6
        const val MISSING_JOB_ROUNDS = 8
    }
}

/** Production engine access through the engine process. */
class ProcessEngineGateway(private val client: TomatoEngineClient) : TomatoEngineGateway {
    override suspend fun call(action: String, payload: JSONObject, timeoutMs: Long): String =
        client.call(action, payload.toString(), timeoutMs)

    override suspend fun shutdown() = client.shutdown()
}
