package com.example.scraper.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL for creating the scraper tables inside a migration. Must stay identical to what Room generates
 * for the entities in [ScraperEntities] (checked by the migration tests against the exported schema).
 */
object ScraperSchema {

    val CREATE_STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `scrape_projects` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `author` TEXT, " +
            "`siteHost` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `lastChapterUrl` TEXT, `resumeUrl` TEXT, `status` TEXT NOT NULL, " +
            "`chapterCount` INTEGER NOT NULL, `totalCharacters` INTEGER NOT NULL, `lastError` TEXT, `linkedBookId` TEXT, " +
            "`linkedChapterCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE INDEX IF NOT EXISTS `index_scrape_projects_siteHost` ON `scrape_projects` (`siteHost`)",
        "CREATE INDEX IF NOT EXISTS `index_scrape_projects_updatedAt` ON `scrape_projects` (`updatedAt`)",

        "CREATE TABLE IF NOT EXISTS `scrape_chapters` (`id` TEXT NOT NULL, `projectId` TEXT NOT NULL, `sequence` INTEGER NOT NULL, " +
            "`chapterNumber` REAL, `title` TEXT NOT NULL, `sourceUrl` TEXT NOT NULL, `urlKey` TEXT NOT NULL, `canonicalUrl` TEXT NOT NULL, " +
            "`contentHtml` TEXT NOT NULL, `contentHash` TEXT NOT NULL, `characterCount` INTEGER NOT NULL, `paragraphCount` INTEGER NOT NULL, " +
            "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_scrape_chapters_projectId_sequence` ON `scrape_chapters` (`projectId`, `sequence`)",
        "CREATE INDEX IF NOT EXISTS `index_scrape_chapters_projectId_urlKey` ON `scrape_chapters` (`projectId`, `urlKey`)",
        "CREATE INDEX IF NOT EXISTS `index_scrape_chapters_projectId_contentHash` ON `scrape_chapters` (`projectId`, `contentHash`)",

        "CREATE TABLE IF NOT EXISTS `scrape_sessions` (`projectId` TEXT NOT NULL, `status` TEXT NOT NULL, `currentUrl` TEXT, " +
            "`chaptersThisRun` INTEGER NOT NULL, `skippedUrlsJson` TEXT NOT NULL, `startedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "`lastError` TEXT, PRIMARY KEY(`projectId`))",

        "CREATE TABLE IF NOT EXISTS `scrape_site_strategies` (`siteHost` TEXT NOT NULL, `contentSelector` TEXT NOT NULL, " +
            "`titleSelector` TEXT NOT NULL, `nextSelector` TEXT NOT NULL, `successCount` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
            "PRIMARY KEY(`siteHost`))",

        "CREATE TABLE IF NOT EXISTS `scrape_browser_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `url` TEXT NOT NULL, " +
            "`title` TEXT NOT NULL, `visitedAt` INTEGER NOT NULL)",
        "CREATE INDEX IF NOT EXISTS `index_scrape_browser_history_visitedAt` ON `scrape_browser_history` (`visitedAt`)",
        "CREATE INDEX IF NOT EXISTS `index_scrape_browser_history_url` ON `scrape_browser_history` (`url`)"
    )

    fun createTables(db: SupportSQLiteDatabase) {
        CREATE_STATEMENTS.forEach { db.execSQL(it) }
    }
}
