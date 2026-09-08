package com.example.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Manages Android Audio Focus for the Reader TTS subsystem.
 * Ensures the app complies with Android platform audio guidelines,
 * interacts seamlessly with external audio interruptions (phone calls, navigation, notifications),
 * and prevents the OS from classifying the foreground service as an idle/non-audio background process.
 */
class TtsAudioManager(
    private val context: Context,
    private val onAudioFocusLoss: () -> Unit,
    private val onAudioFocusTransientLoss: () -> Unit,
    private val onAudioFocusGain: () -> Unit,
    private val onAudioFocusDuck: ((Float) -> Unit)? = null
) {

    private val TAG = "TtsAudioManager"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    var hasFocus: Boolean = false
        private set
    var pausedDueToTransientLoss: Boolean = false
        private set

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "onAudioFocusChange: $focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                pausedDueToTransientLoss = false
                onAudioFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                pausedDueToTransientLoss = true
                onAudioFocusTransientLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (onAudioFocusDuck != null) {
                    onAudioFocusDuck.invoke(0.2f)
                } else {
                    // Default to pausing for speech content so words are not missed during alerts
                    hasFocus = false
                    pausedDueToTransientLoss = true
                    onAudioFocusTransientLoss()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                val wasPausedDueToTransient = pausedDueToTransientLoss
                pausedDueToTransientLoss = false
                onAudioFocusDuck?.invoke(1.0f)
                if (wasPausedDueToTransient) {
                    onAudioFocusGain()
                }
            }
        }
    }

    /**
     * Requests audio focus for speech/media playback.
     * Returns true if audio focus was granted.
     */
    fun requestAudioFocus(): Boolean {
        if (hasFocus) return true
        val am = audioManager ?: return false

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            audioFocusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.d(TAG, "requestAudioFocus result: $result, granted=$hasFocus")
        return hasFocus
    }

    /**
     * Abandons audio focus cleanly.
     */
    fun abandonAudioFocus() {
        if (!hasFocus && !pausedDueToTransientLoss) return
        val am = audioManager ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error abandoning audio focus", e)
        } finally {
            hasFocus = false
            pausedDueToTransientLoss = false
        }
    }
}
