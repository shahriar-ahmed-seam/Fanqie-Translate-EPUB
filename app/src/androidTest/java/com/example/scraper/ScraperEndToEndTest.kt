package com.example.scraper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.scraper.browser.WebViewBrowserEngine
import com.example.scraper.data.ScraperRepository
import com.example.scraper.engine.ScrapeCoordinator
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.epub.ChapterOrdering
import com.example.scraper.epub.ScrapedEpubBuilder
import com.example.scraper.model.ProjectBinding
import com.example.scraper.model.ScrapeSettings
import com.example.scraper.model.ScrapeStatus
import com.example.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Runs the real scrape loop with a real (off-screen) WebView against [FakeNovelSite] served on the
 * device. This checks the DOM scripts, the loop and the storage together.
 */
@RunWith(AndroidJUnit4::class)
class ScraperEndToEndTest {

    private lateinit var server: MockWebServer
    private lateinit var site: FakeNovelSite
    private lateinit var db: AppDatabase
    private lateinit var repo: ScraperRepository
    private val scopes = mutableListOf<CoroutineScope>()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val settings = ScrapeSettings(
        minCharacterCount = 200,
        stabilizationMs = 1_000,
        pollingIntervalMs = 500,
        maxChapterWaitMs = 30_000,
        translationWaitMs = 15_000,
        maxRetryCount = 1,
        delayMinMs = 300,
        delayMaxMs = 700
    )

    @Before
    fun setUp() {
        site = FakeNovelSite(lastChapter = 5)
        server = MockWebServer().apply {
            dispatcher = site
            start()
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repo = ScraperRepository(db, db.scrapeProjectDao(), db.scrapedChapterDao(), db.scrapeSessionDao(), db.siteStrategyDao(), db.browserHistoryDao())
    }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        db.close()
        server.shutdown()
    }

