package com.example.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.db.BookType
import com.example.data.db.ChapterBookmarkEntity
import com.example.data.db.SCRAPED_GROUP_ID
import com.example.scraper.data.ScraperRepository
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.epub.ChapterOrdering
import com.example.scraper.epub.ScrapedEpubBuilder
import com.example.scraper.model.ChapterDraft
import com.example.scraper.model.ScrapeProject
import com.example.tts.ChapterContentLoader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScrapedLibraryBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: AppDatabase
    private lateinit var repo: ScraperRepository
    private lateinit var bridge: ScrapedLibraryBridge

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = ScraperRepository(db, db.scrapeProjectDao(), db.scrapedChapterDao(), db.scrapeSessionDao(), db.siteStrategyDao(), db.browserHistoryDao())
        bridge = ScrapedLibraryBridge(context, db) { error("translation queue not used here") }
    }

    @After
    fun tearDown() = db.close()

    private fun paragraphs(n: Int) = listOf("Paragraph one of chapter $n.", "Paragraph two of chapter $n, with \"quotes\" & more.")

    private suspend fun scrape(projectId: String, n: Int) {
        val url = "https://site.com/n/chapter-$n"
        repo.saveChapter(
            projectId,
            ChapterDraft("Chapter $n", n.toDouble(), url, UrlIdentity.normalize(url), url, paragraphs(n), UrlIdentity.contentHash(paragraphs(n)))
        )
    }

    /** Mirrors ScraperGraph.saveToLibrary. */
    private suspend fun save(project: ScrapeProject, existing: String?): String {
        val chapters = ChapterOrdering.order(repo.chapterSummaries(project.id))
        val file = File.createTempFile("scraped", ".epub")
        try {
            val result = file.outputStream().use { out ->
                ScrapedEpubBuilder.write(project, chapters, { repo.chapterParagraphs(it) }, out)
            }
            return bridge.upsertBook(project, chapters, file, result.language, existing)
        } finally {
            file.delete()
        }
    }

    @Test
    fun savedProjectBecomesAReadableLocalBookInTheScrapedGroup() = runBlocking {
        val project = repo.createProject("Scraped Novel", "https://site.com/n/chapter-1")
        (1..3).forEach { scrape(project.id, it) }
        val bookId = save(repo.getProject(project.id)!!, null)

        val book = db.bookDao().getBookById(bookId)!!
        assertEquals("Scraped Novel", book.title)
        assertEquals(BookType.LOCAL, book.bookType)
        assertEquals(3, book.chapterCount)
        assertTrue(File(book.localFilePath!!).exists())
        assertTrue(db.groupDao().getGroupIdsForBook(bookId).contains(SCRAPED_GROUP_ID))

        val chapters = db.chapterDao().getChaptersByBook(bookId)
        assertEquals(listOf("Chapter 1", "Chapter 2", "Chapter 3"), chapters.map { it.title })
        assertEquals(listOf(0, 1, 2), chapters.map { it.chapterOrder })

        val loaded = ChapterContentLoader.loadChapter(context, db, bookId, chapters[1].id)!!
        assertEquals("Chapter 2", loaded.nextChapterTitle)
        assertTrue("reader text: ${loaded.paragraphs}", loaded.paragraphs.containsAll(paragraphs(2)))
        assertFalse(loaded.paragraphs.any { it.contains("&amp;") })
    }

    @Test
    fun updatingTheLibraryCopyKeepsChapterIdsAndBookmarks() = runBlocking {
        val project = repo.createProject("Scraped Novel", "https://site.com/n/chapter-1")
        (1..2).forEach { scrape(project.id, it) }
        val bookId = save(repo.getProject(project.id)!!, null)
        val before = db.chapterDao().getChaptersByBook(bookId)
        db.bookmarkDao().insertBookmark(ChapterBookmarkEntity(id = "bm", bookId = bookId, chapterId = before[0].id))

        (3..4).forEach { scrape(project.id, it) }
        val sameBook = save(repo.getProject(project.id)!!, bookId)
        assertEquals(bookId, sameBook)

        val after = db.chapterDao().getChaptersByBook(bookId)
        assertEquals(4, after.size)
        assertEquals(before.map { it.id }, after.take(2).map { it.id })
        assertEquals(4, db.bookDao().getBookById(bookId)!!.chapterCount)
        assertEquals(listOf(before[0].id), db.bookmarkDao().getBookmarkedChapterIds(bookId))
        val loaded = ChapterContentLoader.loadChapter(context, db, bookId, after[3].id)!!
        assertTrue(loaded.paragraphs.containsAll(paragraphs(4)))
    }

    @Test
    fun chaptersRemovedFromTheProjectLeaveTheBookOnUpdate() = runBlocking {
        val project = repo.createProject("Scraped Novel", "https://site.com/n/chapter-1")
        (1..3).forEach { scrape(project.id, it) }
        val bookId = save(repo.getProject(project.id)!!, null)
        val removedLibraryId = db.chapterDao().getChaptersByBook(bookId)[1].id
        db.bookmarkDao().insertBookmark(ChapterBookmarkEntity(id = "bm2", bookId = bookId, chapterId = removedLibraryId))

        val scraped = repo.chapterSummaries(project.id)
        repo.deleteChapter(project.id, scraped[1].id)
        save(repo.getProject(project.id)!!, bookId)

        val after = db.chapterDao().getChaptersByBook(bookId)
        assertEquals(listOf("Chapter 1", "Chapter 3"), after.map { it.title })
        assertEquals(listOf(0, 1), after.map { it.chapterOrder })
        assertTrue(db.bookmarkDao().getBookmarkedChapterIds(bookId).isEmpty())
    }
}
