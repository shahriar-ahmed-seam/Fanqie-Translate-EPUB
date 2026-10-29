package com.example.queue
 
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.example.data.db.*
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import com.example.epub.*
import com.example.translation.TomatoMTLProvider
import com.example.translation.TranslationException
import com.example.translation.TranslationProvider
import com.example.translation.TranslationQuality
import com.example.translation.TranslationQualityException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

data class ImportProgress(
    val bookTitle: String,
    val currentChapter: Int,
    val totalChapters: Int,
    val chunksCreated: Int
)

class TranslationQueueManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository,
    /** Builds the translation service for (timeout seconds, workers); replaced in tests. */
    private val providerFactory: (Int, Int) -> TranslationProvider = { timeout, workers ->
        TomatoMTLProvider(timeoutSeconds = timeout.toLong(), maxConcurrentRequests = workers)
    },
    /** Waits between a chunk's attempts; replaced in tests. */
    private val retrySleep: suspend (Long) -> Unit = { delay(it) },
    /** True when the phone has a working internet connection (the start-up repair needs one). */
    private val isOnline: () -> Boolean = { NetworkState.isOnline(context) },
    /** Where the one-time maintenance flags are kept; tests use their own. */
    private val maintenancePrefsName: String = MAINTENANCE_PREFS
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var provider: TranslationProvider = providerFactory(30, 64)
    /** (timeout, workers) the current provider was built with. */
    private var providerConfig: Pair<Int, Int>? = null
    /** Checks every translation before it is saved and re-translates paragraphs left in Chinese. */
    private val chunkTranslator = ChunkTranslator { provider }

    /** Automatic retry rounds used per job for chunks that failed (in memory; a restart starts over). */
    private val autoRetryRounds = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val autoRetryLock = kotlinx.coroutines.sync.Mutex()

    private val activeWorkersCounter = AtomicInteger(0)
    private val _activeWorkers = MutableStateFlow(0)
    val activeWorkers: StateFlow<Int> = _activeWorkers.asStateFlow()

    private val inFlightByJob = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val _activeWorkersByJob = MutableStateFlow<Map<String, Int>>(emptyMap())
    val activeWorkersByJob: StateFlow<Map<String, Int>> = _activeWorkersByJob.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _importProgress = MutableStateFlow<ImportProgress?>(null)
    val importProgress: StateFlow<ImportProgress?> = _importProgress.asStateFlow()

    private var coordinatorJob: Job? = null
    private val workerJobs = mutableListOf<Job>()
    private var currentWorkerCount = 0
    private val roundRobinJobIndex = AtomicInteger(0)
    private val finalizingJobIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Whether any job can be worked on. Idle workers suspend on this instead of polling the database
     * every second (20 workers used to run ~20 queries a second for as long as the process lived,
     * including hours of screen-off audiobook playback). Starts true so workers check once at startup.
     */
    private val runnableJobsAvailable: StateFlow<Boolean> = database.jobDao().observeActiveJobs()
        .map { jobs -> jobs.any { it.status in RUNNABLE_JOB_STATUSES } }
        .distinctUntilChanged()
        .catch { e ->
            Log.e("QueueManager", "Active job observer failed; falling back to polling", e)
            emit(true)
        }
        .stateIn(scope, SharingStarted.Eagerly, true)

    init {
        // Startup recovery:
        // 1. Any TRANSLATING chunks with valid translatedText become COMPLETED.
        // 2. Any TRANSLATING chunks without completed results reset to PENDING.
        // 3. Any PAUSING jobs become PAUSED (since 0 requests are in-flight on cold start).
        // 4. Any RUNNING jobs become QUEUED.
        // 5. Jobs intentionally PAUSED remain PAUSED in the database.
        // 6. Start queue coordinator to pick up QUEUED jobs immediately.
        scope.launch {
            try {
                database.chunkDao().finalizeCompletedTranslatingChunks()
                database.chunkDao().resetTranslatingChunksToPending()
                database.jobDao().resetPausingJobsToPaused()
                database.jobDao().resetRunningJobsToQueued()
                // A finished (or failed) book with chunks still waiting would never be picked up
                // (for example if the app stopped in the middle of "Retry failed"): queue it again.
                database.jobDao().requeueFinishedJobsWithWaitingChunks()
                repairStoredTranslationsOnce()
                val activeJobs = database.jobDao().getActiveJobs()
                for (job in activeJobs) {
                    updateJobProgress(job.id)
                    checkAndFinalizeJob(job.id)
                }
                startQueueProcessing()
            } catch (e: Exception) {
                Log.e("QueueManager", "Error during startup queue recovery", e)
            }
            improveStoredTranslationsInBackground()
        }

        // Apply worker count or timeout adjustments immediately when settings change
        scope.launch {
            settingsRepository.settings.collect { settings ->
                if (_isProcessing.value) {
                    ensureWorkerPool(settings)
                }
            }
        }
    }

    private val maintenancePrefs by lazy { context.getSharedPreferences(maintenancePrefsName, Context.MODE_PRIVATE) }

    /**
     * Once after updating: chunks that earlier versions stored as `[""]` (or empty) are translated
     * again; their books go back into the queue (behind the books being translated now). Language
     * codes glued to translations are removed in place. Waits for a working connection, so books
     * aren't marked failed because the phone was offline at the first start.
     */
    private suspend fun repairStoredTranslationsOnce() {
        if (maintenancePrefs.getBoolean(KEY_QUALITY_REPAIR_DONE, false)) return
        if (!isOnline()) {
            Log.i("QueueManager", "Stored translation check postponed: no internet connection")
            return
        }
        try {
            val summary = StoredTranslationRepair(database).run()
            for (jobId in summary.requeuedJobs) updateJobProgress(jobId)
            // Exports made before this version are built again on the next export (export fixes).
            database.jobDao().clearAllExportCaches()
            maintenancePrefs.edit().putBoolean(KEY_QUALITY_REPAIR_DONE, true).apply()
            val message = "Translation check: ${summary.scanned} chunks checked, ${summary.requeued} sent back for " +
                "translation (${summary.requeuedJobs.size} books), ${summary.cleaned} cleaned"
            Log.i("QueueManager", message)
            com.example.diagnostics.AppDiagnostics.log("translation", message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tried again at the next start.
            Log.e("QueueManager", "Stored translation check failed", e)
        }
    }

    /**
     * Until it has finished once: Chinese left in translations made by earlier versions (paragraphs
     * Google sent back untranslated, words inside English sentences, titles) is translated again and
     * fixed in place. Books stay completed and readable meanwhile. Runs only while no book is being
     * translated and the phone is online, a few chunks at a time; continues at the next start if
     * the app stops or Google starts limiting requests.
     */
    private suspend fun improveStoredTranslationsInBackground() {
        try {
            if (maintenancePrefs.getBoolean(KEY_QUALITY_IMPROVE_DONE, false)) return
            val improver = StoredTranslationImprover(database, chunkTranslator) {
                // Books being translated come first.
                runnableJobsAvailable.first { !it }
                while (!isOnline()) delay(IMPROVE_OFFLINE_RECHECK_MS)
            }
            val summary = improver.run()
            if (summary.finished) maintenancePrefs.edit().putBoolean(KEY_QUALITY_IMPROVE_DONE, true).apply()
            val message = "Translation clean-up: ${summary.checked} chunks with Chinese left, ${summary.improved} improved" +
                if (summary.finished) "" else " (stopped early; continues at the next start)"
            Log.i("QueueManager", message)
            com.example.diagnostics.AppDiagnostics.log("translation", message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("QueueManager", "Translation clean-up failed", e)
        }
    }

    /** Stops all work (tests only; the app's queue lives as long as the process). */
    internal fun close() {
        scope.cancel()
    }

    private fun updateActiveCounts() {
        val total = activeWorkersCounter.get().coerceAtLeast(0)
        _activeWorkers.value = total
        _activeWorkersByJob.value = inFlightByJob.mapValues { it.value.get() }.filterValues { it > 0 }
    }

    fun startQueueProcessing() {
        if (coordinatorJob?.isActive == true) return

        coordinatorJob = scope.launch {
            _isProcessing.value = true

            // Keep worker pool synchronized with settings workerCount. Setting changes apply immediately
            // through the settings collector; this slow tick only replaces workers that died.
            while (isActive) {
                try {
                    val settings = settingsRepository.settings.value
                    ensureWorkerPool(settings)
                } catch (e: Exception) {
                    Log.e("QueueManager", "Exception in queue coordinator", e)
                }
                delay(COORDINATOR_TICK_MS)
            }
            _isProcessing.value = false
        }
    }

    @Synchronized
    private fun ensureWorkerPool(settings: AppSettings) {
        val targetWorkerCount = settings.workerCount.coerceIn(1, 50)

        // Rebuild the provider when the timeout or the worker count changes (the timeout used to be ignored).
        val config = settings.timeoutSeconds to targetWorkerCount
        if (providerConfig != config) {
            provider = providerFactory(settings.timeoutSeconds, targetWorkerCount)
            providerConfig = config
        }

        // Clean up completed/cancelled worker jobs
        workerJobs.removeAll { !it.isActive }

        // Spawn additional workers if needed
        while (workerJobs.size < targetWorkerCount) {
            val workerJob = scope.launch {
                runWorkerLoop()
            }
            workerJobs.add(workerJob)
        }

        // Cancel extra workers if count decreased
        while (workerJobs.size > targetWorkerCount) {
            val excessWorker = workerJobs.removeAt(workerJobs.size - 1)
            excessWorker.cancel()
        }

        currentWorkerCount = targetWorkerCount
    }

    /**
     * Safely reverts a chunk back to PENDING.
     * Atomic check-and-set: ONLY reverts if status is currently TRANSLATING.
     * Never reverts or overwrites a chunk that has reached COMPLETED or FAILED status.
     */
    private suspend fun revertChunkToPending(chunk: TranslationChunkEntity) {
        try {
            database.withTransaction {
                val current = database.chunkDao().getChunkById(chunk.id)
                if (current != null && current.status == "TRANSLATING") {
                    database.chunkDao().updateChunkResult(
                        id = chunk.id,
                        jobId = chunk.jobId,
                        bookId = chunk.bookId,
                        status = "PENDING",
                        translatedText = null,
                        errorMessage = null
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("QueueManager", "Error reverting chunk ${chunk.id} to PENDING", e)
        }
    }

    /**
     * Independent worker loop.
     * Uses Room database state directly as the single source of truth.
     * Implements fair round-robin scheduling across all active books (QUEUED/RUNNING).
     * Strictly verifies database job status prior to executing a task.
     */
    private suspend fun runWorkerLoop() {
        while (currentCoroutineContext().isActive) {
            try {
                val settings = settingsRepository.settings.value
                // Database is the source of truth for active books (QUEUED / RUNNING / TRANSLATING)
                val activeJobs = database.jobDao().getActiveJobs().take(settings.maxActiveBooks)

                if (activeJobs.isEmpty()) {
                    // Sleep until a job becomes runnable; the short delay absorbs a stale "true".
                    runnableJobsAvailable.first { it }
                    delay(IDLE_RECHECK_DELAY_MS)
                    continue
                }

                // Fair scheduling: Select next pending chunk in a round-robin manner across active books
                val chunk = claimNextChunkFairly(activeJobs)
                if (chunk == null) {
                    // No pending chunks available in active jobs; check if any active job is done
                    for (job in activeJobs) {
                        checkAndFinalizeJob(job.id)
                    }
                    delay(300)
                    continue
                }

                // Chunk was claimed and is now TRANSLATING in Room.
                // Immediately register in-flight worker count so pause/cancellation/status know about this worker.
                inFlightByJob.computeIfAbsent(chunk.jobId) { AtomicInteger(0) }.incrementAndGet()
                activeWorkersCounter.incrementAndGet()
                updateActiveCounts()

                var isChunkCompleted = false

                try {
                    // Re-verify the job's current status in the database to guarantee it wasn't PAUSED, PAUSING, or CANCELLED
                    val currentJob = database.jobDao().getJobById(chunk.jobId)
                    if (currentJob == null || currentJob.status == "PAUSED" || currentJob.status == "PAUSING" || 
                        currentJob.status == "CANCELLED" || currentJob.status == "COMPLETED" || currentJob.status == "FAILED") {
                        // Revert claimed chunk back to PENDING safely (only if still TRANSLATING)
                        revertChunkToPending(chunk)
                        delay(100)
                        continue
                    }

                    // Transition job status to RUNNING only if it is still currently QUEUED in the database
                    // (prevent race conditions from overwriting PAUSING, PAUSED, CANCELLED, etc.)
                    database.withTransaction {
                        val live = database.jobDao().getJobById(chunk.jobId)
                        if (live != null && live.status == "QUEUED") {
                            database.jobDao().updateJobStatus(chunk.jobId, "RUNNING")
                        }
                    }

                    // Execute translation task with the retry budget this chunk has left
                    val run = executeChunkWithRetry(
                        chunk = chunk,
                        remainingBudget = settings.maxRetries - chunk.retryCount
                    )
                    val outcome = run.outcome
                    val success = outcome.isSuccess
                    val translatedText = outcome.translatedText
                    val error = outcome.error

                    // Re-check database state in case the job was PAUSED or PAUSING while the chunk was in-flight
                    val jobAfterRequest = database.jobDao().getJobById(chunk.jobId)
                    val isJobPausedOrPausing = jobAfterRequest?.status == "PAUSED" || jobAfterRequest?.status == "PAUSING"

                    if (success && !translatedText.isNullOrBlank()) {
                        // Translation succeeded: safely save completed chunk associated with exact ids
                        database.chunkDao().updateChunkResult(
                            id = chunk.id,
                            jobId = chunk.jobId,
                            bookId = chunk.bookId,
                            status = "COMPLETED",
                            translatedText = translatedText,
                            errorMessage = null
                        )
                        isChunkCompleted = true
                    } else {
                        // Translation request failed
                        if (isJobPausedOrPausing || outcome.deferredByRateLimit) {
                            // Paused mid-request, or only rate limited: back to PENDING without using retries
                            revertChunkToPending(chunk)
                        } else {
                            val newRetryCount = chunk.retryCount + outcome.failedAttempts.coerceAtLeast(1)
                            val outOfRetries = newRetryCount >= settings.maxRetries
                            val lastResortText = if (outOfRetries) lastResortTranslation(chunk, run) else null
                            if (lastResortText != null) {
                                database.chunkDao().updateChunkResult(
                                    id = chunk.id,
                                    jobId = chunk.jobId,
                                    bookId = chunk.bookId,
                                    status = "COMPLETED",
                                    translatedText = lastResortText,
                                    errorMessage = KEPT_UNTRANSLATED_NOTE
                                )
                                isChunkCompleted = true
                                val message = "Chunk ${chunk.id} (${chunk.chunkType}) kept with some Chinese left after $newRetryCount tries"
                                Log.w("QueueManager", message)
                                com.example.diagnostics.AppDiagnostics.log("translation", message)
                            } else {
                                val status = if (outOfRetries) "FAILED" else "PENDING"
                                // Update chunk safely inside a transaction: only update if still TRANSLATING
                                // to guarantee completed chunks are NEVER overwritten!
                                database.withTransaction {
                                    val current = database.chunkDao().getChunkById(chunk.id)
                                    if (current != null && current.status == "TRANSLATING") {
                                        database.chunkDao().updateChunk(
                                            current.copy(
                                                status = status,
                                                retryCount = newRetryCount,
                                                errorMessage = error?.message ?: "Translation failed",
                                                updatedAt = System.currentTimeMillis()
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        // Worker is being cancelled (e.g. dynamic worker pool resized or queue stopped).
                        // Chunk must NOT disappear into TRANSLATING limbo!
                        // The finally block will safely revert the chunk via NonCancellable.
                        throw t
                    }
                    Log.e("QueueManager", "Unexpected error translating chunk ${chunk.id}", t)
                    val newRetryCount = chunk.retryCount + 1
                    val status = if (newRetryCount >= settings.maxRetries) "FAILED" else "PENDING"
                    database.withTransaction {
                        val current = database.chunkDao().getChunkById(chunk.id)
                        if (current != null && current.status == "TRANSLATING") {
                            database.chunkDao().updateChunk(
                                current.copy(
                                    status = status,
                                    retryCount = newRetryCount,
                                    errorMessage = t.message ?: "Unexpected error",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                } finally {
                    withContext(NonCancellable) {
                        // If chunk was claimed but not completed, revert to PENDING safely
                        if (!isChunkCompleted) {
                            revertChunkToPending(chunk)
                        }

                        // Decrement active worker counter in finally to guarantee accurate accounting
                        val remainingInJob = inFlightByJob[chunk.jobId]?.decrementAndGet()?.coerceAtLeast(0) ?: 0
                        activeWorkersCounter.decrementAndGet().coerceAtLeast(0)
                        updateActiveCounts()

                        // Check if job was PAUSING and has now finished all in-flight chunks
                        val jobState = database.jobDao().getJobById(chunk.jobId)
                        val currentInFlight = inFlightByJob[chunk.jobId]?.get() ?: 0
                        if (jobState?.status == "PAUSING" && (remainingInJob == 0 || currentInFlight == 0)) {
                            database.jobDao().updateJobStatus(chunk.jobId, "PAUSED")
                            Log.i("QueueManager", "Job ${chunk.jobId} all in-flight chunks completed -> PAUSED")
                        }

                        updateJobProgress(chunk.jobId)
                        checkAndFinalizeJob(chunk.jobId)
                    }
                }
            } catch (ce: CancellationException) {
                break
            } catch (e: Exception) {
                Log.e("QueueManager", "Exception in worker loop", e)
                delay(400)
            }
        }
    }

    /**
     * Fair dynamic chunk claiming algorithm.
     * Prioritizes active jobs with fewer in-flight workers, distributing the global worker count
     * dynamically across all active books according to pending work.
     * If a book runs out of pending chunks, remaining workers are seamlessly allocated to other active books
     * up to the configured global worker limit, preventing starvation without artificial per-book caps.
     */
    private suspend fun claimNextChunkFairly(activeJobs: List<TranslationJobEntity>): TranslationChunkEntity? {
        if (activeJobs.isEmpty()) return null
        val numJobs = activeJobs.size

        // Rotate starting index for fair round-robin tie-breaking when in-flight counts are equal
        val startIndex = Math.floorMod(roundRobinJobIndex.getAndIncrement(), numJobs)
        val candidateJobs = activeJobs.indices.map { i -> activeJobs[(startIndex + i) % numJobs] }
            .sortedBy { inFlightByJob[it.id]?.get() ?: 0 }

        for (job in candidateJobs) {
            // Ensure the job is still active and has not been paused or set to PAUSING
            val liveJob = database.jobDao().getJobById(job.id)
            if (liveJob == null || (liveJob.status != "RUNNING" && liveJob.status != "QUEUED")) {
                continue
            }

            // Try to claim a chunk for this job.
            // If another worker claimed the top pending chunk at the exact same millisecond,
            // retry up to 2 additional times if pending chunks genuinely remain.
            for (attempt in 0 until 3) {
                val chunk = database.chunkDao().claimNextPendingChunkForJob(job.id)
                if (chunk != null) {
                    return chunk
                }
                // If null was returned, check if any pending chunks remain in this job.
                // If none remain, don't retry this job, move to next candidate job.
                val hasPending = database.chunkDao().getNextPendingChunkForJob(job.id) != null
                if (!hasPending) {
                    break
                }
            }
        }
        return null
    }

    /** A chunk's attempts in one claim, plus its best partial translation (paragraphs still Chinese). */
    private class ChunkRun(val outcome: ChunkAttemptOutcome, val bestPartial: TranslationQualityException?)

    /**
     * What to keep for a chunk that ran out of retries while Google kept answering with some Chinese
     * left (never for network failures, which are retried later):
     * - a body chunk with only a few paragraphs still Chinese keeps its best translation (one Chinese
     *   line beats a missing piece of the chapter);
     * - a title keeps its best translation, or its source text; the reader and the export then show
     *   "Chapter N". A title must never leave the whole book failed.
     */
    private fun lastResortTranslation(chunk: TranslationChunkEntity, run: ChunkRun): String? {
        val partial = run.bestPartial
        if (partial != null && ChunkTranslator.isAcceptableLastResort(partial)) return partial.bestText
        // Google keeps answering, but never with a usable translation (not a network problem).
        val answeredBadly = (run.outcome.error as? TranslationException)?.isSoftFailure == true
        if (!answeredBadly) return null
        // A title never fails a book, and after the automatic rounds neither does a body chunk: the
        // best text Google gave, or the original, is kept (as earlier versions kept any answer).
        val finalRound = (autoRetryRounds[chunk.jobId] ?: 0) >= MAX_AUTO_RETRY_ROUNDS
        if (!ChunkTranslator.isTitle(chunk.chunkType) && !finalRound) return null
        return partial?.bestText
            ?.takeIf { it.isNotBlank() && !TranslationQuality.isJsonJunk(it) && TranslationQuality.hanCount(it) < TranslationQuality.hanCount(chunk.sourceText) }
            ?: chunk.sourceText
    }

    private suspend fun executeChunkWithRetry(
        chunk: TranslationChunkEntity,
        remainingBudget: Int
    ): ChunkRun {
        var bestPartial: TranslationQualityException? = null
        var attempts = 0
        val outcome = translateWithRetry(remainingBudget = remainingBudget, sleep = retrySleep) {
            // From the third try on (counting earlier claims), a chunk Google keeps answering empty
            // or in Chinese is translated paragraph by paragraph.
            val thorough = chunk.retryCount + attempts++ >= THOROUGH_FROM_ATTEMPT
            chunkTranslator.translate(chunk.chunkType, chunk.sourceText, thorough).onFailure { e ->
                if (e is TranslationQualityException && e.bestText != null &&
                    (bestPartial == null || e.badParagraphs < bestPartial!!.badParagraphs)
                ) {
                    bestPartial = e
                }
            }
        }
        if (!outcome.isSuccess) {
            Log.w("QueueManager", "Chunk ${chunk.id} not translated (failed attempts=${outcome.failedAttempts}, deferred=${outcome.deferredByRateLimit}): ${outcome.error?.message}")
        }
        return ChunkRun(outcome, bestPartial)
    }

    /**
     * Adds an EPUB book to the system and queues it for translation.
     * Streams chapters one-by-one with batched Room database insertions.
     * Memory usage is O(1) with respect to chapter count (supports 2,600+ chapters).
     */
    suspend fun enqueueEpub(
        uri: Uri,
        fileName: String,
        onProgress: ((currentChapter: Int, totalChapters: Int, chunksCreated: Int) -> Unit)? = null
    ): Pair<BookEntity, TranslationJobEntity> = withContext(Dispatchers.IO) {
        val bookId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()
        val bookDir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
        val sourceEpubFile = File(bookDir, "source.epub")

        try {
            // 1. Copy source EPUB to app private directory
            EpubParser.copyUriToTempFile(context, uri, sourceEpubFile)

            // 2. Parse EPUB metadata, manifest, and spine dynamically (O(1) chapter memory)
            val quickInfo = EpubParser.parseQuickInfo(sourceEpubFile)

            // Save cover image if available
            var coverPath: String? = null
            if (quickInfo.coverBytes != null && quickInfo.coverBytes.isNotEmpty()) {
                val ext = if (quickInfo.coverMediaType?.contains("png") == true) "png" else "jpg"
                val coverFile = File(bookDir, "cover.$ext")
                coverFile.writeBytes(quickInfo.coverBytes)
                coverPath = coverFile.absolutePath
            }

            val settings = settingsRepository.settings.value
            val totalChapters = quickInfo.spine.size

            // 3. Generate Metadata Chunks (Title & Description)
            val metadataChunks = EpubChunker.generateChunksForMetadata(
                title = quickInfo.metadata.title,
                description = quickInfo.metadata.description
            )

            // 4. Prepare streaming buffers
            val chapterBatch = mutableListOf<ChapterEntity>()
            val chunkBatch = mutableListOf<TranslationChunkEntity>()
            var totalChunksCreated = metadataChunks.size

            // Add metadata chunks to batch
            metadataChunks.forEach { def ->
                chunkBatch.add(
                    TranslationChunkEntity(
                        id = def.chunkId,
                        jobId = jobId,
                        bookId = bookId,
                        chapterId = def.chapterId,
                        chapterOrder = def.chapterOrder,
                        chunkOrder = def.chunkOrder,
                        chunkType = def.chunkType,
                        sourceText = def.text,
                        status = "PENDING"
                    )
                )
            }

            _importProgress.value = ImportProgress(
                bookTitle = quickInfo.metadata.title,
                currentChapter = 0,
                totalChapters = totalChapters,
                chunksCreated = totalChunksCreated
            )
            onProgress?.invoke(0, totalChapters, totalChunksCreated)

            // 5. Stream chapters one-by-one from the EPUB ZipFile
            val zip = ZipFile(sourceEpubFile)
            try {
                for (index in quickInfo.spine.indices) {
                    val spineItem = quickInfo.spine[index]
                    val chapterData = try {
                        EpubParser.extractChapter(zip, quickInfo.opfDirectory, spineItem, index)
                    } catch (e: Exception) {
                        Log.w("QueueManager", "Malformed chapter ${index + 1} (${spineItem.href}), creating fallback chapter entity: ${e.message}")
                        ParsedChapterData(
                            chapterId = UUID.randomUUID().toString(),
                            chapterOrder = index,
                            href = spineItem.href,
                            title = "Chapter ${index + 1}",
                            translatableParagraphs = emptyList()
                        )
                    }

                    val chapterChunks = EpubChunker.generateChunksForChapter(
                        chapterId = chapterData.chapterId,
                        chapterOrder = index,
                        chapterTitle = chapterData.title,
                        paragraphs = chapterData.translatableParagraphs,
                        maxChunkSize = settings.chunkSize
                    )

                    val chunkCount = chapterChunks.size
                    totalChunksCreated += chunkCount

                    chapterBatch.add(
                        ChapterEntity(
                            id = chapterData.chapterId,
                            bookId = bookId,
                            chapterOrder = index,
                            originalHref = spineItem.href,
                            title = chapterData.title,
                            chunkCount = chunkCount
                        )
                    )

                    chapterChunks.forEach { def ->
                        chunkBatch.add(
                            TranslationChunkEntity(
                                id = def.chunkId,
                                jobId = jobId,
                                bookId = bookId,
                                chapterId = def.chapterId,
                                chapterOrder = def.chapterOrder,
                                chunkOrder = def.chunkOrder,
                                chunkType = def.chunkType,
                                sourceText = def.text,
                                status = "PENDING"
                            )
                        )
                    }

                    // Flush batches when threshold reached (100 chapters or 300 chunks)
                    if (chunkBatch.size >= 300 || chapterBatch.size >= 100) {
                        val chList = ArrayList(chapterBatch)
                        val ckList = ArrayList(chunkBatch)
                        chapterBatch.clear()
                        chunkBatch.clear()
                        database.withTransaction {
                            if (chList.isNotEmpty()) database.chapterDao().insertChapters(chList)
                            if (ckList.isNotEmpty()) database.chunkDao().insertChunks(ckList)
                        }
                    }

                    val currentChapter = index + 1
                    _importProgress.value = ImportProgress(
                        bookTitle = quickInfo.metadata.title,
                        currentChapter = currentChapter,
                        totalChapters = totalChapters,
                        chunksCreated = totalChunksCreated
                    )
                    onProgress?.invoke(currentChapter, totalChapters, totalChunksCreated)
                }

                // Flush any remaining chapters/chunks in batch
                if (chapterBatch.isNotEmpty() || chunkBatch.isNotEmpty()) {
                    val chList = ArrayList(chapterBatch)
                    val ckList = ArrayList(chunkBatch)
                    chapterBatch.clear()
                    chunkBatch.clear()
                    database.withTransaction {
                        if (chList.isNotEmpty()) database.chapterDao().insertChapters(chList)
                        if (ckList.isNotEmpty()) database.chunkDao().insertChunks(ckList)
                    }
                }
            } finally {
                zip.close()
            }

            // 6. Strict Import Consistency Verification
            val savedChapters = database.chapterDao().getChaptersByBook(bookId)
            if (savedChapters.size != totalChapters) {
                cleanupFailedImport(bookId, jobId, bookDir)
                throw IllegalStateException("Import verification failed: Expected $totalChapters spine chapters, but found ${savedChapters.size} in database.")
            }

            savedChapters.forEachIndexed { i, ch ->
                if (ch.chapterOrder != i) {
                    cleanupFailedImport(bookId, jobId, bookDir)
                    throw IllegalStateException("Import verification failed: Chapter order mismatch at index $i (found ${ch.chapterOrder}).")
                }
            }

            val persistedChunks = database.chunkDao().getTotalChunkCount(jobId)
            if (persistedChunks != totalChunksCreated) {
                cleanupFailedImport(bookId, jobId, bookDir)
                throw IllegalStateException("Import verification failed: Chunk count mismatch (persisted: $persistedChunks, expected: $totalChunksCreated).")
            }

            // 7. Save Book and Initial Job Entity
            val bookEntity = BookEntity(
                id = bookId,
                title = quickInfo.metadata.title,
                author = quickInfo.metadata.author,
                description = quickInfo.metadata.description,
                coverPath = coverPath,
                originalUri = uri.toString(),
                originalFileName = fileName,
                chapterCount = totalChapters,
                totalChunks = totalChunksCreated,
                createdAt = System.currentTimeMillis()
            )

            val jobEntity = TranslationJobEntity(
                id = jobId,
                bookId = bookId,
                status = "QUEUED",
                progress = 0f,
                completedChunks = 0,
                failedChunks = 0,
                totalChunks = totalChunksCreated,
                startedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            database.withTransaction {
                database.bookDao().insertBook(bookEntity)
                database.jobDao().insertJob(jobEntity)
                database.groupDao().insertCrossRef(
                    com.example.data.db.BookGroupCrossRefEntity(bookId = bookId, groupId = "default_translated")
                )
            }

            _importProgress.value = null
            startQueueProcessing()
            Log.i("QueueManager", "Successfully imported '$fileName' ($totalChapters chapters, $totalChunksCreated chunks).")
            return@withContext Pair(bookEntity, jobEntity)
        } catch (e: Exception) {
            _importProgress.value = null
            cleanupFailedImport(bookId, jobId, bookDir)
            Log.e("QueueManager", "EPUB import failed for '$fileName': ${e.message}", e)
            throw e
        }
    }

    private suspend fun cleanupFailedImport(bookId: String, jobId: String, bookDir: File) {
        try {
            database.chunkDao().deleteChunksByJob(jobId)
            database.chapterDao().deleteChaptersByBook(bookId)
            database.groupDao().deleteCrossRefsByBook(bookId)
            database.bookmarkDao().deleteBookmarksByBook(bookId)
            database.ttsRuleDao().deleteRulesByBook(bookId)
            database.jobDao().deleteJobById(jobId)
            database.bookDao().deleteBookById(bookId)
            if (bookDir.exists()) {
                bookDir.deleteRecursively()
            }
        } catch (cleanupEx: Exception) {
            Log.w("QueueManager", "Failed to clean up partial import records: ${cleanupEx.message}")
        }
    }

    private suspend fun updateJobProgress(jobId: String) {
        val total = database.chunkDao().getTotalChunkCount(jobId)
        val completed = database.chunkDao().getCompletedChunkCount(jobId)
        val failed = database.chunkDao().getFailedChunkCount(jobId)
        val translating = database.chunkDao().getTranslatingChunkCount(jobId)
        val progress = if (total > 0) completed.toFloat() / total.toFloat() else 0f

        val currentJob = database.jobDao().getJobById(jobId) ?: return

        // If job is already COMPLETED, preserve status and only update counts
        if (currentJob.status == "COMPLETED") {
            database.jobDao().updateJobProgress(jobId, completed, failed, 1.0f, "COMPLETED")
            return
        }

        // Preserve PAUSED, PAUSING, CANCELLED, or FAILED statuses
        if (currentJob.status == "PAUSED" || currentJob.status == "PAUSING" || 
            currentJob.status == "CANCELLED" || currentJob.status == "FAILED") {
            database.jobDao().updateJobProgress(jobId, completed, failed, progress, currentJob.status)
            return
        }

        // When all chunks have completed, let checkAndFinalizeJob verify consistency and mark COMPLETED
        if (total > 0 && completed == total && failed == 0 && translating == 0) {
            return
        }

        // If chunks have failed and no pending or translating chunks remain, mark FAILED
        // (after the automatic retry rounds)
        if (failed > 0 && (completed + failed == total) && translating == 0) {
            if (autoRetryFailedChunks(jobId)) return
            val errorMsg = "Translation incomplete: $failed chunks failed"
            database.jobDao().updateJob(
                currentJob.copy(
                    status = "FAILED",
                    completedChunks = completed,
                    failedChunks = failed,
                    progress = progress,
                    errorMessage = errorMsg,
                    updatedAt = System.currentTimeMillis()
                )
            )
            return
        }

        // If job was QUEUED and no workers are translating yet, preserve QUEUED
        if (currentJob.status == "QUEUED" && translating == 0) {
            database.jobDao().updateJobProgress(jobId, completed, failed, progress, "QUEUED")
            return
        }

        // Otherwise job is actively RUNNING
        database.jobDao().updateJobProgress(jobId, completed, failed, progress, "RUNNING")
    }

    /**
     * All of a job's remaining chunks failed. Google's bad spells usually pass, so the failed chunks
     * get [MAX_AUTO_RETRY_ROUNDS] more rounds (after a pause) before the book is marked failed.
     * Returns true when they were queued again (or already were).
     */
    private suspend fun autoRetryFailedChunks(jobId: String): Boolean = autoRetryLock.withLock {
        if (database.chunkDao().getFailedChunkCount(jobId) == 0) return@withLock true
        val job = database.jobDao().getJobById(jobId) ?: return@withLock false
        if (job.status !in RUNNABLE_JOB_STATUSES) return@withLock false
        val round = (autoRetryRounds[jobId] ?: 0) + 1
        if (round > MAX_AUTO_RETRY_ROUNDS) return@withLock false
        autoRetryRounds[jobId] = round
        TomatoMTLProvider.setRateLimitCooldown(AUTO_RETRY_COOLDOWN_MS * round)
        database.chunkDao().retryFailedChunks(jobId)
        val message = "Job $jobId: some chunks failed; retrying them automatically (round $round of $MAX_AUTO_RETRY_ROUNDS)"
        Log.i("QueueManager", message)
        com.example.diagnostics.AppDiagnostics.log("translation", message)
        true
    }

    private data class ConsistencyResult(val isValid: Boolean, val errorMessage: String? = null)

    private suspend fun verifyJobTranslationConsistency(
        jobId: String,
        bookId: String,
        expectedTotalChunks: Int
    ): ConsistencyResult {
        val totalInDb = database.chunkDao().getTotalChunkCount(jobId)
        val completedInDb = database.chunkDao().getCompletedChunkCount(jobId)
        val incompleteInDb = database.chunkDao().getIncompleteChunkCount(jobId)

        if (totalInDb != expectedTotalChunks || completedInDb != expectedTotalChunks || incompleteInDb > 0) {
            return ConsistencyResult(
                false,
                "Chunk count mismatch: expected $expectedTotalChunks, total in db $totalInDb, completed in db $completedInDb, incomplete $incompleteInDb"
            )
        }

        val chapters = database.chapterDao().getChaptersByBook(bookId)
        if (chapters.isEmpty()) {
            return ConsistencyResult(false, "No chapters found for book $bookId")
        }

        // Verify every chapter order
        for (i in chapters.indices) {
            val ch = chapters[i]
            if (ch.chapterOrder != i) {
                return ConsistencyResult(false, "Chapter order mismatch at index $i: expected $i, found ${ch.chapterOrder}")
            }
        }

        // Verify Title chunk if present
        val titleChunk = database.chunkDao().getTitleChunk(jobId)
        if (titleChunk != null && (titleChunk.status != "COMPLETED" || titleChunk.translatedText.isNullOrBlank())) {
            return ConsistencyResult(false, "Book title translation chunk is not completed")
        }

        // Verify Description chunk if present
        val descChunk = database.chunkDao().getDescriptionChunk(jobId)
        if (descChunk != null && (descChunk.status != "COMPLETED" || descChunk.translatedText.isNullOrBlank())) {
            return ConsistencyResult(false, "Book description translation chunk is not completed")
        }

        return ConsistencyResult(true)
    }

    private suspend fun checkAndFinalizeJob(jobId: String) {
        val job = database.jobDao().getJobById(jobId) ?: return
        if (job.status == "COMPLETED" || job.status == "CANCELLED" || job.status == "PAUSED") return

        val total = database.chunkDao().getTotalChunkCount(jobId)
        val completed = database.chunkDao().getCompletedChunkCount(jobId)
        val failed = database.chunkDao().getFailedChunkCount(jobId)
        val translating = database.chunkDao().getTranslatingChunkCount(jobId)
        val incomplete = database.chunkDao().getIncompleteChunkCount(jobId)

        // If any chunk is still translating, pending, or incomplete, or total chunks mismatch, the book must NOT be marked COMPLETED
        if (translating > 0 || incomplete > 0 || completed < total || total < job.totalChunks) {
            if (failed > 0 && (completed + failed == total) && translating == 0) {
                if (autoRetryFailedChunks(jobId)) return
                val errorMsg = "Translation incomplete: $failed chunks failed"
                database.jobDao().updateJob(
                    job.copy(
                        status = "FAILED",
                        completedChunks = completed,
                        failedChunks = failed,
                        progress = if (total > 0) completed.toFloat() / total.toFloat() else 0f,
                        errorMessage = errorMsg,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
            return
        }

        // When total == completed and 0 failed/translating/incomplete, and matches expected totalChunks
        if (total > 0 && total == job.totalChunks && completed == total && failed == 0 && translating == 0 && incomplete == 0) {
            if (!finalizingJobIds.add(jobId)) {
                return // Finalization already in progress
            }

            try {
                // A book is never completed with an empty or junk ("[""]") translation stored: such
                // chunks go back to the queue (new translations are checked before they are saved,
                // so this only catches leftovers).
                val junk = database.chunkDao().getSuspiciousCompletedChunks(jobId).filter {
                    TranslationQuality.needsTranslation(it.sourceText) &&
                        TranslationQuality.usable(it.translatedText, it.sourceText) == null
                }
                if (junk.isNotEmpty()) {
                    junk.forEach { database.chunkDao().requeueCompletedChunk(it.id, StoredTranslationRepair.REASON) }
                    Log.w("QueueManager", "Job $jobId had ${junk.size} unusable translations; translating them again")
                    return
                }

                // Strict final consistency check
                val consistency = verifyJobTranslationConsistency(jobId, job.bookId, total)
                if (!consistency.isValid) {
                    Log.e("QueueManager", "Consistency check failed for job $jobId: ${consistency.errorMessage}")
                    database.jobDao().updateJob(
                        job.copy(
                            status = "FAILED",
                            errorMessage = "Translation consistency check failed: ${consistency.errorMessage}",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return
                }

                // Translation is 100% verified and COMPLETED! Checked again in the same transaction:
                // a chunk sent back meanwhile (by the start-up check) must not end in a completed book.
                val completedNow = database.withTransaction {
                    val live = database.jobDao().getJobById(jobId)
                    if (live == null || live.status == "COMPLETED" || live.status == "CANCELLED" || live.status == "PAUSED" ||
                        database.chunkDao().getIncompleteChunkCount(jobId) > 0
                    ) {
                        false
                    } else {
                        database.jobDao().updateJob(
                            live.copy(
                                status = "COMPLETED",
                                progress = 1.0f,
                                completedChunks = total,
                                failedChunks = 0,
                                errorMessage = null,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                        true
                    }
                }
                if (!completedNow) return
                autoRetryRounds.remove(jobId)
                Log.i("QueueManager", "Job $jobId translation COMPLETED successfully ($total chunks verified).")
            } finally {
                finalizingJobIds.remove(jobId)
            }
        }
    }

    /**
     * Atomically pauses the given translation job.
     * Transitions RUNNING -> PAUSING (or QUEUED -> PAUSED if 0 in-flight requests).
     * Stops scheduling new chunks immediately. In-flight requests complete naturally.
     * When in-flight count drops to 0, automatically transitions PAUSING -> PAUSED.
     */
    fun pauseJob(jobId: String) {
        scope.launch {
            val job = database.jobDao().getJobById(jobId) ?: return@launch
            if (job.status == "COMPLETED" || job.status == "CANCELLED" || job.status == "PAUSED") return@launch

            val inFlight = inFlightByJob[jobId]?.get() ?: 0
            if (inFlight <= 0) {
                database.jobDao().updateJobStatus(jobId, "PAUSED")
                Log.i("QueueManager", "Job $jobId transitioned directly to PAUSED (0 in-flight requests)")
            } else {
                database.jobDao().updateJobStatus(jobId, "PAUSING")
                Log.i("QueueManager", "Job $jobId set to PAUSING ($inFlight in-flight requests remaining)")
                val inFlightAfter = inFlightByJob[jobId]?.get() ?: 0
                if (inFlightAfter <= 0) {
                    database.jobDao().updateJobStatus(jobId, "PAUSED")
                    Log.i("QueueManager", "Job $jobId transitioned to PAUSED (in-flight finished concurrently)")
                }
            }
        }
    }

    /**
     * Resumes the given translation job by setting status to QUEUED in the database.
     * Immediately triggers queue processing and wakes up workers without needing an app restart.
     */
    fun resumeJob(jobId: String) {
        scope.launch {
            val job = database.jobDao().getJobById(jobId) ?: return@launch
            if (job.status == "PAUSED" || job.status == "PAUSING") {
                database.jobDao().updateJobStatus(jobId, "QUEUED")
                startQueueProcessing()
                Log.i("QueueManager", "Job $jobId resumed (status set to QUEUED)")
            }
        }
    }

    /**
     * Retries all failed chunks for the job, marks the job as QUEUED, and kicks off workers.
     */
    fun retryFailed(jobId: String) {
        scope.launch {
            // A manual retry gets the automatic rounds again too.
            autoRetryRounds.remove(jobId)
            database.chunkDao().retryFailedChunks(jobId)
            database.jobDao().updateJobStatus(jobId, "QUEUED")
            updateJobProgress(jobId)
            startQueueProcessing()
        }
    }

    /**
     * Cancels the given job in the database.
     */
    fun cancelJob(jobId: String) {
        scope.launch {
            database.jobDao().updateJobStatus(jobId, "CANCELLED")
        }
    }

    suspend fun deleteBookAndJob(bookId: String) = withContext(Dispatchers.IO) {
        val job = database.jobDao().getJobByBookId(bookId)
        if (job != null) {
            inFlightByJob.remove(job.id)
            finalizingJobIds.remove(job.id)
            database.chunkDao().deleteChunksByJob(job.id)
            database.jobDao().deleteJobById(job.id)
        }
        database.chapterDao().deleteChaptersByBook(bookId)
        database.groupDao().deleteCrossRefsByBook(bookId)
        database.bookmarkDao().deleteBookmarksByBook(bookId)
        database.ttsRuleDao().deleteRulesByBook(bookId)
        database.bookDao().deleteBookById(bookId)
        // A scrape project that was saved into this book now has no library copy.
        database.scrapeProjectDao().unlinkBook(bookId)

        // Delete local files
        val bookDir = File(context.filesDir, "books/$bookId")
        bookDir.deleteRecursively()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private companion object {
        /** Job states workers can claim chunks from (same set as JobDao.getActiveJobs). */
        val RUNNABLE_JOB_STATUSES = setOf("QUEUED", "RUNNING", "TRANSLATING")
        const val COORDINATOR_TICK_MS = 15_000L
        const val IDLE_RECHECK_DELAY_MS = 250L
        const val MAINTENANCE_PREFS = "translation_maintenance"
        const val KEY_QUALITY_REPAIR_DONE = "quality_repair_v1_done"
        const val KEY_QUALITY_IMPROVE_DONE = "quality_improve_v1_done"
        const val IMPROVE_OFFLINE_RECHECK_MS = 60_000L
        /** Tries (counted over all claims) from which a chunk is translated paragraph by paragraph. */
        const val THOROUGH_FROM_ATTEMPT = 2
        /** Stored on chunks kept with Chinese left (diagnostics only; nothing shows chunk messages). */
        const val KEPT_UNTRANSLATED_NOTE = "Kept with some Chinese: Google didn't translate all of it"
        /** Extra full rounds for failed chunks before a book is marked failed. */
        const val MAX_AUTO_RETRY_ROUNDS = 2
        /** Pause before each automatic round, times the round number (lets Google recover). */
        const val AUTO_RETRY_COOLDOWN_MS = 10_000L
    }
}