    private suspend fun newCoordinator(): Pair<ScrapeCoordinator, WebViewBrowserEngine> = withContext(Dispatchers.Main) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        val engine = WebViewBrowserEngine(context, startUrl = { "" }, onPageFinished = { _, _ -> })
        ScrapeCoordinator(engine, repo, { settings }, scope) to engine
    }

    private suspend fun awaitFinished(coordinator: ScrapeCoordinator, timeoutMs: Long = 240_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        delay(300)
        while (System.currentTimeMillis() < deadline) {
            val status = withContext(Dispatchers.Main) { coordinator.progress.value.status }
            if (!status.isActive && !withContext(Dispatchers.Main) { coordinator.isRunning }) return
            delay(250)
        }
        throw AssertionError("scrape did not finish: ${coordinator.progress.value}")
    }

    private suspend fun awaitChapters(projectCount: () -> Int, atLeast: Int, timeoutMs: Long = 120_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (projectCount() >= atLeast) return
            delay(200)
        }
        throw AssertionError("expected at least $atLeast chapters")
    }

    private fun chapterUrl(n: Int) = server.url("/novel/chapter-$n").toString()

    private suspend fun assertCleanNovel(projectId: String) {
        val chapters = ChapterOrdering.order(repo.chapterSummaries(projectId))
        assertEquals((1..5).map { "Chapter $it: Title $it" }, chapters.map { it.title })
        chapters.forEachIndexed { index, chapter ->
            val n = index + 1
            val paragraphs = repo.chapterParagraphs(chapter.id)
            val expected = (1..FakeNovelSite.PARAGRAPHS).map { FakeNovelSite.englishParagraph(n, it) }
            assertEquals("chapter $n text", expected, paragraphs)
            val all = paragraphs.joinToString("\n")
            assertFalse(all.contains("Advertisement"))
            assertFalse(all.contains("Great chapter"))
            assertFalse(all.contains("Next Chapter"))
            assertFalse(all.contains("Discord"))
            assertTrue(UrlIdentity.cjkRatio(all) == 0.0)
        }
    }

    @Test
    fun scrapesAWholeNovelWithMenusAdsLiveTranslationAndLayoutChanges() = runBlocking {
        val (coordinator, engine) = newCoordinator()
        engine.loadUrl(chapterUrl(1))
        delay(1_500)
        withContext(Dispatchers.Main) { assertTrue(coordinator.start(ProjectBinding.New("The Test Novel"))) }
        awaitFinished(coordinator)

        val progress = withContext(Dispatchers.Main) { coordinator.progress.value }
        assertEquals(progress.message, ScrapeStatus.COMPLETED, progress.status)
        val project = repo.allProjects().single()
        assertEquals(5, project.chapterCount)
        assertCleanNovel(project.id)
        assertTrue("the missing chapter-6 was probed and ended the run", site.requests.contains("/novel/chapter-6"))

        val out = ByteArrayOutputStream()
        ScrapedEpubBuilder.write(project, ChapterOrdering.order(repo.chapterSummaries(project.id)), { repo.chapterParagraphs(it) }, out)
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            assertEquals("mimetype", zip.nextEntry?.name)
            var chapterFiles = 0
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith("OEBPS/text/")) chapterFiles++
                entry = zip.nextEntry
            }
            assertEquals(5, chapterFiles)
        }
    }

    @Test
    fun pauseAndResumeInTheRealBrowserSavesEveryChapterOnce() = runBlocking {
        val (coordinator, engine) = newCoordinator()
        engine.loadUrl(chapterUrl(1))
        delay(1_500)
        withContext(Dispatchers.Main) { coordinator.start(ProjectBinding.New("The Test Novel")) }
        awaitChapters({ runBlocking { repo.allProjects().singleOrNull()?.chapterCount ?: 0 } }, atLeast = 2)
        withContext(Dispatchers.Main) { coordinator.pause() }
        delay(500)
        val pausedCount = repo.allProjects().single().chapterCount
        assertEquals(ScrapeStatus.PAUSED, withContext(Dispatchers.Main) { coordinator.progress.value.status })
        delay(4_000)
        assertEquals("nothing is saved while paused", pausedCount, repo.allProjects().single().chapterCount)

        withContext(Dispatchers.Main) { assertTrue(coordinator.resume()) }
        awaitFinished(coordinator)
        assertEquals(ScrapeStatus.COMPLETED, withContext(Dispatchers.Main) { coordinator.progress.value.status })
        assertCleanNovel(repo.allProjects().single().id)
    }

    @Test
    fun aLoginWallStopsWithAClearMessageInsteadOfTimingOut() = runBlocking {
        val (coordinator, engine) = newCoordinator()
        engine.loadUrl(server.url("/locked/chapter-1").toString())
        delay(1_500)
        withContext(Dispatchers.Main) { coordinator.start(ProjectBinding.New("Locked")) }
        awaitFinished(coordinator, timeoutMs = 60_000)
        val progress = withContext(Dispatchers.Main) { coordinator.progress.value }
        assertEquals(progress.message, ScrapeStatus.NEEDS_LOGIN, progress.status)
        assertTrue(progress.message.contains("log in"))
        assertEquals(0, repo.allProjects().single().chapterCount)
    }

    /** Live check against tomatomtl.com. Runs only with `-e live true` (needs network). */
    @Test
    fun liveTomatoMtlChapterPageIsRecognised() = runBlocking {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("live") == "true")
        val (_, engine) = newCoordinator()
        engine.loadUrl("https://tomatomtl.com/book/7480862279345392665/7480883806929437209")
        var result: com.example.scraper.engine.ExtractionResult? = null
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(2_000)
            result = com.example.scraper.engine.PageAnalyzer.parse(engine.evaluate(com.example.scraper.engine.DomScripts.discover()), "")
            if (result != null && (result.isLoginWall || result.characterCount > 1_000)) break
        }
        android.util.Log.i("LiveTomato", "result=$result")
        assertTrue("page should be recognised as a login wall or a readable chapter: $result", result != null && (result.isLoginWall || result.characterCount > 1_000))
        assertEquals("#noidung", result!!.contentSelector)
    }

    @Test
    fun aNewProcessResumesFromTheNextUnscrapedChapter() = runBlocking {
        val (first, engine) = newCoordinator()
        engine.loadUrl(chapterUrl(1))
        delay(1_500)
        withContext(Dispatchers.Main) { first.start(ProjectBinding.New("The Test Novel")) }
        awaitChapters({ runBlocking { repo.allProjects().singleOrNull()?.chapterCount ?: 0 } }, atLeast = 2)
        // Simulate the process dying: the first coordinator and its browser are abandoned.
        withContext(Dispatchers.Main) { scopes.first().cancel() }
        delay(500)
        val project = repo.allProjects().single()
        assertTrue(project.chapterCount in 2..4)

        val (second, _) = newCoordinator()
        withContext(Dispatchers.Main) { assertTrue(second.resumeProject(project.id)) }
        awaitFinished(second)
        assertEquals(ScrapeStatus.COMPLETED, withContext(Dispatchers.Main) { second.progress.value.status })
        assertCleanNovel(project.id)
    }
}
