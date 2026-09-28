package com.example.scraper

import com.example.scraper.FakeSite.Companion.chapterUrl
import com.example.scraper.engine.ScrapeCoordinator
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.model.LogLevel
import com.example.scraper.model.ProjectBinding
import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.ScrapeStatus
import com.example.scraper.model.WebsiteStrategy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapeCoordinatorTest {

    private var settings = ScrapeSettings(
        minCharacterCount = 100,
        stabilizationMs = 500,
        pollingIntervalMs = 250,
        maxChapterWaitMs = 10_000,
        translationWaitMs = 5_000,
        maxRetryCount = 2,
        delayMinMs = 1_000,
        delayMaxMs = 1_000,
        autoScroll = true
    )

    private class Harness(val coordinator: ScrapeCoordinator, val browser: FakeBrowser, val store: FakeStore)

    // Runs must be foreground coroutines of the test: advanceUntilIdle() ignores backgroundScope work.
    private fun TestScope.harness(
        site: FakeSite,
        store: FakeStore = FakeStore(),
        scope: CoroutineScope = this,
        random: Random = Random(42)
    ): Harness {
        val browser = FakeBrowser(site) { testScheduler.currentTime }
        store.clock = { testScheduler.currentTime }
        val coordinator = ScrapeCoordinator(
            browser = browser,
            store = store,
            settings = { settings.normalized() },
            scope = scope,
            clock = { testScheduler.currentTime },
            wallClock = { testScheduler.currentTime },
            random = random
        )
        return Harness(coordinator, browser, store)
    }

    private fun Harness.onlyProject() = store.projects.values.single()
    private fun Harness.savedTitles() = store.chaptersOf(onlyProject().id).map { it.summary.title }

    @Test
    fun scrapesEveryChapterInOrderAndCompletesAtTheLatestChapter() = runTest {
        val h = harness(FakeSite.linear(3))
        h.browser.loadUrl(chapterUrl(1))

        assertTrue(h.coordinator.start(ProjectBinding.New("My Novel")))
        advanceUntilIdle()

        val progress = h.coordinator.progress.value
        assertEquals(ScrapeStatus.COMPLETED, progress.status)
        assertEquals(listOf("Chapter 1: Title 1", "Chapter 2: Title 2", "Chapter 3: Title 3"), h.savedTitles())
        assertEquals(listOf(1, 2, 3), h.store.chaptersOf(h.onlyProject().id).map { it.summary.sequence })
        // After the last linked chapter the scraper probes chapter-4 (from the URL pattern), which doesn't exist yet.
        assertEquals(listOf(chapterUrl(1), chapterUrl(2), chapterUrl(3), chapterUrl(4)), h.browser.loads)
        assertTrue(progress.message, progress.message.contains("Reached the end"))
        assertEquals("My Novel", h.onlyProject().title)
        assertEquals(3, progress.chaptersThisRun)
        assertEquals("resume re-checks the last chapter for a new next link", chapterUrl(3), h.onlyProject().continueUrl)
        assertEquals(ScrapeStatus.COMPLETED, h.onlyProject().status)
        assertFalse(h.coordinator.isRunning)
    }

    @Test
    fun completesAtTheLatestChapterWhenThereIsNothingToGuess() = runTest {
        val site = FakeSite()
        site.add(FakeSite.Page("https://novel.test/read?id=1", "Chapter 1", FakeSite.paragraphsFor(1), next = "https://novel.test/read?id=2"))
        site.add(FakeSite.Page("https://novel.test/read?id=2", "Chapter 2", FakeSite.paragraphsFor(2)))
        val h = harness(site)
        h.browser.loadUrl("https://novel.test/read?id=1")
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertTrue(h.coordinator.progress.value.message.contains("latest chapter"))
        assertEquals(2, h.store.chapters.size)
        assertEquals("https://novel.test/read?id=2", h.onlyProject().resumeUrl)
    }

    @Test
    fun learnsThePageLayoutFromTheFirstChapter() = runTest {
        val h = harness(FakeSite.linear(2))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val strategy = h.store.strategies["novel.test"]
        assertNotNull(strategy)
        assertEquals("#content", strategy!!.contentSelector)
        assertEquals("a.next", strategy.nextSelector)
    }

    @Test
    fun rediscoversTheLayoutWhenTheSavedSelectorStopsMatching() = runTest {
        val site = FakeSite.linear(2) { _, p -> p.copy(strategyMisses = true) }
        val store = FakeStore().apply { strategies["novel.test"] = WebsiteStrategy("novel.test", ".old-selector") }
        val h = harness(site, store)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertEquals(2, h.store.chaptersOf(h.onlyProject().id).size)
        assertEquals("#content", store.strategies["novel.test"]!!.contentSelector)
    }

    @Test
    fun waitsForTheLiveTranslationBeforeSaving() = runTest {
        val site = FakeSite.linear(1) { _, p -> p.copy(chineseUntilMs = 3_000) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val saved = h.store.chaptersOf(h.onlyProject().id).single()
        assertEquals("Chapter 1: Title 1", saved.summary.title)
        assertTrue(saved.paragraphs.all { UrlIdentity.cjkRatio(it) == 0.0 })
        assertTrue("page should be scrolled while waiting", h.browser.scrolls > 0)
    }

    @Test
    fun savesUntranslatedTextAfterTheTranslationWaitRunsOut() = runTest {
        val site = FakeSite.linear(1) { _, p -> p.copy(chineseUntilMs = 600_000) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val saved = h.store.chaptersOf(h.onlyProject().id).single()
        assertTrue(UrlIdentity.cjkRatio(saved.paragraphs.first()) > 0.5)
        assertTrue(h.coordinator.logs.value.any { it.level == LogLevel.WARNING && it.message.contains("still mostly Chinese") })
    }

    @Test
    fun translationWaitCanBeTurnedOffForChineseSites() = runTest {
        settings = settings.copy(translationWaitMs = 0)
        val site = FakeSite.linear(1) { _, p -> p.copy(chineseUntilMs = 600_000) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(2_000)
        runCurrent()

        assertEquals(1, h.store.chapters.size)
        assertFalse(h.coordinator.logs.value.any { it.message.contains("still mostly Chinese") })
    }

    @Test
    fun waitsUntilContentStopsChanging() = runTest {
        settings = settings.copy(stabilizationMs = 1_000)
        val site = FakeSite.linear(1) { _, p -> p.copy(changingUntilMs = 4_000) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val saved = h.store.chaptersOf(h.onlyProject().id).single()
        assertFalse(saved.paragraphs.any { it.startsWith("Loading comment") })
    }

    @Test
    fun waitsForLateContentInsteadOfFailing() = runTest {
        val site = FakeSite.linear(1) { _, p -> p.copy(readyAfterMs = 6_000) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()
        assertEquals(1, h.store.chapters.size)
    }

    @Test
    fun doesNotSaveThePreviousChapterAgainWhileTheNewPageIsStillSwapping() = runTest {
        val site = FakeSite.linear(2) { n, p -> if (n == 2) p.copy(staleUntilMs = 2_000) else p }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val saved = h.store.chaptersOf(h.onlyProject().id)
        assertEquals(2, saved.size)
        assertTrue(saved[1].paragraphs.first().startsWith("Chapter 2"))
        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
    }

    @Test
    fun pauseDuringCooldownThenResumeContinuesWithoutDuplicates() = runTest {
        settings = settings.copy(delayMinMs = 5_000, delayMaxMs = 5_000)
        val h = harness(FakeSite.linear(3))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ScrapeStatus.COOLDOWN, h.coordinator.progress.value.status)

        h.coordinator.pause()
        runCurrent()
        assertEquals(ScrapeStatus.PAUSED, h.coordinator.progress.value.status)
        assertEquals(ScrapeStatus.PAUSED, h.onlyProject().status)
        assertEquals(1, h.store.chapters.size)
        advanceTimeBy(60_000)
        assertEquals("paused run must not keep navigating", listOf(chapterUrl(1)), h.browser.loads)

        assertTrue(h.coordinator.resume())
        advanceUntilIdle()
        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertEquals(listOf("Chapter 1: Title 1", "Chapter 2: Title 2", "Chapter 3: Title 3"), h.savedTitles())
    }

    @Test
    fun pausingAndResumingImmediatelyDoesNotLeaveAStaleState() = runTest {
        val h = harness(FakeSite.linear(3))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(100)
        h.coordinator.pause()
        assertTrue(h.coordinator.resume())
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertEquals(ScrapeStatus.COMPLETED, h.onlyProject().status)
        assertEquals(3, h.store.chapters.size)
    }

    @Test
    fun stopKeepsSavedChaptersAndCannotBeResumedAccidentally() = runTest {
        settings = settings.copy(delayMinMs = 5_000, delayMaxMs = 5_000)
        val h = harness(FakeSite.linear(3))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(2_000)
        h.coordinator.stop()
        advanceUntilIdle()

        assertEquals(ScrapeStatus.STOPPED, h.coordinator.progress.value.status)
        assertEquals(ScrapeStatus.STOPPED, h.onlyProject().status)
        assertEquals(1, h.store.chapters.size)
        assertEquals("resume url points at the next chapter", chapterUrl(2), h.onlyProject().resumeUrl)
    }

    @Test
    fun cannotStartTwoRunsAtOnce() = runTest {
        val h = harness(FakeSite.linear(3))
        h.browser.loadUrl(chapterUrl(1))
        assertTrue(h.coordinator.start(ProjectBinding.New("Novel")))
        assertFalse(h.coordinator.start(ProjectBinding.New("Other")))
        advanceUntilIdle()
        assertEquals(1, h.store.projects.size)
    }

    @Test
    fun perRunLimitStopsAndResumeContinuesFromTheNextChapter() = runTest {
        settings = settings.copy(maxChaptersPerRun = 2)
        val h = harness(FakeSite.linear(5))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertTrue(h.coordinator.progress.value.message.contains("limit"))
        assertEquals(2, h.store.chapters.size)
        val projectId = h.onlyProject().id
        assertEquals(chapterUrl(3), h.onlyProject().resumeUrl)

        assertTrue(h.coordinator.resumeProject(projectId))
        advanceUntilIdle()
        assertEquals(4, h.store.chapters.size)
        assertEquals(chapterUrl(5), h.onlyProject().resumeUrl)
        assertEquals((1..4).map { "Chapter $it: Title $it" }, h.savedTitles())
    }

    @Test
    fun resumeAfterProcessDeathContinuesFromTheSavedPosition() = runTest {
        val store = FakeStore()
        settings = settings.copy(delayMinMs = 3_000, delayMaxMs = 3_000)
        val firstLife = CoroutineScope(coroutineContext + Job())
        val first = harness(FakeSite.linear(4), store, firstLife)
        first.browser.loadUrl(chapterUrl(1))
        first.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(5_500)
        runCurrent()
        val savedBeforeDeath = store.chapters.size
        assertTrue(savedBeforeDeath in 1..3)
        firstLife.cancel()
        runCurrent()

        // A new process: new coordinator and browser, same database.
        val second = harness(FakeSite.linear(4), store)
        val projectId = store.projects.keys.single()
        assertTrue(second.coordinator.resumeProject(projectId))
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, second.coordinator.progress.value.status)
        assertEquals((1..4).map { "Chapter $it: Title $it" }, store.chaptersOf(projectId).map { it.summary.title })
        assertEquals("the browser opened the next unscraped chapter first", chapterUrl(savedBeforeDeath + 1), second.browser.loads.first())
    }

    @Test
    fun loopingNextLinksEndCleanlyWithoutDuplicates() = runTest {
        val site = FakeSite()
        site.add(FakeSite.Page(chapterUrl(1), "Chapter 1", FakeSite.paragraphsFor(1), next = chapterUrl(2)))
        site.add(FakeSite.Page(chapterUrl(2), "Chapter 2", FakeSite.paragraphsFor(2), next = chapterUrl(1)))
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertEquals(2, h.store.chapters.size)
    }

    @Test
    fun guessedNextUrlThatDoesNotExistEndsTheRun() = runTest {
        val site = FakeSite.linear(2) { n, p -> if (n == 2) p.copy(next = null) else p }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        // chapter-3 is synthesised from the URL, returns 404, and the run completes instead of erroring.
        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertEquals(chapterUrl(3), h.browser.loads.last())
        assertEquals(2, h.store.chapters.size)
    }

    @Test
    fun serverErrorsAreRetriedThenReported() = runTest {
        val site = FakeSite.linear(1) { _, p -> p.copy(httpStatus = 503, errorPage = true) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val progress = h.coordinator.progress.value
        assertEquals(ScrapeStatus.ERROR, progress.status)
        assertNotNull(progress.lastError)
        assertEquals(0, h.store.chapters.size)
        assertEquals(ScrapeStatus.ERROR, h.onlyProject().status)
    }

    @Test
    fun loginWallAsksTheUserToActInTheBrowser() = runTest {
        val site = FakeSite.linear(1) { _, p -> p.copy(loginWall = true) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()
        assertEquals(ScrapeStatus.NEEDS_LOGIN, h.coordinator.progress.value.status)
    }

    @Test
    fun menuLikePagesAreNotSavedAsChapters() = runTest {
        val site = FakeSite.linear(1) { _, p -> p.copy(linkDensity = 0.8) }
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()
        assertEquals(ScrapeStatus.ERROR, h.coordinator.progress.value.status)
        assertEquals(0, h.store.chapters.size)
    }

    @Test
    fun chaptersNeverLandInTheWrongProject() = runTest {
        val site = FakeSite.linear(2)
        site.add(FakeSite.Page("https://other.test/n/ch-1", "Other 1", FakeSite.paragraphsFor(100), next = null))
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("First"))
        advanceUntilIdle()

        h.browser.loadUrl("https://other.test/n/ch-1")
        h.coordinator.start(ProjectBinding.New("Second"))
        advanceUntilIdle()

        val first = h.store.projects.values.first { it.title == "First" }
        val second = h.store.projects.values.first { it.title == "Second" }
        assertEquals(listOf("Chapter 1: Title 1", "Chapter 2: Title 2"), h.store.chaptersOf(first.id).map { it.summary.title })
        assertEquals(listOf("Other 1"), h.store.chaptersOf(second.id).map { it.summary.title })
        assertEquals(1, h.coordinator.progress.value.chaptersThisRun)
    }

    @Test
    fun resumeRefusesToMixSites() = runTest {
        settings = settings.copy(delayMinMs = 5_000, delayMaxMs = 5_000)
        val site = FakeSite.linear(3)
        site.add(FakeSite.Page("https://other.test/n/ch-1", "Other 1", FakeSite.paragraphsFor(100)))
        val h = harness(site)
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("First"))
        advanceTimeBy(2_000)
        h.coordinator.pause()
        runCurrent()

        h.browser.loadUrl("https://other.test/n/ch-1")
        h.coordinator.resume()
        advanceUntilIdle()

        assertEquals(ScrapeStatus.PAUSED, h.coordinator.progress.value.status)
        assertTrue(h.coordinator.progress.value.message.contains("other.test"))
        assertEquals(1, h.store.chapters.size)
    }

    @Test
    fun delayBetweenChaptersStaysInsideTheChosenRange() = runTest {
        settings = settings.copy(delayMinMs = 2_000, delayMaxMs = 4_000)
        val h = harness(FakeSite.linear(6), random = Random(7))
        val delays = mutableListOf<Long>()
        var last: ScrapeStatus? = null
        backgroundScope.launch {
            h.coordinator.progress.collect { p ->
                if (p.status == ScrapeStatus.COOLDOWN && last != ScrapeStatus.COOLDOWN) delays += p.delayTotalMs
                last = p.status
            }
        }
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        assertEquals("one countdown after each chapter, including before probing a guessed chapter-7", 6, delays.size)
        assertTrue(delays.toString(), delays.all { it in 2_000L..4_000L })
        assertTrue("delays are randomised", delays.distinct().size > 1)
    }

    @Test
    fun hourlyLimitWaitsUntilTheWindowFreesUpThenContinues() = runTest {
        settings = settings.copy(maxChaptersPerHour = 2, delayMinMs = 1_000, delayMaxMs = 1_000)
        val h = harness(FakeSite.linear(4) { n, p -> if (n == 4) p.copy(next = null) else p })
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))

        advanceTimeBy(10 * 60 * 1000L)
        runCurrent()
        assertEquals("two chapters, then waiting for the hour to pass", 2, h.store.chapters.size)
        val waiting = h.coordinator.progress.value
        assertEquals(ScrapeStatus.COOLDOWN, waiting.status)
        assertTrue(waiting.message, waiting.message.contains("Hourly limit"))

        advanceUntilIdle()
        assertEquals(4, h.store.chapters.size)
        val times = h.store.saveTimes.map { it.second }
        assertTrue("chapter 3 is saved at least an hour after chapter 1", times[2] - times[0] >= 60 * 60 * 1000L)
        assertTrue("never more than 2 chapters in any hour", times.windowed(3).all { it[2] - it[0] >= 60 * 60 * 1000L })
    }

    @Test
    fun dailyLimitPausesTheRunWithAClearMessage() = runTest {
        settings = settings.copy(maxChaptersPerDay = 2, maxChaptersPerHour = 0)
        val h = harness(FakeSite.linear(5))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        val progress = h.coordinator.progress.value
        assertEquals(ScrapeStatus.PAUSED, progress.status)
        assertTrue(progress.message, progress.message.contains("Daily limit"))
        assertEquals(2, h.store.chapters.size)
        assertEquals("resume continues with the next chapter", chapterUrl(3), h.onlyProject().resumeUrl)
    }

    @Test
    fun readingLimitsCountEveryProjectOnTheSameSite() = runTest {
        settings = settings.copy(maxChaptersPerDay = 3, maxChaptersPerHour = 0)
        val h = harness(FakeSite.linear(5))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("First"))
        advanceTimeBy(1_000)
        runCurrent()
        h.coordinator.stop()
        advanceUntilIdle()
        val first = h.store.chapters.size

        h.browser.loadUrl(chapterUrl(3))
        h.coordinator.start(ProjectBinding.New("Second"))
        advanceUntilIdle()
        assertEquals(3, h.store.chapters.size)
        assertTrue(first >= 1)
        assertTrue(h.coordinator.progress.value.message.contains("Daily limit"))
    }

    @Test
    fun shorteningTheDelayTakesEffectDuringTheCountdown() = runTest {
        settings = settings.copy(delayMinMs = 60_000, delayMaxMs = 60_000)
        val h = harness(FakeSite.linear(2))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(ScrapeStatus.COOLDOWN, h.coordinator.progress.value.status)

        settings = settings.copy(delayMinMs = 0, delayMaxMs = 1_000)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(listOf(chapterUrl(1), chapterUrl(2)), h.browser.loads)
    }

    @Test
    fun skipLeavesThePageOutAndMovesOn() = runTest {
        val h = harness(FakeSite.linear(3))
        h.browser.loadUrl(chapterUrl(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceTimeBy(100)
        h.coordinator.pause()
        runCurrent()

        assertTrue(h.coordinator.skip())
        advanceUntilIdle()
        assertEquals(ScrapeStatus.PAUSED, h.coordinator.progress.value.status)
        assertEquals(chapterUrl(2), h.browser.state.value.url)

        h.coordinator.resume()
        advanceUntilIdle()
        assertEquals(listOf("Chapter 2: Title 2", "Chapter 3: Title 3"), h.savedTitles())
        assertTrue(UrlIdentity.normalize(chapterUrl(1)) in h.store.skipped.getValue(h.onlyProject().id))
    }

    @Test
    fun pressesTheNextButtonWhenThePageHasNoNextLink() = runTest {
        val site = FakeSite()
        for (n in 1..3) {
            site.add(
                FakeSite.Page(
                    url = "https://novel.test/read?id=$n",
                    title = "Chapter $n",
                    paragraphs = FakeSite.paragraphsFor(n),
                    next = if (n < 3) "https://novel.test/read?id=${n + 1}" else null,
                    nextByClick = true
                )
            )
        }
        val h = harness(site)
        h.browser.loadUrl("https://novel.test/read?id=1")
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()

        assertEquals(ScrapeStatus.COMPLETED, h.coordinator.progress.value.status)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), h.savedTitles())
        assertEquals("the last chapter is where resume re-checks", "https://novel.test/read?id=3", h.onlyProject().resumeUrl)
    }

    @Test
    fun navigationThatNeverStartsIsReported() = runTest {
        val h = harness(FakeSite.linear(2))
        h.browser.loadUrl(chapterUrl(1))
        h.browser.navigationWorks = false
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()
        assertEquals(ScrapeStatus.ERROR, h.coordinator.progress.value.status)
        assertEquals(1, h.store.chapters.size)
        assertEquals(chapterUrl(2), h.onlyProject().resumeUrl)
    }

    @Test
    fun startingOnABlankPageAsksForAChapter() = runTest {
        val h = harness(FakeSite.linear(1))
        h.coordinator.start(ProjectBinding.New("Novel"))
        advanceUntilIdle()
        assertEquals(ScrapeStatus.PAUSED, h.coordinator.progress.value.status)
        assertTrue(h.coordinator.progress.value.message.contains("Open a chapter"))
    }
}
