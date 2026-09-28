package com.example.scraper

import com.example.scraper.browser.BrowserEngine
import com.example.scraper.browser.BrowserUiState
import com.example.scraper.data.ScrapeStore
import com.example.scraper.engine.DomScripts
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.model.ChapterDraft
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapeStatus
import com.example.scraper.model.ScrapedChapterSummary
import com.example.scraper.model.WebsiteStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A scripted website: what the DOM scripts would report for each page, over time. */
class FakeSite {
    data class Page(
        val url: String,
        val title: String,
        val paragraphs: List<String>,
        val next: String? = null,
        val novelTitle: String = "Test Novel",
        /** Content is missing until this long after the page loaded. */
        val readyAfterMs: Long = 0,
        /** Content is Chinese until this long after the page loaded. */
        val chineseUntilMs: Long = 0,
        /** Content keeps changing until this long after the page loaded. */
        val changingUntilMs: Long = 0,
        /** The previous page's text stays in the DOM until this long after the page loaded. */
        val staleUntilMs: Long = 0,
        val staleParagraphs: List<String> = emptyList(),
        val httpStatus: Int = 200,
        val loginWall: Boolean = false,
        val errorPage: Boolean = false,
        val linkDensity: Double = 0.02,
        /** The saved (learned) selector no longer matches this page. */
        val strategyMisses: Boolean = false,
        /** The page has only a "Next" button (no link); clicking it opens [next]. */
        val nextByClick: Boolean = false
    )

    val pages = LinkedHashMap<String, Page>()

    fun add(page: Page) {
        pages[page.url] = page
    }

    companion object {
        fun paragraphsFor(n: Int) = List(6) { p -> "Chapter $n paragraph ${p + 1}. " + "The story continues with more words here. ".repeat(4) }

        /** A linear novel at https://novel.test/book/chapter-1 … chapter-N. */
        fun linear(count: Int, configure: (Int, Page) -> Page = { _, p -> p }): FakeSite {
            val site = FakeSite()
            for (n in 1..count) {
                val url = chapterUrl(n)
                val base = Page(
                    url = url,
                    title = "Chapter $n: Title $n",
                    paragraphs = paragraphsFor(n),
                    next = if (n < count) chapterUrl(n + 1) else null
                )
                site.add(configure(n, base))
            }
            return site
        }

        fun chapterUrl(n: Int) = "https://novel.test/book/chapter-$n"
    }
}

class FakeBrowser(private val site: FakeSite, private val clock: () -> Long) : BrowserEngine {
    private val _state = MutableStateFlow(BrowserUiState())
    override val state: StateFlow<BrowserUiState> = _state

    private var loadedAt = 0L
    private var previousParagraphs: List<String> = emptyList()
    val loads = mutableListOf<String>()
    var evaluations = 0
    var scrolls = 0
    /** When false, loadUrl is ignored (simulates a navigation that never starts). */
    var navigationWorks = true

    override fun loadUrl(url: String) {
        if (!navigationWorks) return
        loads += url
        val current = site.pages[_state.value.url]
        previousParagraphs = current?.paragraphs ?: emptyList()
        loadedAt = clock()
        val page = site.pages[url]
        _state.value = _state.value.copy(
            url = url,
            title = page?.title.orEmpty(),
            isLoading = false,
            progress = 100,
            navigationCount = _state.value.navigationCount + 1,
            httpStatus = when {
                page == null -> 404
                page.httpStatus >= 400 -> page.httpStatus
                else -> 0
            },
            mainFrameError = null
        )
    }

    override fun reload() {
        val url = _state.value.url
        loadedAt = clock()
        previousParagraphs = emptyList()
        _state.value = _state.value.copy(navigationCount = _state.value.navigationCount + 1)
        if (url.isBlank()) return
        val page = site.pages[url]
        _state.value = _state.value.copy(
            httpStatus = when {
                page == null -> 404
                page.httpStatus >= 400 -> page.httpStatus
                else -> 0
            }
        )
    }

    override fun stopLoading() = Unit
    override fun goBack(): Boolean = false
    override fun goForward(): Boolean = false

    override suspend fun evaluate(script: String): String? {
        evaluations++
        if (script == DomScripts.SCROLL_STEP) {
            scrolls++
            return "\"down\""
        }
        val url = _state.value.url
        val page = site.pages[url]
        if (script.startsWith(DomScripts.MARKER_CLICK)) {
            val target = page?.next?.takeIf { page.nextByClick } ?: return "\"missing\""
            loadUrl(target)
            return "\"clicked\""
        }
        val obj = JSONObject()
        obj.put("url", url)
        obj.put("canonicalUrl", url)
        if (page == null) {
            obj.put("paragraphs", JSONArray())
            obj.put("isErrorPage", true)
            obj.put("elementFound", false)
            return JSONObject.quote(obj.toString())
        }
        if (page.strategyMisses && script.startsWith(DomScripts.MARKER_EXTRACT)) {
            obj.put("paragraphs", JSONArray())
            obj.put("elementFound", false)
            obj.put("chapterTitle", page.title)
            return JSONObject.quote(obj.toString())
        }
        val t = clock() - loadedAt
        val paragraphs = when {
            t < page.staleUntilMs -> page.staleParagraphs.ifEmpty { previousParagraphs }
            t < page.readyAfterMs -> emptyList()
            t < page.chineseUntilMs -> List(6) { "这是第${it}段中文内容，还没有翻译成英文。".repeat(8) }
            t < page.changingUntilMs -> page.paragraphs + "Loading comment $t"
            else -> page.paragraphs
        }
        obj.put("novelTitle", page.novelTitle)
        obj.put("chapterTitle", if (t < page.chineseUntilMs) "第${page.title.filter { it.isDigit() }}章" else page.title)
        obj.put("paragraphs", JSONArray(paragraphs))
        obj.put("linkDensity", page.linkDensity)
        obj.put("elementFound", true)
        obj.put("isLoginWall", page.loginWall)
        obj.put("isChallenge", false)
        obj.put("isErrorPage", page.errorPage)
        obj.put("contentSelector", "#content")
        obj.put("titleSelector", "h1")
        obj.put("nextSelector", if (page.next != null && !page.nextByClick) "a.next" else "")
        val candidates = JSONArray()
        if (page.next != null && !page.nextByClick) {
            candidates.put(JSONObject().put("url", page.next).put("text", "Next Chapter").put("score", 80).put("reason", "text"))
        }
        obj.put("nextCandidates", candidates)
        obj.put("nextClickSelector", if (page.next != null && page.nextByClick) "button.next-chapter" else "")
        obj.put("atBottom", true)
        return JSONObject.quote(obj.toString())
    }
}

