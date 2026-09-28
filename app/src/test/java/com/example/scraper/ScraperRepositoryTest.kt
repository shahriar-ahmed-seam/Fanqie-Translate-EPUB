package com.example.scraper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.scraper.data.ScraperRepository
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.model.ChapterDraft
import com.example.scraper.model.ScrapeStatus
import com.example.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScraperRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ScraperRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ScraperRepository(db, db.scrapeProjectDao(), db.scrapedChapterDao(), db.scrapeSessionDao(), db.siteStrategyDao(), db.browserHistoryDao()) { now++ }
    }

    @After
    fun tearDown() = db.close()

    private fun draft(n: Int, url: String = "https://site.com/n/chapter-$n") = ChapterDraft(
        title = "Chapter $n",
        chapterNumber = n.toDouble(),
        sourceUrl = url,
        urlKey = UrlIdentity.normalize(url),
        canonicalUrl = url,
        paragraphs = listOf("Para one of $n & more", "Para two of $n"),
        contentHash = UrlIdentity.contentHash(listOf("Para one of $n & more", "Para two of $n"))
    )

    @Test
    fun savingAssignsSequencesAndKeepsProjectCountsInSync() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.saveChapter(project.id, draft(1))
        repo.saveChapter(project.id, draft(2))
        val saved = repo.chapterSummaries(project.id)
        assertEquals(listOf(1, 2), saved.map { it.sequence })
        val updated = repo.getProject(project.id)!!
        assertEquals(2, updated.chapterCount)
        assertEquals("https://site.com/n/chapter-2", updated.lastChapterUrl)
        assertEquals(saved.sumOf { it.characterCount.toLong() }, updated.totalCharacters)
        assertEquals(listOf("Para one of 1 & more", "Para two of 1"), repo.chapterParagraphs(saved[0].id))
    }

    @Test
    fun duplicatesAreFoundByUrlOrContent() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.saveChapter(project.id, draft(1))
        val byUrl = repo.findSavedChapter(project.id, UrlIdentity.normalize("https://site.com/n/chapter-1/"), "", "nope")
        assertNotNull(byUrl)
        val byHash = repo.findSavedChapter(project.id, "other", "other", draft(1).contentHash)
        assertNotNull(byHash)
        assertNull(repo.findSavedChapter(project.id, "other", "other", "nope"))
        val otherProject = repo.createProject("Other", "https://site.com/x")
        assertNull("projects are isolated", repo.findSavedChapter(otherProject.id, draft(1).urlKey, draft(1).urlKey, draft(1).contentHash))
    }

    @Test
    fun savingTheSameUrlTwiceDoesNotCreateADuplicateRow() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.saveChapter(project.id, draft(1))
        repo.saveChapter(project.id, draft(1))
        assertEquals(1, repo.chapterSummaries(project.id).size)
    }

    @Test
    fun visitedUrlsIncludeSavedAndSkippedPages() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.saveChapter(project.id, draft(1))
        repo.addSkippedUrl(project.id, UrlIdentity.normalize("https://site.com/n/chapter-2"))
        val visited = repo.visitedUrlKeys(project.id)
        assertTrue(UrlIdentity.normalize("https://site.com/n/chapter-1") in visited)
        assertTrue(UrlIdentity.normalize("https://site.com/n/chapter-2") in visited)
    }

    @Test
    fun runStateSurvivesAndInterruptedRunsBecomePaused() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.updateRun(project.id, ScrapeStatus.LOADING, "https://site.com/n/chapter-3", 2, null)
        assertEquals(2, repo.runChapterCount(project.id))
        assertEquals(project.id, repo.interruptedProject()?.id)

        repo.markInterruptedRunsPaused()
        assertNull(repo.interruptedProject())
        val after = repo.getProject(project.id)!!
        assertEquals(ScrapeStatus.PAUSED, after.status)
        assertEquals(2, repo.runChapterCount(project.id))
    }

    @Test
    fun deletingAChapterUpdatesCountsAndLastUrl() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.saveChapter(project.id, draft(1))
        val second = repo.saveChapter(project.id, draft(2))
        repo.deleteChapter(project.id, second.id)
        val after = repo.getProject(project.id)!!
        assertEquals(1, after.chapterCount)
        assertEquals("https://site.com/n/chapter-1", after.lastChapterUrl)
    }

    @Test
    fun deletingAProjectRemovesItsChaptersAndSession() = runBlocking {
        val project = repo.createProject("Novel", "https://site.com/n/chapter-1")
        repo.saveChapter(project.id, draft(1))
        repo.updateRun(project.id, ScrapeStatus.PAUSED, null, 1, null)
        repo.deleteProject(project.id)
        assertNull(repo.getProject(project.id))
        assertTrue(repo.chapterSummaries(project.id).isEmpty())
        assertNull(repo.interruptedProject())
    }

    @Test
    fun historyCollapsesReloadsAndIsCapped() = runBlocking {
        repo.recordVisit("https://site.com/a", "A")
        repo.recordVisit("https://site.com/a", "A again")
        repo.recordVisit("about:blank", "")
        repo.recordVisit("https://site.com/b", "B")
        val history = repo.observeHistory().first()
        assertEquals(listOf("https://site.com/b", "https://site.com/a"), history.map { it.url })
        assertEquals("A again", history[1].title)

        repeat(ScraperRepository.HISTORY_LIMIT + 20) { repo.recordVisit("https://site.com/p$it", "P$it") }
        assertEquals(ScraperRepository.HISTORY_LIMIT, repo.observeHistory(10_000).first().size)
    }
}
