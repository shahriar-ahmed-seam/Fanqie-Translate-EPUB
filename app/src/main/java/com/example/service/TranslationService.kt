package com.example.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.TranslatorApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine

class TranslationService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var notificationJob: Job? = null

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
        val initialNotification = buildNotification("Translation service active", 0, 0, 0, false)
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

        notificationJob?.cancel()
        notificationJob = serviceScope.launch {
            combine(
                database.jobDao().observeActiveJobs(),
                queueManager.activeWorkers
            ) { activeJobs, activeWorkers ->
                Pair(activeJobs, activeWorkers)
            }.collect { (activeJobs, activeWorkers) ->
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                if (notificationManager == null) return@collect

                if (activeJobs.isEmpty()) {
                    val notif = buildNotification(
                        content = "Queue idle • 0 active jobs",
                        progressMax = 0,
                        progressCurrent = 0,
                        activeWorkers = activeWorkers,
                        isIndeterminate = false
                    )
                    notificationManager.notify(NOTIFICATION_ID, notif)
                } else {
                    val totalChunks = activeJobs.sumOf { it.totalChunks }
                    val completedChunks = activeJobs.sumOf { it.completedChunks }
                    val currentJob = activeJobs.firstOrNull { it.status == "RUNNING" || it.status == "TRANSLATING" }
                        ?: activeJobs.first()

                    val book = database.bookDao().getBookById(currentJob.bookId)
                    val bookTitle = book?.title ?: "Translating EPUB"

                    val content = "$bookTitle • $completedChunks/$totalChunks chunks"

                    val notif = buildNotification(
                        content = content,
                        progressMax = if (totalChunks > 0) totalChunks else 100,
                        progressCurrent = completedChunks,
                        activeWorkers = activeWorkers,
                        isIndeterminate = totalChunks == 0
                    )
                    notificationManager.notify(NOTIFICATION_ID, notif)
                }
            }
        }
    }

    private fun buildNotification(
        content: String,
        progressMax: Int,
        progressCurrent: Int,
        activeWorkers: Int,
        isIndeterminate: Boolean
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val subText = if (activeWorkers > 0) "Workers: $activeWorkers active" else "Workers idle"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EPUB Translator")
            .setContentText(content)
            .setSubText(subText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setProgress(progressMax, progressCurrent, isIndeterminate)
            .build()
    }
}
