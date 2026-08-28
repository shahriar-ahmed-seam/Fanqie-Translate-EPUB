package com.example

import android.app.Application
import com.example.data.db.AppDatabase
import com.example.data.repository.SettingsRepository
import com.example.queue.TranslationQueueManager
import com.example.service.TranslationService

class TranslatorApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var queueManager: TranslationQueueManager
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

        // Ensure service is launched to manage background queue
        TranslationService.start(this)
    }
}
