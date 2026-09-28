package com.example.scraper.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.example.scraper.engine.ChapterText
import com.example.scraper.engine.UrlIdentity
import com.example.scraper.model.BrowserHistoryItem
import com.example.scraper.model.ChapterDraft
import com.example.scraper.model.ScrapeProject
import com.example.scraper.model.ScrapeStatus
import com.example.scraper.model.ScrapedChapterSummary
import com.example.scraper.model.WebsiteStrategy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import java.util.UUID

/** Persistence operations the scrape loop needs. Implemented by [ScraperRepository]; faked in tests. */
interface ScrapeStore {
    suspend fun getProject(id: String): ScrapeProject?
    suspend fun createProject(title: String, sourceUrl: String): ScrapeProject
    suspend fun findSavedChapter(projectId: String, urlKey: String, canonicalKey: String, contentHash: String): ScrapedChapterSummary?
    /** Normalised URLs that must not be visited again: saved chapters plus skipped pages. */
    suspend fun visitedUrlKeys(projectId: String): Set<String>
    suspend fun saveChapter(projectId: String, draft: ChapterDraft): ScrapedChapterSummary
    suspend fun setResumeUrl(projectId: String, url: String?)
    suspend fun addSkippedUrl(projectId: String, urlKey: String)
    /** Chapters saved by the current run so far (survives pause/resume and process death). */
    suspend fun runChapterCount(projectId: String): Int
    suspend fun updateRun(projectId: String, status: ScrapeStatus, currentUrl: String?, chaptersThisRun: Int, error: String?)
    suspend fun getStrategy(host: String): WebsiteStrategy?
    suspend fun saveStrategy(strategy: WebsiteStrategy)
    suspend fun deleteStrategy(host: String)
    /** When chapters from [siteHost] were saved since [since] (all projects), oldest first. */
    suspend fun recentSaveTimes(siteHost: String, since: Long): List<Long>
}

