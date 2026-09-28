package com.example

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.toModel
import com.example.data.repository.SettingsRepository
import com.example.diagnostics.AppDiagnostics
import com.example.diagnostics.BackgroundPolicy
import com.example.library.ScrapedLibraryBridge
import com.example.queue.TranslationQueueManager
import com.example.scraper.ScraperGraph
import com.example.scraper.ScraperHost
import com.example.scraper.data.ScraperRepository
import com.example.service.TranslationService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class TranslatorApplication : Application(), ScraperHost {

    /** Created on first use of the Scraper tab (or its service), never at process start. */
    override val scraperGraph: ScraperGraph by lazy {
        ScraperGraph(
            context = this,
            repository = ScraperRepository(
                db = database,
                projects = database.scrapeProjectDao(),
                chapters = database.scrapedChapterDao(),
                sessions = database.scrapeSessionDao(),
                strategies = database.siteStrategyDao(),
                history = database.browserHistoryDao()
            ),
            libraryBridge = ScrapedLibraryBridge(this, database) { queueManager }
        )
    }

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

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isInForeground = MutableStateFlow(false)
    /** True while one of the app's activities is started (visible to the user). */
    val isInForeground: StateFlow<Boolean> = _isInForeground.asStateFlow()
    private var startedActivities = 0

    companion object {
        lateinit var instance: TranslatorApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppDiagnostics.init(this)
        installCrashLogger()
        trackForeground()

        database = AppDatabase.getInstance(this)
        settingsRepository = SettingsRepository(this)
        queueManager = TranslationQueueManager(this, database, settingsRepository)
        ttsTextProcessor = com.example.tts.rule.TtsTextProcessor()

        // Keep text processor synchronized with persisted TTS rules in Room
        applicationScope.launch {
            try {
                database.ttsRuleDao().observeAllRules().collect { entities ->
                    val models = entities.map { it.toModel() }
                    ttsTextProcessor.setRules(models)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("TranslatorApplication", "Error observing TTS rules from Room", e)
            }
        }

        ttsManager = com.example.tts.ReaderTtsManager(
            context = this,
            textProcessor = ttsTextProcessor,
            settingsRepository = settingsRepository,
            autoRetryInBackground = true,
            isAppInForeground = { isInForeground.value }
        ).apply {
            setTtsEnabled(settingsRepository.isTtsEnabled())
            setSpeechRate(settingsRepository.getTtsSpeechRate())
            setAutoAdvanceChapter(settingsRepository.isTtsAutoAdvanceChapterEnabled())
            savedVoiceId = settingsRepository.getTtsVoiceId()
            chapterTransitionProvider = com.example.tts.DefaultChapterTransitionProvider(this@TranslatorApplication) { database }
        }

        // The translation service runs only while jobs are active. It used to be started with every
        // process, which crashed background restarts on Android 12+ (foreground-service start refused)
        // and used up Android 15's daily dataSync limit, after which the system killed the whole app.
        keepTranslationServiceWithActiveJobs()

        applicationScope.launch { AppDiagnostics.recordProcessExits(this@TranslatorApplication) }
    }

    private fun keepTranslationServiceWithActiveJobs() {
        applicationScope.launch {
            try {
                combine(
                    database.jobDao().observeActiveJobs().map { it.isNotEmpty() }.distinctUntilChanged(),
                    isInForeground
                ) { hasActiveJobs, foreground -> hasActiveJobs to foreground }
                    .distinctUntilChanged()
                    .collect { (hasActiveJobs, foreground) ->
                        val allowed = foreground || BackgroundPolicy.canStartForegroundServiceFromBackground(this@TranslatorApplication)
                        if (hasActiveJobs && allowed) {
                            TranslationService.start(this@TranslatorApplication)
                        }
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("TranslatorApplication", "Error observing active translation jobs", e)
            }
        }
    }

    private fun trackForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                _isInForeground.value = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                _isInForeground.value = startedActivities > 0
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    /** Records uncaught exceptions in the diagnostics log, then lets the default handler run. */
    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                AppDiagnostics.logCrash(thread, error)
            } catch (_: Throwable) {
                // Never block the crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
