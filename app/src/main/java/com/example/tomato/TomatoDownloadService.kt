package com.example.tomato

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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.TranslatorApplication
import com.example.diagnostics.AppDiagnostics
import com.example.tomato.data.TomatoBookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps Tomato downloads going with the screen off or the app in the background (it keeps the app's
 * process, and through its binding the engine process, alive). Shows progress; stops when the last
 * download finishes.
 */
class TomatoDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startInForeground(buildNotification(emptyList()))) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                val repository = (application as? TranslatorApplication)?.tomatoGraph?.repository
                scope.launch {
                    repository?.let { repo ->
                        repository.activeDownloadsOnce().forEach { repo.cancel(it.bookId) }
                    }
                }
            }
        }
        if (observer?.isActive != true) observe()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        val repository = (application as? TranslatorApplication)?.tomatoGraph?.repository ?: run {
            stopSelf()
            return
        }
        observer = scope.launch {
            repository.activeDownloads.conflate().collect { active ->
                if (active.isEmpty()) {
                    // Small grace period: a redownload or the next queued book may start right away.
                    delay(1_500)
                    if (repository.activeDownloadsOnce().isEmpty()) {
                        ServiceCompat.stopForeground(this@TomatoDownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(active))
                }
                delay(700)
            }
        }
    }

    private fun startInForeground(notification: Notification): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't start the download service in the foreground", e)
        AppDiagnostics.log("tomato", "Download service start refused: ${e.javaClass.simpleName}")
        false
    }

    private fun buildNotification(active: List<TomatoBookEntity>): Notification {
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_OPEN_TOMATO, true)
        val openPending = PendingIntent.getActivity(this, 4100, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cancel = Intent(this, TomatoDownloadService::class.java).setAction(ACTION_CANCEL_ALL)
        val cancelPending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, 4101, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 4101, cancel, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val first = active.firstOrNull()
        val title = when {
            first == null -> "Tomato downloads"
            active.size == 1 -> first.title.ifBlank { "Downloading a novel" }
            else -> "${active.size} novels downloading"
        }
        val text = first?.message ?: "Starting…"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (active.size > 1) "Cancel all" else "Cancel", cancelPending)
        if (first != null && first.progressTotal > 0) {
            builder.setProgress(first.progressTotal, first.progressDone.coerceAtMost(first.progressTotal), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "TomatoDownloadService"
        const val CHANNEL_ID = "tomato_downloads"
        const val NOTIFICATION_ID = 4100
        const val ACTION_CANCEL_ALL = "com.example.tomato.CANCEL_ALL"
        const val EXTRA_OPEN_TOMATO = "extra_open_tomato"

        fun start(context: Context): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, TomatoDownloadService::class.java))
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the download service", e)
            false
        }

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(CHANNEL_ID, "Tomato downloads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Progress of novels downloading from Fanqie"
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
