package com.example.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey
    val id: String, // UUID
    val title: String,
    val author: String,
    val description: String,
    val coverPath: String?,
    val originalUri: String,
    val originalFileName: String,
    val chapterCount: Int,
    val totalChunks: Int,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceLanguage: String = "zh",
    val targetLanguage: String = "en"
)

@Entity(
    tableName = "translation_jobs",
    indices = [
        Index(value = ["status"], name = "index_translation_jobs_status"),
        Index(value = ["bookId"], name = "index_translation_jobs_bookId")
    ]
)
data class TranslationJobEntity(
    @PrimaryKey
    val id: String, // UUID (jobId)
    val bookId: String,
    val status: String, // QUEUED, TRANSLATING, PAUSED, COMPLETED, FAILED, CANCELLED
    val progress: Float = 0f,
    val completedChunks: Int = 0,
    val failedChunks: Int = 0,
    val totalChunks: Int = 0,
    val errorMessage: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val exportedUri: String? = null,
    val exportedFileName: String? = null
)

@Entity(
    tableName = "chapters",
    indices = [
        Index(value = ["bookId", "chapterOrder"], name = "index_chapters_bookId_chapterOrder")
    ]
)
data class ChapterEntity(
    @PrimaryKey
    val id: String, // UUID (chapterId)
    val bookId: String,
    val chapterOrder: Int, // 0-indexed spine order
    val originalHref: String,
    val title: String,
    val chunkCount: Int = 0
)

@Entity(
    tableName = "translation_chunks",
    indices = [
        Index(value = ["jobId"], name = "index_translation_chunks_jobId"),
        Index(value = ["status"], name = "index_translation_chunks_status"),
        Index(value = ["jobId", "status"], name = "index_translation_chunks_jobId_status"),
        Index(value = ["jobId", "chapterId"], name = "index_translation_chunks_jobId_chapterId"),
        Index(value = ["jobId", "chapterOrder", "chunkOrder"], name = "index_translation_chunks_jobId_chapterOrder_chunkOrder"),
        Index(value = ["bookId", "chunkType"], name = "index_translation_chunks_bookId_chunkType"),
        Index(value = ["jobId", "chunkType"], name = "index_translation_chunks_jobId_chunkType")
    ]
)
data class TranslationChunkEntity(
    @PrimaryKey
    val id: String, // UUID (chunkId)
    val jobId: String,
    val bookId: String,
    val chapterId: String,
    val chapterOrder: Int,
    val chunkOrder: Int, // 0-indexed chunk within chapter or metadata
    val chunkType: String = "CHAPTER_BODY", // TITLE, DESCRIPTION, CHAPTER_TITLE, CHAPTER_BODY
    val sourceText: String,
    val translatedText: String? = null,
    val status: String = "PENDING", // PENDING, TRANSLATING, COMPLETED, FAILED
    val retryCount: Int = 0,
    val errorMessage: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

data class JobChunkCount(
    val jobId: String,
    val count: Int
)
