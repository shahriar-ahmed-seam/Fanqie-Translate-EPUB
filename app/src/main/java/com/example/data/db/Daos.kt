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

    @Query("SELECT * FROM translation_jobs WHERE status IN ('QUEUED', 'RUNNING', 'TRANSLATING', 'PAUSING') ORDER BY startedAt ASC")
    fun observeActiveJobs(): Flow<List<TranslationJobEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: TranslationJobEntity)

    @Update
    suspend fun updateJob(job: TranslationJobEntity)

    @Query("UPDATE translation_jobs SET status = :status, updatedAt = :timestamp WHERE id = :jobId")
    suspend fun updateJobStatus(jobId: String, status: String, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE translation_jobs SET status = 'PAUSED', updatedAt = :timestamp WHERE status = 'PAUSING'")
    suspend fun resetPausingJobsToPaused(timestamp: Long = System.currentTimeMillis())

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

    @Query("SELECT * FROM chapters WHERE id = :id LIMIT 1")
    suspend fun getChapterById(id: String): ChapterEntity?

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY chapterOrder ASC")
    fun observeChaptersByBook(bookId: String): Flow<List<ChapterEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChaptersByBook(bookId: String)
}

data class BookTitleTuple(
    val bookId: String,
    val translatedText: String?
)

data class ChapterTitleTuple(
    val chapterId: String,
    val translatedText: String?
)

data class ChapterProgressTuple(
    val chapterId: String,
    val totalChunks: Int,
    val completedChunks: Int
)

