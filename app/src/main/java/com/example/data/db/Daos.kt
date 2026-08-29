package com.example.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY createdAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: String)
}

@Dao
interface TranslationJobDao {
    @Query("SELECT * FROM translation_jobs ORDER BY updatedAt DESC")
    fun getAllJobs(): Flow<List<TranslationJobEntity>>

    @Query("SELECT * FROM translation_jobs WHERE id = :id")
    suspend fun getJobById(id: String): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs WHERE bookId = :bookId LIMIT 1")
    suspend fun getJobByBookId(bookId: String): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs WHERE status IN ('QUEUED', 'RUNNING', 'TRANSLATING') ORDER BY startedAt ASC")
    suspend fun getActiveJobs(): List<TranslationJobEntity>

    @Query("SELECT * FROM translation_jobs WHERE status IN ('QUEUED', 'RUNNING', 'TRANSLATING') ORDER BY startedAt ASC")
    fun observeActiveJobs(): Flow<List<TranslationJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: TranslationJobEntity)

    @Update
    suspend fun updateJob(job: TranslationJobEntity)

    @Query("UPDATE translation_jobs SET status = :status, updatedAt = :timestamp WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE translation_jobs SET status = 'QUEUED', updatedAt = :timestamp WHERE status IN ('RUNNING', 'TRANSLATING')")
    suspend fun resetRunningJobsToQueued(timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE translation_jobs SET completedChunks = :completed, failedChunks = :failed, progress = :progress, status = :status, updatedAt = :timestamp WHERE id = :jobId")
    suspend fun updateJobProgress(jobId: String, completed: Int, failed: Int, progress: Float, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM translation_jobs WHERE id = :id")
    suspend fun deleteJobById(id: String)

    @Query("DELETE FROM translation_jobs WHERE bookId = :bookId")
    suspend fun deleteJobByBookId(bookId: String)
}

@Dao
interface ChapterDao {
    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterOrder ASC")
    suspend fun getChaptersByBook(bookId: String): List<ChapterEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersByBook(bookId: String)
}

@Dao
interface TranslationChunkDao {
    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId ORDER BY chapterOrder ASC, chunkOrder ASC")
    suspend fun getChunksByJob(jobId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND chapterId = :chapterId ORDER BY chunkOrder ASC")
    suspend fun getChunksByJobAndChapter(jobId: String, chapterId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND chunkType = 'CHAPTER_TITLE'")
    suspend fun getChapterTitleChunks(jobId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND chunkType = 'TITLE' LIMIT 1")
    suspend fun getTitleChunk(jobId: String): TranslationChunkEntity?

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND chunkType = 'DESCRIPTION' LIMIT 1")
    suspend fun getDescriptionChunk(jobId: String): TranslationChunkEntity?

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE jobId = :jobId AND (status != 'COMPLETED' OR translatedText IS NULL OR translatedText = '')")
    suspend fun getIncompleteChunkCount(jobId: String): Int

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId ORDER BY chapterOrder ASC, chunkOrder ASC")
    fun observeChunksByJob(jobId: String): Flow<List<TranslationChunkEntity>>

    @Query("SELECT * FROM translation_chunks WHERE bookId = :bookId AND chapterId = :chapterId ORDER BY chunkOrder ASC")
    suspend fun getChunksByChapter(bookId: String, chapterId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND status = 'PENDING' ORDER BY chapterOrder ASC, chunkOrder ASC LIMIT :limit")
    suspend fun getPendingChunksForJob(jobId: String, limit: Int): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId IN (:jobIds) AND status = 'PENDING' ORDER BY chapterOrder ASC, chunkOrder ASC LIMIT :limit")
    suspend fun getPendingChunksForJobs(jobIds: List<String>, limit: Int): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND status = 'PENDING' ORDER BY chapterOrder ASC, chunkOrder ASC LIMIT 1")
    suspend fun getNextPendingChunkForJob(jobId: String): TranslationChunkEntity?

    @Query("SELECT * FROM translation_chunks WHERE jobId IN (:jobIds) AND status = 'PENDING' ORDER BY chapterOrder ASC, chunkOrder ASC LIMIT 1")
    suspend fun getNextPendingChunkForJobs(jobIds: List<String>): TranslationChunkEntity?

    @Query("SELECT * FROM translation_chunks WHERE id = :id")
    suspend fun getChunkById(id: String): TranslationChunkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<TranslationChunkEntity>)

    @Update
    suspend fun updateChunk(chunk: TranslationChunkEntity)

    @Query("UPDATE translation_chunks SET status = :status, translatedText = :translatedText, errorMessage = :errorMessage, updatedAt = :timestamp WHERE id = :id AND jobId = :jobId AND bookId = :bookId")
    suspend fun updateChunkResult(
        id: String,
        jobId: String,
        bookId: String,
        status: String,
        translatedText: String?,
        errorMessage: String?,
        timestamp: Long = System.currentTimeMillis()
    )

    @Query("UPDATE translation_chunks SET status = 'TRANSLATING', updatedAt = :timestamp WHERE id = :id AND status = 'PENDING'")
    suspend fun claimChunk(id: String, timestamp: Long = System.currentTimeMillis()): Int

    @Transaction
    suspend fun claimNextPendingChunkForJob(jobId: String): TranslationChunkEntity? {
        val chunk = getNextPendingChunkForJob(jobId) ?: return null
        val updated = claimChunk(chunk.id)
        return if (updated > 0) chunk.copy(status = "TRANSLATING") else null
    }

    @Transaction
    suspend fun claimNextPendingChunk(jobIds: List<String>): TranslationChunkEntity? {
        if (jobIds.isEmpty()) return null
        val chunk = getNextPendingChunkForJobs(jobIds) ?: return null
        val updated = claimChunk(chunk.id)
        return if (updated > 0) chunk.copy(status = "TRANSLATING") else null
    }

    @Query("UPDATE translation_chunks SET status = 'PENDING' WHERE status = 'TRANSLATING' AND (translatedText IS NULL OR translatedText = '')")
    suspend fun resetTranslatingChunksToPending()

    @Query("UPDATE translation_chunks SET status = 'COMPLETED' WHERE status = 'TRANSLATING' AND (translatedText IS NOT NULL AND translatedText != '')")
    suspend fun finalizeCompletedTranslatingChunks()

    @Query("UPDATE translation_chunks SET status = 'PENDING', retryCount = 0, errorMessage = null WHERE jobId = :jobId AND status = 'FAILED'")
    suspend fun retryFailedChunks(jobId: String)

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE jobId = :jobId AND status = 'COMPLETED'")
    suspend fun getCompletedChunkCount(jobId: String): Int

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE jobId = :jobId AND status = 'TRANSLATING'")
    suspend fun getTranslatingChunkCount(jobId: String): Int

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE status = 'TRANSLATING'")
    fun observeTotalTranslatingChunks(): Flow<Int>

    @Query("SELECT jobId, COUNT(*) as count FROM translation_chunks WHERE status = 'TRANSLATING' GROUP BY jobId")
    fun observeTranslatingChunkCountsByJob(): Flow<List<JobChunkCount>>

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE jobId = :jobId AND status = 'FAILED'")
    suspend fun getFailedChunkCount(jobId: String): Int

    @Query("SELECT COUNT(*) FROM translation_chunks WHERE jobId = :jobId")
    suspend fun getTotalChunkCount(jobId: String): Int

    @Query("DELETE FROM translation_chunks WHERE jobId = :jobId")
    suspend fun deleteChunksByJob(jobId: String)

    @Query("DELETE FROM translation_chunks WHERE bookId = :bookId")
    suspend fun deleteChunksByBook(bookId: String)
}
