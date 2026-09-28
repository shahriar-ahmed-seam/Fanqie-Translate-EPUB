package com.example.scraper.data

import android.content.Context
import android.content.SharedPreferences
import com.example.scraper.model.ScrapeSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Scraper settings and small UI state, stored in their own SharedPreferences file. */
class ScraperPreferences(context: Context, fileName: String = FILE_NAME) {

    private val prefs: SharedPreferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ScrapeSettings> = _settings.asStateFlow()

    private fun load(): ScrapeSettings {
        val d = ScrapeSettings()
        return ScrapeSettings(
            minCharacterCount = prefs.getInt(KEY_MIN_CHARS, d.minCharacterCount),
            stabilizationMs = prefs.getLong(KEY_STABILIZATION, d.stabilizationMs),
            pollingIntervalMs = prefs.getLong(KEY_POLLING, d.pollingIntervalMs),
            maxChapterWaitMs = prefs.getLong(KEY_MAX_WAIT, d.maxChapterWaitMs),
            translationWaitMs = prefs.getLong(KEY_TRANSLATION_WAIT, d.translationWaitMs),
            maxRetryCount = prefs.getInt(KEY_RETRIES, d.maxRetryCount),
            delayMinMs = prefs.getLong(KEY_DELAY_MIN, d.delayMinMs),
            delayMaxMs = prefs.getLong(KEY_DELAY_MAX, d.delayMaxMs),
            maxChaptersPerRun = prefs.getInt(KEY_LIMIT, d.maxChaptersPerRun),
            maxChaptersPerHour = prefs.getInt(KEY_PER_HOUR, d.maxChaptersPerHour),
            maxChaptersPerDay = prefs.getInt(KEY_PER_DAY, d.maxChaptersPerDay),
            defaultBaseUrl = prefs.getString(KEY_BASE_URL, d.defaultBaseUrl) ?: d.defaultBaseUrl,
            backgroundScraping = prefs.getBoolean(KEY_BACKGROUND, d.backgroundScraping),
            autoScroll = prefs.getBoolean(KEY_AUTO_SCROLL, d.autoScroll)
        ).normalized()
    }

    fun update(transform: (ScrapeSettings) -> ScrapeSettings) {
        val next = transform(_settings.value).normalized()
        prefs.edit()
            .putInt(KEY_MIN_CHARS, next.minCharacterCount)
            .putLong(KEY_STABILIZATION, next.stabilizationMs)
            .putLong(KEY_POLLING, next.pollingIntervalMs)
            .putLong(KEY_MAX_WAIT, next.maxChapterWaitMs)
            .putLong(KEY_TRANSLATION_WAIT, next.translationWaitMs)
            .putInt(KEY_RETRIES, next.maxRetryCount)
            .putLong(KEY_DELAY_MIN, next.delayMinMs)
            .putLong(KEY_DELAY_MAX, next.delayMaxMs)
            .putInt(KEY_LIMIT, next.maxChaptersPerRun)
            .putInt(KEY_PER_HOUR, next.maxChaptersPerHour)
            .putInt(KEY_PER_DAY, next.maxChaptersPerDay)
            .putString(KEY_BASE_URL, next.defaultBaseUrl)
            .putBoolean(KEY_BACKGROUND, next.backgroundScraping)
            .putBoolean(KEY_AUTO_SCROLL, next.autoScroll)
            .apply()
        _settings.value = next
    }

    /** Last page shown in the scraper browser, restored when the browser is recreated. */
    var lastBrowserUrl: String?
        get() = prefs.getString(KEY_LAST_URL, null)
        set(value) {
            prefs.edit().putString(KEY_LAST_URL, value).apply()
        }

    /** Project whose "resume" banner the user dismissed (until it is scraped again). */
    var dismissedResumeProjectId: String?
        get() = prefs.getString(KEY_DISMISSED_RESUME, null)
        set(value) {
            prefs.edit().putString(KEY_DISMISSED_RESUME, value).apply()
        }

    /** Imports values from the standalone NovelScraper app's old preference file, once. */
    fun migrateLegacy(context: Context, legacyFileName: String) {
        if (prefs.getBoolean(KEY_LEGACY_MIGRATED, false)) return
        val legacy = context.getSharedPreferences(legacyFileName, Context.MODE_PRIVATE)
        if (legacy.all.isNotEmpty()) {
            update { current ->
                val oldDelay = legacy.getLong("delay_between_chapters_ms", current.delayMinMs)
                current.copy(
                    minCharacterCount = legacy.getInt("min_character_count", current.minCharacterCount),
                    maxChapterWaitMs = legacy.getLong("max_chapter_wait_ms", current.maxChapterWaitMs),
                    maxRetryCount = legacy.getInt("max_retry_count", current.maxRetryCount),
                    delayMinMs = oldDelay,
                    delayMaxMs = maxOf(oldDelay, current.delayMaxMs.coerceAtMost(oldDelay * 2)),
                    maxChaptersPerRun = legacy.getInt("max_chapter_limit", current.maxChaptersPerRun),
                    defaultBaseUrl = legacy.getString("default_base_url", current.defaultBaseUrl) ?: current.defaultBaseUrl
                )
            }
        }
        prefs.edit().putBoolean(KEY_LEGACY_MIGRATED, true).apply()
    }

    companion object {
        const val FILE_NAME = "scraper_prefs"
        private const val KEY_MIN_CHARS = "min_character_count"
        private const val KEY_STABILIZATION = "stabilization_ms"
        private const val KEY_POLLING = "polling_interval_ms"
        private const val KEY_MAX_WAIT = "max_chapter_wait_ms"
        private const val KEY_TRANSLATION_WAIT = "translation_wait_ms"
        private const val KEY_RETRIES = "max_retry_count"
        private const val KEY_DELAY_MIN = "delay_min_ms"
        private const val KEY_DELAY_MAX = "delay_max_ms"
        private const val KEY_LIMIT = "max_chapters_per_run"
        private const val KEY_PER_HOUR = "max_chapters_per_hour"
        private const val KEY_PER_DAY = "max_chapters_per_day"
        private const val KEY_BASE_URL = "default_base_url"
        private const val KEY_BACKGROUND = "background_scraping"
        private const val KEY_AUTO_SCROLL = "auto_scroll"
        private const val KEY_LAST_URL = "last_browser_url"
        private const val KEY_DISMISSED_RESUME = "dismissed_resume_project"
        private const val KEY_LEGACY_MIGRATED = "legacy_prefs_migrated"
    }
}
