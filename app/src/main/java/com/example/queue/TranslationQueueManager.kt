package com.example.queue
 
import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.db.*
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import com.example.epub.*
import com.example.translation.TomatoMTLProvider
import com.example.translation.TranslationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class TranslationQueueManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var provider: TranslationProvider = TomatoMTLProvider()

    private val activeWorkersCounter = AtomicInteger(0)
    private val _activeWorkers = MutableStateFlow(0)
    val activeWorkers: StateFlow<Int> = _activeWorkers.asStateFlow()

    private val inFlightByJob = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
    private val _activeWorkersByJob = MutableStateFlow<Map<String, Int>>(emptyMap())
    val activeWorkersByJob: StateFlow<Map<String, Int>> = _activeWorkersByJob.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var coordinatorJob: Job? = null
    private val workerJobs = mutableListOf<Job>()
    private var currentWorkerCount = 0
    private val roundRobinJobIndex = AtomicInteger(0)
    private val finalizingJobIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
                startQueueProcessing()
            } catch (e: Exception) {
                Log.e("QueueManager", "Error during startup queue recovery", e)
            }
        }
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

            // Keep worker pool synchronized with settings workerCount
            while (isActive) {
                try {
                    val settings = settingsRepository.settings.value
                    ensureWorkerPool(settings)
                } catch (e: Exception) {
                    Log.e("QueueManager", "Exception in queue coordinator", e)
                }
                delay(1000)
            }
            _isProcessing.value = false
        }
    }

    @Synchronized
    private fun ensureWorkerPool(settings: AppSettings) {
        val targetWorkerCount = settings.workerCount.coerceIn(1, 50)

        // If provider settings changed or worker pool size changed, update provider
        if (currentWorkerCount != targetWorkerCount) {
            provider = TomatoMTLProvider(
                timeoutSeconds = settings.timeoutSeconds.toLong(),
                maxConcurrentRequests = targetWorkerCount
            )
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
                    delay(300)
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

                // Re-verify the job's current status in the database to guarantee it wasn't PAUSED, PAUSING, or CANCELLED
                val currentJob = database.jobDao().getJobById(chunk.jobId)
                if (currentJob == null || currentJob.status == "PAUSED" || currentJob.status == "PAUSING" || 
                    currentJob.status == "CANCELLED" || currentJob.status == "COMPLETED" || currentJob.status == "FAILED") {
                    // Revert claimed chunk back to PENDING so progress is never lost
                    database.chunkDao().updateChunkResult(
                        id = chunk.id,
                        jobId = chunk.jobId,
                        bookId = chunk.bookId,
                        status = "PENDING",
                        translatedText = null,
                        errorMessage = null
                    )
                    delay(100)
                    continue
                }

                // Transition job status to RUNNING if it is currently QUEUED
                if (currentJob.status == "QUEUED") {
                    database.jobDao().updateJobStatus(currentJob.id, "RUNNING")
                }

                // Execute translation task
                try {
                    // Increment active worker counters immediately before starting network request
                    inFlightByJob.computeIfAbsent(chunk.jobId) { AtomicInteger(0) }.incrementAndGet()
                    activeWorkersCounter.incrementAndGet()
                    updateActiveCounts()

                    val (success, translatedText, error) = executeChunkWithRetry(
                        chunk = chunk,
                        maxRetries = settings.maxRetries
                    )

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
                    } else {
                        // Translation request failed
                        if (isJobPausedOrPausing) {
                            // If paused during execution, restore chunk to PENDING for when resumed
                            database.chunkDao().updateChunkResult(
                                id = chunk.id,
                                jobId = chunk.jobId,
                                bookId = chunk.bookId,
                                status = "PENDING",
                                translatedText = null,
                                errorMessage = null
                            )
                        } else {
                            val newRetryCount = chunk.retryCount + 1
                            val status = if (newRetryCount >= settings.maxRetries) "FAILED" else "PENDING"
                            database.chunkDao().updateChunk(
                                chunk.copy(
                                    status = status,
                                    retryCount = newRetryCount,
                                    errorMessage = error?.message ?: "Translation failed",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    Log.e("QueueManager", "Unexpected error translating chunk ${chunk.id}", t)
                    val newRetryCount = chunk.retryCount + 1
                    val status = if (newRetryCount >= settings.maxRetries) "FAILED" else "PENDING"
                    database.chunkDao().updateChunk(
                        chunk.copy(
                            status = status,
                            retryCount = newRetryCount,
                            errorMessage = t.message ?: "Unexpected error",
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                } finally {
                    // Decrement active worker counter in finally to guarantee accurate accounting
                    val remainingInJob = inFlightByJob[chunk.jobId]?.decrementAndGet()?.coerceAtLeast(0) ?: 0
                    activeWorkersCounter.decrementAndGet().coerceAtLeast(0)
                    updateActiveCounts()

                    // Check if job was PAUSING and has now finished all in-flight chunks
                    val jobState = database.jobDao().getJobById(chunk.jobId)
                    if (jobState?.status == "PAUSING" && remainingInJob == 0) {
                        database.jobDao().updateJobStatus(chunk.jobId, "PAUSED")
                        Log.i("QueueManager", "Job ${chunk.jobId} all in-flight chunks completed -> PAUSED")
                    }

                    updateJobProgress(chunk.jobId)
                    checkAndFinalizeJob(chunk.jobId)
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
     * Fair round-robin chunk claiming algorithm.
     * Rotates through active jobs so that large novels never starve smaller ones.
     */
    private suspend fun claimNextChunkFairly(activeJobs: List<TranslationJobEntity>): TranslationChunkEntity? {
        if (activeJobs.isEmpty()) return null
        val numJobs = activeJobs.size
        val startIndex = Math.floorMod(roundRobinJobIndex.getAndIncrement(), numJobs)

        for (i in 0 until numJobs) {
            val jobIndex = (startIndex + i) % numJobs
            val job = activeJobs[jobIndex]
            val chunk = database.chunkDao().claimNextPendingChunkForJob(job.id)
            if (chunk != null) {
                return chunk
            }
        }
        return null
    }

    private suspend fun executeChunkWithRetry(
        chunk: TranslationChunkEntity,
        maxRetries: Int
    ): Triple<Boolean, String?, Throwable?> {
        var lastError: Throwable? = null
        val attempts = maxRetries.coerceAtLeast(1)

        for (attempt in 1..attempts) {
            try {
                val result = provider.translate(chunk.sourceText, "zh", "en")
                if (result.isSuccess && !result.getOrNull().isNullOrBlank()) {
                    return Triple(true, result.getOrNull(), null)
                }
                lastError = result.exceptionOrNull() ?: IllegalStateException("Empty translation response")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                lastError = e
            }
            if (attempt < attempts) {
                // Exponential backoff
                delay(500L * attempt)
            }
        }
        return Triple(false, null, lastError)
    }

    /**
     * Adds an EPUB book to the system and queues it for translation.
     */
    suspend fun enqueueEpub(uri: Uri, fileName: String): Pair<BookEntity, TranslationJobEntity> = withContext(Dispatchers.IO) {
        val bookId = UUID.randomUUID().toString()
        val jobId = UUID.randomUUID().toString()

        // 1. Copy source EPUB to app private cache directory
        val bookDir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
        val sourceEpubFile = File(bookDir, "source.epub")
        EpubParser.copyUriToTempFile(context, uri, sourceEpubFile)

        // 2. Parse EPUB
        val parsedEpub = EpubParser.parse(sourceEpubFile)

        // Save cover image if available
        var coverPath: String? = null
        if (parsedEpub.coverBytes != null && parsedEpub.coverBytes.isNotEmpty()) {
            val ext = if (parsedEpub.coverMediaType?.contains("png") == true) "png" else "jpg"
            val coverFile = File(bookDir, "cover.$ext")
            coverFile.writeBytes(parsedEpub.coverBytes)
            coverPath = coverFile.absolutePath
        }

        // 3. Generate Chunks
        val settings = settingsRepository.settings.value
        val chunkDefs = EpubChunker.generateChunks(parsedEpub, settings.chunkSize)

        // 4. Save Book Entity
        val bookEntity = BookEntity(
            id = bookId,
            title = parsedEpub.metadata.title,
            author = parsedEpub.metadata.author,
            description = parsedEpub.metadata.description,
            coverPath = coverPath,
            originalUri = uri.toString(),
            originalFileName = fileName,
            chapterCount = parsedEpub.chapters.size,
            totalChunks = chunkDefs.size,
            createdAt = System.currentTimeMillis()
        )
        database.bookDao().insertBook(bookEntity)

        // 5. Save Chapter Entities
        val chapterEntities = parsedEpub.chapters.map { ch ->
            ChapterEntity(
                id = ch.chapterId,
                bookId = bookId,
                chapterOrder = ch.chapterOrder,
                originalHref = ch.href,
                title = ch.title,
                chunkCount = chunkDefs.count { it.chapterId == ch.chapterId }
            )
        }
        database.chapterDao().insertChapters(chapterEntities)

        // 6. Save Translation Chunks
        val chunkEntities = chunkDefs.map { def ->
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
        }
        database.chunkDao().insertChunks(chunkEntities)

        // 7. Save Translation Job with initial QUEUED status
        val jobEntity = TranslationJobEntity(
            id = jobId,
            bookId = bookId,
            status = "QUEUED",
            progress = 0f,
            completedChunks = 0,
            failedChunks = 0,
            totalChunks = chunkDefs.size,
            startedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        database.jobDao().insertJob(jobEntity)

        startQueueProcessing()
        return@withContext Pair(bookEntity, jobEntity)
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

        // Preserve PAUSED, PAUSING, or CANCELLED statuses
        if (currentJob.status == "PAUSED" || currentJob.status == "PAUSING" || currentJob.status == "CANCELLED") {
            database.jobDao().updateJobProgress(jobId, completed, failed, progress, currentJob.status)
            return
        }

        // When all chunks have completed, let checkAndFinalizeJob verify consistency and mark COMPLETED
        if (total > 0 && completed == total && failed == 0 && translating == 0) {
            return
        }

        // If chunks have failed and no pending or translating chunks remain, mark FAILED
        if (failed > 0 && (completed + failed == total) && translating == 0) {
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

        // Verify every chapter has correct order, body chunks, and translated results
        for (i in chapters.indices) {
            val ch = chapters[i]
            if (ch.chapterOrder != i) {
                return ConsistencyResult(false, "Chapter order mismatch at index $i: expected $i, found ${ch.chapterOrder}")
            }

            val chChunks = database.chunkDao().getChunksByJobAndChapter(jobId, ch.id)
            if (chChunks.isEmpty()) {
                return ConsistencyResult(false, "No chunks found for chapter ${ch.id} (index $i: ${ch.title})")
            }

            val sortedChunks = chChunks.sortedBy { it.chunkOrder }
            for (cIdx in sortedChunks.indices) {
                val chunk = sortedChunks[cIdx]
                if (chunk.chunkOrder != cIdx) {
                    return ConsistencyResult(false, "Chunk order mismatch in chapter '${ch.title}': expected order $cIdx, found ${chunk.chunkOrder}")
                }
                if (chunk.status != "COMPLETED" || chunk.translatedText.isNullOrBlank()) {
                    return ConsistencyResult(false, "Incomplete translation for chunk ${chunk.id} in chapter '${ch.title}'")
                }
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

        // If any chunk is still translating, pending, or incomplete, the book must NOT be marked COMPLETED
        if (translating > 0 || incomplete > 0 || completed < total) {
            if (failed > 0 && (completed + failed == total) && translating == 0) {
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

        // When total == completed and 0 failed/translating/incomplete
        if (total > 0 && completed == total && failed == 0 && translating == 0 && incomplete == 0) {
            if (!finalizingJobIds.add(jobId)) {
                return // Finalization already in progress
            }

            try {
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

                // Translation is 100% verified and COMPLETED!
                database.jobDao().updateJob(
                    job.copy(
                        status = "COMPLETED",
                        progress = 1.0f,
                        completedChunks = total,
                        failedChunks = 0,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                )
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
            if (inFlight == 0) {
                database.jobDao().updateJobStatus(jobId, "PAUSED")
                Log.i("QueueManager", "Job $jobId transitioned directly to PAUSED (0 in-flight requests)")
            } else {
                database.jobDao().updateJobStatus(jobId, "PAUSING")
                Log.i("QueueManager", "Job $jobId set to PAUSING ($inFlight in-flight requests remaining)")
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
            database.chunkDao().retryFailedChunks(jobId)
            database.jobDao().updateJobStatus(jobId, "QUEUED")
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
            database.chunkDao().deleteChunksByJob(job.id)
            database.jobDao().deleteJobById(job.id)
        }
        database.chapterDao().deleteChaptersByBook(bookId)
        database.bookDao().deleteBookById(bookId)

        // Delete local files
        val bookDir = File(context.filesDir, "books/$bookId")
        bookDir.deleteRecursively()
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }
}

