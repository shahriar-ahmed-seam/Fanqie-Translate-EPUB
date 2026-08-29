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

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationJob: Job? = null

    private data class NotificationState(
        val jobs: List<TranslationJobEntity>,
        val books: Map<String, BookEntity>,
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
                queueManager.activeWorkers,
                settingsRepository.settings
            ) { allJobs, allBooks, activeWorkers, settings ->
                NotificationState(
                    jobs = allJobs,
                    books = allBooks.associateBy { it.id },
                    activeWorkers = activeWorkers,
                    maxWorkers = settings.workerCount
                )
            }.conflate().collect { state ->
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return@collect

                val notif = buildGroupedNotification(state)
                notificationManager.notify(NOTIFICATION_ID, notif)
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

        val subText = "Workers: ${state.activeWorkers} / ${state.maxWorkers}"
        val totalChunksSum = activeOrQueuedJobs.sumOf { it.totalChunks }
        val completedChunksSum = activeOrQueuedJobs.sumOf { it.completedChunks }
        val runningCount = activeOrQueuedJobs.count { it.status == "RUNNING" || it.status == "TRANSLATING" }
        val hasActiveNetwork = state.activeWorkers > 0 || runningCount > 0

        if (activeOrQueuedJobs.size == 1) {
            val job = activeOrQueuedJobs.first()
            val bookTitle = state.books[job.bookId]?.title ?: "Translating EPUB"
            val percent = if (job.totalChunks > 0) (job.completedChunks * 100 / job.totalChunks) else 0
            val statusLabel = when (job.status) {
                "PAUSED" -> " — PAUSED"
                "PAUSING" -> " — PAUSING..."
                "QUEUED" -> " — QUEUED"
                else -> ""
            }

            val title = "$bookTitle$statusLabel"
            val contentText = "${job.completedChunks} / ${job.totalChunks} chunks · $percent%"

            val bigTextContent = buildString {
                appendLine(title)
                appendLine(contentText)
                append("\n$subText")
            }

            builder
                .setContentTitle(title)
                .setContentText(contentText)
                .setSubText(subText)
                .setOngoing(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))

            if (job.status == "PAUSED" && !hasActiveNetwork) {
                builder.setProgress(0, 0, false)
            } else {
                builder.setProgress(if (job.totalChunks > 0) job.totalChunks else 100, job.completedChunks, false)
            }

            return builder.build()
        }

        // Multiple novels in active/queued queue
        val contentTitle = if (runningCount > 0) {
            "$runningCount novels translating"
        } else {
            "${activeOrQueuedJobs.size} novels in queue"
        }

        // Collapsed summary text
        val summaryText = activeOrQueuedJobs.take(2).joinToString(" · ") { job ->
            val title = state.books[job.bookId]?.title ?: "Novel"
            val percent = if (job.totalChunks > 0) (job.completedChunks * 100 / job.totalChunks) else 0
            val suffix = if (job.status == "PAUSED") " (PAUSED)" else " ($percent%)"
            "$title$suffix"
        }

        // Expanded BigText multi-novel details
        val bigTextContent = buildString {
            val displayJobs = activeOrQueuedJobs.take(3)
            displayJobs.forEachIndexed { index, job ->
                val bookTitle = state.books[job.bookId]?.title ?: "Novel"
                val percent = if (job.totalChunks > 0) (job.completedChunks * 100 / job.totalChunks) else 0
                val statusLabel = when (job.status) {
                    "PAUSED" -> " — PAUSED"
                    "PAUSING" -> " — PAUSING..."
                    "QUEUED" -> " — QUEUED"
                    else -> ""
                }
                appendLine("$bookTitle$statusLabel")
                appendLine("${job.completedChunks} / ${job.totalChunks} chunks · $percent%")
                if (index < displayJobs.size - 1) {
                    appendLine()
                }
            }

            val remainingCount = activeOrQueuedJobs.size - displayJobs.size
            if (remainingCount > 0) {
                appendLine()
                appendLine("+ $remainingCount more ${if (remainingCount == 1) "novel" else "novels"}")
            }

            append("\n$subText")
        }

        builder
            .setContentTitle(contentTitle)
            .setContentText(summaryText)
            .setSubText(subText)
            .setOngoing(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigTextContent))

        if (!hasActiveNetwork && activeOrQueuedJobs.all { it.status == "PAUSED" }) {
            builder.setProgress(0, 0, false)
        } else {
            builder.setProgress(if (totalChunksSum > 0) totalChunksSum else 100, completedChunksSum, false)
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

