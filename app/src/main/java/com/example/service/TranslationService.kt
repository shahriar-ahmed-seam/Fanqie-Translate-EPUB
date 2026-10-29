package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.TranslatorApplication
import com.example.data.db.BookEntity
import com.example.data.db.TranslationJobEntity
import com.example.diagnostics.AppDiagnostics
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the process in the foreground while translation jobs are active.
 *
 * It only runs while there is work: it stops itself a few seconds after the last active job finishes
 * or pauses, and it stops cleanly when Android's dataSync time limit (Android 15+) is reached instead
 * of letting the system crash the app, which used to take audiobook playback down with it.
 */
class TranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationJob: Job? = null
    private var idleStopJob: Job? = null
    /** Jobs seen active by this run, so the closing notification only reports what this run finished. */
    private val jobsSeenActive = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var lastNotification: Notification? = null

    private data class NotificationState(
        val jobs: List<TranslationJobEntity>,
        val books: Map<String, BookEntity>,
        val translatedTitles: Map<String, String?>,
        val activeWorkers: Int,
        val maxWorkers: Int
    )

    companion object {
        private const val TAG = "TranslationService"
        const val CHANNEL_ID = "epub_translation_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_PAUSE_JOB = "com.example.service.ACTION_PAUSE_JOB"
        const val ACTION_RESUME_JOB = "com.example.service.ACTION_RESUME_JOB"
        const val EXTRA_JOB_ID = "extra_job_id"

        /** Job states that need the service (PAUSED jobs don't). */
        val ACTIVE_STATUSES = setOf("QUEUED", "RUNNING", "TRANSLATING", "PAUSING")

        /** Stop this long after the queue goes idle, so back-to-back jobs don't bounce the service. */
        private const val IDLE_STOP_DELAY_MS = 3_000L

        /**
         * Starts the service. Returns false when Android refuses (background start limits, time limit
         * reached). Never throws: the queue keeps working in-process either way.
         */
        fun start(context: Context): Boolean =
            send(context, Intent(context, TranslationService::class.java).setAction(ACTION_START))

        fun pauseJob(context: Context, jobId: String): Boolean = send(
            context,
            Intent(context, TranslationService::class.java).setAction(ACTION_PAUSE_JOB).putExtra(EXTRA_JOB_ID, jobId)
        )

        fun resumeJob(context: Context, jobId: String): Boolean = send(
            context,
            Intent(context, TranslationService::class.java).setAction(ACTION_RESUME_JOB).putExtra(EXTRA_JOB_ID, jobId)
        )

        private fun send(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+), IllegalStateException, SecurityException.
            Log.w(TAG, "Translation service start refused: ${e.javaClass.simpleName}: ${e.message}")
            AppDiagnostics.log("translation", "Service start refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), even when we then stop.
        if (!startInForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val queueManager = (applicationContext as? TranslatorApplication)?.queueManager
        when (intent?.action) {
            ACTION_PAUSE_JOB -> intent.getStringExtra(EXTRA_JOB_ID)?.let { queueManager?.pauseJob(it) }
            ACTION_RESUME_JOB -> intent.getStringExtra(EXTRA_JOB_ID)?.let { queueManager?.resumeJob(it) }
            else -> queueManager?.startQueueProcessing()
        }

        if (notificationJob?.isActive != true) observeTranslationProgress()
        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ allows dataSync services ~6 h per day; the app must stop within seconds or it crashes.
        Log.w(TAG, "dataSync time limit reached; stopping the translation service")
        AppDiagnostics.log("translation", "dataSync time limit reached; service stopped (translation continues while the app is open)")
        finishService(buildTimeLimitNotification())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "EPUB Translation",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background EPUB Translation Service"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startInForeground(): Boolean {
        val notification = lastNotification ?: buildInitialNotification()
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
            )
            true
        } catch (e: Exception) {
            // Background start refused, or the daily dataSync limit is used up. The queue keeps running
            // in-process; the app starts the service again the next time it is opened.
            Log.w(TAG, "startForeground refused", e)
            AppDiagnostics.log("translation", "startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /** Leaves the foreground, optionally keeping [notice] visible (dismissible), and stops. */
    private fun finishService(notice: Notification?) {
        idleStopJob?.cancel()
        notificationJob?.cancel()
        try {
            if (notice != null) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notice)
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
            } else {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        lastNotification = null
        stopSelf()
    }

    private fun formatNumber(number: Int): String {
        return NumberFormat.getNumberInstance(Locale.US).format(number)
    }

    private fun observeTranslationProgress() {
        val app = applicationContext as? TranslatorApplication ?: return
        val database = app.database
        val queueManager = app.queueManager
        val settingsRepository = app.settingsRepository

        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            combine(
                database.jobDao().getAllJobs(),
                database.bookDao().getAllBooks(),
                database.chunkDao().observeAllTitleChunks(),
                queueManager.activeWorkers,
                settingsRepository.settings
            ) { allJobs, allBooks, titleChunks, activeWorkers, settings ->
                val titleMap = titleChunks.associate { it.bookId to com.example.translation.TranslationQuality.usableTitle(it.translatedText) }
                NotificationState(
                    jobs = allJobs,
                    books = allBooks.associateBy { it.id },
                    translatedTitles = titleMap,
                    activeWorkers = activeWorkers,
                    maxWorkers = settings.workerCount
                )
            }.conflate().collect { state ->
                val activeJobs = state.jobs.filter { it.status in ACTIVE_STATUSES }
                activeJobs.forEach { jobsSeenActive.add(it.id) }

                if (activeJobs.isNotEmpty()) {
                    idleStopJob?.cancel()
                    idleStopJob = null
                    val notification = buildGroupedNotification(state, ongoing = true)
                    lastNotification = notification
                    try {
                        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
                    } catch (e: Exception) {
                        Log.w(TAG, "Couldn't update the translation notification", e)
                    }
                } else if (idleStopJob?.isActive != true) {
                    idleStopJob = launch {
                        delay(IDLE_STOP_DELAY_MS)
                        val stillIdle = database.jobDao().observeActiveJobs().first().isEmpty()
                        if (stillIdle) {
                            Log.i(TAG, "Translation queue idle; stopping the service")
                            finishService(buildIdleSummary(state))
                        }
                    }
                }
                // Throttle updates to prevent OS notification spam
                delay(500)
            }
        }
    }

    /** What to leave behind when the queue goes idle: finished or paused work, or nothing. */
    private fun buildIdleSummary(state: NotificationState): Notification? {
        val paused = state.jobs.filter { it.status == "PAUSED" }
        if (paused.isNotEmpty()) return buildGroupedNotification(state, ongoing = false)

        val finished = state.jobs.filter { it.id in jobsSeenActive }
        val completed = finished.count { it.status == "COMPLETED" }
        val failed = finished.count { it.status == "FAILED" }
        if (completed == 0 && failed == 0) return null

        val title = when {
            failed == 0 -> if (completed == 1) "Translation completed" else "All translations completed"
            completed == 0 -> "Translation stopped with errors"
            else -> "Translations finished"
        }
        val text = buildString {
            if (completed > 0) append("$completed completed")
            if (failed > 0) {
                if (isNotEmpty()) append(" · ")
                append("$failed with failed chunks (retry from the Queue tab)")
            }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun buildTimeLimitNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Background translation paused by Android")
            .setContentText("Android limits background translation time. Open the app to continue.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Android limits how long apps can translate in the background each day. " +
                        "Open the app to continue; translation resumes automatically."
                )
            )
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun openAppPendingIntent(): PendingIntent {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    /** Pause/Resume buttons restart the service if it already stopped (tapping them allows that). */
    private fun jobActionPendingIntent(action: String, jobId: String): PendingIntent {
        val intent = Intent(this, TranslationService::class.java).apply {
            this.action = action
            putExtra(EXTRA_JOB_ID, jobId)
        }
        val requestCode = (action + jobId).hashCode()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, requestCode, intent, flags)
        } else {
            PendingIntent.getService(this, requestCode, intent, flags)
        }
    }

    private fun buildGroupedNotification(state: NotificationState, ongoing: Boolean): Notification {
        val pendingIntent = openAppPendingIntent()

        val activeOrQueuedJobs = state.jobs.filter {
            it.status in listOf("RUNNING", "TRANSLATING", "PAUSING", "QUEUED", "PAUSED")
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (activeOrQueuedJobs.isEmpty()) {
            return builder
                .setContentTitle("EPUB Translator")
                .setContentText("Translation queue idle")
                .setOngoing(false)
                .setProgress(0, 0, false)
                .build()
        }

        val totalChunksSum = activeOrQueuedJobs.sumOf { it.totalChunks }
        val completedChunksSum = activeOrQueuedJobs.sumOf { it.completedChunks }
        val runningCount = activeOrQueuedJobs.count { it.status == "RUNNING" || it.status == "TRANSLATING" }
        val hasActiveNetwork = state.activeWorkers > 0 || runningCount > 0

        if (activeOrQueuedJobs.size == 1) {
            val job = activeOrQueuedJobs.first()
            val englishTitle = state.translatedTitles[job.bookId] ?: state.books[job.bookId]?.title ?: "Translating EPUB"
            val percentVal = if (job.totalChunks > 0) (job.completedChunks.toDouble() * 100.0 / job.totalChunks.toDouble()) else 0.0
            val percentStr = String.format(Locale.US, "%.1f%%", percentVal)
            val chunkProgressStr = "${formatNumber(job.completedChunks)} / ${formatNumber(job.totalChunks)} chunks"

            val statusLabel = when (job.status) {
                "PAUSED" -> "Paused"
                "PAUSING" -> "Pausing..."
                "QUEUED" -> "Queued"
                else -> "${state.activeWorkers} workers"
            }

            val subText = "$percentStr · $statusLabel"

            val bigTextContent = buildString {
                appendLine(englishTitle)
                appendLine(chunkProgressStr)
                append("$percentStr · $statusLabel")
            }

            builder
                .setContentTitle(englishTitle)
                .setContentText(chunkProgressStr)
                .setSubText(subText)
                .setOngoing(ongoing)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))

            if (job.status == "PAUSED" && !hasActiveNetwork) {
                builder.setProgress(0, 0, false)
            } else {
                builder.setProgress(if (job.totalChunks > 0) job.totalChunks else 100, job.completedChunks, false)
            }

            // Pause / Resume action button directly in notification
            if (job.status in listOf("RUNNING", "TRANSLATING", "QUEUED")) {
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    jobActionPendingIntent(ACTION_PAUSE_JOB, job.id)
                )
            } else if (job.status == "PAUSED") {
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    jobActionPendingIntent(ACTION_RESUME_JOB, job.id)
                )
            }

            return builder.build()
        }

        // Multiple novels in active/queued queue
        val contentTitle = if (runningCount > 0) {
            "$runningCount novels translating"
        } else if (activeOrQueuedJobs.all { it.status == "PAUSED" }) {
            "${activeOrQueuedJobs.size} novels paused"
        } else {
            "${activeOrQueuedJobs.size} novels in queue"
        }

        val totalPercentVal = if (totalChunksSum > 0) (completedChunksSum.toDouble() * 100.0 / totalChunksSum.toDouble()) else 0.0
        val totalPercentStr = String.format(Locale.US, "%.1f%%", totalPercentVal)
        val multiSubText = "$totalPercentStr · ${state.activeWorkers} workers"

        // Collapsed summary text (e.g. "Night Fall — 1,245/3,800 · Another Novel — 430/2,100")
        val summaryText = activeOrQueuedJobs.take(2).joinToString(" · ") { job ->
            val title = state.translatedTitles[job.bookId] ?: state.books[job.bookId]?.title ?: "Novel"
            val suffix = if (job.status == "PAUSED") " (Paused)" else ""
            "$title — ${formatNumber(job.completedChunks)}/${formatNumber(job.totalChunks)}$suffix"
        }

        // Expanded BigText multi-novel details
        val bigTextContent = buildString {
            activeOrQueuedJobs.forEach { job ->
                val title = state.translatedTitles[job.bookId] ?: state.books[job.bookId]?.title ?: "Novel"
                val statusSuffix = when (job.status) {
                    "PAUSED" -> " (Paused)"
                    "PAUSING" -> " (Pausing...)"
                    "QUEUED" -> " (Queued)"
                    else -> ""
                }
                appendLine("$title$statusSuffix — ${formatNumber(job.completedChunks)}/${formatNumber(job.totalChunks)}")
            }
            append("\n${formatNumber(completedChunksSum)} / ${formatNumber(totalChunksSum)} chunks ($totalPercentStr) · ${state.activeWorkers} workers")
        }

        builder
            .setContentTitle(contentTitle)
            .setContentText(summaryText)
            .setSubText(multiSubText)
            .setOngoing(ongoing)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))

        if (!hasActiveNetwork && activeOrQueuedJobs.all { it.status == "PAUSED" }) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(if (totalChunksSum > 0) totalChunksSum else 100, completedChunksSum, false)
        }

        // Action button for multiple novels
        val runningJob = activeOrQueuedJobs.firstOrNull { it.status in listOf("RUNNING", "TRANSLATING") }
        if (runningJob != null) {
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", jobActionPendingIntent(ACTION_PAUSE_JOB, runningJob.id))
        } else {
            val pausedJob = activeOrQueuedJobs.firstOrNull { it.status == "PAUSED" }
            if (pausedJob != null) {
                builder.addAction(android.R.drawable.ic_media_play, "Resume", jobActionPendingIntent(ACTION_RESUME_JOB, pausedJob.id))
            }
        }

        return builder.build()
    }

    private fun buildInitialNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EPUB Translator")
            .setContentText("Translation service active")
            .setSubText("Starting...")
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