class ScraperRepository(
    private val db: RoomDatabase,
    private val projects: ScrapeProjectDao,
    private val chapters: ScrapedChapterDao,
    private val sessions: ScrapeSessionDao,
    private val strategies: SiteStrategyDao,
    private val history: BrowserHistoryDao,
    private val now: () -> Long = { System.currentTimeMillis() }
) : ScrapeStore {

    // ---- UI queries ----

    fun observeProjects(): Flow<List<ScrapeProject>> = projects.observeAll().map { list -> list.map { it.toModel() } }

    fun observeProject(id: String): Flow<ScrapeProject?> = projects.observe(id).map { it?.toModel() }

    fun observeChapters(projectId: String): Flow<List<ScrapedChapterSummary>> = chapters.observeSummaries(projectId)

    fun observeHistory(limit: Int = HISTORY_LIMIT): Flow<List<BrowserHistoryItem>> =
        history.observeRecent(limit).map { list -> list.map { BrowserHistoryItem(it.id, it.url, it.title, it.visitedAt) } }

    fun observeStrategyCount(): Flow<Int> = strategies.observeCount()

    suspend fun allProjects(): List<ScrapeProject> = projects.getAll().map { it.toModel() }

    suspend fun chapterSummaries(projectId: String): List<ScrapedChapterSummary> = chapters.getSummaries(projectId)

    suspend fun chapterParagraphs(chapterId: String): List<String> = ChapterText.fromStored(chapters.content(chapterId).orEmpty())

    suspend fun chapterXhtml(chapterId: String): String = chapters.content(chapterId).orEmpty()

    suspend fun renameProject(id: String, title: String, author: String?) {
        projects.rename(id, title.trim().ifBlank { "Untitled novel" }, author?.trim()?.ifBlank { null }, now())
    }

    suspend fun deleteProject(id: String) = db.withTransaction {
        chapters.deleteByProject(id)
        sessions.delete(id)
        projects.delete(id)
    }

    suspend fun deleteChapter(projectId: String, chapterId: String) = db.withTransaction {
        chapters.delete(chapterId)
        val t = now()
        projects.refreshCounts(projectId, t)
        projects.setLastChapterUrl(projectId, chapters.lastSourceUrl(projectId), t)
    }

    suspend fun setLinkedBook(projectId: String, bookId: String?, chapterCount: Int) =
        projects.setLinkedBook(projectId, bookId, chapterCount, now())

    suspend fun unlinkBook(bookId: String) = projects.unlinkBook(bookId)

    /** A project whose run was still active when the process died (candidate for "Resume?"). */
    suspend fun interruptedProject(): ScrapeProject? {
        val session = sessions.getAll().firstOrNull { ScrapeStatus.fromName(it.status).isActive } ?: return null
        return projects.get(session.projectId)?.toModel()
    }

    /** Marks runs that were active when the process died as paused, so the UI state is truthful. */
    suspend fun markInterruptedRunsPaused() {
        for (session in sessions.getAll()) {
            if (ScrapeStatus.fromName(session.status).isActive) {
                sessions.upsert(session.copy(status = ScrapeStatus.PAUSED.name, updatedAt = now(), lastError = "Interrupted: the app was closed while scraping"))
                projects.setStatus(session.projectId, ScrapeStatus.PAUSED.name, "Interrupted: the app was closed while scraping", now())
            }
        }
    }

    suspend fun recordVisit(url: String, title: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val t = now()
        val latest = history.latest()
        if (latest != null && latest.url == url) {
            history.touch(latest.id, title.ifBlank { latest.title }, t)
        } else {
            history.insert(BrowserHistoryEntity(url = url, title = title, visitedAt = t))
            history.prune(HISTORY_LIMIT)
        }
    }

    suspend fun deleteHistoryItem(id: Long) = history.delete(id)

    suspend fun clearHistory() = history.clear()

    suspend fun clearStrategies() = strategies.deleteAll()

    // ---- ScrapeStore ----

    override suspend fun getProject(id: String): ScrapeProject? = projects.get(id)?.toModel()

    override suspend fun createProject(title: String, sourceUrl: String): ScrapeProject {
        val t = now()
        val entity = ScrapeProjectEntity(
            id = UUID.randomUUID().toString(),
            title = title.trim().ifBlank { "Untitled novel" },
            author = null,
            siteHost = UrlIdentity.siteHost(sourceUrl),
            sourceUrl = sourceUrl,
            lastChapterUrl = null,
            resumeUrl = sourceUrl,
            status = ScrapeStatus.IDLE.name,
            chapterCount = 0,
            totalCharacters = 0,
            lastError = null,
            linkedBookId = null,
            linkedChapterCount = 0,
            createdAt = t,
            updatedAt = t
        )
        projects.upsert(entity)
        return entity.toModel()
    }

    override suspend fun findSavedChapter(projectId: String, urlKey: String, canonicalKey: String, contentHash: String): ScrapedChapterSummary? =
        chapters.findByUrl(projectId, urlKey, canonicalKey)
            ?: contentHash.takeIf { it.isNotBlank() }?.let { chapters.findByHash(projectId, it) }

    override suspend fun visitedUrlKeys(projectId: String): Set<String> {
        val keys = HashSet<String>(chapters.urlKeys(projectId))
        sessions.get(projectId)?.let { keys += parseSkipped(it.skippedUrlsJson) }
        return keys
    }

    override suspend fun saveChapter(projectId: String, draft: ChapterDraft): ScrapedChapterSummary {
        val id = UrlIdentity.chapterId(projectId, draft.urlKey)
        return try {
            db.withTransaction {
                val t = now()
                val sequence = (chapters.maxSequence(projectId) ?: 0) + 1
                val entity = ScrapedChapterEntity(
                    id = id,
                    projectId = projectId,
                    sequence = sequence,
                    chapterNumber = draft.chapterNumber,
                    title = draft.title,
                    sourceUrl = draft.sourceUrl,
                    urlKey = draft.urlKey,
                    canonicalUrl = draft.canonicalUrl,
                    contentHtml = ChapterText.toXhtmlParagraphs(draft.paragraphs),
                    contentHash = draft.contentHash,
                    characterCount = draft.paragraphs.sumOf { it.length },
                    paragraphCount = draft.paragraphs.size,
                    createdAt = t
                )
                chapters.insert(entity)
                projects.setLastChapterUrl(projectId, draft.sourceUrl, t)
                projects.refreshCounts(projectId, t)
                ScrapedChapterSummary(
                    id = entity.id,
                    projectId = projectId,
                    sequence = sequence,
                    chapterNumber = entity.chapterNumber,
                    title = entity.title,
                    sourceUrl = entity.sourceUrl,
                    characterCount = entity.characterCount,
                    paragraphCount = entity.paragraphCount,
                    createdAt = t
                )
            }
        } catch (e: SQLiteConstraintException) {
            // Same URL saved concurrently: return the existing row instead of failing the run.
            chapters.findByUrl(projectId, draft.urlKey, draft.urlKey) ?: throw e
        }
    }

    override suspend fun setResumeUrl(projectId: String, url: String?) = projects.setResumeUrl(projectId, url, now())

    override suspend fun addSkippedUrl(projectId: String, urlKey: String) {
        val t = now()
        val session = sessions.get(projectId) ?: emptySession(projectId, t)
        val skipped = parseSkipped(session.skippedUrlsJson) + urlKey
        sessions.upsert(session.copy(skippedUrlsJson = JSONArray(skipped.toList()).toString(), updatedAt = t))
    }

    override suspend fun runChapterCount(projectId: String): Int = sessions.get(projectId)?.chaptersThisRun ?: 0

    override suspend fun updateRun(projectId: String, status: ScrapeStatus, currentUrl: String?, chaptersThisRun: Int, error: String?) {
        db.withTransaction {
            val t = now()
            val session = sessions.get(projectId) ?: emptySession(projectId, t)
            sessions.upsert(
                session.copy(
                    status = status.name,
                    currentUrl = currentUrl ?: session.currentUrl,
                    chaptersThisRun = chaptersThisRun,
                    updatedAt = t,
                    lastError = error
                )
            )
            projects.setStatus(projectId, status.name, error, t)
        }
    }

    override suspend fun getStrategy(host: String): WebsiteStrategy? = strategies.get(host)?.let {
        WebsiteStrategy(it.siteHost, it.contentSelector, it.titleSelector, it.nextSelector, it.successCount, it.updatedAt)
    }

    override suspend fun saveStrategy(strategy: WebsiteStrategy) = strategies.upsert(
        SiteStrategyEntity(
            siteHost = strategy.siteHost,
            contentSelector = strategy.contentSelector,
            titleSelector = strategy.titleSelector,
            nextSelector = strategy.nextSelector,
            successCount = strategy.successCount,
            updatedAt = now()
        )
    )

    override suspend fun deleteStrategy(host: String) = strategies.delete(host)

    override suspend fun recentSaveTimes(siteHost: String, since: Long): List<Long> = chapters.saveTimesSince(siteHost, since)

    private fun emptySession(projectId: String, t: Long) = ScrapeSessionEntity(
        projectId = projectId,
        status = ScrapeStatus.IDLE.name,
        currentUrl = null,
        chaptersThisRun = 0,
        skippedUrlsJson = "[]",
        startedAt = t,
        updatedAt = t,
        lastError = null
    )

    private fun parseSkipped(json: String): Set<String> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }.toSet()
    } catch (_: Exception) {
        emptySet()
    }

    companion object {
        const val HISTORY_LIMIT = 500
    }
}

fun ScrapeProjectEntity.toModel() = ScrapeProject(
    id = id,
    title = title,
    author = author,
    siteHost = siteHost,
    sourceUrl = sourceUrl,
    lastChapterUrl = lastChapterUrl,
    resumeUrl = resumeUrl,
    status = ScrapeStatus.fromName(status),
    chapterCount = chapterCount,
    totalCharacters = totalCharacters,
    lastError = lastError,
    linkedBookId = linkedBookId,
    linkedChapterCount = linkedChapterCount,
    createdAt = createdAt,
    updatedAt = updatedAt
)
