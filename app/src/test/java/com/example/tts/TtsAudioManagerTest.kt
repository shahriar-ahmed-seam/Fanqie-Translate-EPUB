package com.example.tts

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsAudioManagerTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testRequestAudioFocusGrantsActiveFocus() {
        var lossCount = 0
        var transientLossCount = 0
        var gainCount = 0

        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = { lossCount++ },
            onAudioFocusTransientLoss = { transientLossCount++ },
            onAudioFocusGain = { gainCount++ }
        )

        val granted = audioManager.requestAudioFocus()
        assertTrue(granted)
        assertTrue(audioManager.hasFocus)
        assertEquals(AudioFocusState.ACTIVE_FOCUS, audioManager.focusState)
        assertFalse(audioManager.pausedDueToTransientLoss)
    }

    @Test
    fun testTransientLossPausesAndSetsTransientFlag() {
        var transientLossCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = { transientLossCount++ },
            onAudioFocusGain = {}
        )

        audioManager.requestAudioFocus()
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(1, transientLossCount)
        assertFalse(audioManager.hasFocus)
        assertEquals(AudioFocusState.TRANSIENT_LOSS, audioManager.focusState)
        assertTrue(audioManager.pausedDueToTransientLoss)
    }

    @Test
    fun testFocusGainResumesIfPausedDueToTransient() {
        var gainCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = {},
            onAudioFocusGain = { gainCount++ }
        )

        audioManager.requestAudioFocus()
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0, gainCount)

        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1, gainCount)
        assertTrue(audioManager.hasFocus)
        assertEquals(AudioFocusState.ACTIVE_FOCUS, audioManager.focusState)
        assertFalse(audioManager.pausedDueToTransientLoss)
    }

    @Test
    fun testExplicitPauseClearsTransientFlagAndPreventsResumeOnGain() {
        var gainCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = {},
            onAudioFocusGain = { gainCount++ }
        )

        audioManager.requestAudioFocus()
        // Phone call comes in -> transient loss
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(audioManager.pausedDueToTransientLoss)

        // User explicitly taps Pause during the call
        audioManager.clearTransientLoss()
        assertFalse(audioManager.pausedDueToTransientLoss)
        assertEquals(AudioFocusState.NO_FOCUS, audioManager.focusState)

        // Call finishes -> focus gained
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        // Must NOT auto-resume!
        assertEquals(0, gainCount)
    }

    @Test
    fun testPermanentLossPausesAndAbandonsFocusWithoutAutoResume() {
        var lossCount = 0
        var gainCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = { lossCount++ },
            onAudioFocusTransientLoss = {},
            onAudioFocusGain = { gainCount++ }
        )

        audioManager.requestAudioFocus()
        // Another media app (e.g. Spotify) starts
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS)

        assertEquals(1, lossCount)
        assertFalse(audioManager.hasFocus)
        assertEquals(AudioFocusState.NO_FOCUS, audioManager.focusState)
        assertFalse(audioManager.pausedDueToTransientLoss)

        // Focus returns later -> must not auto-resume
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, gainCount)
    }

    @Test
    fun testAudioBecomingNoisyTriggersCallbackAndPreventsAutoResume() {
        var noisyCount = 0
        var gainCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = {},
            onAudioFocusGain = { gainCount++ },
            onAudioBecomingNoisy = { noisyCount++ }
        )

        audioManager.requestAudioFocus()
        audioManager.simulateAudioBecomingNoisyForTesting()

        assertEquals(1, noisyCount)
        assertFalse(audioManager.hasFocus)
        assertEquals(AudioFocusState.NO_FOCUS, audioManager.focusState)
        assertFalse(audioManager.pausedDueToTransientLoss)

        // Headphones re-plugged -> focus gain does not auto-resume
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0, gainCount)
    }

    @Test
    fun testTransientCanDuckDefaultsToPausingForSpeech() {
        var transientLossCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = { transientLossCount++ },
            onAudioFocusGain = {},
            onAudioFocusDuck = null
        )

        audioManager.requestAudioFocus()
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)

        // Spoken word defaults to pausing so words are not missed
        assertEquals(1, transientLossCount)
        assertTrue(audioManager.pausedDueToTransientLoss)
        assertEquals(AudioFocusState.TRANSIENT_LOSS, audioManager.focusState)
    }

    @Test
    fun testTransientCanDuckInvokesDuckCallbackWhenConfigured() {
        var duckRatio = 1.0f
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = {},
            onAudioFocusGain = {},
            onAudioFocusDuck = { duckRatio = it }
        )

        audioManager.requestAudioFocus()
        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(0.2f, duckRatio, 0.01f)

        audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1.0f, duckRatio, 0.01f)
    }

    @Test
    fun testRapidFocusOscillationHandledSafely() {
        var lossCount = 0
        var gainCount = 0
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = { lossCount++ },
            onAudioFocusGain = { gainCount++ }
        )

        audioManager.requestAudioFocus()
        // Simulate rapid oscillation
        repeat(10) {
            audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            audioManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        }

        assertTrue(lossCount > 0)
        assertTrue(gainCount > 0)
        assertTrue(audioManager.hasFocus)
        assertEquals(AudioFocusState.ACTIVE_FOCUS, audioManager.focusState)
    }

    @Test
    fun testAbandonAudioFocusCleansUpState() {
        val audioManager = TtsAudioManager(
            context = context,
            onAudioFocusLoss = {},
            onAudioFocusTransientLoss = {},
            onAudioFocusGain = {}
        )

        audioManager.requestAudioFocus()
        assertTrue(audioManager.hasFocus)

        audioManager.abandonAudioFocus()
        assertFalse(audioManager.hasFocus)
        assertEquals(AudioFocusState.NO_FOCUS, audioManager.focusState)
        assertFalse(audioManager.pausedDueToTransientLoss)
    }
}
