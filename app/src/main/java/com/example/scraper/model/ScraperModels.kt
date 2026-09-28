package com.example.scraper.model

/**
 * Lifecycle of a scrape run. [isActive] states own the browser and must be paused/stopped
 * before another run can start.
 */
enum class ScrapeStatus(val label: String, val isActive: Boolean) {
    IDLE("Idle", false),
    STARTING("Starting", true),
    LOADING("Loading page", true),
    WAITING_CONTENT("Waiting for content", true),
    SAVING("Saving chapter", true),
    COOLDOWN("Waiting before next chapter", true),
    NAVIGATING("Opening next chapter", true),
    PAUSED("Paused", false),
    STOPPED("Stopped", false),
    COMPLETED("Completed", false),
    NEEDS_LOGIN("Action needed in browser", false),
    ERROR("Error", false);

    /** A run that ended in this state can be continued with Resume. */
    val isResumable: Boolean get() = this == PAUSED || this == NEEDS_LOGIN || this == ERROR || this == STOPPED || this == COMPLETED

    companion object {
        fun fromName(name: String?): ScrapeStatus = entries.firstOrNull { it.name == name } ?: IDLE
    }
}

/** User-tunable scraping behaviour. All durations are milliseconds. */
data class ScrapeSettings(
    val minCharacterCount: Int = 300,
    /** Content must stay unchanged this long before it is accepted (lets live translation finish). */
    val stabilizationMs: Long = 2_500L,
    val pollingIntervalMs: Long = 1_000L,
    val maxChapterWaitMs: Long = 90_000L,
    /** How long to wait for Chinese text to be replaced by a translation before saving it as-is. */
    val translationWaitMs: Long = 30_000L,
    val maxRetryCount: Int = 3,
    /** Random delay between chapters is picked from [delayMinMs, delayMaxMs]. */
    val delayMinMs: Long = 15_000L,
    val delayMaxMs: Long = 30_000L,
    /** Stop after this many new chapters in one run. 0 = no limit. */
    val maxChaptersPerRun: Int = 0,
    /**
     * Chapters per site in any rolling hour / day. TomatoMTL restricts accounts reading more than
     * 100 chapters an hour or 676 a day, so the defaults stay below that. 0 = no limit.
     */
    val maxChaptersPerHour: Int = 90,
    val maxChaptersPerDay: Int = 600,
    val defaultBaseUrl: String = DEFAULT_BASE_URL,
    val backgroundScraping: Boolean = true,
    /** Scroll the page while waiting so lazily translated/loaded text gets rendered. */
    val autoScroll: Boolean = true
) {
    fun normalized(): ScrapeSettings {
        val min = delayMinMs.coerceIn(0L, MAX_DELAY_MS)
        return copy(
            minCharacterCount = minCharacterCount.coerceIn(20, 20_000),
            stabilizationMs = stabilizationMs.coerceIn(0L, 30_000L),
            pollingIntervalMs = pollingIntervalMs.coerceIn(250L, 5_000L),
            maxChapterWaitMs = maxChapterWaitMs.coerceIn(10_000L, 600_000L),
            translationWaitMs = translationWaitMs.coerceIn(0L, 300_000L),
            maxRetryCount = maxRetryCount.coerceIn(0, 10),
            delayMinMs = min,
            delayMaxMs = delayMaxMs.coerceIn(min, MAX_DELAY_MS),
            maxChaptersPerRun = maxChaptersPerRun.coerceIn(0, 100_000),
            maxChaptersPerHour = maxChaptersPerHour.coerceIn(0, 10_000),
            maxChaptersPerDay = maxChaptersPerDay.coerceIn(0, 100_000),
            defaultBaseUrl = defaultBaseUrl.trim().ifBlank { DEFAULT_BASE_URL }
        )
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://tomatomtl.com"
        const val MAX_DELAY_MS = 600_000L
    }
}

enum class LogLevel { DEBUG, INFO, SUCCESS, WARNING, ERROR }

data class ScrapeLogEntry(
    val id: Long,
    val timestamp: Long,
    val message: String,
    val level: LogLevel
)

/** Snapshot of the running (or last) scrape, rendered by the UI and the notification. */
data class ScrapeProgress(
    val status: ScrapeStatus = ScrapeStatus.IDLE,
    val projectId: String? = null,
    val projectTitle: String = "",
    val currentUrl: String = "",
    val chapterTitle: String = "",
    val characterCount: Int = 0,
    val paragraphCount: Int = 0,
    val chaptersThisRun: Int = 0,
    val totalChapters: Int = 0,
    val retryCount: Int = 0,
    val delayRemainingMs: Long = 0L,
    val delayTotalMs: Long = 0L,
    val message: String = "Open a chapter page, then tap Start",
    val strategyInfo: String = "",
    val lastError: String? = null
)

/** Which project a run writes into. Chosen explicitly by the user every time a run starts. */
sealed interface ProjectBinding {
    data class New(val title: String) : ProjectBinding
    data class Existing(val projectId: String) : ProjectBinding
}

data class ScrapeProject(
    val id: String,
    val title: String,
    val author: String?,
    val siteHost: String,
    val sourceUrl: String,
    val lastChapterUrl: String?,
    val resumeUrl: String?,
    val status: ScrapeStatus,
    val chapterCount: Int,
    val totalCharacters: Long,
    val lastError: String?,
    val linkedBookId: String?,
    val linkedChapterCount: Int,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** Where "Resume" continues: the known next chapter, else the last saved chapter, else the start page. */
    val continueUrl: String get() = resumeUrl ?: lastChapterUrl ?: sourceUrl
}

/** Chapter row without its (potentially large) text. */
data class ScrapedChapterSummary(
    val id: String,
    val projectId: String,
    val sequence: Int,
    val chapterNumber: Double?,
    val title: String,
    val sourceUrl: String,
    val characterCount: Int,
    val paragraphCount: Int,
    val createdAt: Long
)

data class ChapterDraft(
    val title: String,
    val chapterNumber: Double?,
    val sourceUrl: String,
    val urlKey: String,
    val canonicalUrl: String,
    val paragraphs: List<String>,
    val contentHash: String
)

data class WebsiteStrategy(
    val siteHost: String,
    val contentSelector: String,
    val titleSelector: String = "",
    val nextSelector: String = "",
    val successCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

data class BrowserHistoryItem(
    val id: Long,
    val url: String,
    val title: String,
    val visitedAt: Long
)

data class NextChapterCandidate(
    val url: String,
    val text: String,
    val score: Double,
    val reason: String,
    val selector: String = ""
)

data class ValidationResult(val isValid: Boolean, val message: String)
