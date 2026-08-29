package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.TranslatorApplication
import com.example.data.db.AppDatabase
import com.example.data.db.BookEntity
import com.example.data.db.TranslationJobEntity
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import com.example.epub.EpubParser
import com.example.epub.ParsedEpub
import com.example.queue.TranslationQueueManager
import com.example.update.AppUpdateManager
import com.example.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class BookWithJob(
    val book: BookEntity,
    val job: TranslationJobEntity?
)

data class BookPreviewState(
    val uri: Uri,
    val fileName: String,
    val parsedEpub: ParsedEpub,
    val tempCoverFile: File?
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TranslatorApplication
    val database = app.database
    val settingsRepository = app.settingsRepository
    val queueManager = app.queueManager
    val updateManager = AppUpdateManager(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val allBooksWithJobs: StateFlow<List<BookWithJob>> = combine(
        database.bookDao().getAllBooks(),
        database.jobDao().getAllJobs()
    ) { books, jobs ->
        val jobMap = jobs.associateBy { it.bookId }
        books.map { BookWithJob(it, jobMap[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeWorkersByJob: StateFlow<Map<String, Int>> = database.chunkDao()
        .observeTranslatingChunkCountsByJob()
        .map { list -> list.associate { it.jobId to it.count } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _exportingBookIds = MutableStateFlow<Set<String>>(emptySet())
    val exportingBookIds: StateFlow<Set<String>> = _exportingBookIds.asStateFlow()

    val activeWorkers: StateFlow<Int> = queueManager.activeWorkers
    val isProcessing: StateFlow<Boolean> = queueManager.isProcessing

    private val _previewState = MutableStateFlow<BookPreviewState?>(null)
    val previewState: StateFlow<BookPreviewState?> = _previewState.asStateFlow()

    private val _updateState = MutableStateFlow<ReleaseInfo?>(null)
    val updateState: StateFlow<ReleaseInfo?> = _updateState.asStateFlow()

    private val _updateProgress = MutableStateFlow<Float?>(null)
    val updateProgress: StateFlow<Float?> = _updateProgress.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        if (settings.value.autoCheckUpdates) {
            checkForUpdates(silent = true)
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    fun closePreview() {
        _previewState.value?.tempCoverFile?.delete()
        _previewState.value = null
    }

    fun previewSingleEpub(uri: Uri) {
        viewModelScope.launch {
            try {
                val fileName = getFileNameFromUri(getApplication(), uri)
                val tempFile = File(getApplication<Application>().cacheDir, "preview_${System.currentTimeMillis()}.epub")
                EpubParser.copyUriToTempFile(getApplication(), uri, tempFile)
                val parsed = EpubParser.parse(tempFile)

                var coverFile: File? = null
                if (parsed.coverBytes != null && parsed.coverBytes.isNotEmpty()) {
                    coverFile = File(getApplication<Application>().cacheDir, "preview_cover_${System.currentTimeMillis()}.png")
                    coverFile.writeBytes(parsed.coverBytes)
                }

                _previewState.value = BookPreviewState(uri, fileName, parsed, coverFile)
            } catch (e: Exception) {
                _message.value = "Failed to parse EPUB: ${e.message}"
            }
        }
    }

    fun enqueuePreviewedBook() {
        val state = _previewState.value ?: return
        viewModelScope.launch {
            try {
                queueManager.enqueueEpub(state.uri, state.fileName)
                closePreview()
                com.example.service.TranslationService.start(getApplication())
                _message.value = "Added '${state.parsedEpub.metadata.title}' to translation queue"
            } catch (e: Exception) {
                _message.value = "Failed to queue book: ${e.message}"
            }
        }
    }

    fun importMultipleEpubs(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var count = 0
            for (uri in uris) {
                try {
                    val fileName = getFileNameFromUri(getApplication(), uri)
                    queueManager.enqueueEpub(uri, fileName)
                    count++
                } catch (e: Exception) {
                    _message.value = "Error queuing EPUB: ${e.message}"
                }
            }
            if (count > 0) {
                com.example.service.TranslationService.start(getApplication())
                _message.value = "Successfully queued $count EPUB novel${if (count > 1) "s" else ""}"
            }
        }
    }

    fun pauseJob(jobId: String) = queueManager.pauseJob(jobId)
    fun resumeJob(jobId: String) = queueManager.resumeJob(jobId)
    fun retryFailed(jobId: String) = queueManager.retryFailed(jobId)
    fun cancelJob(jobId: String) = queueManager.cancelJob(jobId)

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            try {
                queueManager.deleteBookAndJob(bookId)
                _message.value = "Book deleted"
            } catch (e: Exception) {
                _message.value = "Failed to delete: ${e.message}"
            }
        }
    }

    fun exportEpubToUri(context: Context, bookId: String, exportedFilePath: String?, destinationUri: Uri) {
        if (_exportingBookIds.value.contains(bookId)) {
            _message.value = "Export already in progress for this book"
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _exportingBookIds.update { it + bookId }
            try {
                val book = database.bookDao().getBookById(bookId)
                val job = database.jobDao().getJobByBookId(bookId)
                val bookDir = File(getApplication<Application>().filesDir, "books/$bookId")
                val sourceEpubFile = File(bookDir, "source.epub")

                var exportFile = if (!exportedFilePath.isNullOrBlank()) File(exportedFilePath) else null

                // If export file doesn't exist or is empty or needs rebuilding, build it using streaming rebuilder
                if (exportFile == null || !exportFile.exists() || exportFile.length() == 0L) {
                    if (job == null || book == null) {
                        _message.value = "Cannot export: book record not found"
                        return@launch
                    }

                    val titleChunk = database.chunkDao().getTitleChunk(job.id)
                    val translatedTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() } ?: book.title
                    val exportName = "${com.example.epub.EpubRebuilder.sanitizeFileName(translatedTitle)}.epub"
                    exportFile = File(bookDir, exportName)

                    com.example.epub.EpubRebuilder.rebuild(
                        sourceEpubFile = sourceEpubFile,
                        bookId = book.id,
                        jobId = job.id,
                        database = database,
                        outputFile = exportFile
                    )

                    database.jobDao().updateJob(
                        job.copy(
                            status = "COMPLETED",
                            exportedUri = exportFile.absolutePath,
                            exportedFileName = exportName,
                            errorMessage = null,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                }

                // Stream export file to Destination URI via SAF
                val outputStream = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw IllegalStateException("Could not open destination document for writing")

                outputStream.use { output ->
                    exportFile.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }

                _message.value = "EPUB exported successfully!"
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Export failed for book $bookId", e)
                _message.value = "EPUB export failed: ${e.message ?: "Unknown error"}"
            } finally {
                _exportingBookIds.update { it - bookId }
            }
        }
    }

    fun updateSettings(newSettings: AppSettings) {
        settingsRepository.updateSettings(newSettings)
        _message.value = "Settings saved"
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            val s = settings.value
            val result = updateManager.checkForUpdates(s.githubOwner, s.githubRepo)
            if (result.isSuccess) {
                val info = result.getOrNull()
                if (info != null && info.isNewer) {
                    _updateState.value = info
                } else if (!silent) {
                    _message.value = "You are using the latest version!"
                }
            } else if (!silent) {
                _message.value = "Update check failed: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    fun dismissUpdateDialog() {
        _updateState.value = null
        _updateProgress.value = null
    }

    fun startApkDownload(downloadUrl: String) {
        viewModelScope.launch {
            _updateProgress.value = 0f
            val result = updateManager.downloadAndInstallApk(downloadUrl) { progress ->
                _updateProgress.value = progress
            }
            if (result.isFailure) {
                _updateProgress.value = null
                _message.value = "Failed to download update: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    companion object {
        fun getFileNameFromUri(context: Context, uri: Uri): String {
            var name = "novel.epub"
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = it.getString(index)
                    }
                }
            }
            return name
        }
    }
}
