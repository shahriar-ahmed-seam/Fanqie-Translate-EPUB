package com.example.tts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * Audio focus states for explicit tracking of focus lifecycle.
 */
enum class AudioFocusState {
    NO_FOCUS,
    ACTIVE_FOCUS,
    TRANSIENT_LOSS,
    DELAYED_FOCUS
}

/**
 * Manages Android Audio Focus and audio route changes for the Reader TTS subsystem.
 *
 * Implements Android media guidelines tailored for spoken-word playback:
 * - Temporary audio focus loss (e.g. phone calls, voice prompts): pauses playback, preserves position, resumes on focus gain.
 * - Permanent audio focus loss (e.g. other media app started): stops/pauses safely, abandons focus, never auto-resumes.
 * - Audio becoming noisy (e.g. wired headphones unplugged, Bluetooth headset disconnected):
 *   immediately pauses playback and prevents unexpected loud speaker playback.
 * - Explicit user pause: clears transient resume state so focus gain does not blindly restart speech.
 * - Rapid focus loops: debounced and guarded against rapid oscillation.
 */
class TtsAudioManager(
    private val context: Context,
    private val onAudioFocusLoss: () -> Unit,
    private val onAudioFocusTransientLoss: () -> Unit,
    private val onAudioFocusGain: () -> Unit,
    private val onAudioFocusDuck: ((Float) -> Unit)? = null,
    private val onAudioBecomingNoisy: (() -> Unit)? = null
) {

    private val TAG = "TtsAudioManager"

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    var hasFocus: Boolean = false
        private set

    var pausedDueToTransientLoss: Boolean = false
        private set

    var focusState: AudioFocusState = AudioFocusState.NO_FOCUS
        private set

    private var lastFocusChangeTimestamp = 0L
    private val FOCUS_DEBOUNCE_MS = 100L

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "ACTION_AUDIO_BECOMING_NOISY received. Headphones or Bluetooth disconnected.")
                hasFocus = false
                pausedDueToTransientLoss = false
                focusState = AudioFocusState.NO_FOCUS
                unregisterNoisyReceiver()
                onAudioBecomingNoisy?.invoke()
            }
        }
    }
    private var isNoisyReceiverRegistered = false

    fun registerNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        try {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            context.registerReceiver(becomingNoisyReceiver, filter)
            isNoisyReceiverRegistered = true
            Log.d(TAG, "Registered ACTION_AUDIO_BECOMING_NOISY receiver")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to register becoming noisy receiver", e)
        }
    }

    fun unregisterNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            context.unregisterReceiver(becomingNoisyReceiver)
            isNoisyReceiverRegistered = false
            Log.d(TAG, "Unregistered ACTION_AUDIO_BECOMING_NOISY receiver")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to unregister becoming noisy receiver", e)
        }
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val now = SystemClock.elapsedRealtime()
        if (now - lastFocusChangeTimestamp < FOCUS_DEBOUNCE_MS && lastFocusChangeTimestamp != 0L) {
            Log.d(TAG, "Rapid focus change detected ($focusChange); processing safely.")
        }
        lastFocusChangeTimestamp = now

        Log.d(TAG, "onAudioFocusChange: $focusChange (current state=$focusState, hasFocus=$hasFocus)")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                focusState = AudioFocusState.NO_FOCUS
                hasFocus = false
                pausedDueToTransientLoss = false
                unregisterNoisyReceiver()
                abandonAudioFocusInternal()
                onAudioFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                focusState = AudioFocusState.TRANSIENT_LOSS
                hasFocus = false
                pausedDueToTransientLoss = true
                unregisterNoisyReceiver()
                onAudioFocusTransientLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (onAudioFocusDuck != null) {
                    onAudioFocusDuck.invoke(0.2f)
                } else {
                    // Default to pausing for speech content so words are not missed during alerts
                    focusState = AudioFocusState.TRANSIENT_LOSS
                    hasFocus = false
                    pausedDueToTransientLoss = true
                    unregisterNoisyReceiver()
                    onAudioFocusTransientLoss()
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val wasTransient = (focusState == AudioFocusState.TRANSIENT_LOSS || pausedDueToTransientLoss)
                val wasDelayed = (focusState == AudioFocusState.DELAYED_FOCUS)
                focusState = AudioFocusState.ACTIVE_FOCUS
                hasFocus = true
                pausedDueToTransientLoss = false
                registerNoisyReceiver()
                onAudioFocusDuck?.invoke(1.0f)
                if (wasTransient || wasDelayed) {
                    onAudioFocusGain()
                }
            }
        }
    }

    /**
     * Requests audio focus for speech/media playback.
     * Returns true if audio focus was granted immediately.
     */
    fun requestAudioFocus(): Boolean {
        if (hasFocus && focusState == AudioFocusState.ACTIVE_FOCUS) {
            registerNoisyReceiver()
            return true
        }
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

        when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasFocus = true
                focusState = AudioFocusState.ACTIVE_FOCUS
                pausedDueToTransientLoss = false
                registerNoisyReceiver()
                Log.d(TAG, "requestAudioFocus: GRANTED")
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                hasFocus = false
                focusState = AudioFocusState.DELAYED_FOCUS
                pausedDueToTransientLoss = true
                Log.d(TAG, "requestAudioFocus: DELAYED (will resume on AUDIOFOCUS_GAIN)")
            }
            else -> {
                hasFocus = false
                focusState = AudioFocusState.NO_FOCUS
                pausedDueToTransientLoss = false
                Log.d(TAG, "requestAudioFocus: FAILED ($result)")
            }
        }
        return hasFocus
    }

    /**
     * Clears any transient loss flag so subsequent focus gains do not resume playback
     * if the user explicitly paused or stopped.
     */
    fun clearTransientLoss() {
        pausedDueToTransientLoss = false
        if (focusState == AudioFocusState.TRANSIENT_LOSS || focusState == AudioFocusState.DELAYED_FOCUS) {
            focusState = AudioFocusState.NO_FOCUS
        }
    }

    private fun abandonAudioFocusInternal() {
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
        }
    }

    /**
     * Abandons audio focus cleanly and unregisters noisy receiver.
     */
    fun abandonAudioFocus() {
        unregisterNoisyReceiver()
        hasFocus = false
        pausedDueToTransientLoss = false
        focusState = AudioFocusState.NO_FOCUS
        abandonAudioFocusInternal()
    }

    /**
     * Clean up all audio focus resources.
     */
    fun release() {
        abandonAudioFocus()
    }

    // Testing helper functions
    internal fun handleAudioFocusChangeForTesting(focusChange: Int) {
        focusChangeListener.onAudioFocusChange(focusChange)
    }

    internal fun simulateAudioBecomingNoisyForTesting() {
        becomingNoisyReceiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
    }
}
