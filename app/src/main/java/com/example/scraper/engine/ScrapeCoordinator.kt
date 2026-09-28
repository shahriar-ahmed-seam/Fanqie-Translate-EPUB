package com.example.scraper.engine

import com.example.scraper.browser.BrowserEngine
import com.example.scraper.data.ScrapeStore
import com.example.scraper.model.ChapterDraft
import com.example.scraper.model.LogLevel
import com.example.scraper.model.ProjectBinding
import com.example.scraper.model.ScrapeLogEntry
import com.example.scraper.model.ScrapeProgress
import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.ScrapeStatus
import com.example.scraper.model.WebsiteStrategy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Drives one scrape run at a time: extract the chapter on the current page, save it into the bound
 * project, wait the user's delay, open the next chapter, repeat.
 *
 * All public commands must be called from [scope]'s thread (the main thread in the app). Every run
 * is bound to one project explicitly; progress (resume URL, run counter, status) is persisted after
 * every chapter so a killed process can continue exactly where it stopped.
 */
class ScrapeCoordinator(
    private val browser: BrowserEngine,
    private val store: ScrapeStore,
    private val settings: () -> ScrapeSettings,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val random: Random = Random.Default
) {
    private val _progress = MutableStateFlow(ScrapeProgress())
    val progress: StateFlow<ScrapeProgress> = _progress.asStateFlow()

    private val _logs = MutableStateFlow<List<ScrapeLogEntry>>(emptyList())
    val logs: StateFlow<List<ScrapeLogEntry>> = _logs.asStateFlow()

    private var runJob: Job? = null
    private var currentToken: RunToken? = null
    private var logSeq = 0L
    private var lastWaitLogAt = Long.MIN_VALUE / 2
    private var lastWaitMessage = ""

    /** Project the next Resume continues. */
    var boundProjectId: String? = null
        private set

    val isRunning: Boolean get() = runJob?.isActive == true

    // ------------------------------------------------------------------ commands

    /** Starts a new run on the current page. Returns false if a run is already active. */
    fun start(binding: ProjectBinding): Boolean {
        if (isRunning) return false
        val url = browser.state.value.url
        _progress.value = ScrapeProgress(status = ScrapeStatus.STARTING, currentUrl = url, message = "Starting…")
        launchRun { token ->
            val projectId = try {
                when (binding) {
                    is ProjectBinding.New -> store.createProject(binding.title, url).id.also {
                        log(LogLevel.SUCCESS, "Created project \"${binding.title}\"")
                    }
                    is ProjectBinding.Existing -> binding.projectId
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish { copy(status = ScrapeStatus.ERROR, message = "Could not create the project: ${e.message}") }
                return@launchRun
            }
            boundProjectId = projectId
            guarded(token, projectId) {
                store.updateRun(projectId, ScrapeStatus.STARTING, url, 0, null)
                log(LogLevel.INFO, "Started a new run")
                runLoop(projectId, checkSite = false)
            }
        }
        return true
    }

    /** Continues the bound project from the page that is open now. */
    fun resume(): Boolean {
        if (isRunning) return false
        val projectId = boundProjectId ?: return false
        publish { copy(status = ScrapeStatus.STARTING, message = "Resuming…", lastError = null) }
        launchRun { token ->
            guarded(token, projectId) {
                log(LogLevel.INFO, "Resumed")
                runLoop(projectId, checkSite = true)
            }
        }
        return true
    }

    /** Opens the project's continue URL (next unscraped chapter) and runs from there. */
    fun resumeProject(projectId: String): Boolean {
        if (isRunning) return false
        boundProjectId = projectId
        _progress.value = ScrapeProgress(status = ScrapeStatus.STARTING, projectId = projectId, message = "Opening where you left off…")
        launchRun { token ->
            guarded(token, projectId) {
                val project = store.getProject(projectId) ?: throw ScrapeAbort(ScrapeStatus.ERROR, "Project not found")
                val unfinished = project.status == ScrapeStatus.PAUSED || project.status == ScrapeStatus.ERROR ||
                    project.status == ScrapeStatus.NEEDS_LOGIN || project.status.isActive
                if (!unfinished) store.updateRun(projectId, ScrapeStatus.STARTING, null, 0, null)
                val target = project.continueUrl
                publish { copy(projectTitle = project.title, totalChapters = project.chapterCount) }
                if (UrlIdentity.normalize(browser.state.value.url) != UrlIdentity.normalize(target)) {
                    publish { copy(status = ScrapeStatus.NAVIGATING, message = "Opening $target") }
                    log(LogLevel.INFO, "Opening $target")
                    if (!navigate(target)) throw ScrapeAbort(ScrapeStatus.ERROR, "Couldn't open $target")
                }
                runLoop(projectId, checkSite = false)
            }
        }
        return true
    }

    /** Remembers [projectId] as the one Resume continues, without starting. */
    fun bind(projectId: String?, title: String = "") {
        if (isRunning) return
        boundProjectId = projectId
        publish { copy(projectId = projectId, projectTitle = if (projectId == null) "" else title.ifBlank { projectTitle }) }
    }

    fun pause(message: String = "Paused. Tap Resume to continue.") = interrupt(ScrapeStatus.PAUSED, message)

    fun stop() = interrupt(ScrapeStatus.STOPPED, "Stopped. Saved chapters are kept.")

    /** Leaves the current page out of the book and moves on to the next chapter. */
    fun skip(): Boolean {
        val projectId = boundProjectId ?: return false
        val wasRunning = isRunning
        if (wasRunning) {
            currentToken?.terminal = ScrapeStatus.PAUSED to "Skipping this page…"
            runJob?.cancel()
        }
        launchRun { token ->
            guarded(token, projectId) {
                val url = browser.state.value.url
                val key = UrlIdentity.normalize(url)
                store.addSkippedUrl(projectId, key)
                log(LogLevel.WARNING, "Skipped $url")
                publish { copy(status = ScrapeStatus.NAVIGATING, message = "Looking for the next chapter…") }
                val visited = store.visitedUrlKeys(projectId) + key
                val result = PageAnalyzer.parse(browser.evaluate(DomScripts.discover()), url)
                val next = result?.let { findNext(it, url, visited) }
                    ?: NavigationResolver.synthesizeNext(url, UrlIdentity.chapterNumber(result?.chapterTitle, url))
                        ?.takeIf { UrlIdentity.normalize(it) !in visited }
                        ?.let { NextTarget(it, synthesized = true) }
                    ?: throw ScrapeAbort(
                        ScrapeStatus.PAUSED,
                        "Skipped, but no next chapter link was found. Open the next chapter yourself, then tap Resume."
                    )
                val opened = if (next.clickSelector != null) {
                    clickNext(next.clickSelector)
                } else {
                    store.setResumeUrl(projectId, next.url)
                    navigate(next.url)
                }
                if (!opened) throw ScrapeAbort(ScrapeStatus.ERROR, "Couldn't open the next chapter")
                store.setResumeUrl(projectId, browser.state.value.url)
                if (wasRunning) runLoop(projectId, checkSite = false)
                throw ScrapeAbort(ScrapeStatus.PAUSED, "Skipped. Tap Resume to scrape from this page.")
            }
        }
        return true
    }

    /** Reloads the page and tries the current chapter again. */
    fun retry(): Boolean {
        val projectId = boundProjectId ?: return false
        if (isRunning) {
            currentToken?.terminal = ScrapeStatus.PAUSED to "Retrying…"
            runJob?.cancel()
        }
        publish { copy(status = ScrapeStatus.LOADING, message = "Reloading…", lastError = null, retryCount = 0) }
        launchRun { token ->
            guarded(token, projectId) {
                log(LogLevel.INFO, "Retrying this page")
                browser.reload()
                delay(RELOAD_SETTLE_MS)
                runLoop(projectId, checkSite = false)
            }
        }
        return true
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }

    /** Each run has its own token so a run that is still cancelling can't overwrite a newer run's state. */
    private class RunToken {
        var terminal: Pair<ScrapeStatus, String>? = null
    }

    private fun launchRun(block: suspend CoroutineScope.(RunToken) -> Unit) {
        val previous = runJob
        val token = RunToken()
        currentToken = token
        runJob = scope.launch {
            previous?.join()
            block(token)
        }
    }

    private fun interrupt(status: ScrapeStatus, message: String) {
        val job = runJob
        if (job?.isActive == true) {
            currentToken?.terminal = status to message
            publish { copy(status = status, message = message, delayRemainingMs = 0L, delayTotalMs = 0L) }
            job.cancel()
        } else if (status == ScrapeStatus.STOPPED && _progress.value.status.isResumable && _progress.value.status != ScrapeStatus.STOPPED) {
            publish { copy(status = ScrapeStatus.STOPPED, message = message) }
            val id = boundProjectId ?: return
            scope.launch {
                runCatching { store.updateRun(id, ScrapeStatus.STOPPED, null, _progress.value.chaptersThisRun, null) }
            }
        }
    }

    // ------------------------------------------------------------------ run loop

    private class ScrapeAbort(val status: ScrapeStatus, message: String) : Exception(message)

    private data class NextTarget(val url: String, val synthesized: Boolean, val clickSelector: String? = null)

    private sealed interface Outcome {
        data class Ready(val result: ExtractionResult) : Outcome
        data class Failed(val message: String, val errorPage: Boolean) : Outcome
        data class Blocked(val message: String) : Outcome
    }

    private suspend fun guarded(token: RunToken, projectId: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (abort: ScrapeAbort) {
            finish(projectId, abort.status, abort.message ?: abort.status.label)
        } catch (e: CancellationException) {
            val (status, message) = token.terminal ?: (ScrapeStatus.STOPPED to "Stopped")
            withContext(NonCancellable) {
                // A newer run may already be waiting; only the latest run may publish its end state.
                if (currentToken === token) finish(projectId, status, message) else persistOnly(projectId, status)
            }
            throw e
        } catch (e: Exception) {
            finish(projectId, ScrapeStatus.ERROR, "Unexpected error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun persistOnly(projectId: String, status: ScrapeStatus) {
        try {
            store.updateRun(projectId, status, browser.state.value.url.takeIf { isWebUrl(it) }, _progress.value.chaptersThisRun, null)
        } catch (_: Exception) {
        }
    }

    private suspend fun runLoop(projectId: String, checkSite: Boolean): Nothing {
        val project = store.getProject(projectId) ?: throw ScrapeAbort(ScrapeStatus.ERROR, "Project not found")
        var chaptersThisRun = store.runChapterCount(projectId)
        var totalChapters = project.chapterCount
        val visited = store.visitedUrlKeys(projectId).toMutableSet()
        var previousHash: String? = null
        var pageRetries = 0
        var duplicatesInARow = 0
        var lastNavigationSynthesized = false

        publish {
            copy(
                status = ScrapeStatus.STARTING,
                projectId = projectId,
                projectTitle = project.title,
                chaptersThisRun = chaptersThisRun,
                totalChapters = totalChapters,
                retryCount = 0,
                lastError = null
            )
        }

        val startUrl = browser.state.value.url
        if (!isWebUrl(startUrl)) {
            throw ScrapeAbort(ScrapeStatus.PAUSED, "Open a chapter page in the browser, then tap Resume.")
        }
        if (checkSite && !UrlIdentity.isSameSite(startUrl, project.sourceUrl)) {
            throw ScrapeAbort(
                ScrapeStatus.PAUSED,
                "This page is on ${UrlIdentity.siteHost(startUrl)}, but \"${project.title}\" comes from ${project.siteHost}. " +
                    "Open a chapter from ${project.siteHost}, or start a new project."
            )
        }
        store.updateRun(projectId, ScrapeStatus.LOADING, startUrl, chaptersThisRun, null)

        while (true) {
            currentCoroutineContext().ensureActive()
            val url = browser.state.value.url
            val host = UrlIdentity.siteHost(url)
            publish { copy(status = ScrapeStatus.LOADING, currentUrl = url, message = "Loading page…", delayRemainingMs = 0L, delayTotalMs = 0L) }
            waitForLoad(minOf(PAGE_LOAD_WAIT_MS, settings().maxChapterWaitMs / 3))

            when (val outcome = extract(url, host, previousHash)) {
                is Outcome.Blocked -> throw ScrapeAbort(ScrapeStatus.NEEDS_LOGIN, outcome.message)
                is Outcome.Failed -> {
                    if (outcome.errorPage && lastNavigationSynthesized) {
                        store.setResumeUrl(projectId, null)
                        throw ScrapeAbort(ScrapeStatus.COMPLETED, "Reached the end: the guessed next chapter doesn't exist yet.")
                    }
                    val maxRetries = settings().maxRetryCount
                    if (pageRetries < maxRetries) {
                        pageRetries++
                        publish { copy(retryCount = pageRetries, message = "${outcome.message}. Retrying ($pageRetries/$maxRetries)…") }
                        log(LogLevel.WARNING, "${outcome.message}. Reloading (attempt $pageRetries of $maxRetries)")
                        browser.reload()
                        delay(RETRY_BACKOFF_MS * pageRetries)
                        continue
                    }
                    throw ScrapeAbort(ScrapeStatus.ERROR, "${outcome.message}. Check the page, then tap Retry or Resume.")
                }
                is Outcome.Ready -> {
                    pageRetries = 0
                    publish { copy(retryCount = 0) }
                    val result = outcome.result
                    val urlKey = UrlIdentity.normalize(url)
                    val canonicalKey = UrlIdentity.normalize(result.canonicalUrl)
                        .takeIf { it.isNotEmpty() && UrlIdentity.isSameSite(it, url) } ?: urlKey
                    val existing = store.findSavedChapter(projectId, urlKey, canonicalKey, result.contentHash)
                    var savedNewChapter = false
                    if (existing != null) {
                        duplicatesInARow++
                        log(LogLevel.INFO, "Already saved as #${existing.sequence} \"${existing.title}\", moving on")
                        if (duplicatesInARow > MAX_DUPLICATES_IN_A_ROW) {
                            throw ScrapeAbort(ScrapeStatus.COMPLETED, "Stopped after $MAX_DUPLICATES_IN_A_ROW pages in a row that were already saved.")
                        }
                    } else {
                        duplicatesInARow = 0
                        publish { copy(status = ScrapeStatus.SAVING, message = "Saving \"${result.chapterTitle}\"…") }
                        val saved = store.saveChapter(
                            projectId,
                            ChapterDraft(
                                title = result.chapterTitle.ifBlank { "Chapter ${totalChapters + 1}" },
                                chapterNumber = result.titleChapterNumber,
                                sourceUrl = url,
                                urlKey = urlKey,
                                canonicalUrl = result.canonicalUrl,
                                paragraphs = result.paragraphs,
                                contentHash = result.contentHash
                            )
                        )
                        chaptersThisRun++
                        totalChapters++
                        savedNewChapter = true
                        log(LogLevel.SUCCESS, "Saved #${saved.sequence} \"${saved.title}\" (${saved.characterCount} characters)")
                        publish {
                            copy(
                                chapterTitle = saved.title,
                                characterCount = saved.characterCount,
                                paragraphCount = saved.paragraphCount,
                                chaptersThisRun = chaptersThisRun,
                                totalChapters = totalChapters,
                                message = "Saved #${saved.sequence}"
                            )
                        }
                    }
                    visited += urlKey
                    visited += canonicalKey
                    previousHash = result.contentHash

                    val next = findNext(result, url, visited)
                    // A click target has no URL yet: resume re-opens this chapter and clicks again.
                    store.setResumeUrl(projectId, next?.url?.takeIf { it.isNotEmpty() } ?: url)
                    store.updateRun(projectId, ScrapeStatus.LOADING, url, chaptersThisRun, null)

                    val limit = settings().maxChaptersPerRun
                    if (limit > 0 && chaptersThisRun >= limit) {
                        throw ScrapeAbort(ScrapeStatus.COMPLETED, "Reached your limit of $limit chapters for this run. Resume continues from the next chapter.")
                    }
                    if (next == null) {
                        throw ScrapeAbort(ScrapeStatus.COMPLETED, "No next chapter link found. This looks like the latest chapter.")
                    }
                    if (savedNewChapter) {
                        cooldown()
                        respectSiteLimits(host)
                    } else {
                        delay(DUPLICATE_SKIP_DELAY_MS)
                    }

                    publish { copy(status = ScrapeStatus.NAVIGATING, message = "Opening the next chapter…") }
                    if (next.clickSelector != null) {
                        log(LogLevel.INFO, "The next chapter has no link; pressing the page's Next button")
                        if (!clickNext(next.clickSelector)) {
                            throw ScrapeAbort(ScrapeStatus.ERROR, "Pressed Next, but the next chapter didn't open. Open it yourself, then tap Resume.")
                        }
                    } else if (!navigate(next.url)) {
                        throw ScrapeAbort(ScrapeStatus.ERROR, "Couldn't open the next chapter (${next.url}). Tap Resume to try again.")
                    }
                    lastNavigationSynthesized = next.synthesized
                    if (next.synthesized) log(LogLevel.INFO, "No next link on the page; trying ${next.url}")
                }
            }
        }
    }

    private suspend fun extract(url: String, host: String, previousHash: String?): Outcome {
        val started = clock()
        val strategy: WebsiteStrategy? = store.getStrategy(host)
        var discover = strategy == null
        var rediscovered = false
        var misses = 0
        var stableHash: String? = null
        var stableSince = 0L
        var blockedSince = -1L
        var lastScrollAt = Long.MIN_VALUE / 2
        var lastReason = "No chapter text found on this page"

        while (true) {
            currentCoroutineContext().ensureActive()
            val s = settings()
            val now = clock()
            val elapsed = now - started
            if (elapsed > s.maxChapterWaitMs) {
                if (blockedSince >= 0) {
                    return Outcome.Blocked("The site is asking for a login or verification. Complete it in the browser, then tap Resume.")
                }
                return Outcome.Failed("Timed out after ${s.maxChapterWaitMs / 1000}s: $lastReason", errorPage = false)
            }
            val browserState = browser.state.value
            if (browserState.mainFrameError != null && !browserState.isLoading) {
                return Outcome.Failed("The page failed to load (${browserState.mainFrameError})", errorPage = true)
            }

            val script = if (discover || strategy == null) DomScripts.discover() else DomScripts.extract(strategy)
            val result = PageAnalyzer.parse(browser.evaluate(script), url)
            if (result == null) {
                delay(s.pollingIntervalMs)
                continue
            }

            if (result.isChallenge || result.isLoginWall) {
                if (blockedSince < 0) blockedSince = now
                val message = if (result.isChallenge) {
                    "The site is showing a verification check. Complete it in the browser, then tap Resume."
                } else {
                    "The site asks you to log in. Log in in the browser, then tap Resume."
                }
                waiting(if (result.isChallenge) "Waiting for the site's verification check…" else "Waiting: the site is asking you to log in…")
                // Verification pages often clear on their own after a few seconds.
                if (now - blockedSince >= BLOCKED_GRACE_MS) return Outcome.Blocked(message)
                delay(s.pollingIntervalMs)
                continue
            }
            blockedSince = -1L

            if (result.isErrorPage || (browserState.httpStatus >= 400 && !browserState.isLoading)) {
                val code = if (browserState.httpStatus >= 400) " (HTTP ${browserState.httpStatus})" else ""
                return Outcome.Failed("The page returned an error$code", errorPage = true)
            }

            if (!discover && !result.elementFound) {
                misses++
                lastReason = "the saved layout for $host doesn't match this page"
                if (misses >= STRATEGY_MISSES_BEFORE_REDISCOVERY && !browser.state.value.isLoading) {
                    discover = true
                    log(LogLevel.WARNING, "Saved layout for $host doesn't match this page; detecting it again")
                }
                waiting("Waiting for the chapter to appear…")
                delay(s.pollingIntervalMs)
                continue
            }

            if (previousHash != null && result.paragraphs.isNotEmpty() && result.contentHash == previousHash) {
                lastReason = "the page still shows the previous chapter"
                waiting("Waiting for the new chapter to replace the previous one…")
                delay(s.pollingIntervalMs)
                continue
            }

            if (result.characterCount < s.minCharacterCount) {
                stableHash = null
                lastReason = "only ${result.characterCount} of ${s.minCharacterCount} characters appeared"
                waiting("Waiting for chapter text (${result.characterCount}/${s.minCharacterCount} characters)…")
                if (s.autoScroll && now - lastScrollAt >= SCROLL_INTERVAL_MS) {
                    lastScrollAt = now
                    browser.evaluate(DomScripts.SCROLL_STEP)
                }
                delay(s.pollingIntervalMs)
                continue
            }

            val validation = ChapterValidator.validate(result, s)
            if (!validation.isValid) {
                lastReason = validation.message.replaceFirstChar { it.lowercase() }
                if (!discover && !rediscovered) {
                    discover = true
                    rediscovered = true
                    log(LogLevel.WARNING, "${validation.message} with the saved layout; detecting the page layout again")
                    continue
                }
                waiting(validation.message)
                delay(s.pollingIntervalMs)
                continue
            }

            val translationWaitEnabled = s.translationWaitMs > 0
            val untranslated = translationWaitEnabled && result.hasChineseContent
            if (untranslated && elapsed < s.translationWaitMs) {
                stableHash = null
                lastReason = "the text is still untranslated"
                val percent = ((1 - result.chineseRatio) * 100).toInt().coerceIn(0, 100)
                waiting("Waiting for the translation ($percent% English so far)…")
                // Some sites only translate text once it scrolls into view.
                if (s.autoScroll && now - lastScrollAt >= SCROLL_INTERVAL_MS) {
                    lastScrollAt = now
                    browser.evaluate(DomScripts.SCROLL_STEP)
                }
                delay(s.pollingIntervalMs)
                continue
            }
            // Mostly translated but some Chinese left: give the translation a little longer to finish.
            val partlyTranslated = translationWaitEnabled && !untranslated &&
                result.chineseRatio > PARTIAL_TRANSLATION_RATIO && elapsed < s.translationWaitMs
            val requiredStableMs = if (partlyTranslated) maxOf(s.stabilizationMs, PARTIAL_TRANSLATION_STABLE_MS) else s.stabilizationMs
            if (result.contentHash != stableHash) {
                stableHash = result.contentHash
                stableSince = now
                if (requiredStableMs > 0) {
                    waiting("Content is still changing…")
                    delay(s.pollingIntervalMs)
                    continue
                }
            }
            if (now - stableSince < requiredStableMs) {
                val left = (requiredStableMs - (now - stableSince) + 999) / 1000
                waiting("Letting the page settle (${left}s)…")
                delay(s.pollingIntervalMs)
                continue
            }

            if (untranslated) {
                log(LogLevel.WARNING, "The text is still mostly Chinese after ${s.translationWaitMs / 1000}s; saving it as it is")
            }

            if (discover && result.contentSelector.isNotBlank()) {
                store.saveStrategy(
                    WebsiteStrategy(
                        siteHost = host,
                        contentSelector = result.contentSelector,
                        titleSelector = result.titleSelector,
                        nextSelector = result.nextSelector,
                        successCount = 1
                    )
                )
                log(LogLevel.INFO, "Learned the page layout for $host (text: ${result.contentSelector})")
            } else if (strategy != null) {
                val learnedNext = strategy.nextSelector.isBlank() && result.nextSelector.isNotBlank()
                store.saveStrategy(
                    strategy.copy(
                        nextSelector = if (learnedNext) result.nextSelector else strategy.nextSelector,
                        successCount = strategy.successCount + 1
                    )
                )
            }
            publish { copy(strategyInfo = result.contentSelector, chapterTitle = result.chapterTitle) }
            return Outcome.Ready(result)
        }
    }

    /** Link on the page first, then a "Next" button without a link, then a URL guessed from the chapter number. */
    private fun findNext(result: ExtractionResult, currentUrl: String, visited: Set<String>): NextTarget? {
        NavigationResolver.selectNext(result.nextCandidates, currentUrl, result.navigationChapterNumber, visited)
            ?.let { return NextTarget(it.url, synthesized = false) }
        if (result.nextCandidates.isEmpty() && result.nextClickSelector.isNotBlank()) {
            return NextTarget(url = "", synthesized = false, clickSelector = result.nextClickSelector)
        }
        NavigationResolver.synthesizeNext(currentUrl, result.navigationChapterNumber)
            ?.takeIf { UrlIdentity.normalize(it) !in visited }
            ?.let { return NextTarget(it, synthesized = true) }
        return null
    }

    /** Clicks a "Next" button and waits until the browser is on a different page. */
    private suspend fun clickNext(selector: String): Boolean {
        val before = browser.state.value
        val beforeKey = UrlIdentity.normalize(before.url)
        val clicked = PageAnalyzer.decodeString(browser.evaluate(DomScripts.click(selector))) == "clicked"
        if (!clicked) return false
        val deadline = clock() + NAVIGATION_TIMEOUT_MS
        while (clock() < deadline) {
            delay(NAVIGATION_POLL_MS)
            val now = browser.state.value
            if (now.navigationCount != before.navigationCount && UrlIdentity.normalize(now.url) != beforeKey) return true
        }
        return false
    }

    private suspend fun waitForLoad(maxMs: Long) {
        val deadline = clock() + maxMs
        while (browser.state.value.isLoading && clock() < deadline) {
            delay(LOAD_POLL_MS)
        }
    }

    /** Loads [target] and waits until the browser starts the navigation. */
    private suspend fun navigate(target: String): Boolean {
        val attempts = settings().maxRetryCount + 1
        repeat(attempts) { attempt ->
            val before = browser.state.value.navigationCount
            browser.loadUrl(target)
            val deadline = clock() + NAVIGATION_TIMEOUT_MS
            while (clock() < deadline) {
                delay(NAVIGATION_POLL_MS)
                if (browser.state.value.navigationCount != before) return true
            }
            if (attempt < attempts - 1) log(LogLevel.WARNING, "The next chapter didn't start loading; trying again")
        }
        return false
    }

    /**
     * Keeps a run under the per-site reading limits (chapters saved from [host] across all projects).
     * The hourly limit waits until the oldest chapter in the window is an hour old; the daily limit
     * pauses the run, since waiting up to a day in the background isn't reasonable.
     */
    private suspend fun respectSiteLimits(host: String) {
        val daily = settings().maxChaptersPerDay
        if (daily > 0) {
            val now = wallClock()
            val times = store.recentSaveTimes(host, now - DAY_MS)
            if (times.size >= daily) {
                val resumeAt = times[times.size - daily] + DAY_MS
                throw ScrapeAbort(
                    ScrapeStatus.PAUSED,
                    "Daily limit reached: $daily chapters from $host in the last 24 hours. Resume after ${clockTime(resumeAt)}, " +
                        "or raise the limit in scraper settings."
                )
            }
        }
        while (true) {
            val hourly = settings().maxChaptersPerHour
            if (hourly <= 0) return
            val now = wallClock()
            val times = store.recentSaveTimes(host, now - HOUR_MS)
            if (times.size < hourly) return
            val until = times[times.size - hourly] + HOUR_MS
            val remaining = until - now
            if (remaining <= 0L) return
            log(LogLevel.INFO, "Hourly limit for $host reached ($hourly chapters). Waiting until ${clockTime(until)}")
            countdown(minOf(remaining, RATE_RECHECK_MS), remaining) { left ->
                "Hourly limit for $host reached ($hourly chapters). Continuing at ${clockTime(until)} (${formatDuration(left)})"
            }
        }
    }

    /** Shows a countdown of [waitMs] (out of [totalMs]) as COOLDOWN. */
    private suspend fun countdown(waitMs: Long, totalMs: Long, message: (remainingMs: Long) -> String) {
        var waited = 0L
        while (waited < waitMs) {
            val left = totalMs - waited
            publish { copy(status = ScrapeStatus.COOLDOWN, delayRemainingMs = left, delayTotalMs = totalMs, message = message(left)) }
            val step = minOf(1_000L, waitMs - waited)
            delay(step)
            waited += step
        }
        publish { copy(delayRemainingMs = 0L, delayTotalMs = 0L) }
    }

    private fun clockTime(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(epochMs))

    private fun formatDuration(ms: Long): String {
        val totalSeconds = (ms + 999) / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private suspend fun cooldown() {
        val s = settings()
        val total = if (s.delayMaxMs > s.delayMinMs) random.nextLong(s.delayMinMs, s.delayMaxMs + 1) else s.delayMinMs
        if (total <= 0L) return
        var remaining = total
        while (remaining > 0L) {
            val shown = remaining
            publish {
                copy(
                    status = ScrapeStatus.COOLDOWN,
                    delayRemainingMs = shown,
                    delayTotalMs = total,
                    message = "Next chapter in ${(shown + 999) / 1000}s"
                )
            }
            val step = minOf(COOLDOWN_TICK_MS, remaining)
            delay(step)
            remaining -= step
            // The delay can be shortened live from the scrape panel.
            val maxNow = settings().delayMaxMs
            if (remaining > maxNow) remaining = maxNow
        }
        publish { copy(delayRemainingMs = 0L, delayTotalMs = 0L) }
    }

    private suspend fun finish(projectId: String, status: ScrapeStatus, message: String) {
        val isProblem = status == ScrapeStatus.ERROR || status == ScrapeStatus.NEEDS_LOGIN
        publish {
            copy(
                status = status,
                message = message,
                delayRemainingMs = 0L,
                delayTotalMs = 0L,
                lastError = if (isProblem) message else null
            )
        }
        log(
            when (status) {
                ScrapeStatus.COMPLETED -> LogLevel.SUCCESS
                ScrapeStatus.ERROR -> LogLevel.ERROR
                ScrapeStatus.NEEDS_LOGIN -> LogLevel.WARNING
                else -> LogLevel.INFO
            },
            message
        )
        try {
            store.updateRun(projectId, status, browser.state.value.url.takeIf { isWebUrl(it) }, _progress.value.chaptersThisRun, if (isProblem) message else null)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Couldn't save the run state: ${e.message}")
        }
    }

    private fun waiting(message: String) {
        publish { copy(status = ScrapeStatus.WAITING_CONTENT, message = message) }
        val now = clock()
        if (message != lastWaitMessage || now - lastWaitLogAt >= WAIT_LOG_INTERVAL_MS) {
            lastWaitMessage = message
            lastWaitLogAt = now
            log(LogLevel.DEBUG, message)
        }
    }

    private inline fun publish(transform: ScrapeProgress.() -> ScrapeProgress) {
        _progress.update { it.transform() }
    }

    private fun log(level: LogLevel, message: String) {
        val entry = ScrapeLogEntry(id = ++logSeq, timestamp = wallClock(), message = message, level = level)
        _logs.update { (listOf(entry) + it).take(MAX_LOGS) }
    }

    private fun isWebUrl(url: String) = url.startsWith("http://") || url.startsWith("https://")

    companion object {
        const val MAX_LOGS = 300
        const val MAX_DUPLICATES_IN_A_ROW = 25
        const val PAGE_LOAD_WAIT_MS = 15_000L
        const val LOAD_POLL_MS = 250L
        const val NAVIGATION_TIMEOUT_MS = 30_000L
        const val NAVIGATION_POLL_MS = 200L
        const val COOLDOWN_TICK_MS = 250L
        const val RETRY_BACKOFF_MS = 2_000L
        const val RELOAD_SETTLE_MS = 500L
        const val DUPLICATE_SKIP_DELAY_MS = 400L
        const val BLOCKED_GRACE_MS = 15_000L
        const val SCROLL_INTERVAL_MS = 2_000L
        const val WAIT_LOG_INTERVAL_MS = 8_000L
        const val STRATEGY_MISSES_BEFORE_REDISCOVERY = 3
        const val PARTIAL_TRANSLATION_RATIO = 0.02
        const val PARTIAL_TRANSLATION_STABLE_MS = 5_000L
        const val HOUR_MS = 60 * 60 * 1000L
        const val DAY_MS = 24 * HOUR_MS
        /** How often a long rate-limit wait re-checks (so raising the limit takes effect). */
        const val RATE_RECHECK_MS = 30_000L
    }
}
