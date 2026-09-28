package com.example.scraper.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Scraper tables. Table names are prefixed with `scrape_` so they can live in the same database as
 * the translator's own tables (which already has `books` and `chapters`).
 */

@Entity(
    tableName = "scrape_projects",
    indices = [Index(value = ["siteHost"]), Index(value = ["updatedAt"])]
)
data class ScrapeProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val siteHost: String,
    val sourceUrl: String,
    val lastChapterUrl: String?,
    val resumeUrl: String?,
    val status: String,
    val chapterCount: Int,
    val totalCharacters: Long,
    val lastError: String?,
    val linkedBookId: String?,
    val linkedChapterCount: Int,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "scrape_chapters",
    indices = [
        Index(value = ["projectId", "sequence"], unique = true),
        Index(value = ["projectId", "urlKey"]),
        Index(value = ["projectId", "contentHash"])
    ]
)
data class ScrapedChapterEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val sequence: Int,
    val chapterNumber: Double?,
    val title: String,
    val sourceUrl: String,
    val urlKey: String,
    val canonicalUrl: String,
    /** Escaped `<p>` paragraphs, see [com.example.scraper.engine.ChapterText]. */
    val contentHtml: String,
    val contentHash: String,
    val characterCount: Int,
    val paragraphCount: Int,
    val createdAt: Long
)

/** Resumable state of the latest run for a project. */
@Entity(tableName = "scrape_sessions")
data class ScrapeSessionEntity(
    @PrimaryKey val projectId: String,
    val status: String,
    val currentUrl: String?,
    val chaptersThisRun: Int,
    /** JSON array of normalised URLs the user skipped. */
    val skippedUrlsJson: String,
    val startedAt: Long,
    val updatedAt: Long,
    val lastError: String?
)

/** Selectors learned for a site, reused for every following chapter. */
@Entity(tableName = "scrape_site_strategies")
data class SiteStrategyEntity(
    @PrimaryKey val siteHost: String,
    val contentSelector: String,
    val titleSelector: String,
    val nextSelector: String,
    val successCount: Int,
    val updatedAt: Long
)

@Entity(
    tableName = "scrape_browser_history",
    indices = [Index(value = ["visitedAt"]), Index(value = ["url"])]
)
data class BrowserHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val visitedAt: Long
)
