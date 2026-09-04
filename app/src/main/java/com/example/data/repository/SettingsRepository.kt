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
    val githubOwner: String = "shahriar-ahmed-seam",
    val githubRepo: String = "Fanqie-Translate-EPUB",
    val autoCheckUpdates: Boolean = true,
    val isDarkMode: Boolean = false
)

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("epub_translator_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val rawOwner = prefs.getString("github_owner", "shahriar-ahmed-seam") ?: "shahriar-ahmed-seam"
        val owner = if (rawOwner.isBlank() || rawOwner.equals("shahriarseam", ignoreCase = true)) "shahriar-ahmed-seam" else rawOwner
        val rawRepo = prefs.getString("github_repo", "Fanqie-Translate-EPUB") ?: "Fanqie-Translate-EPUB"
        val repo = if (rawRepo.isBlank() || rawRepo.equals("epub-translator", ignoreCase = true)) "Fanqie-Translate-EPUB" else rawRepo

        return AppSettings(
            workerCount = prefs.getInt("worker_count", 20).coerceIn(1, 50),
            maxActiveBooks = prefs.getInt("max_active_books", 2).coerceIn(1, 5),
            chunkSize = prefs.getInt("chunk_size", 4200).coerceIn(1000, 4800),
            maxRetries = prefs.getInt("max_retries", 5).coerceIn(1, 10),
            timeoutSeconds = prefs.getInt("timeout_seconds", 30).coerceIn(5, 120),
            githubOwner = owner,
            githubRepo = repo,
            autoCheckUpdates = prefs.getBoolean("auto_check_updates", true),
            isDarkMode = prefs.getBoolean("dark_mode", false)
        )
    }

    fun updateSettings(newSettings: AppSettings) {
        val validated = AppSettings(
            workerCount = newSettings.workerCount.coerceIn(1, 50),
            maxActiveBooks = newSettings.maxActiveBooks.coerceIn(1, 5),
            chunkSize = newSettings.chunkSize.coerceIn(1000, 4800),
            maxRetries = newSettings.maxRetries.coerceIn(1, 10),
            timeoutSeconds = newSettings.timeoutSeconds.coerceIn(5, 120),
            githubOwner = newSettings.githubOwner.trim().ifBlank { "shahriar-ahmed-seam" },
            githubRepo = newSettings.githubRepo.trim().ifBlank { "Fanqie-Translate-EPUB" },
            autoCheckUpdates = newSettings.autoCheckUpdates,
            isDarkMode = newSettings.isDarkMode
        )
        prefs.edit().apply {
            putInt("worker_count", validated.workerCount)
            putInt("max_active_books", validated.maxActiveBooks)
            putInt("chunk_size", validated.chunkSize)
            putInt("max_retries", validated.maxRetries)
            putInt("timeout_seconds", validated.timeoutSeconds)
            putString("github_owner", validated.githubOwner)
            putString("github_repo", validated.githubRepo)
            putBoolean("auto_check_updates", validated.autoCheckUpdates)
            putBoolean("dark_mode", validated.isDarkMode)
            apply()
        }
        _settings.value = validated
    }

    fun getLastReadChapterId(bookId: String): String? {
        return prefs.getString("last_read_chapter_$bookId", null)
    }

    fun setLastReadChapterId(bookId: String, chapterId: String) {
        prefs.edit().putString("last_read_chapter_$bookId", chapterId).apply()
    }

    fun getLastReadParagraphIndex(bookId: String, chapterId: String): Int {
        return prefs.getInt("last_read_para_${bookId}_$chapterId", 0)
    }

    fun setLastReadParagraphIndex(bookId: String, chapterId: String, paragraphIndex: Int) {
        prefs.edit().putInt("last_read_para_${bookId}_$chapterId", paragraphIndex.coerceAtLeast(0)).apply()
    }

    fun isTtsAutoAdvanceChapterEnabled(): Boolean {
        return prefs.getBoolean("tts_auto_advance_chapter", true)
    }

    fun setTtsAutoAdvanceChapterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_auto_advance_chapter", enabled).apply()
    }

    fun isTtsEnabled(): Boolean {
        return prefs.getBoolean("tts_master_enabled", true)
    }

    fun setTtsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_master_enabled", enabled).apply()
    }

    fun getTtsSpeechRate(): Float {
        return prefs.getFloat("tts_speech_rate", 1.0f).coerceIn(0.5f, 2.5f)
    }

    fun setTtsSpeechRate(rate: Float) {
        prefs.edit().putFloat("tts_speech_rate", rate.coerceIn(0.5f, 2.5f)).apply()
    }

    fun getTtsVoiceId(): String? {
        return prefs.getString("tts_selected_voice_id", null)
    }

    fun setTtsVoiceId(voiceId: String?) {
        prefs.edit().putString("tts_selected_voice_id", voiceId).apply()
    }

    fun getLastActiveBookId(): String? {
        return prefs.getString("last_active_book_id", null)
    }

    fun setLastActiveBookId(bookId: String?) {
        prefs.edit().putString("last_active_book_id", bookId).apply()
    }

    fun isTtsAutoResumePlaybackEnabled(): Boolean {
        return prefs.getBoolean("tts_auto_resume_playback", false)
    }

    fun setTtsAutoResumePlaybackEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("tts_auto_resume_playback", enabled).apply()
    }

    fun saveTtsSessionState(state: TtsPlaybackSessionState) {
        prefs.edit().apply {
            putString("tts_session_book_id", state.bookId)
            putString("tts_session_chapter_id", state.chapterId)
            putInt("tts_session_chapter_order", state.chapterOrder)
            putInt("tts_session_paragraph_index", state.paragraphIndex.coerceAtLeast(0))
            putString("tts_session_playback_state", state.playbackState)
            putFloat("tts_session_speech_rate", state.speechRate)
            putString("tts_session_voice_id", state.voiceId)
            putLong("tts_session_timestamp", state.timestamp)
            putInt("last_read_para_${state.bookId}_${state.chapterId}", state.paragraphIndex.coerceAtLeast(0))
            putString("last_read_chapter_${state.bookId}", state.chapterId)
            putString("last_active_book_id", state.bookId)
            apply()
        }
    }

    fun getTtsSessionState(): TtsPlaybackSessionState? {
        val bookId = prefs.getString("tts_session_book_id", null) ?: return null
        val chapterId = prefs.getString("tts_session_chapter_id", null) ?: return null
        return TtsPlaybackSessionState(
            bookId = bookId,
            chapterId = chapterId,
            chapterOrder = prefs.getInt("tts_session_chapter_order", 0),
            paragraphIndex = prefs.getInt("tts_session_paragraph_index", 0),
            playbackState = prefs.getString("tts_session_playback_state", "IDLE") ?: "IDLE",
            speechRate = prefs.getFloat("tts_session_speech_rate", 1.0f),
            voiceId = prefs.getString("tts_session_voice_id", null),
            timestamp = prefs.getLong("tts_session_timestamp", 0L)
        )
    }

    fun clearTtsSessionState() {
        prefs.edit().apply {
            remove("tts_session_book_id")
            remove("tts_session_chapter_id")
            remove("tts_session_chapter_order")
            remove("tts_session_paragraph_index")
            remove("tts_session_playback_state")
            remove("tts_session_speech_rate")
            remove("tts_session_voice_id")
            remove("tts_session_timestamp")
            apply()
        }
    }
}

data class TtsPlaybackSessionState(
    val bookId: String,
    val chapterId: String,
    val chapterOrder: Int = 0,
    val paragraphIndex: Int = 0,
    val playbackState: String = "IDLE",
    val speechRate: Float = 1.0f,
    val voiceId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

