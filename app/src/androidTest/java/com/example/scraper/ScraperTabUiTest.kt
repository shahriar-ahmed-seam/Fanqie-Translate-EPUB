package com.example.scraper

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.TranslatorApplication
import com.example.data.db.BookType
import com.example.data.db.SCRAPED_GROUP_ID
import com.example.scraper.model.ScrapeStatus
import com.example.tts.ChapterContentLoader
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real Scraper tab in the translator app: open a chapter URL, start a new project,
 * scrape to the end, then save the project into the library and read a chapter back.
 */
@RunWith(AndroidJUnit4::class)
class ScraperTabUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var server: MockWebServer
    private val app get() = compose.activity.application as TranslatorApplication
    private val graph get() = app.scraperGraph
    private var createdProjectId: String? = null

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = FakeNovelSite(lastChapter = 3)
            start()
        }
        graph.preferences.update {
            it.copy(
                delayMinMs = 300, delayMaxMs = 600, stabilizationMs = 1_000, pollingIntervalMs = 500,
                translationWaitMs = 15_000, minCharacterCount = 200, maxChaptersPerRun = 0
            )
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            createdProjectId?.let { id ->
                graph.repository.getProject(id)?.linkedBookId?.let { app.queueManager.deleteBookAndJob(it) }
                graph.repository.deleteProject(id)
            }
        }
        graph.preferences.update { it.copy(delayMinMs = 15_000, delayMaxMs = 30_000) }
        server.shutdown()
    }

    @Test
    fun scrapeANovelAndSaveItToTheLibrary() {
        compose.onNodeWithTag("nav_scraper").performClick()
        compose.onNodeWithTag("scraper_address_input").performTextInput(server.url("/novel/chapter-1").toString())
        compose.onNodeWithTag("scraper_go_button").performClick()

        compose.waitUntil(30_000) { graph.browser.state.value.url.contains("/novel/chapter-1") && !graph.browser.state.value.isLoading }
        compose.onNodeWithTag("scrape_start_button").assertIsEnabled().performClick()

        compose.waitUntil(30_000) { graph.coordinator.progress.value.status == ScrapeStatus.IDLE && runCatching { compose.onNodeWithTag("chooser_title_input").assertExists() }.isSuccess }
        compose.onNodeWithTag("chooser_title_input").performTextClearance()
        compose.onNodeWithTag("chooser_title_input").performTextInput("UI Test Novel")
        compose.onNodeWithTag("chooser_new_project").performClick()

        compose.waitUntil(180_000) { graph.coordinator.progress.value.status == ScrapeStatus.COMPLETED }
        val projectId = graph.coordinator.progress.value.projectId!!
        createdProjectId = projectId
        val project = runBlocking { graph.repository.getProject(projectId)!! }
        assertEquals("UI Test Novel", project.title)
        assertEquals(3, project.chapterCount)

        compose.onNodeWithText("Project").performClick()
        compose.onNodeWithTag("project_library_button").performClick()
        compose.waitUntil(60_000) { runBlocking { graph.repository.getProject(projectId)?.linkedBookId } != null }

        runBlocking {
            val bookId = graph.repository.getProject(projectId)!!.linkedBookId!!
            val book = app.database.bookDao().getBookById(bookId)!!
            assertEquals(BookType.LOCAL, book.bookType)
            assertEquals(3, book.chapterCount)
            assertTrue(app.database.groupDao().getGroupIdsForBook(bookId).contains(SCRAPED_GROUP_ID))
            val chapters = app.database.chapterDao().getChaptersByBook(bookId)
            assertEquals(listOf("Chapter 1: Title 1", "Chapter 2: Title 2", "Chapter 3: Title 3"), chapters.map { it.title })
            val loaded = ChapterContentLoader.loadChapter(app, app.database, bookId, chapters[1].id)!!
            assertTrue(loaded.paragraphs.contains(FakeNovelSite.englishParagraph(2, 1)))
        }
        compose.onNodeWithText("Update library copy").assertExists()
    }
}
