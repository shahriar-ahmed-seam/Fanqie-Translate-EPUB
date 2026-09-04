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
}
