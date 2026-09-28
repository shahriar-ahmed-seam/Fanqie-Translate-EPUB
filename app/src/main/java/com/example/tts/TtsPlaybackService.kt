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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.TranslatorApplication
import com.example.data.repository.TtsPlaybackSessionState
import com.example.diagnostics.AppDiagnostics
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
 *
 * It mirrors [ReaderTtsManager]: the manager owns playback state and is the only writer of the saved
 * position; this service keeps the process in the foreground, the CPU awake while playback is wanted,
 * and the notification / media session up to date.
 */
class TtsPlaybackService : Service() {

    private val TAG = "TtsPlaybackService"

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaSession: MediaSessionCompat? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var observationJob: Job? = null
    private var wakeLockRenewJob: Job? = null
    private val isRestoringSession = java.util.concurrent.atomic.AtomicBoolean(false)
    /** Most recent start request; stopping with it can't cancel a start that is still on its way. */
    @Volatile
    private var lastStartId = 0

    private val wakeLockSync = Any()

    /**
     * Centralized WakeLock management. Held for the whole time playback is wanted (speaking, engine
     * recovery, chapter loading, automatic retries, short interruptions), not only while PLAYING:
     * without it the phone slept during a recovery and playback never came back with the screen off.
     * The timeout is re-armed on every progress update and every minute; released as soon as the
     * session is idle, paused by the user, or stopped.
     */
    private fun manageWakeLock(shouldHold: Boolean) {
        synchronized(wakeLockSync) {
            val lock = wakeLock ?: return
            try {
                if (shouldHold) {
                    // setReferenceCounted(false) ensures acquire(timeout) renews without stacking counts
                    lock.acquire(WAKELOCK_TIMEOUT_MS)
                } else if (lock.isHeld) {
                    lock.release()
                    Log.d(TAG, "WakeLock released")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error managing WakeLock (shouldHold=$shouldHold)", e)
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "epub_tts_channel"
        const val NOTIFICATION_ID = 1002
        /** "Playback stopped, tap to resume" after Android refused a background restart. */
        const val RESUME_NOTIFICATION_ID = 1003

        const val ACTION_START = "com.example.tts.ACTION_START"
        const val ACTION_PLAY = "com.example.tts.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.tts.ACTION_PAUSE"
        const val ACTION_PLAY_PAUSE = "com.example.tts.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.example.tts.ACTION_NEXT"
        const val ACTION_PREV = "com.example.tts.ACTION_PREV"
        const val ACTION_STOP = "com.example.tts.ACTION_STOP"

        const val EXTRA_BOOK_ID = "extra_book_id"
        const val EXTRA_CHAPTER_ID = "extra_chapter_id"

        /** Set on intents that should open the reader at the live TTS position (resolved on arrival). */
        const val EXTRA_OPEN_TTS_SESSION = "extra_open_tts_session"
        const val EXTRA_OPEN_BOOK_ID = "extra_open_book_id"
        const val EXTRA_OPEN_CHAPTER_ID = "extra_open_chapter_id"

        private const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val WAKELOCK_RENEW_INTERVAL_MS = 60 * 1000L

        /** Starts (or updates) the foreground service. Never throws; false if Android refused. */
        fun start(context: Context, bookId: String? = null, chapterId: String? = null): Boolean {
            val intent = Intent(context, TtsPlaybackService::class.java).apply {
                action = ACTION_START
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
                if (chapterId != null) putExtra(EXTRA_CHAPTER_ID, chapterId)
            }
            return startForegroundCompat(context, intent)
        }

        fun play(context: Context): Boolean =
            startForegroundCompat(context, Intent(context, TtsPlaybackService::class.java).setAction(ACTION_PLAY))

        fun pause(context: Context): Boolean =
            startPlain(context, Intent(context, TtsPlaybackService::class.java).setAction(ACTION_PAUSE))

        fun stop(context: Context): Boolean =
            startPlain(context, Intent(context, TtsPlaybackService::class.java).setAction(ACTION_STOP))

        private fun startForegroundCompat(context: Context, intent: Intent): Boolean = try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException (Android 12+ from the background), IllegalStateException.
            Log.w("TtsPlaybackService", "Playback service start refused", e)
            AppDiagnostics.log("service", "Playback service start refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }

        private fun startPlain(context: Context, intent: Intent): Boolean = try {
            context.startService(intent)
            true
        } catch (e: Exception) {
            Log.w("TtsPlaybackService", "Playback service command refused", e)
            false
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
            AppDiagnostics.log("service", "Restored session at chapter ${session.chapterId}, paragraph ${session.paragraphIndex} (autoPlay=$autoPlay)")
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
        lastStartId = startId
        // Every startForegroundService() must be answered with startForeground() before anything else
        // (including stopping); a start that reached an already running service used to go unanswered.
        val inForeground = startInForeground()

        val app = applicationContext as? TranslatorApplication
        val ttsManager = app?.ttsManager ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }

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
                // Already answered with startForeground() above.
            }
            null -> return handleSystemRestart(app, ttsManager, inForeground)
        }

        return START_STICKY
    }

    /**
     * Android restarted the service after the process died (START_STICKY, null intent). Resume only
     * what was really playing, and only if Android let the service back into the foreground; playing
     * without it gets cut off again within a minute, so offer a one-tap resume instead.
     */
    private fun handleSystemRestart(app: TranslatorApplication, ttsManager: ReaderTtsManager, inForeground: Boolean): Int {
        Log.i(TAG, "TtsPlaybackService restarted with null intent. Checking session recovery...")
        val session = app.settingsRepository.getTtsSessionState()
        if (session == null || session.interruptionReason == TtsInterruptionReason.EXPLICIT_STOP || session.playbackState == "STOPPED") {
            Log.i(TAG, "Session was explicitly stopped or absent. Stopping recreated service.")
            stopServiceSafely()
            return START_NOT_STICKY
        }
        if (ttsManager.hasActiveSession()) {
            // The process survived; the manager still has the session.
            return START_STICKY
        }

        val shouldResumePlayback = session.wasActivelyPlaying &&
            session.interruptionReason == TtsInterruptionReason.UNEXPECTED_INTERRUPTION
        AppDiagnostics.log(
            "service",
            "Restarted by Android after the process ended (was playing=$shouldResumePlayback, foreground allowed=$inForeground)"
        )

        if (shouldResumePlayback && !inForeground) {
            postResumeNotification()
            stopServiceSafely()
            return START_NOT_STICKY
        }

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
        return START_STICKY
    }

    /** Returns false when Android refused to (re)enter the foreground. */
    private fun startInForeground(): Boolean {
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

        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service in foreground", e)
            AppDiagnostics.log("service", "startForeground refused: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    private fun observeTtsManager() {
        val app = applicationContext as? TranslatorApplication ?: return
        val ttsManager = app.ttsManager

        // The manager persists every position change itself (single writer). This hook only re-arms
        // the wake lock on progress so long sessions never outlive its timeout.
        ttsManager.onPositionChanged = { _, _ ->
            manageWakeLock(ttsManager.wantsCpuAwake())
        }

        observationJob?.cancel()
        observationJob = serviceScope.launch {
            var restartAttempts = 0
            while (isActive && restartAttempts < 10) {
                try {
                    combine(
                        ttsManager.ttsState,
                        ttsManager.currentParagraphIndex,
                        ttsManager.mediaMetadata,
                        ttsManager.autoRetryPending
                    ) { state, paraIndex, meta, _ ->
                        Triple(state, paraIndex, meta)
                    }.conflate().collect { (state, paraIndex, meta) ->
                        try {
                            handleObservedState(state, paraIndex, meta, ttsManager)
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

        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = serviceScope.launch {
            while (isActive) {
                delay(WAKELOCK_RENEW_INTERVAL_MS)
                manageWakeLock(ttsManager.wantsCpuAwake())
            }
        }
    }

    private fun handleObservedState(
        state: TtsState,
        paraIndex: Int,
        meta: TtsMediaMetadata,
        ttsManager: ReaderTtsManager
    ) {
        manageWakeLock(ttsManager.wantsCpuAwake())

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
                if (state == TtsState.PLAYING) notificationManager.cancel(RESUME_NOTIFICATION_ID)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to update notification", e)
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

    /** Opens the reader at the TTS session; MainActivity resolves the newest position on arrival. */
    private fun openSessionIntent(bookId: String, chapterId: String): Intent =
        Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_TTS_SESSION, true)
            if (bookId.isNotBlank()) putExtra(EXTRA_OPEN_BOOK_ID, bookId)
            if (chapterId.isNotBlank()) putExtra(EXTRA_OPEN_CHAPTER_ID, chapterId)
        }

    private fun postResumeNotification() {
        try {
            val playIntent = Intent(this, TtsPlaybackService::class.java).setAction(ACTION_PLAY)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            // Tapping a notification action lets Android start the foreground service again.
            val playPendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                PendingIntent.getForegroundService(this, 206, playIntent, flags)
            } else {
                PendingIntent.getService(this, 206, playIntent, flags)
            }
            val openPendingIntent = PendingIntent.getActivity(this, 207, openSessionIntent("", ""), flags)
            val text = "Android stopped playback in the background. Tap Resume to continue where you left off."
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Audiobook playback stopped")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openPendingIntent)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(android.R.drawable.ic_media_play, "Resume", playPendingIntent)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(RESUME_NOTIFICATION_ID, notification)
            AppDiagnostics.log("service", "Foreground restart refused; posted a tap-to-resume notification")
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't post the resume notification", e)
        }
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
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            201,
            openSessionIntent(bookId, chapterId),
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
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
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
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping foreground", e)
        }
        // Stop only if no newer start is on its way; that start will answer with startForeground().
        if (lastStartId > 0) stopSelf(lastStartId) else stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observationJob?.cancel()
        observationJob = null
        wakeLockRenewJob?.cancel()
        wakeLockRenewJob = null
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
