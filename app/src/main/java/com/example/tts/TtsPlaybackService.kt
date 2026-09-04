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
import com.example.data.repository.TtsPlaybackSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
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

        mediaSession = MediaSessionCompat(this, "TtsPlaybackService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    ttsManager?.resume()
                }

                override fun onPause() {
                    ttsManager?.pause()
                }

                override fun onSkipToNext() {
                    ttsManager?.nextParagraph()
                }

                override fun onSkipToPrevious() {
                    ttsManager?.previousParagraph()
                }

                override fun onStop() {
                    ttsManager?.stop()
                    stopServiceSafely()
                }
            })
            isActive = true
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

        when (intent?.action) {
            ACTION_PLAY -> {
                ttsManager.resume()
            }
            ACTION_PAUSE -> {
                ttsManager.pause()
            }
            ACTION_PLAY_PAUSE -> {
                if (ttsManager.ttsState.value == TtsState.PLAYING) {
                    ttsManager.pause()
                } else {
                    ttsManager.resume()
                }
            }
            ACTION_NEXT -> {
                ttsManager.nextParagraph()
            }
            ACTION_PREV -> {
                ttsManager.previousParagraph()
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }
    }

    private fun observeTtsManager() {
        val app = applicationContext as? TranslatorApplication ?: return
        val ttsManager = app.ttsManager
        val settingsRepo = app.settingsRepository
        val database = app.database

        // Connect chapter completion to background chapter progression
        ttsManager.onChapterComplete = {
            serviceScope.launch(Dispatchers.IO) {
                handleBackgroundChapterEnd(ttsManager, database)
            }
        }

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
                        playbackState = state.name,
                        speechRate = ttsManager.speechRate.value,
                        voiceId = ttsManager.selectedVoice.value?.id ?: ttsManager.savedVoiceId
                    )
                )
            }
        }

        observationJob?.cancel()
        observationJob = serviceScope.launch {
            combine(
                ttsManager.ttsState,
                ttsManager.currentParagraphIndex,
                ttsManager.mediaMetadata
            ) { state, paraIndex, meta ->
                Triple(state, paraIndex, meta)
            }.conflate().collect { (state, paraIndex, meta) ->
                // Manage WakeLock
                if (state == TtsState.PLAYING) {
                    try {
                        if (wakeLock?.isHeld == false) {
                            wakeLock?.acquire(30 * 60 * 1000L) // 30 min safety timeout
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WakeLock acquire error", e)
                    }
                } else {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "WakeLock release error", e)
                    }
                }

                // Update MediaSession PlaybackState and Metadata
                updateMediaSessionState(state, paraIndex, meta, ttsManager.getParagraphs().size)

                // Update Foreground Notification
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

                // Persist state
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

                // If playback was explicitly stopped or ended, stop foreground service safely
                if (state == TtsState.STOPPED) {
                    stopServiceSafely()
                }
            }
        }
    }

    private suspend fun handleBackgroundChapterEnd(
        ttsManager: ReaderTtsManager,
        database: com.example.data.db.AppDatabase
    ) {
        if (!ttsManager.autoAdvanceChapter.value) {
            ttsManager.stop()
            return
        }

        val meta = ttsManager.mediaMetadata.value
        if (meta.bookId.isBlank() || meta.chapterId.isBlank()) {
            ttsManager.stop()
            return
        }

        val chapters = database.chapterDao().getChaptersByBook(meta.bookId)
        val currentIndex = chapters.indexOfFirst { it.id == meta.chapterId }
        if (currentIndex in 0 until chapters.size - 1) {
            val nextChapter = chapters[currentIndex + 1]
            val job = database.jobDao().getJobByBookId(meta.bookId)
            val chunks = if (job != null) {
                database.chunkDao().getChunksByJobAndChapter(job.id, nextChapter.id)
            } else {
                database.chunkDao().getChunksByChapter(meta.bookId, nextChapter.id)
            }

            val titleChunk = chunks.firstOrNull { it.chunkType == "CHAPTER_TITLE" }
            val nextTitle = titleChunk?.translatedText?.takeIf { it.isNotBlank() }
                ?: if (nextChapter.title.any { it.code in 0x4e00..0x9fff }) "Chapter ${nextChapter.chapterOrder + 1}" else nextChapter.title

            val bodyChunks = chunks.filter { it.chunkType == "CHAPTER_BODY" }.sortedBy { it.chunkOrder }
            val nextParagraphs = mutableListOf<String>()
            for (chunk in bodyChunks) {
                val text = chunk.translatedText?.takeIf { it.isNotBlank() } ?: continue
                val rawParas = text.split(Regex("(\r?\n)+|<p[^>]*>|</p>|<br\\s*/?>"))
                for (p in rawParas) {
                    val clean = p.replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotBlank()) {
                        nextParagraphs.add(clean)
                    }
                }
            }

            if (nextParagraphs.isNotEmpty()) {
                ttsManager.setChapterAndParagraphs(
                    chapterId = nextChapter.id,
                    newParagraphs = nextParagraphs,
                    continuePlaying = true,
                    startIndex = 0,
                    bookId = meta.bookId,
                    novelTitle = meta.novelTitle,
                    chapterTitle = nextTitle,
                    chapterOrder = nextChapter.chapterOrder
                )
            } else {
                ttsManager.stop()
            }
        } else {
            ttsManager.stop()
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

        val subText = if (totalParagraphs > 0) {
            "Paragraph ${paragraphIndex + 1} of $totalParagraphs"
        } else {
            "Audiobook"
        }

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
            .setOngoing(isPlaying)
            .setSilent(true)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextPendingIntent)
            .setStyle(mediaStyle)
            .build()
    }

    private fun stopServiceSafely() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock on stop", e)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        observationJob?.cancel()
        serviceScope.cancel()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock on destroy", e)
        }

        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null

        super.onDestroy()
    }
}
