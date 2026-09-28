package com.example.library

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.db.AppDatabase
import com.example.data.db.BookEntity
import com.example.data.db.BookGroupCrossRefEntity
import com.example.data.db.BookType
import com.example.data.db.ChapterEntity
import com.example.data.db.SCRAPED_GROUP_ID
import com.example.epub.EpubParser
import com.example.queue.TranslationQueueManager
import com.example.scraper.ScraperGraph
import com.example.scraper.ScraperLibraryBridge
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.epub.ScrapedEpubBuilder
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapedChapterSummary
import com.example.service.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Puts scraped projects into the library as local EPUB books (reader, TTS, bookmarks and export
 * work as for any imported EPUB), and hands them to the translation queue on request.
 *
 * Library chapter ids are derived from the book id and the scraped chapter id, so refreshing a book
 * after more chapters were scraped keeps existing chapter ids, bookmarks and the listening position.
 */
class ScrapedLibraryBridge(
    private val context: Context,
    private val database: AppDatabase,
    private val queueManager: () -> TranslationQueueManager
) : ScraperLibraryBridge {

    override val supportsTranslation: Boolean = true

    override suspend fun bookExists(bookId: String): Boolean = database.bookDao().getBookById(bookId) != null

    override suspend fun upsertBook(
        project: ScrapeProject,
        chapters: List<ScrapedChapterSummary>,
        epubFile: File,
        language: String,
        existingBookId: String?
    ): String = withContext(Dispatchers.IO) {
        require(chapters.isNotEmpty()) { "There are no chapters to save yet" }
        val existing = existingBookId?.let { database.bookDao().getBookById(it) }
        val bookId = existing?.id ?: UUID.randomUUID().toString()
        val bookDir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
        val target = File(bookDir, "source.epub")

        // Validate the new file before it replaces the one the reader is using.
        val staged = File(bookDir, "source.epub.new")
        epubFile.copyTo(staged, overwrite = true)
        val spine = try {
            EpubParser.parseQuickInfo(staged).spine
        } catch (e: Exception) {
            staged.delete()
            throw IllegalStateException("The generated EPUB could not be read: ${e.message}", e)
        }
        if (spine.size != chapters.size) {
            staged.delete()
            throw IllegalStateException("The generated EPUB has ${spine.size} chapters, expected ${chapters.size}")
        }

        val chapterRows = chapters.mapIndexed { index, chapter ->
            ChapterEntity(
                id = libraryChapterId(bookId, chapter.id),
                bookId = bookId,
                chapterOrder = index,
                originalHref = ScrapedEpubBuilder.chapterHref(chapter.id),
                title = chapter.title.ifBlank { "Chapter ${index + 1}" },
                chunkCount = 0
            )
        }

        if (target.exists()) target.delete()
        if (!staged.renameTo(target)) {
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }

        database.withTransaction {
            if (existing == null) {
                database.bookDao().insertBook(
                    BookEntity(
                        id = bookId,
                        title = project.title,
                        author = project.author?.takeIf { it.isNotBlank() } ?: "Unknown",
                        description = "Scraped from ${project.siteHost}",
                        coverPath = null,
                        originalUri = project.sourceUrl,
                        originalFileName = ScraperGraph.safeFileName(project.title) + ".epub",
                        chapterCount = chapterRows.size,
                        totalChunks = 0,
                        createdAt = System.currentTimeMillis(),
                        sourceLanguage = language,
                        targetLanguage = "en",
                        bookType = BookType.LOCAL,
                        localFilePath = target.absolutePath
                    )
                )
                database.groupDao().insertCrossRef(BookGroupCrossRefEntity(bookId = bookId, groupId = SCRAPED_GROUP_ID))
            } else {
                database.bookDao().updateBook(
                    existing.copy(
                        title = project.title,
                        author = project.author?.takeIf { it.isNotBlank() } ?: existing.author,
                        chapterCount = chapterRows.size,
                        localFilePath = target.absolutePath,
                        bookType = BookType.LOCAL
                    )
                )
            }
            val keep = chapterRows.map { it.id }.toSet()
            val removed = database.chapterDao().getChaptersByBook(bookId).filter { it.id !in keep }
            for (chapter in removed) {
                database.chapterDao().deleteChapterById(chapter.id)
                database.bookmarkDao().deleteBookmark(bookId, chapter.id)
            }
            database.chapterDao().insertChapters(chapterRows)
        }
        bookId
    }

    override suspend fun queueTranslation(project: ScrapeProject, epubFile: File): String {
        val fileName = ScraperGraph.safeFileName(project.title) + ".epub"
        val (book, _) = queueManager().enqueueEpub(Uri.fromFile(epubFile), fileName)
        TranslationService.start(context)
        return book.title
    }

    companion object {
        fun libraryChapterId(bookId: String, scrapedChapterId: String): String =
            "s_" + UrlIdentity.chapterId(bookId, scrapedChapterId)
    }
}
