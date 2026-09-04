package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.TranslatorApplication
import com.example.data.db.BookEntity
import com.example.data.db.TranslationJobEntity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import java.text.NumberFormat
import java.util.Locale

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationJob: Job? = null

    private data class NotificationState(
        val jobs: List<TranslationJobEntity>,
        val books: Map<String, BookEntity>,
        val translatedTitles: Map<String, String?>,
        val activeWorkers: Int,
        val maxWorkers: Int
    )

    companion object {
        const val CHANNEL_ID = "epub_translation_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.example.service.ACTION_START"
        const val ACTION_PAUSE_JOB = "com.example.service.ACTION_PAUSE_JOB"
        const val ACTION_RESUME_JOB = "com.example.service.ACTION_RESUME_JOB"
        const val EXTRA_JOB_ID = "extra_job_id"

        fun start(context: Context) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pauseJob(context: Context, jobId: String) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = ACTION_PAUSE_JOB
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }

        fun resumeJob(context: Context, jobId: String) {
            val intent = Intent(context, TranslationService::class.java).apply {
                action = ACTION_RESUME_JOB
                putExtra(EXTRA_JOB_ID, jobId)
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()
        observeTranslationProgress()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? TranslatorApplication
        val queueManager = app?.queueManager

        when (intent?.action) {
            ACTION_PAUSE_JOB -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId != null) {
                    queueManager?.pauseJob(jobId)
                }
            }
            ACTION_RESUME_JOB -> {
                val jobId = intent.getStringExtra(EXTRA_JOB_ID)
                if (jobId != null) {
                    queueManager?.resumeJob(jobId)
                }
            }
            else -> {
                queueManager?.startQueueProcessing()
            }
        }

        return START_STICKY
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

    private fun startInForeground() {
        val initialNotification = buildInitialNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
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
                val titleMap = titleChunks.associate { it.bookId to it.translatedText?.takeIf { t -> t.isNotBlank() } }
                NotificationState(
                    jobs = allJobs,
                    books = allBooks.associateBy { it.id },
                    translatedTitles = titleMap,
                    activeWorkers = activeWorkers,
                    maxWorkers = settings.workerCount
                )
            }.conflate().collect { state ->
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return@collect

                val notif = buildGroupedNotification(state)
                notificationManager.notify(NOTIFICATION_ID, notif)
                // Throttle updates to prevent OS notification spam
                delay(500)
            }
        }
    }

    private fun buildGroupedNotification(state: NotificationState): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

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
            val hasCompleted = state.jobs.any { it.status == "COMPLETED" }
            val title = if (hasCompleted) "All translations completed" else "EPUB Translator"
            val text = if (hasCompleted) "All EPUB translations finished successfully." else "Queue idle • 0 active jobs"

            return builder
                .setContentTitle(title)
                .setContentText(text)
                .setSubText(if (hasCompleted) "Completed" else "Idle")
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
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
                .setOngoing(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))

            if (job.status == "PAUSED" && !hasActiveNetwork) {
                builder.setProgress(0, 0, false)
            } else {
                builder.setProgress(if (job.totalChunks > 0) job.totalChunks else 100, job.completedChunks, false)
            }

            // Pause / Resume action button directly in notification
            if (job.status in listOf("RUNNING", "TRANSLATING", "QUEUED")) {
                val pauseIntent = Intent(this, TranslationService::class.java).apply {
                    action = ACTION_PAUSE_JOB
                    putExtra(EXTRA_JOB_ID, job.id)
                }
                val pausePendingIntent = PendingIntent.getService(
                    this,
                    job.id.hashCode(),
                    pauseIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(
                    android.R.drawable.ic_media_pause,
                    "Pause",
                    pausePendingIntent
                )
            } else if (job.status == "PAUSED") {
                val resumeIntent = Intent(this, TranslationService::class.java).apply {
                    action = ACTION_RESUME_JOB
                    putExtra(EXTRA_JOB_ID, job.id)
                }
                val resumePendingIntent = PendingIntent.getService(
                    this,
                    job.id.hashCode(),
                    resumeIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(
                    android.R.drawable.ic_media_play,
                    "Resume",
                    resumePendingIntent
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
            activeOrQueuedJobs.forEachIndexed { index, job ->
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
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))

        if (!hasActiveNetwork && activeOrQueuedJobs.all { it.status == "PAUSED" }) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(if (totalChunksSum > 0) totalChunksSum else 100, completedChunksSum, false)
        }

        // Action button for multiple novels
        val runningJob = activeOrQueuedJobs.firstOrNull { it.status in listOf("RUNNING", "TRANSLATING") }
        if (runningJob != null) {
            val pauseIntent = Intent(this, TranslationService::class.java).apply {
                action = ACTION_PAUSE_JOB
                putExtra(EXTRA_JOB_ID, runningJob.id)
            }
            val pausePendingIntent = PendingIntent.getService(
                this,
                runningJob.id.hashCode(),
                pauseIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Pause", pausePendingIntent)
        } else {
            val pausedJob = activeOrQueuedJobs.firstOrNull { it.status == "PAUSED" }
            if (pausedJob != null) {
                val resumeIntent = Intent(this, TranslationService::class.java).apply {
                    action = ACTION_RESUME_JOB
                    putExtra(EXTRA_JOB_ID, pausedJob.id)
                }
                val resumePendingIntent = PendingIntent.getService(
                    this,
                    pausedJob.id.hashCode(),
                    resumeIntent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(android.R.drawable.ic_media_play, "Resume", resumePendingIntent)
            }
        }

        return builder.build()
    }

    private fun buildInitialNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EPUB Translator")
            .setContentText("Translation service active")
            .setSubText("Starting...")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

