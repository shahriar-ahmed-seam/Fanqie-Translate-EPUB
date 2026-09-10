package com.example.tts

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
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.TranslatorApplication
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TtsPlaybackSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dedicated foreground service managing Android background audio playback for Reader TTS.
 * Integrates with MediaSessionCompat for lock screen and system media controls.
 */
class TtsPlaybackService : Service() {

    private val TAG = "TtsPlaybackService"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observationJob: Job? = null
    private val isRestoringSession = java.util.concurrent.atomic.AtomicBoolean(false)

    private val wakeLockSync = Any()
    private val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutes safely renewable timeout

    /**
     * Centralized WakeLock management:
     * While actively playing, safely renews the timeout to prevent OEM killing or abandonment drain.
     * When playback stops or pauses, immediately releases the WakeLock.
     */
    private fun manageWakeLock(shouldHold: Boolean) {
        synchronized(wakeLockSync) {
            val lock = wakeLock ?: return
            try {
                if (shouldHold) {
                    // setReferenceCounted(false) ensures acquire(timeout) renews without stacking counts
                    lock.acquire(WAKELOCK_TIMEOUT_MS)
                    Log.d(TAG, "WakeLock acquired/renewed with ${WAKELOCK_TIMEOUT_MS / 60000}m timeout")
                } else {
                    if (lock.isHeld) {
                        lock.release()
                        Log.d(TAG, "WakeLock released")
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error managing WakeLock (shouldHold=$shouldHold)", e)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "epub_tts_channel"
        const val NOTIFICATION_ID = 1002

        const val ACTION_START = "com.example.tts.ACTION_START"
        const val ACTION_PLAY = "com.example.tts.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.tts.ACTION_PAUSE"
        const val ACTION_PLAY_PAUSE = "com.example.tts.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.tts.ACTION_NEXT"
        const val ACTION_PREV = "com.example.tts.ACTION_PREV"
        const val ACTION_STOP = "com.example.tts.ACTION_STOP"

        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_CHAPTER_ID = "extra_chapter_id"

        fun start(context: Context, bookId: String? = null, chapterId: String? = null) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_START
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
                if (chapterId != null) putExtra(EXTRA_CHAPTER_ID, chapterId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun play(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_PLAY
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        initWakeLock()
        createNotificationChannel()
        initMediaSession()
        startInForeground()
        observeTtsManager()
    }

    private fun initWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "FanqieTranslate:TtsWakeLock"
            )?.apply {
                setReferenceCounted(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize wake lock", e)
        }
    }

    private fun initMediaSession() {
        val app = applicationContext as? TranslatorApplication
        val ttsManager = app?.ttsManager

        val mediaButtonReceiver = android.content.ComponentName(this, androidx.media.session.MediaButtonReceiver::class.java)
        val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            component = mediaButtonReceiver
        }
        val mediaButtonPendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            mediaButtonIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "TtsPlaybackService", mediaButtonReceiver, mediaButtonPendingIntent).apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    handlePlayRequest(app)
                }

                override fun onPause() {
                    ttsManager?.pause()
                }

                override fun onSkipToNext() {
                    if (ttsManager?.getParagraphs()?.isEmpty() == true) {
                        serviceScope.launch {
                            app?.let { restoreSessionIfPossible(it, autoPlay = (ttsManager.ttsState.value == TtsState.PLAYING)) }
                            ttsManager.nextParagraph()
                        }
                    } else {
                        ttsManager?.nextParagraph()
                    }
                }

                override fun onSkipToPrevious() {
                    if (ttsManager?.getParagraphs()?.isEmpty() == true) {
                        serviceScope.launch {
                            app?.let { restoreSessionIfPossible(it, autoPlay = (ttsManager.ttsState.value == TtsState.PLAYING)) }
                            ttsManager.previousParagraph()
                        }
                    } else {
                        ttsManager?.previousParagraph()
                    }
                }

                override fun onStop() {
                    ttsManager?.stop()
                    stopServiceSafely()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            })
            setMediaButtonReceiver(mediaButtonPendingIntent)
            isActive = true
        }
    }

    private fun handlePlayRequest(app: TranslatorApplication?) {
        val ttsManager = app?.ttsManager ?: return
        when (ttsManager.ttsState.value) {
            TtsState.PAUSED -> ttsManager.resume()
            TtsState.PLAYING -> { /* Already playing */ }
            TtsState.RECOVERING -> { /* Recovery already in progress */ }
            TtsState.ERROR -> ttsManager.recoverFromError(resumePlaying = true)
            else -> {
                if (ttsManager.getParagraphs().isNotEmpty()) {
                    ttsManager.play(startIndex = ttsManager.currentParagraphIndex.value)
                } else {
                    serviceScope.launch {
                        restoreSessionIfPossible(app, autoPlay = true)
                    }
                }
            }
        }
    }

