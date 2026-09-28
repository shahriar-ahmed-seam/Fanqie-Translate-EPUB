package com.example.scraper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.scraper.ScraperGraph
import com.example.scraper.engine.DomScripts
import com.example.scraper.engine.PageAnalyzer
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.epub.ChapterOrdering
import com.example.scraper.model.BrowserHistoryItem
import com.example.scraper.model.ProjectBinding
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.ScrapedChapterSummary
import com.example.scraper.model.ScrapeStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface ScraperScreen {
    data object Home : ScraperScreen
    data object Browser : ScraperScreen
    data object History : ScraperScreen
    data object Settings : ScraperScreen
    data class Project(val projectId: String) : ScraperScreen
}

/** What the "Start scraping" sheet offers for the page that is open. */
data class ProjectChooserState(
    val suggestedTitle: String,
    val projects: List<ScrapeProject>,
    val suggestedProjectId: String?
)

/** A chapter opened in the preview sheet. */
data class ChapterPreview(val chapter: ScrapedChapterSummary, val paragraphs: List<String>)

class ScraperViewModel(application: Application) : AndroidViewModel(application) {

    val graph: ScraperGraph = ScraperGraph.from(application)
        ?: error("The Application must implement ScraperHost")
    private val repository = graph.repository
    val coordinator = graph.coordinator
    val browser = graph.browser

    val settings: StateFlow<ScrapeSettings> = graph.preferences.settings
    val progress = coordinator.progress
    val logs = coordinator.logs
    val browserState = browser.state
    val hasLibrary: Boolean get() = graph.libraryBridge != null
    val canTranslate: Boolean get() = graph.libraryBridge?.supportsTranslation == true

    val projects: StateFlow<List<ScrapeProject>> = repository.observeProjects()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val history: StateFlow<List<BrowserHistoryItem>> = repository.observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val learnedLayouts: StateFlow<Int> = repository.observeStrategyCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val dismissedResume = MutableStateFlow(graph.preferences.dismissedResumeProjectId)

