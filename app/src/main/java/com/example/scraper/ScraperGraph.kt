package com.example.scraper

import android.content.Context
import android.util.Log
import com.example.scraper.browser.WebViewBrowserEngine
import com.example.scraper.data.ScraperPreferences
import com.example.scraper.data.ScraperRepository
import com.example.scraper.engine.ScrapeCoordinator
import com.example.scraper.epub.ChapterOrdering
import com.example.scraper.epub.ScrapedEpubBuilder
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapedChapterSummary
import com.example.scraper.service.ScrapeForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

/** Implemented by the Application so services and view models can reach the scraper singletons. */
interface ScraperHost {
    val scraperGraph: ScraperGraph
}

/** Connects the scraper to the host app's library. */
interface ScraperLibraryBridge {
    /**
     * Adds or refreshes the library book for [project] from [epubFile], whose spine lists
     * [chapters] in the same order. Returns the book id.
     */
    suspend fun upsertBook(
        project: ScrapeProject,
        chapters: List<ScrapedChapterSummary>,
        epubFile: File,
        language: String,
        existingBookId: String?
    ): String
    suspend fun bookExists(bookId: String): Boolean
    val supportsTranslation: Boolean get() = false
    /** Queues [epubFile] for machine translation; returns the new book's title. */
    suspend fun queueTranslation(project: ScrapeProject, epubFile: File): String =
        throw UnsupportedOperationException("Translation is not available")
}

data class LibrarySyncResult(val bookId: String, val chapterCount: Int, val updatedExisting: Boolean)

/**
 * Process-wide scraper objects. Created lazily by the Application the first time the scraper is
 * used, so apps that never open the scraper never create a WebView.
 */
class ScraperGraph(
    context: Context,
    val repository: ScraperRepository,
    val libraryBridge: ScraperLibraryBridge? = null,
    legacyPreferencesFile: String? = null
) {
    val appContext: Context = context.applicationContext
    val preferences = ScraperPreferences(appContext).also { prefs ->
        if (legacyPreferencesFile != null) prefs.migrateLegacy(appContext, legacyPreferencesFile)
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val browser = WebViewBrowserEngine(
        appContext = appContext,
        startUrl = { preferences.lastBrowserUrl ?: preferences.settings.value.defaultBaseUrl },
        onPageFinished = { url, title ->
            preferences.lastBrowserUrl = url
            scope.launch(Dispatchers.IO) {
                try {
                    repository.recordVisit(url, title)
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't record history", e)
                }
            }
        }
    )

    val coordinator = ScrapeCoordinator(
        browser = browser,
        store = repository,
        settings = { preferences.settings.value },
        scope = scope
    )

    private val _interruptedProjectId = MutableStateFlow<String?>(null)
    /** Project whose run was cut off when the process died; drives the "Resume?" prompt. */
    val interruptedProjectId: StateFlow<String?> = _interruptedProjectId.asStateFlow()

    init {
        scope.launch {
            try {
                val interrupted = repository.interruptedProject()
                if (interrupted != null) {
                    _interruptedProjectId.value = interrupted.id
                    coordinator.bind(interrupted.id, interrupted.title)
                }
                repository.markInterruptedRunsPaused()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't check for interrupted runs", e)
            }
        }
        scope.launch {
            coordinator.progress.map { it.status.isActive }.distinctUntilChanged().collect { active ->
                if (active) {
                    _interruptedProjectId.value = null
                    if (preferences.settings.value.backgroundScraping) ScrapeForegroundService.start(appContext)
                }
            }
        }
    }

    fun dismissInterrupted() {
        _interruptedProjectId.value = null
    }

    /** Writes the project as an EPUB into [out], in reading order. */
    suspend fun writeEpub(projectId: String, out: OutputStream): ScrapedEpubBuilder.Result =
        writeEpub(projectId, out, ChapterOrdering.order(repository.chapterSummaries(projectId)))

    private suspend fun writeEpub(
        projectId: String,
        out: OutputStream,
        chapters: List<ScrapedChapterSummary>
    ): ScrapedEpubBuilder.Result = withContext(Dispatchers.IO) {
        val project = repository.getProject(projectId) ?: error("Project not found")
        ScrapedEpubBuilder.write(project, chapters, { repository.chapterParagraphs(it) }, out)
    }

    /** Builds the EPUB into a private file (written atomically). */
    suspend fun buildEpubFile(
        projectId: String,
        target: File,
        chapters: List<ScrapedChapterSummary>? = null
    ): ScrapedEpubBuilder.Result = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val ordered = chapters ?: ChapterOrdering.order(repository.chapterSummaries(projectId))
        val tmp = File(target.parentFile, target.name + ".tmp")
        val result = tmp.outputStream().buffered().use { writeEpub(projectId, it, ordered) }
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        result
    }

    /** A shareable copy in the cache directory, named after the project. */
    suspend fun buildShareFile(projectId: String): File {
        val project = repository.getProject(projectId) ?: error("Project not found")
        val file = File(File(appContext.cacheDir, "scraper_share"), safeFileName(project.title) + ".epub")
        buildEpubFile(projectId, file)
        return file
    }

    suspend fun saveToLibrary(projectId: String): LibrarySyncResult = withContext(Dispatchers.IO) {
        val bridge = libraryBridge ?: error("This app has no library")
        val project = repository.getProject(projectId) ?: error("Project not found")
        val file = File(File(appContext.cacheDir, "scraper_library"), "${project.id}.epub")
        val chapters = ChapterOrdering.order(repository.chapterSummaries(projectId))
        val result = buildEpubFile(projectId, file, chapters)
        try {
            val existing = project.linkedBookId?.takeIf { bridge.bookExists(it) }
            val bookId = bridge.upsertBook(project, chapters, file, result.language, existing)
            repository.setLinkedBook(projectId, bookId, result.chapterCount)
            LibrarySyncResult(bookId, result.chapterCount, updatedExisting = existing != null)
        } finally {
            file.delete()
        }
    }

    suspend fun queueTranslation(projectId: String): String = withContext(Dispatchers.IO) {
        val bridge = libraryBridge?.takeIf { it.supportsTranslation } ?: error("Translation is not available")
        val project = repository.getProject(projectId) ?: error("Project not found")
        val file = File(File(appContext.cacheDir, "scraper_translate"), "${project.id}.epub")
        buildEpubFile(projectId, file)
        try {
            bridge.queueTranslation(project, file)
        } finally {
            file.delete()
        }
    }

    companion object {
        private const val TAG = "ScraperGraph"
        const val EXTRA_OPEN_SCRAPER = "com.example.scraper.OPEN_SCRAPER"

        fun from(context: Context): ScraperGraph? = (context.applicationContext as? ScraperHost)?.scraperGraph

        fun safeFileName(title: String): String = title
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(80)
            .ifBlank { "novel" }
    }
}