    private suspend fun restoreSessionIfPossible(
        app: TranslatorApplication,
        autoPlay: Boolean = false,
        forcedSubChunk: Int? = null
    ) {
        if (!isRestoringSession.compareAndSet(false, true)) {
            Log.d(TAG, "Session restoration already in progress; skipping duplicate request")
            return
        }
        try {
            val settingsRepo = app.settingsRepository
            val session = settingsRepo.getTtsSessionState() ?: return
            if (session.bookId.isBlank() || session.chapterId.isBlank()) return

            val data = ChapterContentLoader.loadChapter(
                context = applicationContext,
                database = app.database,
                bookId = session.bookId,
                chapterId = session.chapterId
            ) ?: return

            val book = kotlinx.coroutines.withContext(Dispatchers.IO) {
                app.database.bookDao().getBookById(session.bookId)
            }
            val novelTitle = book?.title ?: "Audiobook"

            val ttsManager = app.ttsManager
            if (session.speechRate in 0.5f..2.5f) {
                ttsManager.setSpeechRate(session.speechRate)
            }
            if (!session.voiceId.isNullOrBlank()) {
                ttsManager.selectVoiceById(session.voiceId)
            }

            val subChunk = forcedSubChunk ?: session.subChunkIndex
            val targetState = if (autoPlay) null else {
                if (session.playbackState == "PAUSED") TtsState.PAUSED else TtsState.IDLE
            }

            ttsManager.setChapterAndParagraphs(
                chapterId = data.nextChapterId,
                newParagraphs = data.paragraphs,
                continuePlaying = autoPlay,
                startIndex = session.paragraphIndex,
                startSubChunk = subChunk,
                bookId = session.bookId,
                novelTitle = novelTitle,
                chapterTitle = data.nextChapterTitle,
                chapterOrder = data.nextChapterOrder,
                targetState = targetState
            )
            Log.i(TAG, "Restored TTS session for book ${session.bookId}, chapter ${session.chapterId}, para ${session.paragraphIndex}, subChunk $subChunk, autoPlay=$autoPlay, targetState=$targetState")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore TTS session from repository", e)
        } finally {
            isRestoringSession.set(false)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audiobook Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Text-to-Speech audio reading controls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = applicationContext as? TranslatorApplication
        val ttsManager = app?.ttsManager ?: return START_NOT_STICKY

        if (intent != null) {
            androidx.media.session.MediaButtonReceiver.handleIntent(mediaSession, intent)
        }

        when (intent?.action) {
            ACTION_PLAY -> {
                handlePlayRequest(app)
            }
            ACTION_PAUSE -> {
                ttsManager.pause()
            }
            ACTION_PLAY_PAUSE -> {
                if (ttsManager.ttsState.value == TtsState.PLAYING) {
                    ttsManager.pause()
                } else {
                    handlePlayRequest(app)
                }
            }
            ACTION_NEXT -> {
                if (ttsManager.getParagraphs().isEmpty()) {
                    serviceScope.launch {
                        restoreSessionIfPossible(app, autoPlay = (ttsManager.ttsState.value == TtsState.PLAYING))
                        ttsManager.nextParagraph()
                    }
                } else {
                    ttsManager.nextParagraph()
                }
            }
            ACTION_PREV -> {
                if (ttsManager.getParagraphs().isEmpty()) {
                    serviceScope.launch {
                        restoreSessionIfPossible(app, autoPlay = (ttsManager.ttsState.value == TtsState.PLAYING))
                        ttsManager.previousParagraph()
                    }
                } else {
                    ttsManager.previousParagraph()
                }
            }
            ACTION_STOP -> {
                ttsManager.stop()
                stopServiceSafely()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                // Ensure service is running in foreground
                updateNotification(ttsManager)
            }
            null -> {
                // Recover from process recreation (START_STICKY restarted service with null intent)
                Log.i(TAG, "TtsPlaybackService restarted with null intent. Checking session recovery...")
                val session = app.settingsRepository.getTtsSessionState()
                if (session == null || session.interruptionReason == TtsInterruptionReason.EXPLICIT_STOP || session.playbackState == "STOPPED") {
                    Log.i(TAG, "Session was explicitly stopped or absent. Stopping recreated service.")
                    stopServiceSafely()
                    return START_NOT_STICKY
                }

                val shouldResumePlayback = session.wasActivelyPlaying &&
                    session.interruptionReason == TtsInterruptionReason.UNEXPECTED_INTERRUPTION

                if (shouldResumePlayback) {
                    serviceScope.launch {
                        restoreSessionIfPossible(app, autoPlay = true)
                    }
                } else if (session.playbackState == "PAUSED" || session.interruptionReason == TtsInterruptionReason.EXPLICIT_PAUSE) {
                    serviceScope.launch {
                        restoreSessionIfPossible(app, autoPlay = false)
                    }
                } else {
                    stopServiceSafely()
                    return START_NOT_STICKY
                }
            }
        }

        return START_STICKY
    }

    private fun startInForeground() {
        val app = applicationContext as? TranslatorApplication
        val ttsManager = app?.ttsManager
        val initialNotification = buildNotification(
            novelTitle = ttsManager?.mediaMetadata?.value?.novelTitle?.ifBlank { "Audiobook Playback" } ?: "Audiobook Playback",
            chapterTitle = ttsManager?.mediaMetadata?.value?.chapterTitle?.ifBlank { "EPUB Reader" } ?: "EPUB Reader",
            paragraphIndex = ttsManager?.currentParagraphIndex?.value ?: 0,
            totalParagraphs = ttsManager?.getParagraphs()?.size ?: 0,
            state = ttsManager?.ttsState?.value ?: TtsState.IDLE,
            bookId = ttsManager?.mediaMetadata?.value?.bookId ?: "",
            chapterId = ttsManager?.mediaMetadata?.value?.chapterId ?: ""
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    initialNotification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service in foreground", e)
        }
    }

    private fun observeTtsManager() {
        val app = applicationContext as? TranslatorApplication ?: return
        val ttsManager = app.ttsManager
        val settingsRepo = app.settingsRepository
        val database = app.database

        // Automatic position persistence hook
        ttsManager.onPositionChanged = { paraIndex, state ->
            val meta = ttsManager.mediaMetadata.value
            if (meta.bookId.isNotBlank() && meta.chapterId.isNotBlank()) {
                settingsRepo.saveTtsSessionState(
                    TtsPlaybackSessionState(
                        bookId = meta.bookId,
                        chapterId = meta.chapterId,
                        chapterOrder = meta.chapterOrder,
                        paragraphIndex = paraIndex,
                        subChunkIndex = ttsManager.getCurrentSubChunkIndex(),
                        playbackState = state.name,
                        speechRate = ttsManager.speechRate.value,
                        voiceId = ttsManager.selectedVoice.value?.id ?: ttsManager.savedVoiceId,
                        timestamp = System.currentTimeMillis(),
                        wasActivelyPlaying = (state == TtsState.PLAYING),
                        interruptionReason = when (state) {
                            TtsState.PLAYING -> TtsInterruptionReason.UNEXPECTED_INTERRUPTION
                            TtsState.PAUSED -> TtsInterruptionReason.EXPLICIT_PAUSE
                            TtsState.STOPPED -> TtsInterruptionReason.EXPLICIT_STOP
                            else -> TtsInterruptionReason.NONE
                        }
                    )
                )
                settingsRepo.setLastReadChapterId(meta.bookId, meta.chapterId)
                settingsRepo.setLastReadParagraphIndex(meta.bookId, meta.chapterId, paraIndex)
            }
        }

        observationJob?.cancel()
        observationJob = serviceScope.launch {
            var restartAttempts = 0
            while (isActive && restartAttempts < 10) {
                try {
                    combine(
                        ttsManager.ttsState,
                        ttsManager.currentParagraphIndex,
                        ttsManager.mediaMetadata
                    ) { state, paraIndex, meta ->
                        Triple(state, paraIndex, meta)
                    }.conflate().collect { (state, paraIndex, meta) ->
                        try {
                            handleObservedState(state, paraIndex, meta, ttsManager, settingsRepo)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            Log.e(TAG, "Error handling observed TTS state change ($state, para $paraIndex)", e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    restartAttempts++
                    Log.e(TAG, "Exception in observeTtsManager collection (attempt $restartAttempts/10)", e)
                    delay(1000L)
                }
            }
        }
    }

    private fun handleObservedState(
        state: TtsState,
        paraIndex: Int,
        meta: TtsMediaMetadata,
        ttsManager: ReaderTtsManager,
        settingsRepo: SettingsRepository
    ) {
        // Manage WakeLock (safely renewable while PLAYING, released otherwise)
        manageWakeLock(state == TtsState.PLAYING)

        // Update MediaSession PlaybackState and Metadata
        try {
            updateMediaSessionState(state, paraIndex, meta, ttsManager.getParagraphs().size)
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update media session state", e)
        }

        // Update Foreground Notification
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (notificationManager != null) {
                val notification = buildNotification(
                    novelTitle = meta.novelTitle.ifBlank { "Audiobook Playback" },
                    chapterTitle = meta.chapterTitle.ifBlank { "EPUB Reader" },
                    paragraphIndex = paraIndex,
                    totalParagraphs = ttsManager.getParagraphs().size,
                    state = state,
                    bookId = meta.bookId,
                    chapterId = meta.chapterId
                )
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update notification", e)
        }

        // Persist state
        try {
            if (meta.bookId.isNotBlank() && meta.chapterId.isNotBlank()) {
                settingsRepo.saveTtsSessionState(
                    TtsPlaybackSessionState(
                        bookId = meta.bookId,
                        chapterId = meta.chapterId,
                        chapterOrder = meta.chapterOrder,
                        paragraphIndex = paraIndex,
                        playbackState = state.name,
                        speechRate = ttsManager.speechRate.value,
                        voiceId = ttsManager.selectedVoice.value?.id ?: ttsManager.savedVoiceId
                    )
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to persist TTS session state from service", e)
        }

        // If playback was explicitly stopped or ended, stop foreground service safely
        if (state == TtsState.STOPPED) {
            stopServiceSafely()
        }
    }

    private fun updateMediaSessionState(
        state: TtsState,
        paraIndex: Int,
        meta: TtsMediaMetadata,
        totalParagraphs: Int
    ) {
        val session = mediaSession ?: return

        val playbackStateCode = when (state) {
            TtsState.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            TtsState.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            TtsState.STOPPED -> PlaybackStateCompat.STATE_STOPPED
            TtsState.RECOVERING, TtsState.INITIALIZING -> PlaybackStateCompat.STATE_BUFFERING
            else -> PlaybackStateCompat.STATE_NONE
        }

        val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_STOP

        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(playbackStateCode, paraIndex.toLong(), 1.0f)
                .build()
        )

        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, meta.chapterTitle.ifBlank { "Chapter" })
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, meta.novelTitle.ifBlank { "Audiobook" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Fanqie Translate")
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Paragraph ${paraIndex + 1} of $totalParagraphs")
                .build()
        )
    }

    private fun updateNotification(ttsManager: ReaderTtsManager) {
        val meta = ttsManager.mediaMetadata.value
        val notification = buildNotification(
            novelTitle = meta.novelTitle.ifBlank { "Audiobook Playback" },
            chapterTitle = meta.chapterTitle.ifBlank { "EPUB Reader" },
            paragraphIndex = ttsManager.currentParagraphIndex.value,
            totalParagraphs = ttsManager.getParagraphs().size,
            state = ttsManager.ttsState.value,
            bookId = meta.bookId,
            chapterId = meta.chapterId
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        notificationManager?.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        novelTitle: String,
        chapterTitle: String,
        paragraphIndex: Int,
        totalParagraphs: Int,
        state: TtsState,
        bookId: String,
        chapterId: String
    ): Notification {
        val isPlaying = state == TtsState.PLAYING
        val isRecovering = state == TtsState.RECOVERING

        // Content intent: open reader in MainActivity
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (bookId.isNotBlank()) putExtra("extra_open_book_id", bookId)
            if (chapterId.isNotBlank()) putExtra("extra_open_chapter_id", chapterId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            201,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Previous paragraph intent
        val prevIntent = Intent(this, TtsPlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPendingIntent = PendingIntent.getService(
            this,
            202,
            prevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Play/Pause toggle intent
        val playPauseIntent = Intent(this, TtsPlaybackService::class.java).apply { action = ACTION_PLAY_PAUSE }
        val playPausePendingIntent = PendingIntent.getService(
            this,
            203,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Next paragraph intent
        val nextIntent = Intent(this, TtsPlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPendingIntent = PendingIntent.getService(
            this,
            204,
            nextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Stop intent
        val stopIntent = Intent(this, TtsPlaybackService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            205,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val playPauseText = if (isPlaying) "Pause" else "Resume"

        val stateLabel = when (state) {
            TtsState.PLAYING -> "Playing"
            TtsState.PAUSED -> "Paused"
            TtsState.RECOVERING -> "Recovering engine..."
            TtsState.INITIALIZING -> "Initializing..."
            TtsState.ERROR -> "Error"
            TtsState.STOPPED -> "Stopped"
            TtsState.IDLE -> "Idle"
        }
        val lineInfo = if (totalParagraphs > 0) "Line ${paragraphIndex + 1} of $totalParagraphs" else "Line ${paragraphIndex + 1}"
        val subText = if (isRecovering) "Recovering speech engine..." else "$stateLabel • $lineInfo"

        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession?.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(novelTitle)
            .setContentText(chapterTitle)
            .setSubText(subText)
            .setContentIntent(contentPendingIntent)
            .setDeleteIntent(stopPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(isPlaying || isRecovering)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous line", prevPendingIntent)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next line", nextPendingIntent)
            .setStyle(mediaStyle)
            .build()
    }

    private fun stopServiceSafely() {
        manageWakeLock(false)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground", e)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observationJob?.cancel()
        observationJob = null
        serviceScope.cancel()

        manageWakeLock(false)

        try {
            mediaSession?.isActive = false
            mediaSession?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaSession on service destroy", e)
        } finally {
            mediaSession = null
        }

        super.onDestroy()
    }
}
