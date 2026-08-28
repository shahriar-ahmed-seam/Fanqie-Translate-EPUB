package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    val database = AppDatabase.getInstance(application)
    val settingsRepository = SettingsRepository(application)
    val queueManager = TranslationQueueManager(application, database, settingsRepository)
    val updateManager = AppUpdateManager(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settings

    val allBooksWithJobs: StateFlow<List<BookWithJob>> = combine(
        database.bookDao().getAllBooks(),
        database.jobDao().getAllJobs()
    ) { books, jobs ->
        val jobMap = jobs.associateBy { it.bookId }
        books.map { BookWithJob(it, jobMap[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    fun exportEpubToUri(context: Context, exportedFilePath: String, destinationUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sourceFile = File(exportedFilePath)
                if (!sourceFile.exists()) {
                    _message.value = "Exported file does not exist"
                    return@launch
                }
                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                    sourceFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
                _message.value = "EPUB exported successfully!"
            } catch (e: Exception) {
                _message.value = "Export failed: ${e.message}"
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