@Dao
interface TranslationChunkDao {
    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId ORDER BY chapterOrder ASC, chunkOrder ASC")
    suspend fun getChunksByJob(jobId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND chapterId = :chapterId ORDER BY chunkOrder ASC")
    suspend fun getChunksByJobAndChapter(jobId: String, chapterId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE jobId = :jobId AND chapterOrder >= :fromChapterOrder AND chapterOrder <= :toChapterOrder ORDER BY chapterOrder ASC, chunkOrder ASC")
    suspend fun getChunksByJobAndChapterRange(jobId: String, fromChapterOrder: Int, toChapterOrder: Int): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE bookId = :bookId AND chapterOrder >= :fromChapterOrder AND chapterOrder <= :toChapterOrder ORDER BY chapterOrder ASC, chunkOrder ASC")
    suspend fun getChunksByBookAndChapterRange(bookId: String, fromChapterOrder: Int, toChapterOrder: Int): List<TranslationChunkEntity>

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

    @Query("SELECT chapterId, translatedText FROM translation_chunks WHERE bookId = :bookId AND chunkType = 'CHAPTER_TITLE'")
    suspend fun getChapterTitlesByBook(bookId: String): List<ChapterTitleTuple>

    @Query("SELECT chapterId, translatedText FROM translation_chunks WHERE jobId = :jobId AND chunkType = 'CHAPTER_TITLE'")
    suspend fun getChapterTitlesByJob(jobId: String): List<ChapterTitleTuple>

    @Query("SELECT * FROM translation_chunks WHERE bookId = :bookId AND chunkType = 'CHAPTER_TITLE'")
    suspend fun getChapterTitleChunksByBook(bookId: String): List<TranslationChunkEntity>

    @Query("SELECT * FROM translation_chunks WHERE bookId = :bookId AND chunkType = 'TITLE' LIMIT 1")
    suspend fun getTitleChunkByBook(bookId: String): TranslationChunkEntity?

    @Query("SELECT bookId, translatedText FROM translation_chunks WHERE chunkType = 'TITLE' AND translatedText IS NOT NULL AND translatedText != ''")
    fun observeAllTitleChunks(): Flow<List<BookTitleTuple>>

    @Query("SELECT bookId, translatedText FROM translation_chunks WHERE chunkType = 'TITLE' AND translatedText IS NOT NULL AND translatedText != ''")
    suspend fun getAllTitleChunks(): List<BookTitleTuple>

    @Query("SELECT * FROM translation_chunks WHERE bookId = :bookId AND chunkType = 'DESCRIPTION' LIMIT 1")
    suspend fun getDescriptionChunkByBook(bookId: String): TranslationChunkEntity?

    @Query("SELECT chapterId, COUNT(*) as totalChunks, SUM(CASE WHEN (status = 'COMPLETED' OR (translatedText IS NOT NULL AND translatedText != '')) THEN 1 ELSE 0 END) as completedChunks FROM translation_chunks WHERE bookId = :bookId AND chunkType = 'CHAPTER_BODY' GROUP BY chapterId")
    suspend fun getChapterBodyProgressByBook(bookId: String): List<ChapterProgressTuple>

    @Query("SELECT chapterId, COUNT(*) as totalChunks, SUM(CASE WHEN (status = 'COMPLETED' OR (translatedText IS NOT NULL AND translatedText != '')) THEN 1 ELSE 0 END) as completedChunks FROM translation_chunks WHERE jobId = :jobId AND chunkType = 'CHAPTER_BODY' GROUP BY chapterId")
    suspend fun getChapterBodyProgressByJob(jobId: String): List<ChapterProgressTuple>

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

@Dao
interface LibraryGroupDao {
    @Query("SELECT * FROM library_groups ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAllGroups(): Flow<List<LibraryGroupEntity>>

    @Query("SELECT * FROM library_groups ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllGroups(): List<LibraryGroupEntity>

    @Query("SELECT * FROM library_groups WHERE id = :id LIMIT 1")
    suspend fun getGroupById(id: String): LibraryGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: LibraryGroupEntity)

    @Update
    suspend fun updateGroup(group: LibraryGroupEntity)

    @Query("UPDATE library_groups SET name = :name WHERE id = :id AND isSystemGroup = 0")
    suspend fun renameGroup(id: String, name: String)

    @Query("DELETE FROM library_groups WHERE id = :id AND isSystemGroup = 0")
    suspend fun deleteGroup(id: String)

    @Query("SELECT groupId FROM book_group_cross_ref WHERE bookId = :bookId")
    fun observeGroupIdsForBook(bookId: String): Flow<List<String>>

    @Query("SELECT groupId FROM book_group_cross_ref WHERE bookId = :bookId")
    suspend fun getGroupIdsForBook(bookId: String): List<String>

    @Query("SELECT bookId FROM book_group_cross_ref WHERE groupId = :groupId")
    fun observeBookIdsInGroup(groupId: String): Flow<List<String>>

    @Query("SELECT bookId FROM book_group_cross_ref WHERE groupId = :groupId")
    suspend fun getBookIdsInGroup(groupId: String): List<String>

    @Query("SELECT * FROM book_group_cross_ref")
    fun observeAllCrossRefs(): Flow<List<BookGroupCrossRefEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: BookGroupCrossRefEntity)

    @Query("DELETE FROM book_group_cross_ref WHERE bookId = :bookId AND groupId = :groupId")
    suspend fun deleteCrossRef(bookId: String, groupId: String)

    @Query("DELETE FROM book_group_cross_ref WHERE bookId = :bookId")
    suspend fun deleteCrossRefsByBook(bookId: String)

    @Query("DELETE FROM book_group_cross_ref WHERE groupId = :groupId")
    suspend fun deleteCrossRefsByGroup(groupId: String)
}

@Dao
interface BookmarkDao {
    @Query("SELECT chapterId FROM chapter_bookmarks WHERE bookId = :bookId")
    fun observeBookmarkedChapterIds(bookId: String): Flow<List<String>>

    @Query("SELECT chapterId FROM chapter_bookmarks WHERE bookId = :bookId")
    suspend fun getBookmarkedChapterIds(bookId: String): List<String>

    @Query("SELECT * FROM chapter_bookmarks WHERE bookId = :bookId ORDER BY createdAt DESC")
    fun observeBookmarksByBook(bookId: String): Flow<List<ChapterBookmarkEntity>>

    @Query("SELECT EXISTS(SELECT 1 FROM chapter_bookmarks WHERE bookId = :bookId AND chapterId = :chapterId)")
    fun observeIsBookmarked(bookId: String, chapterId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM chapter_bookmarks WHERE bookId = :bookId AND chapterId = :chapterId)")
    suspend fun isBookmarked(bookId: String, chapterId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertBookmark(bookmark: ChapterBookmarkEntity)

    @Query("DELETE FROM chapter_bookmarks WHERE bookId = :bookId AND chapterId = :chapterId")
    suspend fun deleteBookmark(bookId: String, chapterId: String)

    @Query("DELETE FROM chapter_bookmarks WHERE bookId = :bookId")
    suspend fun deleteBookmarksByBook(bookId: String)
}

@Dao
interface TtsRuleDao {
    @Query("SELECT * FROM tts_rules ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAllRules(): Flow<List<TtsRuleEntity>>

    @Query("SELECT * FROM tts_rules ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAllRules(): List<TtsRuleEntity>

    @Query("SELECT * FROM tts_rules WHERE bookId IS NULL OR bookId = :bookId ORDER BY sortOrder ASC, createdAt ASC")
    fun observeRulesForBook(bookId: String): Flow<List<TtsRuleEntity>>

    @Query("SELECT * FROM tts_rules WHERE id = :id LIMIT 1")
    suspend fun getRuleById(id: String): TtsRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: TtsRuleEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRules(rules: List<TtsRuleEntity>)

    @Update
    suspend fun updateRule(rule: TtsRuleEntity)

    @Delete
    suspend fun deleteRule(rule: TtsRuleEntity)

    @Query("DELETE FROM tts_rules WHERE id = :id")
    suspend fun deleteRuleById(id: String)

    @Query("DELETE FROM tts_rules WHERE bookId = :bookId")
    suspend fun deleteRulesByBook(bookId: String)
}


