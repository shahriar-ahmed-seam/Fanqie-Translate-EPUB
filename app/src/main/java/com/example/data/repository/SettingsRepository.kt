package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val workerCount: Int = 20,
    val maxActiveBooks: Int = 2,
    val chunkSize: Int = 4200,
    val maxRetries: Int = 5,
    val timeoutSeconds: Int = 30,
    val githubOwner: String = "shahriarseam",
    val githubRepo: String = "epub-translator",
    val autoCheckUpdates: Boolean = true,
    val isDarkMode: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("epub_translator_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        return AppSettings(
            workerCount = prefs.getInt("worker_count", 20).coerceIn(1, 50),
            maxActiveBooks = prefs.getInt("max_active_books", 2).coerceIn(1, 5),
            chunkSize = prefs.getInt("chunk_size", 4200).coerceIn(1000, 4800),
            maxRetries = prefs.getInt("max_retries", 5).coerceIn(1, 10),
            timeoutSeconds = prefs.getInt("timeout_seconds", 30).coerceIn(5, 120),
            githubOwner = prefs.getString("github_owner", "shahriarseam") ?: "shahriarseam",
            githubRepo = prefs.getString("github_repo", "epub-translator") ?: "epub-translator",
            autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
            isDarkMode = prefs.getBoolean("dark_mode", false)
        )
    }

    fun updateSettings(newSettings: AppSettings) {
        prefs.edit().apply {
            putInt("worker_count", newSettings.workerCount)
            putInt("max_active_books", newSettings.maxActiveBooks)
            putInt("chunk_size", newSettings.chunkSize)
            putInt("max_retries", newSettings.maxRetries)
            putInt("timeout_seconds", newSettings.timeoutSeconds)
            putString("github_owner", newSettings.githubOwner)
            putString("github_repo", newSettings.githubRepo)
            putBoolean("auto_check_updates", newSettings.autoCheckUpdates)
            putBoolean("dark_mode", newSettings.isDarkMode)
            apply()
        }
        _settings.value = newSettings
    }

    fun getLastReadChapterId(bookId: String): String? {
        return prefs.getString("last_read_chapter_$bookId", null)
    }

    fun setLastReadChapterId(bookId: String, chapterId: String) {
        prefs.edit().putString("last_read_chapter_$bookId", chapterId).apply()
    }
}
