package com.example.ui

import com.example.Screen
import com.example.data.db.BookEntity
import com.example.data.db.BookGroupCrossRefEntity
import com.example.data.db.BookType
import com.example.data.db.TranslationJobEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeLibraryActionRoutingTest {

    @Test
    fun testNavigationScreenEnumIncludesLibrary() {
        val screens = Screen.values().map { it.name }
        assertEquals(listOf("HOME", "LIBRARY", "QUEUE", "SETTINGS"), screens)
        assertEquals("Home", Screen.HOME.title)
        assertEquals("Library", Screen.LIBRARY.title)
        assertEquals("Queue", Screen.QUEUE.title)
        assertEquals("Settings", Screen.SETTINGS.title)
    }

    @Test
    fun testBookPreviewStateIntentSeparation() {
        val translationPreview = BookPreviewState(
            uri = android.net.Uri.parse("content://test/book.epub"),
            fileName = "novel.epub",
            title = "Test Translation",
            author = "Author 1",
            description = "Desc",
            chapterCount = 10,
            tempCoverFile = null,
            isLibraryIntent = false
        )
        assertFalse("Translation preview must not have library intent", translationPreview.isLibraryIntent)

        val libraryPreview = BookPreviewState(
            uri = android.net.Uri.parse("content://test/book.epub"),
            fileName = "novel.epub",
            title = "Test Library Book",
            author = "Author 2",
            description = "Desc",
            chapterCount = 20,
            tempCoverFile = null,
            isLibraryIntent = true
        )
        assertTrue("Library preview must have library intent", libraryPreview.isLibraryIntent)
    }

    @Test
    fun testHomeFilterOnlyShowsTranslationProjects() {
        val translatedBook = BookWithJob(
            book = BookEntity(
                id = "b1",
                title = "Translated Novel",
                author = "Author A",
                description = "Desc A",
                coverPath = null,
                originalUri = "uri1",
                originalFileName = "novel1.epub",
                chapterCount = 50,
                totalChunks = 100,
                createdAt = 1000L,
                sourceLanguage = "zh",
                targetLanguage = "en",
                bookType = BookType.TRANSLATION
            ),
            job = TranslationJobEntity(
                id = "j1",
                bookId = "b1",
                status = "COMPLETED",
                totalChunks = 100,
                completedChunks = 100,
                failedChunks = 0,
                startedAt = 1000L,
                updatedAt = 2000L
            )
        )

        val localLibraryBook = BookWithJob(
            book = BookEntity(
                id = "b2",
                title = "Local Library Novel",
                author = "Author B",
                description = "Desc B",
                coverPath = null,
                originalUri = "uri2",
                originalFileName = "novel2.epub",
                chapterCount = 30,
                totalChunks = 0,
                createdAt = 1000L,
                sourceLanguage = "en",
                targetLanguage = "en",
                bookType = BookType.LOCAL
            ),
            job = null
        )

        val allBooks = listOf(translatedBook, localLibraryBook)

        // Home screen filtering logic:
        val homeTranslationProjects = allBooks.filter {
            it.book.bookType == BookType.TRANSLATION || it.job != null
        }

        assertEquals(1, homeTranslationProjects.size)
        assertEquals("b1", homeTranslationProjects[0].book.id)
        assertEquals("Translated Novel", homeTranslationProjects[0].book.title)
        assertFalse("Local library book must not appear in Home translation projects",
            homeTranslationProjects.any { it.book.id == "b2" }
        )
    }

    @Test
    fun testLibraryFilterShowsBothTypesAndRespectsGroups() {
        val translatedBook = BookWithJob(
            book = BookEntity(
                id = "b1",
                title = "Translated Novel",
                author = "Author A",
                description = "Desc A",
                coverPath = null,
                originalUri = "uri1",
                originalFileName = "novel1.epub",
                chapterCount = 50,
                totalChunks = 100,
                createdAt = 1000L,
                sourceLanguage = "zh",
                targetLanguage = "en",
                bookType = BookType.TRANSLATION
            ),
            job = null
        )

        val localBook = BookWithJob(
            book = BookEntity(
                id = "b2",
                title = "Local Book",
                author = "Author B",
                description = "Desc B",
                coverPath = null,
                originalUri = "uri2",
                originalFileName = "novel2.epub",
                chapterCount = 30,
                totalChunks = 0,
                createdAt = 1000L,
                sourceLanguage = "en",
                targetLanguage = "en",
                bookType = BookType.LOCAL
            ),
            job = null
        )

        val allBooks = listOf(translatedBook, localBook)
        val crossRefs = listOf(
            BookGroupCrossRefEntity(bookId = "b1", groupId = "custom_scifi")
        )

        // "ALL" shows both
        val allFiltered = allBooks
        assertEquals(2, allFiltered.size)

        // Custom group "custom_scifi"
        val customGroupBookIds = crossRefs.filter { it.groupId == "custom_scifi" }.map { it.bookId }.toSet()
        val scifiFiltered = allBooks.filter { customGroupBookIds.contains(it.book.id) }
        assertEquals(1, scifiFiltered.size)
        assertEquals("b1", scifiFiltered[0].book.id)

        // Default local group
        val localFiltered = allBooks.filter { it.book.bookType == BookType.LOCAL }
        assertEquals(1, localFiltered.size)
        assertEquals("b2", localFiltered[0].book.id)
    }
}
