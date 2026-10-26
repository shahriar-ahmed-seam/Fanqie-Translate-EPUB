package com.example.library

import android.content.Context
import android.net.Uri
import com.example.data.db.AppDatabase
import com.example.data.db.BookEntity
import com.example.data.db.BookGroupCrossRefEntity
import com.example.data.db.BookType
import com.example.data.db.ChapterEntity
import com.example.epub.EpubParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Adds an EPUB to the library as a local (untranslated) book: copies it into app storage, reads its
 * metadata, cover and chapters, and puts it in the "Local Books" group. Used by "Add to Library" and
 * by the Tomato tab's "Add to bookshelf".
 */
class LocalBookImporter(private val context: Context, private val database: AppDatabase) {

    suspend fun import(uri: Uri, fileName: String, groupId: String = LOCAL_GROUP_ID): BookEntity = withContext(Dispatchers.IO) {
        val bookId = UUID.randomUUID().toString()
        val bookDir = File(context.filesDir, "books/$bookId").apply { mkdirs() }
        val sourceEpubFile = File(bookDir, "source.epub")

        // 1. Copy original file to persistent app storage
        context.contentResolver.openInputStream(uri)?.use { input ->
            sourceEpubFile.outputStream().use { output ->
                input.copyTo(output, bufferSize = 32 * 1024)
            }
        } ?: throw IllegalStateException("Cannot open input stream for $uri")

        // 2. Parse EPUB metadata and spine
        val quickInfo = EpubParser.parseQuickInfo(sourceEpubFile)

        // 3. Extract and persist cover
        var coverPath: String? = null
        if (quickInfo.coverBytes != null && quickInfo.coverBytes.isNotEmpty()) {
            val ext = if (quickInfo.coverMediaType?.contains("png") == true) "png" else "jpg"
            val coverFile = File(bookDir, "cover.$ext")
            coverFile.writeBytes(quickInfo.coverBytes)
            coverPath = coverFile.absolutePath
        }

        // 4. Create and insert BookEntity (bookType = LOCAL, totalChunks = 0)
        val bookTitle = quickInfo.metadata.title.ifBlank { fileName.removeSuffix(".epub") }
        val bookAuthor = quickInfo.metadata.author.ifBlank { "Unknown Author" }
        val book = BookEntity(
            id = bookId,
            title = bookTitle,
            author = bookAuthor,
            description = quickInfo.metadata.description,
            coverPath = coverPath,
            originalUri = uri.toString(),
            originalFileName = fileName,
            chapterCount = quickInfo.spine.size,
            totalChunks = 0,
            createdAt = System.currentTimeMillis(),
            sourceLanguage = quickInfo.metadata.language.ifBlank { "und" },
            targetLanguage = "en",
            bookType = BookType.LOCAL,
            localFilePath = sourceEpubFile.absolutePath
        )
        database.bookDao().insertBook(book)
        database.groupDao().insertCrossRef(
            BookGroupCrossRefEntity(bookId = bookId, groupId = groupId)
        )

        // 5. Parse TOC chapter titles and insert ChapterEntities
        val tocTitles = EpubParser.extractTocChapterTitles(sourceEpubFile, quickInfo)
        ZipFile(sourceEpubFile).use { zip ->
            val chapters = quickInfo.spine.mapIndexed { index, spineItem ->
                val title = EpubParser.resolveChapterTitle(
                    zip = zip,
                    opfDir = quickInfo.opfDirectory,
                    href = spineItem.href,
                    chapterOrder = index,
                    tocTitles = tocTitles
                )
                ChapterEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    chapterOrder = index,
                    originalHref = spineItem.href,
                    title = title,
                    chunkCount = 0
                )
            }
            database.chapterDao().insertChapters(chapters)
        }

        book
    }

    companion object {
        const val LOCAL_GROUP_ID = "default_local"
    }
}