/** In-memory [ScrapeStore] with the same semantics as the Room repository. */
class FakeStore : ScrapeStore {
    data class Stored(val summary: ScrapedChapterSummary, val urlKey: String, val hash: String, val paragraphs: List<String>)

    val projects = LinkedHashMap<String, ScrapeProject>()
    val chapters = mutableListOf<Stored>()
    val skipped = HashMap<String, MutableSet<String>>()
    val runCounts = HashMap<String, Int>()
    val strategies = HashMap<String, WebsiteStrategy>()
    val statusHistory = mutableListOf<Pair<String, ScrapeStatus>>()
    /** Wall-clock time of each save, keyed by site host (for the reading limits). */
    val saveTimes = mutableListOf<Pair<String, Long>>()
    /** Wall clock used for save times; tests point it at the virtual test clock. */
    var clock: () -> Long = { 0L }
    private var time = 1_000L

    fun chaptersOf(projectId: String) = chapters.filter { it.summary.projectId == projectId }.sortedBy { it.summary.sequence }

    override suspend fun getProject(id: String): ScrapeProject? = projects[id]

    override suspend fun createProject(title: String, sourceUrl: String): ScrapeProject {
        val project = ScrapeProject(
            id = UUID.randomUUID().toString(),
            title = title,
            author = null,
            siteHost = UrlIdentity.siteHost(sourceUrl),
            sourceUrl = sourceUrl,
            lastChapterUrl = null,
            resumeUrl = sourceUrl,
            status = ScrapeStatus.IDLE,
            chapterCount = 0,
            totalCharacters = 0,
            lastError = null,
            linkedBookId = null,
            linkedChapterCount = 0,
            createdAt = time,
            updatedAt = time++
        )
        projects[project.id] = project
        return project
    }

    override suspend fun findSavedChapter(projectId: String, urlKey: String, canonicalKey: String, contentHash: String): ScrapedChapterSummary? =
        chaptersOf(projectId).firstOrNull { it.urlKey == urlKey || it.urlKey == canonicalKey }?.summary
            ?: chaptersOf(projectId).firstOrNull { it.hash == contentHash }?.summary

    override suspend fun visitedUrlKeys(projectId: String): Set<String> =
        chaptersOf(projectId).map { it.urlKey }.toSet() + skipped[projectId].orEmpty()

    override suspend fun saveChapter(projectId: String, draft: ChapterDraft): ScrapedChapterSummary {
        val sequence = (chaptersOf(projectId).maxOfOrNull { it.summary.sequence } ?: 0) + 1
        val summary = ScrapedChapterSummary(
            id = UrlIdentity.chapterId(projectId, draft.urlKey),
            projectId = projectId,
            sequence = sequence,
            chapterNumber = draft.chapterNumber,
            title = draft.title,
            sourceUrl = draft.sourceUrl,
            characterCount = draft.paragraphs.sumOf { it.length },
            paragraphCount = draft.paragraphs.size,
            createdAt = time++
        )
        chapters += Stored(summary, draft.urlKey, draft.contentHash, draft.paragraphs)
        val project = projects.getValue(projectId)
        saveTimes += project.siteHost to clock()
        projects[projectId] = project.copy(
            chapterCount = chaptersOf(projectId).size,
            totalCharacters = chaptersOf(projectId).sumOf { it.summary.characterCount.toLong() },
            lastChapterUrl = draft.sourceUrl,
            updatedAt = time++
        )
        return summary
    }

    override suspend fun setResumeUrl(projectId: String, url: String?) {
        projects[projectId] = projects.getValue(projectId).copy(resumeUrl = url)
    }

    override suspend fun addSkippedUrl(projectId: String, urlKey: String) {
        skipped.getOrPut(projectId) { mutableSetOf() } += urlKey
    }

    override suspend fun runChapterCount(projectId: String): Int = runCounts[projectId] ?: 0

    override suspend fun updateRun(projectId: String, status: ScrapeStatus, currentUrl: String?, chaptersThisRun: Int, error: String?) {
        runCounts[projectId] = chaptersThisRun
        statusHistory += projectId to status
        projects[projectId]?.let { projects[projectId] = it.copy(status = status, lastError = error) }
    }

    override suspend fun getStrategy(host: String): WebsiteStrategy? = strategies[host]

    override suspend fun saveStrategy(strategy: WebsiteStrategy) {
        strategies[strategy.siteHost] = strategy
    }

    override suspend fun deleteStrategy(host: String) {
        strategies.remove(host)
    }

    override suspend fun recentSaveTimes(siteHost: String, since: Long): List<Long> =
        saveTimes.filter { it.first == siteHost && it.second >= since }.map { it.second }.sorted()
}
