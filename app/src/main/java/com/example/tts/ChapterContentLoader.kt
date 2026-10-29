package com.example.tts

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.db.ChapterEntity
import com.example.epub.EpubParser
import com.example.translation.TranslationQuality
import java.io.File

/** Whether a loaded chapter has real text to read, or only a placeholder for the reader screen. */
enum class ChapterContentStatus {
    READY,
    /** No text at all (cover, image-only or blank page): auto-advance skips it. */
    EMPTY,
    /** Translation not finished yet: auto-advance waits instead of reading the placeholder aloud. */
    UNTRANSLATED,
    /** The book's source file is gone. */
    MISSING
}

data class ChapterTransitionData(
    val nextChapterId: String,
    val nextChapterTitle: String,
    val nextChapterOrder: Int,
    val paragraphs: List<String>,
    val status: ChapterContentStatus = ChapterContentStatus.READY
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

            val status: ChapterContentStatus
            val extractedParas = if (epubFile.exists() && epubFile.length() > 0L) {
                val parsed = EpubParser.extractChapterParagraphs(epubFile, chapter.originalHref)
                status = if (parsed.isNotEmpty()) ChapterContentStatus.READY else ChapterContentStatus.EMPTY
                if (parsed.isNotEmpty()) parsed else listOf("This chapter is empty.")
            } else {
                status = ChapterContentStatus.MISSING
                listOf("Source EPUB file not found.")
            }

            ChapterTransitionData(
                nextChapterId = chapter.id,
                nextChapterTitle = title,
                nextChapterOrder = chapter.chapterOrder,
                paragraphs = extractedParas,
                status = status
            )
        } else {
            val chunks = if (job != null) {
                database.chunkDao().getChunksByJobAndChapter(job.id, chapterId)
            } else {
                database.chunkDao().getChunksByChapter(bookId, chapterId)
            }

            val titleChunk = chunks.firstOrNull { it.chunkType == "CHAPTER_TITLE" }
            val resolvedTitle = TranslationQuality.usableTitle(titleChunk?.translatedText, titleChunk?.sourceText)
                ?: if (chapter.title.any { it.code in 0x4e00..0x9fff }) "Chapter ${chapter.chapterOrder + 1}" else chapter.title

            val bodyChunks = chunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
            val extractedParagraphs = mutableListOf<String>()
            for (chunk in bodyChunks) {
                // Junk such as `[""]` (stored by older versions) is never shown or read aloud.
                val text = TranslationQuality.usable(chunk.translatedText, chunk.sourceText) ?: continue
                val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
                for (p in rawParas) {
                    val clean = p.replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotBlank()) {
                        extractedParagraphs.add(clean)
                    }
                }
            }

            val finalParagraphs = when {
                extractedParagraphs.isNotEmpty() -> extractedParagraphs
                // A cover or picture page: there is nothing to translate (it used to say "not translated yet").
                bodyChunks.isEmpty() -> listOf("This page has no text.")
                else -> listOf("This chapter has not been translated yet. Please wait for translation to complete.")
            }

            // Chunks still waiting for (or in) translation mean the chapter isn't complete yet. Failed
            // chunks won't change by waiting, so a chapter with only those left counts as readable.
            // (A chunk being translated again still has its earlier text, so it is readable.)
            val stillTranslating = bodyChunks.any {
                (it.status == "PENDING" || it.status == "TRANSLATING") &&
                    TranslationQuality.usable(it.translatedText, it.sourceText) == null
            }
            val status = when {
                stillTranslating -> ChapterContentStatus.UNTRANSLATED
                extractedParagraphs.isEmpty() && bodyChunks.isNotEmpty() -> ChapterContentStatus.UNTRANSLATED
                extractedParagraphs.isEmpty() -> ChapterContentStatus.EMPTY
                else -> ChapterContentStatus.READY
            }

            ChapterTransitionData(
                nextChapterId = chapter.id,
                nextChapterTitle = resolvedTitle,
                nextChapterOrder = chapter.chapterOrder,
                paragraphs = finalParagraphs,
                status = status
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
