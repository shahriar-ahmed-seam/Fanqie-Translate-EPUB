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
import com.example.data.db.BookType
import com.example.data.db.ChapterEntity
import com.example.data.db.TranslationJobEntity
import com.example.data.db.LibraryGroupEntity
import com.example.data.db.BookGroupCrossRefEntity
import com.example.data.db.ChapterBookmarkEntity
import com.example.data.repository.AppSettings
import com.example.data.repository.LibraryViewMode
import com.example.data.repository.SettingsRepository
import com.example.data.db.toEntity
import com.example.data.db.toModel
import com.example.tts.rule.TtsRule
import com.example.epub.EpubParser
import com.example.queue.ImportProgress
import com.example.queue.TranslationQueueManager
import com.example.update.AppUpdateManager
import com.example.update.ReleaseInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipFile

data class BookWithJob(
    val book: BookEntity,
    val job: TranslationJobEntity?,
    val translatedTitle: String? = null
) {
    val displayTitle: String
        get() = translatedTitle?.takeIf { it.isNotBlank() } ?: book.title
}

data class BookPreviewState(
    val uri: Uri,
    val fileName: String,
    val title: String,
    val author: String,
    val description: String,
    val chapterCount: Int,
    val tempCoverFile: File?,
    val isLibraryIntent: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as TranslatorApplication
    val database = app.database
    val settingsRepository = app.settingsRepository
    val queueManager = app.queueManager
    val updateManager = AppUpdateManager(application)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
    val libraryViewMode: StateFlow<LibraryViewMode> = settingsRepository.libraryViewMode

    fun setLibraryViewMode(mode: LibraryViewMode) {
        settingsRepository.setLibraryViewMode(mode)
    }

    val allBooksWithJobs: StateFlow<List<BookWithJob>> = combine(
        database.bookDao().getAllBooks(),
        database.jobDao().getAllJobs(),
        database.chunkDao().observeAllTitleChunks()
    ) { books, jobs, titleChunks ->
        val jobMap = jobs.associateBy { it.bookId }
        val titleMap = titleChunks.associate { it.bookId to it.translatedText?.takeIf { t -> t.isNotBlank() } }
        books.map { BookWithJob(it, jobMap[it.id], titleMap[it.id]) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val libraryGroups: StateFlow<List<LibraryGroupEntity>> = database.groupDao().observeAllGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bookGroupCrossRefs: StateFlow<List<BookGroupCrossRefEntity>> = database.groupDao().observeAllCrossRefs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val ttsRules: StateFlow<List<TtsRule>> = database.ttsRuleDao().observeAllRules()
        .map { list -> list.map { it.toModel() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeWorkersByJob: StateFlow<Map<String, Int>> = queueManager.activeWorkersByJob

    private val _exportingBookIds = MutableStateFlow<Set<String>>(emptySet())
    val exportingBookIds: StateFlow<Set<String>> = _exportingBookIds.asStateFlow()

    val activeWorkers: StateFlow<Int> = queueManager.activeWorkers
    val isProcessing: StateFlow<Boolean> = queueManager.isProcessing
    val importProgress: StateFlow<ImportProgress?> = queueManager.importProgress

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

    fun previewSingleEpub(uri: Uri, isLibraryIntent: Boolean = false) {
        viewModelScope.launch {
            try {
                val fileName = getFileNameFromUri(getApplication(), uri)
                val tempFile = File(getApplication<Application>().cacheDir, "preview_${System.currentTimeMillis()}.epub")
                try {
                    EpubParser.copyUriToTempFile(getApplication(), uri, tempFile)
                    val quickInfo = EpubParser.parseQuickInfo(tempFile)

                    var coverFile: File? = null
                    if (quickInfo.coverBytes != null && quickInfo.coverBytes.isNotEmpty()) {
                        val ext = if (quickInfo.coverMediaType?.contains("png") == true) "png" else "jpg"
                        coverFile = File(getApplication<Application>().cacheDir, "preview_cover_${System.currentTimeMillis()}.$ext")
                        coverFile.writeBytes(quickInfo.coverBytes)
                    }

                    _previewState.value = BookPreviewState(
                        uri = uri,
                        fileName = fileName,
                        title = quickInfo.metadata.title,
                        author = quickInfo.metadata.author,
                        description = quickInfo.metadata.description,
                        chapterCount = quickInfo.spine.size,
                        tempCoverFile = coverFile,
                        isLibraryIntent = isLibraryIntent
                    )
                } finally {
                    if (tempFile.exists()) {
                        tempFile.delete()
                    }
                }
            } catch (e: Exception) {
                _message.value = "Failed to parse EPUB: ${e.message}"
            }
        }
    }

    suspend fun addEpubToLibraryInternal(uri: Uri, customFileName: String? = null): BookEntity = withContext(Dispatchers.IO) {
        val fileName = customFileName ?: getFileNameFromUri(getApplication(), uri)
        val bookId = UUID.randomUUID().toString()
        val bookDir = File(getApplication<Application>().filesDir, "books/$bookId").apply { mkdirs() }
        val sourceEpubFile = File(bookDir, "source.epub")

        // 1. Copy original file to persistent app storage
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            sourceEpubFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 32 * 1024)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")

        // 2. Parse EPUB metadata and spine
        val quickInfo = EpubParser.parseQuickInfo(sourceEpubFile)

        // 3. Extract and persist cover
        var coverPath: String? = null
        if (quickInfo.coverBytes != null && quickInfo.coverBytes.isNotEmpty()) {
            val ext = if (quickInfo.coverMediaType?.contains("png") == true) "png" else "jpg"
            val coverFile = File(bookDir, "cover.$ext")
            coverFile.writeBytes(quickInfo.coverBytes)
            coverPath = coverFile.absolutePath
        }

        // 4. Create and insert BookEntity (bookType = LOCAL, totalChunks = 0)
        val bookTitle = quickInfo.metadata.title.ifBlank { fileName.removeSuffix(".epub") }
        val bookAuthor = quickInfo.metadata.author.ifBlank { "Unknown Author" }
        val book = BookEntity(
            id = bookId,
            title = bookTitle,
            author = bookAuthor,
            description = quickInfo.metadata.description,
            coverPath = coverPath,
            originalUri = uri.toString(),
            originalFileName = fileName,
            chapterCount = quickInfo.spine.size,
            totalChunks = 0,
            createdAt = System.currentTimeMillis(),
            sourceLanguage = quickInfo.metadata.language.ifBlank { "und" },
            targetLanguage = "en",
            bookType = BookType.LOCAL,
            localFilePath = sourceEpubFile.absolutePath
        )
        database.bookDao().insertBook(book)
        database.groupDao().insertCrossRef(
            BookGroupCrossRefEntity(bookId = bookId, groupId = "default_local")
        )

        // 5. Parse TOC chapter titles and insert ChapterEntities
        val tocTitles = EpubParser.extractTocChapterTitles(sourceEpubFile, quickInfo)
        ZipFile(sourceEpubFile).use { zip ->
            val chapters = quickInfo.spine.mapIndexed { index, spineItem ->
                val title = EpubParser.resolveChapterTitle(
                    zip = zip,
                    opfDir = quickInfo.opfDirectory,
                    href = spineItem.href,
                    chapterOrder = index,
                    tocTitles = tocTitles
                )
                ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    chapterOrder = index,
                    originalHref = spineItem.href,
                    title = title,
                    chunkCount = 0
                )
            }
            database.chapterDao().insertChapters(chapters)
        }

        book
    }

    fun addEpubToLibrary(uri: Uri) {
        viewModelScope.launch {
            try {
                val book = addEpubToLibraryInternal(uri)
                _message.value = "Added '${book.title}' to library"
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to add EPUB to library", e)
                _message.value = "Failed to add to library: ${e.message}"
            }
        }
    }

    fun addPreviewedBookToLibrary() {
        val state = _previewState.value ?: return
        viewModelScope.launch {
            try {
                closePreview()
                val book = addEpubToLibraryInternal(state.uri, state.fileName)
                _message.value = "Added '${book.title}' to library"
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to add previewed book to library", e)
                _message.value = "Failed to add to library: ${e.message}"
            }
        }
    }

    fun importMultipleEpubsToLibrary(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            var count = 0
            for (uri in uris) {
                try {
                    addEpubToLibraryInternal(uri)
                    count++
                } catch (e: Exception) {
                    android.util.Log.e("MainViewModel", "Error adding EPUB to library", e)
                }
            }
            if (count > 0) {
                _message.value = "Added $count book${if (count > 1) "s" else ""} to library"
            }
        }
    }

    fun enqueuePreviewedBook() {
        val state = _previewState.value ?: return
        viewModelScope.launch {
            try {
                closePreview()
                queueManager.enqueueEpub(state.uri, state.fileName)
                com.example.service.TranslationService.start(getApplication())
                _message.value = "Added '${state.title}' to translation queue"
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

                if (book != null && (book.isLocalBook || job == null)) {
                    val src = book.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                        ?: sourceEpubFile
                    if (!src.exists() || src.length() == 0L) {
                        _message.value = "Cannot export: EPUB file not found"
                        return@launch
                    }
                    val outputStream = context.contentResolver.openOutputStream(destinationUri)
                        ?: throw IllegalStateException("Could not open destination document for writing")
                    outputStream.use { output ->
                        src.inputStream().use { input ->
                            input.copyTo(output, bufferSize = 32 * 1024)
                        }
                    }
                    _message.value = "EPUB exported successfully!"
                    return@launch
                }

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

    fun exportEnglishEpub(context: Context, bookId: String, destinationUri: Uri) {
        exportEpubToUri(context, bookId, null, destinationUri)
    }

    fun exportCustomChapters(
        context: Context,
        bookId: String,
        selectedChapterIds: Set<String>,
        customTitle: String?,
        destinationUri: Uri
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (_exportingBookIds.value.contains(bookId)) return@launch
            _exportingBookIds.update { it + bookId }

            try {
                val db = AppDatabase.getInstance(context)
                val book = db.bookDao().getBookById(bookId)
                    ?: throw IllegalArgumentException("Book not found: $bookId")
                val job = db.jobDao().getJobByBookId(bookId)

                val bookDir = File(getApplication<Application>().filesDir, "books/$bookId")
                val sourceFile = book.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                    ?: File(bookDir, "source.epub")
                if (!sourceFile.exists() || sourceFile.length() == 0L) {
                    throw IllegalStateException("Original EPUB source file is missing from local storage")
                }

                if (book.isLocalBook || job == null) {
                    val outputStream = context.contentResolver.openOutputStream(destinationUri)
                        ?: throw IllegalStateException("Could not open destination document for writing")
                    outputStream.use { output ->
                        sourceFile.inputStream().use { input ->
                            input.copyTo(output, bufferSize = 32 * 1024)
                        }
                    }
                    _message.value = "EPUB exported successfully!"
                    return@launch
                }

                val exportDir = File(context.filesDir, "exports")
                if (!exportDir.exists()) exportDir.mkdirs()

                val sanitizedTitle = (customTitle ?: book.title)
                    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    .trim()
                    .ifBlank { "novel_custom_export" }
                val exportName = "${sanitizedTitle}.epub"
                val exportFile = File(exportDir, "${System.currentTimeMillis()}_$exportName")

                // Rebuild Selected Chapters EPUB
                com.example.epub.EpubRebuilder.rebuild(
                    sourceEpubFile = sourceFile,
                    bookId = bookId,
                    jobId = job.id,
                    database = db,
                    outputFile = exportFile,
                    selectedChapterIds = selectedChapterIds,
                    customTitle = customTitle
                )

                // Stream export file to Destination URI via SAF
                val outputStream = context.contentResolver.openOutputStream(destinationUri)
                    ?: throw IllegalStateException("Could not open destination document for writing")

                outputStream.use { output ->
                    exportFile.inputStream().use { input ->
                        input.copyTo(output, bufferSize = 32 * 1024)
                    }
                }

                _message.value = "Selected chapters exported successfully!"
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Custom export failed for book $bookId", e)
                _message.value = "Export failed: ${e.message ?: "Unknown error"}"
            } finally {
                _exportingBookIds.update { it - bookId }
            }
        }
    }

    fun getLastReadChapterId(bookId: String): String? {
        return settingsRepository.getLastReadChapterId(bookId)
    }

    fun setLastReadChapterId(bookId: String, chapterId: String) {
        settingsRepository.setLastReadChapterId(bookId, chapterId)
    }

    fun getLastReadParagraphIndex(bookId: String, chapterId: String): Int {
        return settingsRepository.getLastReadParagraphIndex(bookId, chapterId)
    }

    fun setLastReadParagraphIndex(bookId: String, chapterId: String, paragraphIndex: Int) {
        settingsRepository.setLastReadParagraphIndex(bookId, chapterId, paragraphIndex)
    }

    fun updateSettings(newSettings: AppSettings) {
        settingsRepository.updateSettings(newSettings)
        _message.value = "Settings saved"
    }

    fun checkForUpdates(silent: Boolean = false, force: Boolean = !silent) {
        viewModelScope.launch {
            val s = settings.value
            val result = updateManager.checkForUpdates(s.githubOwner, s.githubRepo, force = force)
            if (result.isSuccess) {
                val info = result.getOrNull()
                if (info != null && info.isNewer) {
                    _updateState.value = info
                } else if (!silent) {
                    _message.value = "You are using the latest version (v${com.example.BuildConfig.VERSION_NAME})"
                }
            } else if (!silent) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _message.value = "Update check failed: $errorMsg"
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

    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val currentGroups = database.groupDao().getAllGroups()
            val maxOrder = currentGroups.maxOfOrNull { it.sortOrder } ?: 0
            val group = LibraryGroupEntity(
                id = UUID.randomUUID().toString(),
                name = trimmed,
                sortOrder = maxOrder + 1,
                isSystemGroup = false,
                systemKey = null
            )
            database.groupDao().insertGroup(group)
            _message.value = "Created group '$trimmed'"
        }
    }

    fun renameGroup(groupId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val group = database.groupDao().getGroupById(groupId) ?: return@launch
            if (!group.isSystemGroup) {
                database.groupDao().renameGroup(groupId, trimmed)
                _message.value = "Renamed to '$trimmed'"
            }
        }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch {
            val group = database.groupDao().getGroupById(groupId) ?: return@launch
            if (!group.isSystemGroup) {
                database.groupDao().deleteCrossRefsByGroup(groupId)
                database.groupDao().deleteGroup(groupId)
                _message.value = "Deleted group '${group.name}'"
            }
        }
    }

    fun toggleBookGroup(bookId: String, groupId: String, add: Boolean) {
        viewModelScope.launch {
            if (add) {
                database.groupDao().insertCrossRef(BookGroupCrossRefEntity(bookId = bookId, groupId = groupId))
            } else {
                database.groupDao().deleteCrossRef(bookId = bookId, groupId = groupId)
            }
        }
    }

    fun setBookGroups(bookId: String, selectedGroupIds: Set<String>) {
        viewModelScope.launch {
            val current = database.groupDao().getGroupIdsForBook(bookId).toSet()
            val toAdd = selectedGroupIds - current
            val toRemove = current - selectedGroupIds
            for (g in toAdd) {
                database.groupDao().insertCrossRef(BookGroupCrossRefEntity(bookId = bookId, groupId = g))
            }
            for (g in toRemove) {
                database.groupDao().deleteCrossRef(bookId = bookId, groupId = g)
            }
        }
    }

    fun observeBookmarkedChapterIds(bookId: String): Flow<List<String>> {
        return database.bookmarkDao().observeBookmarkedChapterIds(bookId)
    }

    fun observeIsChapterBookmarked(bookId: String, chapterId: String): Flow<Boolean> {
        return database.bookmarkDao().observeIsBookmarked(bookId, chapterId)
    }

    fun toggleBookmark(bookId: String, chapterId: String) {
        viewModelScope.launch {
            val exists = database.bookmarkDao().isBookmarked(bookId, chapterId)
            if (exists) {
                database.bookmarkDao().deleteBookmark(bookId, chapterId)
            } else {
                database.bookmarkDao().insertBookmark(
                    ChapterBookmarkEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = bookId,
                        chapterId = chapterId
                    )
                )
            }
        }
    }

    private suspend fun syncTtsRulesToMemory() {
        val app = getApplication<Application>() as? TranslatorApplication
        val all = database.ttsRuleDao().getAllRules().map { it.toModel() }
        app?.ttsTextProcessor?.setRules(all)
    }

    fun saveTtsRule(rule: TtsRule) {
        viewModelScope.launch {
            database.ttsRuleDao().insertRule(rule.toEntity())
            syncTtsRulesToMemory()
        }
    }

    fun deleteTtsRule(ruleId: String) {
        viewModelScope.launch {
            database.ttsRuleDao().deleteRuleById(ruleId)
            syncTtsRulesToMemory()
        }
    }

    fun toggleTtsRule(ruleId: String) {
        viewModelScope.launch {
            val entity = database.ttsRuleDao().getRuleById(ruleId) ?: return@launch
            database.ttsRuleDao().updateRule(entity.copy(isEnabled = !entity.isEnabled))
            syncTtsRulesToMemory()
        }
    }

    fun reorderTtsRule(ruleId: String, moveUp: Boolean) {
        viewModelScope.launch {
            val rules = database.ttsRuleDao().getAllRules().toMutableList()
            val index = rules.indexOfFirst { it.id == ruleId }
            if (index < 0) return@launch
            val targetIndex = if (moveUp) index - 1 else index + 1
            if (targetIndex in rules.indices) {
                val temp = rules[index]
                rules[index] = rules[targetIndex]
                rules[targetIndex] = temp

                val updated = rules.mapIndexed { idx, entity ->
                    entity.copy(sortOrder = idx)
                }
                database.ttsRuleDao().insertRules(updated)
                syncTtsRulesToMemory()
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