    /** Project to offer "Resume?" for: a run cut off by process death, else a paused/failed one. */
    val resumeCandidate: StateFlow<ScrapeProject?> = combine(
        projects,
        graph.interruptedProjectId,
        progress.map { it.status.isActive },
        dismissedResume
    ) { list, interruptedId, active, dismissed ->
        if (active) return@combine null
        list.firstOrNull { it.id == interruptedId }
            ?: list.firstOrNull {
                it.id != dismissed && (it.status == ScrapeStatus.PAUSED || it.status == ScrapeStatus.ERROR || it.status == ScrapeStatus.NEEDS_LOGIN)
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val backStack = ArrayDeque<ScraperScreen>()
    private val _screen = MutableStateFlow<ScraperScreen>(ScraperScreen.Home)
    val screen: StateFlow<ScraperScreen> = _screen.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _busyProjects = MutableStateFlow<Set<String>>(emptySet())
    val busyProjects: StateFlow<Set<String>> = _busyProjects.asStateFlow()

    private val _shareFile = MutableStateFlow<File?>(null)
    val shareFile: StateFlow<File?> = _shareFile.asStateFlow()

    private val _chooser = MutableStateFlow<ProjectChooserState?>(null)
    val chooser: StateFlow<ProjectChooserState?> = _chooser.asStateFlow()

    private val _preview = MutableStateFlow<ChapterPreview?>(null)
    val preview: StateFlow<ChapterPreview?> = _preview.asStateFlow()

    // ------------------------------------------------------------ navigation

    fun open(target: ScraperScreen) {
        val current = _screen.value
        if (current == target) return
        backStack.addLast(current)
        if (backStack.size > 20) backStack.removeFirst()
        _screen.value = target
    }

    /** Returns false when already on the scraper's first screen. */
    fun back(): Boolean {
        if (_screen.value == ScraperScreen.Home) return false
        _screen.value = backStack.removeLastOrNull() ?: ScraperScreen.Home
        return true
    }

    fun goHome() {
        backStack.clear()
        _screen.value = ScraperScreen.Home
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun consumeShareFile() {
        _shareFile.value = null
    }

    // ------------------------------------------------------------ browser

    fun openUrl(input: String) {
        if (input.isBlank()) return
        browser.loadUrl(input)
        open(ScraperScreen.Browser)
    }

    fun openBrowser() = open(ScraperScreen.Browser)

    // ------------------------------------------------------------ scraping

    fun requestStart() {
        viewModelScope.launch {
            val url = browserState.value.url
            val result = PageAnalyzer.parse(browser.evaluate(DomScripts.discover()), url)
            val pageTitle = browserState.value.title
            val suggestedTitle = result?.novelTitle?.takeIf { it.isNotBlank() }
                ?: pageTitle.split(Regex("\\s[-–—|]\\s")).firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: "Untitled novel"
            val all = withContext(Dispatchers.IO) { repository.allProjects() }
            val key = UrlIdentity.normalize(url)
            val host = UrlIdentity.siteHost(url)
            val suggested = all.firstOrNull { UrlIdentity.normalize(it.resumeUrl) == key || UrlIdentity.normalize(it.lastChapterUrl) == key }
                ?: all.firstOrNull { it.id == coordinator.boundProjectId && it.siteHost == host }
                ?: all.firstOrNull { it.siteHost == host && it.title.equals(suggestedTitle, ignoreCase = true) }
            val ordered = all.sortedWith(compareByDescending<ScrapeProject> { it.id == suggested?.id }.thenByDescending { it.siteHost == host }.thenByDescending { it.updatedAt })
            _chooser.value = ProjectChooserState(suggestedTitle, ordered, suggested?.id)
        }
    }

    fun dismissChooser() {
        _chooser.value = null
    }

    fun startNewProject(title: String) {
        _chooser.value = null
        if (!coordinator.start(ProjectBinding.New(title.trim().ifBlank { "Untitled novel" }))) {
            _message.value = "A scrape is already running"
        }
    }

    fun startInProject(projectId: String) {
        _chooser.value = null
        if (!coordinator.start(ProjectBinding.Existing(projectId))) {
            _message.value = "A scrape is already running"
        }
    }

    fun resume() {
        if (!coordinator.resume()) requestStart()
    }

    fun pause() = coordinator.pause()
    fun stop() = coordinator.stop()
    fun skip() {
        if (!coordinator.skip()) _message.value = "Start a scrape first"
    }

    fun retry() {
        if (!coordinator.retry()) requestStart()
    }

    fun resumeProject(projectId: String) {
        dismissResumePrompt(projectId)
        if (coordinator.resumeProject(projectId)) {
            open(ScraperScreen.Browser)
        } else {
            _message.value = "Pause the running scrape first"
        }
    }

    /** Opens the project's next chapter in the browser without starting. */
    fun openProjectInBrowser(project: ScrapeProject, url: String = project.continueUrl) {
        coordinator.bind(project.id, project.title)
        browser.loadUrl(url)
        open(ScraperScreen.Browser)
    }

    fun dismissResumePrompt(projectId: String?) {
        graph.dismissInterrupted()
        graph.preferences.dismissedResumeProjectId = projectId
        dismissedResume.value = projectId
    }

    fun setDelayRange(minMs: Long, maxMs: Long) = updateSettings { it.copy(delayMinMs = minMs, delayMaxMs = maxMs) }

    fun updateSettings(transform: (ScrapeSettings) -> ScrapeSettings) = graph.preferences.update(transform)

    // ------------------------------------------------------------ projects

    fun observeProject(id: String): Flow<ScrapeProject?> = repository.observeProject(id)

    fun observeChapters(id: String): Flow<List<ScrapedChapterSummary>> =
        repository.observeChapters(id).map { ChapterOrdering.order(it) }

    fun renameProject(id: String, title: String, author: String?) {
        viewModelScope.launch { repository.renameProject(id, title, author) }
    }

    fun deleteProject(id: String) {
        if (coordinator.isRunning && progress.value.projectId == id) {
            _message.value = "Stop the scrape before deleting this project"
            return
        }
        viewModelScope.launch {
            repository.deleteProject(id)
            if (coordinator.boundProjectId == id) coordinator.bind(null)
            if (_screen.value == ScraperScreen.Project(id)) back()
            _message.value = "Project deleted"
        }
    }

    fun deleteChapter(projectId: String, chapterId: String) {
        viewModelScope.launch { repository.deleteChapter(projectId, chapterId) }
    }

    fun openPreview(chapter: ScrapedChapterSummary) {
        viewModelScope.launch {
            val paragraphs = withContext(Dispatchers.IO) { repository.chapterParagraphs(chapter.id) }
            _preview.value = ChapterPreview(chapter, paragraphs)
        }
    }

    fun closePreview() {
        _preview.value = null
    }

    fun exportEpub(projectId: String, destination: Uri) = runProjectTask(projectId) {
        val resolver = getApplication<Application>().contentResolver
        val result = withContext(Dispatchers.IO) {
            resolver.openOutputStream(destination)?.buffered()?.use { graph.writeEpub(projectId, it) }
                ?: error("Couldn't open the destination file")
        }
        "Exported ${result.chapterCount} chapters"
    }

    fun shareEpub(projectId: String) = runProjectTask(projectId) {
        _shareFile.value = graph.buildShareFile(projectId)
        null
    }

    fun saveToLibrary(projectId: String) = runProjectTask(projectId) {
        val result = graph.saveToLibrary(projectId)
        if (result.updatedExisting) "Library copy updated (${result.chapterCount} chapters)"
        else "Added to your library (${result.chapterCount} chapters)"
    }

    fun queueTranslation(projectId: String) = runProjectTask(projectId) {
        val title = graph.queueTranslation(projectId)
        "\"$title\" was added to the translation queue"
    }

    private fun runProjectTask(projectId: String, block: suspend () -> String?) {
        if (projectId in _busyProjects.value) return
        viewModelScope.launch {
            _busyProjects.update { it + projectId }
            try {
                block()?.let { _message.value = it }
            } catch (e: Exception) {
                _message.value = e.message ?: "Something went wrong"
            } finally {
                _busyProjects.update { it - projectId }
            }
        }
    }

    // ------------------------------------------------------------ history & maintenance

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { repository.deleteHistoryItem(id) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            _message.value = "Browsing history cleared"
        }
    }

    fun clearLearnedLayouts() {
        viewModelScope.launch {
            repository.clearStrategies()
            _message.value = "Learned page layouts cleared"
        }
    }

    fun clearLogs() = coordinator.clearLogs()
}
