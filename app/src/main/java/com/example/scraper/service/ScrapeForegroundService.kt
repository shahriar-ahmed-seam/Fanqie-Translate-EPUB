package com.example.scraper.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.scraper.ScraperGraph
import com.example.scraper.model.ScrapeProgress
import com.example.scraper.model.ScrapeStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps the process (and the WebView renderer) alive while a scrape runs with the screen off or the
 * app in the background. It only mirrors the coordinator: it never starts a run by itself, stops as
 * soon as the run ends, and pauses the run cleanly if Android's background time limit is reached.
 */
class ScrapeForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false
    private var lastStartId = 0

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        val graph = ScraperGraph.from(this)
        if (graph == null) {
            stopSelf()
            return
        }
        startInForeground(buildNotification(graph.coordinator.progress.value))
        acquireWakeLock()
        observer = scope.launch {
            graph.coordinator.progress.collect { progress ->
                if (progress.status.isActive) {
                    notifyProgress(progress)
                } else {
                    finish(progress)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = ScraperGraph.from(this)
        if (graph == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        lastStartId = startId
        // Every startForegroundService() must be answered with startForeground().
        startInForeground(buildNotification(graph.coordinator.progress.value))
        when (intent?.action) {
            ACTION_PAUSE -> graph.coordinator.pause()
            ACTION_STOP -> graph.coordinator.stop()
        }
        if (graph.coordinator.progress.value.status.isActive) {
            stopping = false
            if (wakeLock == null) acquireWakeLock()
        } else {
            finish(graph.coordinator.progress.value)
        }
        return START_NOT_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "Foreground service time limit reached; pausing the scrape")
        ScraperGraph.from(this)?.coordinator?.pause(
            "Paused: Android's background time limit for this app was reached. Open the app and tap Resume."
        )
        finish(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observer?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Android refused a foreground start (background start or time limit): scrape stays in-app only.
            Log.w(TAG, "Couldn't start the scraper foreground service", e)
            stopSelf()
        }
    }

    private fun notifyProgress(progress: ScrapeProgress) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(progress))
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't update the scraper notification", e)
        }
    }

    private fun finish(progress: ScrapeProgress?) {
        if (!stopping) {
            stopping = true
            releaseWakeLock()
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (e: Exception) {
                Log.w(TAG, "stopForeground failed", e)
            }
            if (progress != null && progress.projectId != null && progress.status in SUMMARY_STATES) {
                val manager = getSystemService(NotificationManager::class.java)
                try {
                    manager?.notify(SUMMARY_NOTIFICATION_ID, buildSummary(progress))
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't post the scrape summary", e)
                }
            }
        }
        // Only stops if no newer start arrived (a new run may already have restarted us).
        if (lastStartId > 0) stopSelf(lastStartId)
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Scraper:Run").apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't acquire the scraper wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't release the scraper wake lock", e)
        }
        wakeLock = null
    }

    private fun contentIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        launch.putExtra(ScraperGraph.EXTRA_OPEN_SCRAPER, true)
        return PendingIntent.getActivity(this, 3100, launch, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, ScrapeForegroundService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(progress: ScrapeProgress): Notification {
        val title = progress.projectTitle.ifBlank { "Scraping chapters" }
        val text = buildString {
            append(progress.status.label)
            if (progress.chaptersThisRun > 0) append(" · ${progress.chaptersThisRun} new")
            if (progress.totalChapters > 0) append(" · ${progress.totalChapters} total")
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${progress.message}"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Pause", actionIntent(ACTION_PAUSE, 3101))
            .addAction(0, "Stop", actionIntent(ACTION_STOP, 3102))
        contentIntent()?.let { builder.setContentIntent(it) }
        if (progress.status == ScrapeStatus.COOLDOWN && progress.delayTotalMs > 0) {
            val done = (progress.delayTotalMs - progress.delayRemainingMs).coerceAtLeast(0)
            builder.setProgress(progress.delayTotalMs.toInt(), done.toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun buildSummary(progress: ScrapeProgress): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(progress.projectTitle.ifBlank { "Scraper" } + " · " + progress.status.label)
            .setContentText(progress.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(progress.message))
            .setAutoCancel(true)
        contentIntent()?.let { builder.setContentIntent(it) }
        return builder.build()
    }

    companion object {
        private const val TAG = "ScrapeService"
        const val CHANNEL_ID = "scraper_channel"
        const val NOTIFICATION_ID = 3101
        const val SUMMARY_NOTIFICATION_ID = 3102
        const val ACTION_PAUSE = "com.example.scraper.action.PAUSE"
        const val ACTION_STOP = "com.example.scraper.action.STOP"
        private const val WAKE_LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private val SUMMARY_STATES = setOf(ScrapeStatus.COMPLETED, ScrapeStatus.ERROR, ScrapeStatus.NEEDS_LOGIN)

        /** Called when a run becomes active; the user just tapped Start/Resume, so the app is visible. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, ScrapeForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't start the scraper service; scraping continues while the app is open", e)
            }
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chapter scraper", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Progress while chapters are scraped in the background"
                    setShowBadge(false)
                }
            )
        }
    }
}
