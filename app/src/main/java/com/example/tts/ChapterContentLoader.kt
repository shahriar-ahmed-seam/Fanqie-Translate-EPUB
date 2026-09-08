package com.example.tts

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.epub.EpubParser
import java.io.File

data class ChapterTransitionData(
    val nextChapterId: String,
    val nextChapterTitle: String,
    val nextChapterOrder: Int,
    val paragraphs: List<String>
)

interface ChapterTransitionProvider {
    suspend fun getNextChapterContent(
        bookId: String,
        currentChapterId: String
    ): ChapterTransitionData?

    suspend fun getPreviousChapterContent(
        bookId: String,
        currentChapterId: String
    ): ChapterTransitionData? = null
}

object ChapterContentLoader : ChapterTransitionProvider {

    suspend fun loadChapter(
        context: Context,
        database: AppDatabase,
        bookId: String,
        chapterId: String
    ): ChapterTransitionData? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val chapter = database.chapterDao().getChapterById(chapterId) ?: return@withContext null
        val book = database.bookDao().getBookById(bookId)
        val job = database.jobDao().getJobByBookId(bookId)

        if (book?.isLocalBook == true || (job == null && book?.localFilePath != null)) {
            val title = if (chapter.title.isNotBlank()) chapter.title else "Chapter ${chapter.chapterOrder + 1}"
            val bookDir = File(context.filesDir, "books/$bookId")
            val epubFile = book?.localFilePath?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                ?: File(bookDir, "source.epub")

            val extractedParas = if (epubFile.exists() && epubFile.length() > 0L) {
                val parsed = EpubParser.extractChapterParagraphs(epubFile, chapter.originalHref)
                if (parsed.isNotEmpty()) parsed else listOf("This chapter is empty.")
            } else {
                listOf("Source EPUB file not found.")
            }

            ChapterTransitionData(
                nextChapterId = chapter.id,
                nextChapterTitle = title,
                nextChapterOrder = chapter.chapterOrder,
                paragraphs = extractedParas
            )
        } else {
            val chunks = if (job != null) {
                database.chunkDao().getChunksByJobAndChapter(job.id, chapterId)
            } else {
                database.chunkDao().getChunksByChapter(bookId, chapterId)
            }

            val titleChunk = chunks.firstOrNull { it.chunkType == "CHAPTER_TITLE" }
            val resolvedTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() }
                ?: if (chapter.title.any { it.code in 0x4e00..0x9fff }) "Chapter ${chapter.chapterOrder + 1}" else chapter.title

            val bodyChunks = chunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
            val extractedParagraphs = mutableListOf<String>()
            for (chunk in bodyChunks) {
                val text = chunk.translatedText?.takeIf { it.isNotBlank() } ?: continue
                val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
                for (p in rawParas) {
                    val clean = p.replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotBlank()) {
                        extractedParagraphs.add(clean)
                    }
                }
            }

            val finalParagraphs = if (extractedParagraphs.isNotEmpty()) {
                extractedParagraphs
            } else {
                listOf("This chapter has not been translated yet. Please wait for translation to complete.")
            }

            ChapterTransitionData(
                nextChapterId = chapter.id,
                nextChapterTitle = resolvedTitle,
                nextChapterOrder = chapter.chapterOrder,
                paragraphs = finalParagraphs
            )
        }
    }

    suspend fun getNextChapter(
        database: AppDatabase,
        bookId: String,
        currentChapterId: String
    ): ChapterEntity? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val chapters = database.chapterDao().getChaptersByBook(bookId)
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }
        if (currentIndex in 0 until chapters.size - 1) {
            chapters[currentIndex + 1]
        } else {
            null
        }
    }

    suspend fun getPreviousChapter(
        database: AppDatabase,
        bookId: String,
        currentChapterId: String
    ): ChapterEntity? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val chapters = database.chapterDao().getChaptersByBook(bookId)
        val currentIndex = chapters.indexOfFirst { it.id == currentChapterId }
        if (currentIndex > 0 && currentIndex < chapters.size) {
            chapters[currentIndex - 1]
        } else {
            null
        }
    }

    override suspend fun getNextChapterContent(
        bookId: String,
        currentChapterId: String
    ): ChapterTransitionData? {
        return null
    }

    override suspend fun getPreviousChapterContent(
        bookId: String,
        currentChapterId: String
    ): ChapterTransitionData? {
        return null
    }
}

class DefaultChapterTransitionProvider(
    private val context: Context,
    private val databaseProvider: () -> AppDatabase
) : ChapterTransitionProvider {

    override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val db = databaseProvider()
            val nextChapter = ChapterContentLoader.getNextChapter(db, bookId, currentChapterId) ?: return@withContext null
            ChapterContentLoader.loadChapter(context, db, bookId, nextChapter.id)
        }

    override suspend fun getPreviousChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val db = databaseProvider()
            val prevChapter = ChapterContentLoader.getPreviousChapter(db, bookId, currentChapterId) ?: return@withContext null
            ChapterContentLoader.loadChapter(context, db, bookId, prevChapter.id)
        }
}
