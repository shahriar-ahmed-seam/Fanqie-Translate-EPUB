package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.db.toModel
import com.example.data.repository.SettingsRepository
import com.example.queue.TranslationQueueManager
import com.example.service.TranslationService
import kotlinx.coroutines.launch

class TranslatorApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var queueManager: TranslationQueueManager
        private set

    lateinit var ttsTextProcessor: com.example.tts.rule.TtsTextProcessor
        private set

    lateinit var ttsManager: com.example.tts.ReaderTtsManager
        private set

    companion object {
        lateinit var instance: TranslatorApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        queueManager = TranslationQueueManager(this, database, settingsRepository)
        ttsTextProcessor = com.example.tts.rule.TtsTextProcessor()

        // Keep text processor synchronized with persisted TTS rules in Room
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            database.ttsRuleDao().observeAllRules().collect { entities ->
                val models = entities.map { it.toModel() }
                ttsTextProcessor.setRules(models)
            }
        }

        ttsManager = com.example.tts.ReaderTtsManager(this, textProcessor = ttsTextProcessor).apply {
            setTtsEnabled(settingsRepository.isTtsEnabled())
            setSpeechRate(settingsRepository.getTtsSpeechRate())
            setAutoAdvanceChapter(settingsRepository.isTtsAutoAdvanceChapterEnabled())
            savedVoiceId = settingsRepository.getTtsVoiceId()
        }

        // Ensure service is launched to manage background queue
        TranslationService.start(this)
    }
}
