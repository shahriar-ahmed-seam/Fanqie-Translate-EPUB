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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class TranslationQueueManager(
    private val context: Context,
    private val database: AppDatabase,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var provider: TranslationProvider = TomatoMTLProvider()

    private val _activeWorkers = MutableStateFlow(0)
    val activeWorkers: StateFlow<Int> = _activeWorkers.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val pausedJobs = ConcurrentHashMap.newKeySet<String>()
    private var loopJob: Job? = null

    init {
        // Startup recovery: reset interrupted chunks and start queue
        scope.launch {
            try {
                database.chunkDao().resetTranslatingChunksToPending()
                startQueueProcessing()
            } catch (e: Exception) {
                Log.e("QueueManager", "Error during startup queue recovery", e)
            }
        }
    }

    fun startQueueProcessing() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            _isProcessing.value = true
            while (isActive) {
                try {
                    val settings = settingsRepository.settings.value
                    processQueueIteration(settings)
                } catch (e: Exception) {
                    Log.e("QueueManager", "Exception in queue loop", e)
                }
                delay(1000)
            }
            _isProcessing.value = false
        }
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

        // 7. Save Translation Job
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

    private suspend fun processQueueIteration(settings: AppSettings) {
        val activeJobs = database.jobDao().getActiveJobs()
            .filterNot { pausedJobs.contains(it.id) }
            .take(settings.maxActiveBooks)

        if (activeJobs.isEmpty()) {
            _activeWorkers.value = 0
            return
        }

        // Set status to TRANSLATING for active jobs
        for (job in activeJobs) {
            if (job.status != "TRANSLATING") {
                database.jobDao().updateJobStatus(job.id, "TRANSLATING")
            }
        }

        val activeJobIds = activeJobs.map { it.id }
        val workerConcurrency = settings.workerCount
        val semaphore = Semaphore(workerConcurrency)
        val activeCounter = AtomicInteger(0)

        // Fetch batch of pending chunks for active jobs
        val pendingChunks = database.chunkDao().getPendingChunksForJobs(activeJobIds, workerConcurrency * 2)
        if (pendingChunks.isEmpty()) {
            // Check if any job completed
            for (job in activeJobs) {
                checkAndFinalizeJob(job.id)
            }
            return
        }

        coroutineScope {
            for (chunk in pendingChunks) {
                launch {
                    semaphore.withPermit {
                        if (pausedJobs.contains(chunk.jobId)) return@withPermit

                        val currentCount = activeCounter.incrementAndGet()
                        _activeWorkers.value = currentCount

                        // Mark chunk as translating
                        database.chunkDao().updateChunkResult(
                            id = chunk.id,
                            jobId = chunk.jobId,
                            bookId = chunk.bookId,
                            status = "TRANSLATING",
                            translatedText = null,
                            errorMessage = null
                        )

                        // Execute translation with retries
                        val (success, translatedText, error) = executeChunkWithRetry(
                            chunk = chunk,
                            maxRetries = settings.maxRetries
                        )

                        if (success && translatedText != null) {
                            database.chunkDao().updateChunkResult(
                                id = chunk.id,
                                jobId = chunk.jobId,
                                bookId = chunk.bookId,
                                status = "COMPLETED",
                                translatedText = translatedText,
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

                        activeCounter.decrementAndGet()
                        _activeWorkers.value = activeCounter.get()

                        // Update job progress
                        updateJobProgress(chunk.jobId)
                    }
                }
            }
        }

        // Post iteration: check if any job is fully done
        for (job in activeJobs) {
            checkAndFinalizeJob(job.id)
        }
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
                lastError = e
            }
            // Exponential backoff
            delay(500L * attempt)
        }
        return Triple(false, null, lastError)
    }

    private suspend fun updateJobProgress(jobId: String) {
        val total = database.chunkDao().getTotalChunkCount(jobId)
        val completed = database.chunkDao().getCompletedChunkCount(jobId)
        val failed = database.chunkDao().getFailedChunkCount(jobId)
        val progress = if (total > 0) completed.toFloat() / total.toFloat() else 0f
        val status = if (completed == total && total > 0) "COMPLETED" else "TRANSLATING"

        database.jobDao().updateJobProgress(jobId, completed, failed, progress, status)
    }

    private suspend fun checkAndFinalizeJob(jobId: String) {
        val total = database.chunkDao().getTotalChunkCount(jobId)
        val completed = database.chunkDao().getCompletedChunkCount(jobId)
        val failed = database.chunkDao().getFailedChunkCount(jobId)

        val job = database.jobDao().getJobById(jobId) ?: return

        if (total > 0 && completed == total) {
            // Rebuild final English EPUB
            try {
                val book = database.bookDao().getBookById(job.bookId) ?: return
                val bookDir = File(context.filesDir, "books/${book.id}")
                val sourceEpubFile = File(bookDir, "source.epub")
                val parsedEpub = EpubParser.parse(sourceEpubFile)

                val chapters = database.chapterDao().getChaptersByBook(book.id)
                val chunks = database.chunkDao().getChunksByJob(jobId)

                val exportName = sanitizeFileName("${book.title}-English.epub")
                val exportFile = File(bookDir, exportName)

                EpubRebuilder.rebuild(parsedEpub, chapters, chunks, exportFile)

                database.jobDao().updateJob(
                    job.copy(
                        status = "COMPLETED",
                        progress = 1.0f,
                        completedChunks = completed,
                        failedChunks = 0,
                        exportedUri = exportFile.absolutePath,
                        exportedFileName = exportName,
                        updatedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.e("QueueManager", "Failed to rebuild EPUB for job $jobId", e)
                database.jobDao().updateJob(
                    job.copy(
                        status = "FAILED",
                        errorMessage = "Rebuild failed: ${e.message}",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        } else if (failed > 0 && (completed + failed == total)) {
            database.jobDao().updateJobStatus(jobId, "FAILED")
        }
    }

    fun pauseJob(jobId: String) {
        pausedJobs.add(jobId)
        scope.launch {
            database.jobDao().updateJobStatus(jobId, "PAUSED")
        }
    }

    fun resumeJob(jobId: String) {
        pausedJobs.remove(jobId)
        scope.launch {
            database.jobDao().updateJobStatus(jobId, "QUEUED")
            startQueueProcessing()
        }
    }

    fun retryFailed(jobId: String) {
        pausedJobs.remove(jobId)
        scope.launch {
            database.chunkDao().retryFailedChunks(jobId)
            database.jobDao().updateJobStatus(jobId, "QUEUED")
            startQueueProcessing()
        }
    }

    fun cancelJob(jobId: String) {
        pausedJobs.remove(jobId)
        scope.launch {
            database.jobDao().updateJobStatus(jobId, "CANCELLED")
        }
    }

    suspend fun deleteBookAndJob(bookId: String) = withContext(Dispatchers.IO) {
        val job = database.jobDao().getJobByBookId(bookId)
        if (job != null) {
            pausedJobs.remove(job.id)
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
