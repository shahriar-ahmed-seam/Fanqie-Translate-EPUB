package com.example.scraper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.scraper.model.ScrapedChapterSummary
import kotlinx.coroutines.flow.Flow

@Dao
interface ScrapeProjectDao {
    @Query("SELECT * FROM scrape_projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ScrapeProjectEntity>>

    @Query("SELECT * FROM scrape_projects WHERE id = :id LIMIT 1")
    fun observe(id: String): Flow<ScrapeProjectEntity?>

    @Query("SELECT * FROM scrape_projects WHERE id = :id LIMIT 1")
    suspend fun get(id: String): ScrapeProjectEntity?

    @Query("SELECT * FROM scrape_projects ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ScrapeProjectEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(project: ScrapeProjectEntity)

    @Query("UPDATE scrape_projects SET title = :title, author = :author, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, author: String?, now: Long)

    @Query("UPDATE scrape_projects SET resumeUrl = :url, updatedAt = :now WHERE id = :id")
    suspend fun setResumeUrl(id: String, url: String?, now: Long)

    @Query("UPDATE scrape_projects SET status = :status, lastError = :error, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: String, status: String, error: String?, now: Long)

    @Query("UPDATE scrape_projects SET linkedBookId = :bookId, linkedChapterCount = :count, updatedAt = :now WHERE id = :id")
    suspend fun setLinkedBook(id: String, bookId: String?, count: Int, now: Long)

    @Query("UPDATE scrape_projects SET linkedBookId = NULL, linkedChapterCount = 0 WHERE linkedBookId = :bookId")
    suspend fun unlinkBook(bookId: String)

    @Query(
        "UPDATE scrape_projects SET chapterCount = (SELECT COUNT(*) FROM scrape_chapters WHERE projectId = :id), " +
            "totalCharacters = (SELECT COALESCE(SUM(characterCount), 0) FROM scrape_chapters WHERE projectId = :id), " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun refreshCounts(id: String, now: Long)

    @Query("UPDATE scrape_projects SET lastChapterUrl = :url, updatedAt = :now WHERE id = :id")
    suspend fun setLastChapterUrl(id: String, url: String?, now: Long)

    @Query("DELETE FROM scrape_projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ScrapedChapterDao {
    @Query(
        "SELECT id, projectId, sequence, chapterNumber, title, sourceUrl, characterCount, paragraphCount, createdAt " +
            "FROM scrape_chapters WHERE projectId = :projectId ORDER BY sequence ASC"
    )
    fun observeSummaries(projectId: String): Flow<List<ScrapedChapterSummary>>

    @Query(
        "SELECT id, projectId, sequence, chapterNumber, title, sourceUrl, characterCount, paragraphCount, createdAt " +
            "FROM scrape_chapters WHERE projectId = :projectId ORDER BY sequence ASC"
    )
    suspend fun getSummaries(projectId: String): List<ScrapedChapterSummary>

    @Query(
        "SELECT id, projectId, sequence, chapterNumber, title, sourceUrl, characterCount, paragraphCount, createdAt " +
            "FROM scrape_chapters WHERE projectId = :projectId AND (urlKey = :urlKey OR urlKey = :canonicalKey) LIMIT 1"
    )
    suspend fun findByUrl(projectId: String, urlKey: String, canonicalKey: String): ScrapedChapterSummary?

    @Query(
        "SELECT id, projectId, sequence, chapterNumber, title, sourceUrl, characterCount, paragraphCount, createdAt " +
            "FROM scrape_chapters WHERE projectId = :projectId AND contentHash = :hash LIMIT 1"
    )
    suspend fun findByHash(projectId: String, hash: String): ScrapedChapterSummary?

    @Query("SELECT urlKey FROM scrape_chapters WHERE projectId = :projectId")
    suspend fun urlKeys(projectId: String): List<String>

    @Query("SELECT contentHtml FROM scrape_chapters WHERE id = :id")
    suspend fun content(id: String): String?

    @Query("SELECT MAX(sequence) FROM scrape_chapters WHERE projectId = :projectId")
    suspend fun maxSequence(projectId: String): Int?

    @Query(
        "SELECT c.createdAt FROM scrape_chapters c INNER JOIN scrape_projects p ON p.id = c.projectId " +
            "WHERE p.siteHost = :siteHost AND c.createdAt >= :since ORDER BY c.createdAt ASC"
    )
    suspend fun saveTimesSince(siteHost: String, since: Long): List<Long>

    @Query("SELECT sourceUrl FROM scrape_chapters WHERE projectId = :projectId ORDER BY sequence DESC LIMIT 1")
    suspend fun lastSourceUrl(projectId: String): String?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(chapter: ScrapedChapterEntity)

    @Query("DELETE FROM scrape_chapters WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scrape_chapters WHERE projectId = :projectId")
    suspend fun deleteByProject(projectId: String)
}

@Dao
interface ScrapeSessionDao {
    @Query("SELECT * FROM scrape_sessions WHERE projectId = :projectId LIMIT 1")
    suspend fun get(projectId: String): ScrapeSessionEntity?

    @Query("SELECT * FROM scrape_sessions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<ScrapeSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ScrapeSessionEntity)

    @Query("DELETE FROM scrape_sessions WHERE projectId = :projectId")
    suspend fun delete(projectId: String)
}

@Dao
interface SiteStrategyDao {
    @Query("SELECT * FROM scrape_site_strategies WHERE siteHost = :host LIMIT 1")
    suspend fun get(host: String): SiteStrategyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(strategy: SiteStrategyEntity)

    @Query("DELETE FROM scrape_site_strategies WHERE siteHost = :host")
    suspend fun delete(host: String)

    @Query("DELETE FROM scrape_site_strategies")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scrape_site_strategies")
    fun observeCount(): Flow<Int>
}

@Dao
interface BrowserHistoryDao {
    @Query("SELECT * FROM scrape_browser_history ORDER BY visitedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<BrowserHistoryEntity>>

    @Query("SELECT * FROM scrape_browser_history ORDER BY visitedAt DESC LIMIT 1")
    suspend fun latest(): BrowserHistoryEntity?

    @Insert
    suspend fun insert(entry: BrowserHistoryEntity): Long

    @Query("UPDATE scrape_browser_history SET title = :title, visitedAt = :visitedAt WHERE id = :id")
    suspend fun touch(id: Long, title: String, visitedAt: Long)

    @Query("DELETE FROM scrape_browser_history WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM scrape_browser_history")
    suspend fun clear()

    @Query("DELETE FROM scrape_browser_history WHERE id NOT IN (SELECT id FROM scrape_browser_history ORDER BY visitedAt DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}
