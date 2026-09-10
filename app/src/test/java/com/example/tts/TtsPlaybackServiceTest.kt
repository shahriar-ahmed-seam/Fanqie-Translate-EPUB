package com.example.tts

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.example.service.TranslationService
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsPlaybackServiceTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testNotificationChannelAndIdAreSeparateFromTranslationService() {
        // Translation Service: channel "epub_translation_channel", NOTIFICATION_ID 1001
        // TTS Playback Service: channel "epub_tts_channel", NOTIFICATION_ID 1002
        assertNotEquals(TranslationService.CHANNEL_ID, TtsPlaybackService.CHANNEL_ID)
        assertNotEquals(TranslationService.NOTIFICATION_ID, TtsPlaybackService.NOTIFICATION_ID)
        assertEquals("epub_tts_channel", TtsPlaybackService.CHANNEL_ID)
        assertEquals(1002, TtsPlaybackService.NOTIFICATION_ID)
    }

    @Test
    fun testServiceCreationInitializesMediaSessionAndChannel() {
        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()

        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channel = notificationManager.getNotificationChannel(TtsPlaybackService.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals("Audiobook Playback", channel.name)
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)

        controller.destroy()
    }

    @Test
    fun testServiceActionIntents() {
        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()

        // Start command with ACTION_PLAY
        val playIntent = Intent(context, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PLAY
        }
        val playResult = service.onStartCommand(playIntent, 0, 1)
        assertEquals(android.app.Service.START_STICKY, playResult)

        // Start command with ACTION_PAUSE
        val pauseIntent = Intent(context, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PAUSE
        }
        val pauseResult = service.onStartCommand(pauseIntent, 0, 2)
        assertEquals(android.app.Service.START_STICKY, pauseResult)

        // Start command with ACTION_NEXT
        val nextIntent = Intent(context, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_NEXT
        }
        val nextResult = service.onStartCommand(nextIntent, 0, 3)
        assertEquals(android.app.Service.START_STICKY, nextResult)

        // Start command with ACTION_PREV
        val prevIntent = Intent(context, TtsPlaybackService::class.java).apply {
            action = TtsPlaybackService.ACTION_PREV
        }
        val prevResult = service.onStartCommand(prevIntent, 0, 4)
        assertEquals(android.app.Service.START_STICKY, prevResult)

        controller.destroy()
    }

    @Test
    fun testServiceDestructionPreservesTtsManagerPositionCallback() {
        val app = context.applicationContext as? com.example.TranslatorApplication
        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        controller.create().get()

        // Verify observeTtsManager installed a position hook
        assertNotNull(app?.ttsManager?.onPositionChanged)

        // Destroy the service
        controller.destroy()

        // Verify the callback on ttsManager was NOT nullified by onDestroy()
        assertNotNull(app?.ttsManager?.onPositionChanged)
    }

    @Test
    fun testServiceRecreationReturnsStartSticky() {
        val app = context.applicationContext as com.example.TranslatorApplication
        val settingsRepo = app.settingsRepository
        settingsRepo.saveTtsSessionState(
            com.example.data.repository.TtsPlaybackSessionState(
                bookId = "book_sticky",
                chapterId = "chap_sticky",
                paragraphIndex = 3,
                subChunkIndex = 0,
                playbackState = "PLAYING",
                wasActivelyPlaying = true,
                interruptionReason = "UNEXPECTED_INTERRUPTION"
            )
        )

        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()

        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)

        controller.destroy()
    }

    @Test
    fun testServiceRecreationWhenExplicitPauseDoesNotAutoplay() {
        val app = context.applicationContext as com.example.TranslatorApplication
        val settingsRepo = app.settingsRepository
        settingsRepo.saveTtsSessionState(
            com.example.data.repository.TtsPlaybackSessionState(
                bookId = "book_paused",
                chapterId = "chap_paused",
                paragraphIndex = 5,
                subChunkIndex = 1,
                playbackState = "PAUSED",
                wasActivelyPlaying = false,
                interruptionReason = "EXPLICIT_PAUSE"
            )
        )

        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()

        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_STICKY, result)

        // Verify ttsManager does NOT start speaking
        assertNotEquals(TtsState.PLAYING, app.ttsManager.ttsState.value)

        controller.destroy()
    }

    @Test
    fun testServiceRecreationWhenExplicitStopDoesNotAutoplay() {
        val app = context.applicationContext as com.example.TranslatorApplication
        val settingsRepo = app.settingsRepository
        settingsRepo.saveTtsSessionState(
            com.example.data.repository.TtsPlaybackSessionState(
                bookId = "book_stopped",
                chapterId = "chap_stopped",
                paragraphIndex = 10,
                subChunkIndex = 0,
                playbackState = "STOPPED",
                wasActivelyPlaying = false,
                interruptionReason = "EXPLICIT_STOP"
            )
        )

        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()

        val result = service.onStartCommand(null, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, result)

        // Verify ttsManager does NOT start speaking
        assertNotEquals(TtsState.PLAYING, app.ttsManager.ttsState.value)

        controller.destroy()
    }

    @Test
    fun testServiceRecreationDuplicateCallsAreSafe() {
        val app = context.applicationContext as com.example.TranslatorApplication
        val settingsRepo = app.settingsRepository
        settingsRepo.saveTtsSessionState(
            com.example.data.repository.TtsPlaybackSessionState(
                bookId = "book_dup",
                chapterId = "chap_dup",
                paragraphIndex = 2,
                subChunkIndex = 0,
                playbackState = "PLAYING",
                wasActivelyPlaying = true,
                interruptionReason = "UNEXPECTED_INTERRUPTION"
            )
        )

        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()

        // Multiple rapid null intents should execute safely without crash or duplicate sessions
        val res1 = service.onStartCommand(null, 0, 1)
        val res2 = service.onStartCommand(null, 0, 2)
        assertEquals(android.app.Service.START_STICKY, res1)
        assertEquals(android.app.Service.START_STICKY, res2)

        controller.destroy()
    }
}

