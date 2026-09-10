package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TtsPlaybackSessionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Explicit states representing the native Android TTS engine lifecycle and playback.
 */
enum class TtsState {
    INITIALIZING,
    IDLE,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR,
    RECOVERING
}

/**
 * Interruption and termination reasons to clearly distinguish user actions from unexpected crashes.
 */
object TtsInterruptionReason {
    const val NONE = "NONE"
    const val EXPLICIT_PAUSE = "EXPLICIT_PAUSE"
    const val EXPLICIT_STOP = "EXPLICIT_STOP"
    const val NATURALLY_FINISHED = "NATURALLY_FINISHED"
    const val UNEXPECTED_INTERRUPTION = "UNEXPECTED_INTERRUPTION"
}

/**
 * Information describing an available TTS voice option.
 */
data class TtsVoiceInfo(
    val id: String,
    val displayName: String,
    val locale: Locale,
    val isNetworkRequired: Boolean,
    val voice: Voice? = null
)

/**
 * Metadata describing the active novel and chapter being read by TTS.
 */
data class TtsMediaMetadata(
    val bookId: String = "",
    val chapterId: String = "",
    val novelTitle: String = "",
    val chapterTitle: String = "",
    val chapterOrder: Int = 0
)

/**
 * Interface abstracting TextToSpeech operations for clean isolation and testability.
 */
interface TextToSpeechClient {
    fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int
    fun stop(): Int
    fun setSpeechRate(speechRate: Float): Int
    fun setVoice(voice: Voice): Int
    fun getVoices(): Set<Voice>?
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int
    fun shutdown()
}

/**
 * Default production wrapper around Android's native TextToSpeech engine.
 */
class AndroidTextToSpeechClient(
    private val tts: TextToSpeech
) : TextToSpeechClient {
    init {
        try {
            val audioAttributes = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            tts.setAudioAttributes(audioAttributes)
        } catch (e: Exception) {
            Log.w("AndroidTextToSpeechClient", "Failed to set audio attributes on TTS engine", e)
        }
    }

    override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
        return tts.speak(text, queueMode, params, utteranceId)
    }

    override fun stop(): Int {
        return tts.stop()
    }

    override fun setSpeechRate(speechRate: Float): Int {
        return tts.setSpeechRate(speechRate)
    }

    override fun setVoice(voice: Voice): Int {
        return tts.setVoice(voice)
    }

    override fun getVoices(): Set<Voice>? {
        return tts.voices
    }

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
        return tts.setOnUtteranceProgressListener(listener)
    }

    override fun shutdown() {
        tts.shutdown()
    }
}

/**
 * Isolated manager coordinating Android TextToSpeech for reader paragraph playback.
 * Maintains explicit states, paragraph navigation, voice selection, and rate control.
 */
class ReaderTtsManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val clientFactory: ((TextToSpeech.OnInitListener) -> TextToSpeechClient?)? = null,
    val textProcessor: com.example.tts.rule.TtsTextProcessor =
        (context.applicationContext as? com.example.TranslatorApplication)?.ttsTextProcessor ?: com.example.tts.rule.TtsTextProcessor(),
    private val settingsRepository: SettingsRepository? =
        (context.applicationContext as? com.example.TranslatorApplication)?.settingsRepository
            ?: runCatching { SettingsRepository(context.applicationContext) }.getOrNull()
) : TextToSpeech.OnInitListener {

    private val TAG = "ReaderTtsManager"

    @Volatile
    private var managerScope: CoroutineScope = scope

    private fun getSafeScope(): CoroutineScope {
        val current = managerScope
        if (current.isActive) return current
        synchronized(this) {
            if (!managerScope.isActive) {
                Log.w(TAG, "CoroutineScope was cancelled or inactive; renewing with SupervisorJob + Dispatchers.Main")
                managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            }
            return managerScope
        }
    }

    private fun launchSafe(tag: String, block: suspend CoroutineScope.() -> Unit): Job? {
        if (isReleased) return null
        return try {
            getSafeScope().launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.e(TAG, "Uncaught exception in coroutine [$tag]", t)
                    if (_ttsState.value == TtsState.PLAYING) {
                        _errorMessage.value = "Playback encountered an unexpected error"
                        _ttsState.value = TtsState.ERROR
                        notifyAndPersistPosition(_currentParagraphIndex.value, TtsState.ERROR)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to launch coroutine [$tag]", t)
            null
        }
    }

    fun notifyAndPersistPosition(
        paragraphIndex: Int,
        state: TtsState,
        subChunkIndex: Int = currentSubChunkIndex,
        wasActivelyPlaying: Boolean = (state == TtsState.PLAYING),
        interruptionReason: String = when (state) {
            TtsState.PLAYING -> TtsInterruptionReason.UNEXPECTED_INTERRUPTION
            TtsState.PAUSED -> TtsInterruptionReason.EXPLICIT_PAUSE
            TtsState.STOPPED -> TtsInterruptionReason.EXPLICIT_STOP
            else -> TtsInterruptionReason.NONE
        }
    ) {
        try {
            onPositionChanged?.invoke(paragraphIndex, state)
        } catch (e: Throwable) {
            Log.w(TAG, "Error invoking onPositionChanged callback", e)
        }

        try {
            val repo = settingsRepository ?: return
            val meta = _mediaMetadata.value
            if (meta.bookId.isNotBlank() && meta.chapterId.isNotBlank()) {
                repo.saveTtsSessionState(
                    TtsPlaybackSessionState(
                        bookId = meta.bookId,
                        chapterId = meta.chapterId,
                        chapterOrder = meta.chapterOrder,
                        paragraphIndex = paragraphIndex,
                        subChunkIndex = subChunkIndex,
                        playbackState = state.name,
                        speechRate = _speechRate.value,
                        voiceId = _selectedVoice.value?.id ?: savedVoiceId,
                        timestamp = System.currentTimeMillis(),
                        wasActivelyPlaying = wasActivelyPlaying,
                        interruptionReason = interruptionReason
                    )
                )
                repo.setLastReadChapterId(meta.bookId, meta.chapterId)
                repo.setLastReadParagraphIndex(meta.bookId, meta.chapterId, paragraphIndex)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error persisting TTS position intrinsically", e)
        }
    }

    // Bounded reinitialization & recovery state
    private var reinitRetryCount = 0
    private var lastReinitTimestamp = 0L
    private val MAX_REINIT_RETRIES = 3
    private val REINIT_WINDOW_MS = 60_000L
    private var recoveryJob: Job? = null
    private val isEngineReady = java.util.concurrent.atomic.AtomicBoolean(false)
    private val isRecovering = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastSynthesisErrorIndex: Int = -1
    private var synthesisErrorRepeatCount: Int = 0

    fun isEngineReady(): Boolean = isEngineReady.get()

    /**
     * Safely tears down any active or broken native TTS engine,
     * detaching callbacks and incrementing generation tokens so
     * stale callbacks from dead engines cannot control subsequent engines.
     */
    private fun teardownBrokenEngine() {
        isEngineReady.set(false)
        activeUtteranceId = null
        engineGeneration++
        playbackSessionEpoch++
        try {
            ttsClient?.setOnUtteranceProgressListener(null)
            nativeTts?.setOnUtteranceProgressListener(null)
            ttsClient?.stop()
            ttsClient?.shutdown()
            nativeTts?.shutdown()
        } catch (e: Throwable) {
            Log.w(TAG, "Error shutting down broken TTS engine", e)
        } finally {
            ttsClient = null
            nativeTts = null
            isInitializing.set(false)
        }
    }

    /**
     * Public recovery mechanism to recover TTS from an ERROR or uninitialized state without clearing app data.
     * Resets the bounded retry counter, tears down any existing/stale TTS engine, and triggers a clean reinitialization.
     * If resumePlaying is true, resumes playback once the engine is ready.
     */
    fun recoverFromError(
        resumePlaying: Boolean = true,
        startIndex: Int? = null,
        startSubChunk: Int = 0
    ) {
        if (isReleased) return
        Log.i(TAG, "recoverFromError invoked: resumePlaying=$resumePlaying, startIndex=$startIndex, startSubChunk=$startSubChunk")
        recoveryJob?.cancel()
        recoveryJob = null
        isRecovering.set(false)
        reinitRetryCount = 0
        lastReinitTimestamp = 0L
        lastSynthesisErrorIndex = -1
        synthesisErrorRepeatCount = 0

        if (startIndex != null && paragraphs.isNotEmpty()) {
            _currentParagraphIndex.value = startIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
        }
        pendingStartSubChunk = startSubChunk

        if (resumePlaying) {
            wasPlayingBeforeEngineReinit = true
        }

        _errorMessage.value = "Reconnecting speech engine..."
        _ttsState.value = TtsState.RECOVERING

        reinitialize(resumeOnReady = resumePlaying)
    }

    private fun triggerEngineRecovery(resumeOnReady: Boolean, reason: String) {
        if (isReleased) return

        // Concurrency guard: prevent multiple simultaneous recoveries
        if (!isRecovering.compareAndSet(false, true)) {
            Log.d(TAG, "Recovery already in progress ($reason); ignoring duplicate trigger.")
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastReinitTimestamp > REINIT_WINDOW_MS) {
            reinitRetryCount = 0
        }

        if (resumeOnReady || _ttsState.value == TtsState.PLAYING) {
            wasPlayingBeforeEngineReinit = true
        }

        // 1. Stop using the broken engine
        // 2. Safely detach callbacks
        // 3. Shut down the broken engine
        teardownBrokenEngine()

        if (reinitRetryCount < MAX_REINIT_RETRIES) {
            reinitRetryCount++
            lastReinitTimestamp = now

            // 4. Transition playback to RECOVERING
            _ttsState.value = TtsState.RECOVERING
            _errorMessage.value = "Recovering speech engine (attempt $reinitRetryCount/$MAX_REINIT_RETRIES)..."
            Log.w(TAG, "Non-fatal TTS failure ($reason). Scheduling recovery attempt $reinitRetryCount/$MAX_REINIT_RETRIES")

            // Preserve current reading position
            notifyAndPersistPosition(
                _currentParagraphIndex.value,
                TtsState.RECOVERING,
                subChunkIndex = currentSubChunkIndex,
                wasActivelyPlaying = wasPlayingBeforeEngineReinit,
                interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
            )

            // Bounded retry with sensible exponential backoff (500ms, 1500ms, 3000ms)
            val backoffMs = when (reinitRetryCount) {
                1 -> 500L
                2 -> 1500L
                else -> 3000L
            }

            recoveryJob?.cancel()
            recoveryJob = launchSafe("engineRecovery") {
                try {
                    delay(backoffMs)
                    // 5. Recreate the engine
                    initializeEngine()
                } finally {
                    isRecovering.set(false)
                }
            }
        } else {
            isRecovering.set(false)
            Log.e(TAG, "Exceeded maximum TTS recovery attempts ($MAX_REINIT_RETRIES). Entering ERROR state.")
            _errorMessage.value = "Speech engine unavailable. Tap Play to retry."
            _ttsState.value = TtsState.ERROR
            audioFocusManager.abandonAudioFocus()
            notifyAndPersistPosition(
                _currentParagraphIndex.value,
                TtsState.ERROR,
                subChunkIndex = currentSubChunkIndex,
                wasActivelyPlaying = false,
                interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
            )
        }
    }

    private var ttsClient: TextToSpeechClient? = null
    private var nativeTts: TextToSpeech? = null

    private val _ttsState = MutableStateFlow(TtsState.INITIALIZING)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _currentParagraphIndex = MutableStateFlow(0)
    val currentParagraphIndex: StateFlow<Int> = _currentParagraphIndex.asStateFlow()

    private val _speechRate = MutableStateFlow(1.0f)
    val speechRate: StateFlow<Float> = _speechRate.asStateFlow()

    private val _availableVoices = MutableStateFlow<List<TtsVoiceInfo>>(emptyList())
    val availableVoices: StateFlow<List<TtsVoiceInfo>> = _availableVoices.asStateFlow()

    private val _selectedVoice = MutableStateFlow<TtsVoiceInfo?>(null)
    val selectedVoice: StateFlow<TtsVoiceInfo?> = _selectedVoice.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _mediaMetadata = MutableStateFlow(TtsMediaMetadata())
    val mediaMetadata: StateFlow<TtsMediaMetadata> = _mediaMetadata.asStateFlow()

    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled: StateFlow<Boolean> = _isTtsEnabled.asStateFlow()

    private val _autoAdvanceChapter = MutableStateFlow(true)
    val autoAdvanceChapter: StateFlow<Boolean> = _autoAdvanceChapter.asStateFlow()

    var onChapterComplete: (() -> Unit)? = null
    var onPlaybackStarted: (() -> Unit)? = null
    var onPositionChanged: ((paragraphIndex: Int, state: TtsState) -> Unit)? = null
    var chapterTransitionProvider: ChapterTransitionProvider? = null

    private var paragraphs: List<String> = emptyList()
    private var currentChapterId: String = ""
    private var utteranceSeq: Long = 0L
    private var activeUtteranceId: String? = null
    private var playbackSessionEpoch: Long = 0L
    private var engineGeneration: Long = 1L
    private var isAdvancingChapter: Boolean = false
    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var isReleased = false
    private var wasPlayingBeforeBackground = false
    private var wasPlayingBeforeEngineReinit = false
    private var pendingStartSubChunk: Int = 0
    private var currentVolume: Float = 1.0f
    var savedVoiceId: String? = null
    private var pendingInitCallback: (() -> Unit)? = null

    fun getEngineGeneration(): Long = engineGeneration
    fun getCurrentSubChunkIndex(): Int = currentSubChunkIndex

    val audioFocusManager: TtsAudioManager = TtsAudioManager(
        context = context.applicationContext,
        onAudioFocusLoss = {
            handleAudioFocusLoss()
        },
        onAudioFocusTransientLoss = {
            handleAudioFocusTransientLoss()
        },
        onAudioFocusGain = {
            handleAudioFocusGain()
        },
        onAudioFocusDuck = null, // Default to pausing for speech content so words are not missed or repeated
        onAudioBecomingNoisy = {
            handleAudioBecomingNoisy()
        }
    )

    private fun handleAudioFocusLoss() {
        Log.i(TAG, "Audio focus lost permanently. Pausing playback and preserving position.")
        pause(isExplicitUserAction = false)
    }

    private fun handleAudioFocusTransientLoss() {
        Log.i(TAG, "Audio focus lost transiently (e.g. phone call/alert). Pausing playback.")
        pause(isExplicitUserAction = false)
    }

    private fun handleAudioFocusGain() {
        Log.i(TAG, "Audio focus regained. State=${_ttsState.value}")
        if (_ttsState.value == TtsState.PAUSED && _isTtsEnabled.value) {
            resume()
        }
    }

    private fun handleAudioBecomingNoisy() {
        Log.i(TAG, "Audio route changed to noisy (headphones/Bluetooth unplugged). Pausing playback.")
        pause(isExplicitUserAction = true)
    }

    init {
        initializeEngine()
    }

    fun getParagraphs(): List<String> = paragraphs
    fun getCurrentChapterId(): String = currentChapterId

    fun setTtsEnabled(enabled: Boolean) {
        _isTtsEnabled.value = enabled
        if (!enabled && (_ttsState.value == TtsState.PLAYING || _ttsState.value == TtsState.PAUSED)) {
            stop()
        }
    }

    fun setAutoAdvanceChapter(enabled: Boolean) {
        _autoAdvanceChapter.value = enabled
    }

    fun onAppBackgrounded() {
        // With foreground service, background playback continues seamlessly.
        // We notify position listeners and persist state.
        notifyAndPersistPosition(_currentParagraphIndex.value, _ttsState.value)
    }

    fun onAppForegrounded(autoResume: Boolean = true) {
        // UI reattaches seamlessly to ongoing playback.
    }

    fun reinitialize(resumeOnReady: Boolean = false, onSuccess: (() -> Unit)? = null) {
        if (isReleased) return
        recoveryJob?.cancel()
        recoveryJob = null
        isRecovering.set(false)
        if (resumeOnReady || _ttsState.value == TtsState.PLAYING) {
            wasPlayingBeforeEngineReinit = true
        }
        teardownBrokenEngine()
        initializeEngine(onSuccess)
    }

    private class GenerationOnInitListener(
        val generation: Long,
        val onInitCallback: (Int, Long) -> Unit
    ) : TextToSpeech.OnInitListener {
        override fun onInit(status: Int) {
            onInitCallback(status, generation)
        }
    }

    private fun initializeEngine(onSuccess: (() -> Unit)? = null) {
        if (isReleased) return
        if (!isInitializing.compareAndSet(false, true)) return

        if (onSuccess != null) {
            pendingInitCallback = onSuccess
        }

        if (_ttsState.value != TtsState.RECOVERING && _ttsState.value != TtsState.PAUSED && _ttsState.value != TtsState.STOPPED) {
            _ttsState.value = TtsState.INITIALIZING
        }

        val currentGen = engineGeneration
        val initListener = GenerationOnInitListener(currentGen) { status, gen ->
            handleOnInit(status, gen)
        }

        try {
            if (clientFactory != null) {
                ttsClient = clientFactory.invoke(initListener)
            } else {
                nativeTts?.shutdown()
                nativeTts = TextToSpeech(context.applicationContext, initListener)
            }
        } catch (e: Throwable) {
            isInitializing.set(false)
            Log.e(TAG, "Failed to instantiate Android TextToSpeech engine", e)
            teardownBrokenEngine()
            triggerEngineRecovery(
                resumeOnReady = wasPlayingBeforeEngineReinit,
                reason = "Failed to instantiate TextToSpeech: ${e.message}"
            )
        }
    }

    override fun onInit(status: Int) {
        handleOnInit(status, engineGeneration)
    }

    private fun verifyEngineUsability(client: TextToSpeechClient): Boolean {
        return try {
            client.setSpeechRate(_speechRate.value)
            _selectedVoice.value?.voice?.let { client.setVoice(it) }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "Engine usability check failed", e)
            false
        }
    }

    private fun handleOnInit(status: Int, gen: Long) {
        isInitializing.set(false)
        if (isReleased) return

        // Ignore late callbacks from older engine generations
        if (gen != engineGeneration) {
            Log.w(TAG, "Discarding onInit from stale engine generation: $gen (current=$engineGeneration)")
            return
        }

        if (status == TextToSpeech.SUCCESS) {
            try {
                if (ttsClient == null && nativeTts != null) {
                    ttsClient = AndroidTextToSpeechClient(nativeTts!!)
                }

                val client = ttsClient
                if (client != null) {
                    client.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            // Already in PLAYING state
                        }

                        override fun onDone(utteranceId: String?) {
                            if (isReleased) return
                            launchSafe("onDone") {
                                handleUtteranceDone(utteranceId)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            onError(utteranceId, -1)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (isReleased) return
                            launchSafe("onError") {
                                handleUtteranceError(utteranceId, errorCode)
                            }
                        }
                    })

                    // Query and populate available voices
                    populateVoices(client)

                    // Apply speech rate
                    client.setSpeechRate(_speechRate.value)

                    // Verify engine is actually usable
                    val isUsable = verifyEngineUsability(client)
                    if (!isUsable) {
                        Log.e(TAG, "Engine usability verification failed (generation $gen)")
                        teardownBrokenEngine()
                        triggerEngineRecovery(
                            resumeOnReady = wasPlayingBeforeEngineReinit,
                            reason = "Engine usability verification failed"
                        )
                        return
                    }

                    // Open initialization complete gate
                    isEngineReady.set(true)
                    _errorMessage.value = null
                    Log.i(TAG, "Android TTS engine initialized and verified successfully (generation $gen).")

                    val shouldResume = wasPlayingBeforeEngineReinit &&
                        _ttsState.value != TtsState.PAUSED &&
                        _ttsState.value != TtsState.STOPPED &&
                        paragraphs.isNotEmpty() &&
                        _isTtsEnabled.value

                    wasPlayingBeforeEngineReinit = false

                    if (shouldResume) {
                        val restoreSubChunk = pendingStartSubChunk
                        pendingStartSubChunk = 0
                        _ttsState.value = TtsState.PLAYING
                        audioFocusManager.requestAudioFocus()
                        notifyAndPersistPosition(
                            _currentParagraphIndex.value,
                            TtsState.PLAYING,
                            subChunkIndex = restoreSubChunk,
                            wasActivelyPlaying = true,
                            interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
                        )
                        speakCurrentParagraph(restoreSubChunk)
                    } else if (_ttsState.value == TtsState.PAUSED) {
                        _ttsState.value = TtsState.PAUSED
                        notifyAndPersistPosition(
                            _currentParagraphIndex.value,
                            TtsState.PAUSED,
                            subChunkIndex = pendingStartSubChunk,
                            wasActivelyPlaying = false,
                            interruptionReason = TtsInterruptionReason.EXPLICIT_PAUSE
                        )
                    } else if (_ttsState.value == TtsState.STOPPED) {
                        _ttsState.value = TtsState.STOPPED
                    } else {
                        _ttsState.value = TtsState.IDLE
                    }

                    pendingInitCallback?.invoke()
                    pendingInitCallback = null
                } else {
                    teardownBrokenEngine()
                    triggerEngineRecovery(
                        resumeOnReady = wasPlayingBeforeEngineReinit,
                        reason = "ttsClient was null on init"
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error configuring TTS engine", e)
                teardownBrokenEngine()
                triggerEngineRecovery(
                    resumeOnReady = wasPlayingBeforeEngineReinit,
                    reason = "Error configuring TTS engine: ${e.message}"
                )
            }
        } else {
            Log.e(TAG, "TTS initialization failed with code: $status (generation $gen)")
            teardownBrokenEngine()
            if (wasPlayingBeforeEngineReinit) {
                triggerEngineRecovery(
                    resumeOnReady = true,
                    reason = "TTS initialization failed during playback recovery with status $status"
                )
            } else {
                _errorMessage.value = "Speech engine unavailable"
                _ttsState.value = TtsState.ERROR
                notifyAndPersistPosition(_currentParagraphIndex.value, TtsState.ERROR)
            }
        }
    }

    private fun populateVoices(client: TextToSpeechClient) {
        try {
            val voices = client.getVoices()
            if (!voices.isNullOrEmpty()) {
                val voiceList = voices
                    .filter { voice ->
                        // English preferred, but include others if available
                        voice.locale.language.equals("en", ignoreCase = true)
                    }
                    .ifEmpty { voices.toList() }
                    .map { voice ->
                        val country = voice.locale.displayCountry.ifBlank { voice.locale.country }
                        val lang = voice.locale.displayLanguage.ifBlank { voice.locale.language }
                        val locLabel = if (country.isNotBlank()) "$lang ($country)" else lang
                        val cleanName = voice.name
                            .substringAfterLast("/")
                            .substringAfterLast("#")
                            .replace("_", " ")
                            .replace("-", " ")
                            .capitalizeWords()

                        val displayName = if (cleanName.isNotBlank() && !cleanName.equals(voice.name, ignoreCase = true)) {
                            "$locLabel • $cleanName"
                        } else {
                            locLabel
                        }

                        TtsVoiceInfo(
                            id = voice.name,
                            displayName = displayName,
                            locale = voice.locale,
                            isNetworkRequired = voice.isNetworkConnectionRequired,
                            voice = voice
                        )
                    }
                    .sortedWith(compareBy({ it.isNetworkRequired }, { it.displayName }))

                _availableVoices.value = voiceList
                val targetVoice = if (savedVoiceId != null) {
                    voiceList.find { it.id == savedVoiceId } ?: voiceList.firstOrNull()
                } else {
                    _selectedVoice.value ?: voiceList.firstOrNull()
                }
                if (targetVoice != null) {
                    _selectedVoice.value = targetVoice
                    targetVoice.voice?.let { client.setVoice(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to query TTS voices", e)
        }
    }

    private fun String.capitalizeWords(): String = split(" ")
        .joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }

    fun setChapterMetadata(
        bookId: String,
        chapterId: String,
        novelTitle: String,
        chapterTitle: String,
        chapterOrder: Int
    ) {
        _mediaMetadata.value = TtsMediaMetadata(
            bookId = bookId,
            chapterId = chapterId,
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
            chapterOrder = chapterOrder
        )
    }

    /**
     * Prepares for an explicit manual or external chapter change.
     * Stops pending audio, cancels any active utterance, and increments
     * session epoch to guarantee stale callbacks from previous chapters are discarded.
     */
    fun prepareForChapterChange() {
        playbackSessionEpoch++
        isAdvancingChapter = false
        activeUtteranceId = null
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        try {
            ttsClient?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS during prepareForChapterChange", e)
        }
    }

    /**
     * Updates the chapter ID and paragraphs.
     * Optionally continues playing immediately from the specified start index and subchunk.
     */
    fun setChapterAndParagraphs(
        chapterId: String,
        newParagraphs: List<String>,
        continuePlaying: Boolean = false,
        startIndex: Int = 0,
        startSubChunk: Int = 0,
        bookId: String = _mediaMetadata.value.bookId,
        novelTitle: String = _mediaMetadata.value.novelTitle,
        chapterTitle: String = _mediaMetadata.value.chapterTitle,
        chapterOrder: Int = _mediaMetadata.value.chapterOrder,
        targetState: TtsState? = null
    ) {
        if (isReleased) return

        playbackSessionEpoch++
        isAdvancingChapter = false

        val chapterChanged = currentChapterId != chapterId
        currentChapterId = chapterId
        paragraphs = newParagraphs

        _mediaMetadata.value = TtsMediaMetadata(
            bookId = bookId,
            chapterId = chapterId,
            novelTitle = novelTitle,
            chapterTitle = chapterTitle,
            chapterOrder = chapterOrder
        )

        if (chapterChanged || startIndex != _currentParagraphIndex.value) {
            _currentParagraphIndex.value = startIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
        }

        currentSubChunkIndex = startSubChunk
        currentSubChunks = emptyList()

        if (continuePlaying && paragraphs.isNotEmpty() && _isTtsEnabled.value) {
            play(_currentParagraphIndex.value, startSubChunk = startSubChunk)
        } else if (targetState == TtsState.PAUSED) {
            _ttsState.value = TtsState.PAUSED
            notifyAndPersistPosition(
                _currentParagraphIndex.value,
                TtsState.PAUSED,
                subChunkIndex = startSubChunk,
                wasActivelyPlaying = false,
                interruptionReason = TtsInterruptionReason.EXPLICIT_PAUSE
            )
        } else if (targetState == TtsState.STOPPED) {
            _ttsState.value = TtsState.STOPPED
            notifyAndPersistPosition(
                _currentParagraphIndex.value,
                TtsState.STOPPED,
                subChunkIndex = startSubChunk,
                wasActivelyPlaying = false,
                interruptionReason = TtsInterruptionReason.EXPLICIT_STOP
            )
        } else if (_ttsState.value == TtsState.PLAYING && chapterChanged) {
            stop()
        } else if (chapterChanged && _ttsState.value == TtsState.PAUSED) {
            stop()
        } else if (_ttsState.value != TtsState.INITIALIZING && _ttsState.value != TtsState.ERROR && _ttsState.value != TtsState.PAUSED) {
            _ttsState.value = TtsState.IDLE
        }
    }

    /**
     * Updates the paragraphs for the current chapter.
     * Stops playback if currently playing and resets index.
     */
    fun setParagraphs(newParagraphs: List<String>, resetIndex: Boolean = true) {
        setChapterAndParagraphs(
            chapterId = currentChapterId,
            newParagraphs = newParagraphs,
            continuePlaying = false,
            startIndex = if (resetIndex) 0 else _currentParagraphIndex.value
        )
    }

    /**
     * Starts playback from given paragraph index or current index and subchunk.
     * Previously playing speech is guaranteed to stop before starting the new paragraph.
     */
    fun play(startIndex: Int? = null, startSubChunk: Int = 0) {
        if (isReleased) return
        if (!_isTtsEnabled.value) {
            Log.w(TAG, "Cannot play while TTS is disabled")
            return
        }
        if (_ttsState.value == TtsState.ERROR) {
            Log.i(TAG, "play() called while in ERROR state; triggering automatic recovery")
            recoverFromError(resumePlaying = true, startIndex = startIndex, startSubChunk = startSubChunk)
            return
        }
        if (_ttsState.value == TtsState.INITIALIZING || _ttsState.value == TtsState.RECOVERING) {
            Log.w(TAG, "play() called while in ${_ttsState.value} state; setting pending autoplay")
            wasPlayingBeforeEngineReinit = true
            pendingStartSubChunk = startSubChunk
            if (startIndex != null && paragraphs.isNotEmpty()) {
                _currentParagraphIndex.value = startIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
            }
            return
        }

        if (startIndex == null && _ttsState.value == TtsState.PAUSED) {
            resume()
            return
        }

        if (paragraphs.isEmpty()) {
            Log.w(TAG, "No paragraphs available to play")
            _ttsState.value = TtsState.IDLE
            return
        }

        // Previously playing speech must stop before starting from the new paragraph.
        activeUtteranceId = null
        try {
            ttsClient?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping active speech before new play", e)
        }

        if (startIndex != null) {
            _currentParagraphIndex.value = startIndex.coerceIn(0, (paragraphs.size - 1).coerceAtLeast(0))
        }
        currentSubChunkIndex = startSubChunk
        currentSubChunks = emptyList()

        _ttsState.value = TtsState.PLAYING
        audioFocusManager.requestAudioFocus()
        try {
            val meta = _mediaMetadata.value
            TtsPlaybackService.start(context.applicationContext, meta.bookId, meta.chapterId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start TtsPlaybackService", e)
        }
        onPlaybackStarted?.invoke()
        notifyAndPersistPosition(
            _currentParagraphIndex.value,
            TtsState.PLAYING,
            subChunkIndex = currentSubChunkIndex,
            wasActivelyPlaying = true,
            interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
        )
        speakCurrentParagraph(startSubChunk)
    }

    /**
     * Pauses the current playback, retaining paragraph position.
     * If isExplicitUserAction is true, clears transient loss so subsequent audio focus gain does not auto-resume.
     */
    fun pause(isExplicitUserAction: Boolean = true) {
        if (isReleased) return
        playbackSessionEpoch++
        isAdvancingChapter = false
        wasPlayingBeforeEngineReinit = false
        if (isExplicitUserAction) {
            audioFocusManager.clearTransientLoss()
        }
        if (_ttsState.value == TtsState.PLAYING || _ttsState.value == TtsState.RECOVERING) {
            activeUtteranceId = null
            try {
                ttsClient?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping TTS on pause", e)
            }
            _ttsState.value = TtsState.PAUSED
            notifyAndPersistPosition(
                _currentParagraphIndex.value,
                TtsState.PAUSED,
                subChunkIndex = currentSubChunkIndex,
                wasActivelyPlaying = false,
                interruptionReason = if (isExplicitUserAction) TtsInterruptionReason.EXPLICIT_PAUSE else TtsInterruptionReason.UNEXPECTED_INTERRUPTION
            )
        }
    }

    /**
     * Resumes playback from the paused paragraph position without resetting processed subchunk position.
     */
    fun resume() {
        if (isReleased) return
        if (_ttsState.value == TtsState.PAUSED) {
            _ttsState.value = TtsState.PLAYING
            audioFocusManager.requestAudioFocus()
            try {
                val meta = _mediaMetadata.value
                TtsPlaybackService.start(context.applicationContext, meta.bookId, meta.chapterId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start TtsPlaybackService", e)
            }
            onPlaybackStarted?.invoke()
            notifyAndPersistPosition(
                _currentParagraphIndex.value,
                TtsState.PLAYING,
                subChunkIndex = currentSubChunkIndex,
                wasActivelyPlaying = true,
                interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
            )
            speakCurrentParagraph(currentSubChunkIndex)
        }
    }

    /**
     * Stops playback completely and resets state to STOPPED.
     */
    fun stop() {
        if (isReleased) return
        playbackSessionEpoch++
        isAdvancingChapter = false
        wasPlayingBeforeEngineReinit = false
        activeUtteranceId = null
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        try {
            ttsClient?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        _ttsState.value = TtsState.STOPPED
        audioFocusManager.abandonAudioFocus()
        notifyAndPersistPosition(
            _currentParagraphIndex.value,
            TtsState.STOPPED,
            subChunkIndex = 0,
            wasActivelyPlaying = false,
            interruptionReason = TtsInterruptionReason.EXPLICIT_STOP
        )
    }

    /**
     * Moves to the previous paragraph. If playing, begins speaking it immediately.
     * Safely skips backward past any paragraphs omitted by TTS rules.
     */
    fun previousParagraph() {
        if (isReleased || paragraphs.isEmpty()) return
        val bookId = _mediaMetadata.value.bookId.ifBlank { null }
        var prevIndex = (_currentParagraphIndex.value - 1).coerceAtLeast(0)
        while (prevIndex > 0 && textProcessor.process(paragraphs[prevIndex].trim(), bookId).isBlank()) {
            prevIndex--
        }
        _currentParagraphIndex.value = prevIndex
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        notifyAndPersistPosition(_currentParagraphIndex.value, _ttsState.value)

        if (_ttsState.value == TtsState.PLAYING) {
            activeUtteranceId = null
            speakCurrentParagraph(0)
        }
    }

    /**
     * Moves to the next paragraph. If at the end, stops or triggers chapter advance.
     * Safely skips forward past any paragraphs omitted by TTS rules.
     */
    fun nextParagraph() {
        if (isReleased || paragraphs.isEmpty()) return
        val bookId = _mediaMetadata.value.bookId.ifBlank { null }
        var nextIndex = _currentParagraphIndex.value + 1
        while (nextIndex < paragraphs.size - 1 && textProcessor.process(paragraphs[nextIndex].trim(), bookId).isBlank()) {
            nextIndex++
        }
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        if (nextIndex < paragraphs.size) {
            _currentParagraphIndex.value = nextIndex
            notifyAndPersistPosition(_currentParagraphIndex.value, _ttsState.value)
            if (_ttsState.value == TtsState.PLAYING) {
                activeUtteranceId = null
                speakCurrentParagraph(0)
            }
        } else {
            handleChapterEnd()
        }
    }

    /**
     * Sets playback speed / speech rate (0.5x to 2.5x).
     */
    fun setSpeechRate(rate: Float) {
        val clampedRate = rate.coerceIn(0.5f, 2.5f)
        _speechRate.value = clampedRate
        try {
            ttsClient?.setSpeechRate(clampedRate)
            if (_ttsState.value == TtsState.PLAYING) {
                speakCurrentParagraph(currentSubChunkIndex)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error setting speech rate", e)
        }
    }

    /**
     * Selects and applies a TTS voice safely.
     */
    fun selectVoice(voiceInfo: TtsVoiceInfo) {
        _selectedVoice.value = voiceInfo
        savedVoiceId = voiceInfo.id
        voiceInfo.voice?.let {
            try {
                ttsClient?.setVoice(it)
            } catch (e: Exception) {
                Log.w(TAG, "Error setting voice: ${voiceInfo.id}", e)
            }
        }
        if (_ttsState.value == TtsState.PLAYING) {
            speakCurrentParagraph(currentSubChunkIndex)
        }
    }

    /**
     * Selects a TTS voice by unique voice ID.
     */
    fun selectVoiceById(voiceId: String?) {
        savedVoiceId = voiceId
        if (voiceId.isNullOrBlank()) {
            val fallback = _availableVoices.value.firstOrNull()
            if (fallback != null) selectVoice(fallback)
            return
        }
        val found = _availableVoices.value.find { it.id == voiceId }
        if (found != null) {
            selectVoice(found)
        } else {
            val fallback = _availableVoices.value.firstOrNull()
            if (fallback != null) selectVoice(fallback)
        }
    }

    private var currentSubChunkIndex = 0
    private var currentSubChunks: List<String> = emptyList()

    private fun chunkLongText(text: String, maxChunkSize: Int = 2500): List<String> {
        if (text.length <= maxChunkSize) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxChunkSize) {
                chunks.add(remaining)
                break
            }
            val candidate = remaining.substring(0, maxChunkSize)
            val splitIndex = candidate.lastIndexOfAny(charArrayOf('.', '!', '?', '\n', ';', ','))
            val actualSplit = if (splitIndex > maxChunkSize / 2) {
                splitIndex + 1
            } else {
                candidate.lastIndexOf(' ').takeIf { it > maxChunkSize / 2 } ?: maxChunkSize
            }
            chunks.add(remaining.substring(0, actualSplit).trim())
            remaining = remaining.substring(actualSplit).trimStart()
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun speakCurrentParagraph(startSubChunk: Int = 0) {
        val index = _currentParagraphIndex.value
        if (index !in paragraphs.indices) {
            _ttsState.value = TtsState.STOPPED
            notifyAndPersistPosition(index, TtsState.STOPPED)
            return
        }

        // Initialization-complete gate: never blindly call speak() before engine is ready
        if (!isEngineReady.get() || _ttsState.value == TtsState.INITIALIZING || _ttsState.value == TtsState.RECOVERING) {
            Log.w(TAG, "speakCurrentParagraph blocked by init gate: isReady=${isEngineReady.get()}, state=${_ttsState.value}. Pending autoplay set.")
            wasPlayingBeforeEngineReinit = true
            pendingStartSubChunk = startSubChunk
            return
        }

        val fullText = paragraphs[index].trim()
        val bookId = _mediaMetadata.value.bookId.ifBlank { null }
        val processedText = textProcessor.process(fullText, bookId)

        if (processedText.isBlank()) {
            // Skip empty paragraph automatically
            var nextIndex = index + 1
            while (nextIndex < paragraphs.size - 1 && textProcessor.process(paragraphs[nextIndex].trim(), bookId).isBlank()) {
                nextIndex++
            }
            if (nextIndex < paragraphs.size) {
                _currentParagraphIndex.value = nextIndex
                notifyAndPersistPosition(nextIndex, TtsState.PLAYING)
                speakCurrentParagraph(0)
            } else {
                handleChapterEnd()
            }
            return
        }

        currentSubChunks = chunkLongText(processedText)
        currentSubChunkIndex = startSubChunk.coerceIn(0, (currentSubChunks.size - 1).coerceAtLeast(0))
        val textToSpeak = currentSubChunks.getOrNull(currentSubChunkIndex) ?: processedText

        try {
            val client = ttsClient
            if (client == null) {
                Log.w(TAG, "speakCurrentParagraph: client is null, attempting recovery")
                triggerEngineRecovery(resumeOnReady = true, reason = "ttsClient was null")
                return
            }

            client.setSpeechRate(_speechRate.value)
            _selectedVoice.value?.voice?.let { client.setVoice(it) }

            val utteranceId = "utt_g${engineGeneration}_e${playbackSessionEpoch}_${currentChapterId}_${index}_${currentSubChunkIndex}_${++utteranceSeq}"
            activeUtteranceId = utteranceId

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
            }

            val result = client.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                if (reinitRetryCount > 0 && System.currentTimeMillis() - lastReinitTimestamp > 5000L) {
                    reinitRetryCount = 0
                }
            } else {
                Log.w(TAG, "speak() returned failure code: $result")
                triggerEngineRecovery(resumeOnReady = true, reason = "speak() returned failure code $result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during speakCurrentParagraph", e)
            triggerEngineRecovery(resumeOnReady = true, reason = "Exception during speak: ${e.message}")
        }
    }

    private fun handleUtteranceDone(utteranceId: String?) {
        try {
            // Requirements: Never repeat a paragraph. Never skip a paragraph.
            if (_ttsState.value != TtsState.PLAYING) return
            if (utteranceId == null || utteranceId != activeUtteranceId) {
                // Outdated, canceled, or duplicate callback - safely discard
                return
            }
            val genPrefix = "utt_g${engineGeneration}_"
            if (!utteranceId.startsWith(genPrefix)) {
                Log.w(TAG, "Discarding onDone from stale engine generation: $utteranceId (current=$engineGeneration)")
                return
            }
            activeUtteranceId = null

            // If there are remaining sub-chunks for a long paragraph, speak next sub-chunk
            if (currentSubChunkIndex < currentSubChunks.size - 1) {
                if (!isEngineReady.get() || _ttsState.value != TtsState.PLAYING) return
                currentSubChunkIndex++
                notifyAndPersistPosition(
                    _currentParagraphIndex.value,
                    TtsState.PLAYING,
                    subChunkIndex = currentSubChunkIndex,
                    wasActivelyPlaying = true,
                    interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
                )
                val textToSpeak = currentSubChunks.getOrNull(currentSubChunkIndex) ?: ""
                val nextUtteranceId = "utt_g${engineGeneration}_e${playbackSessionEpoch}_${currentChapterId}_${_currentParagraphIndex.value}_${currentSubChunkIndex}_${++utteranceSeq}"
                activeUtteranceId = nextUtteranceId
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume)
                    putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, android.media.AudioManager.STREAM_MUSIC)
                }
                val result = ttsClient?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, nextUtteranceId)
                if (result != null && result != TextToSpeech.SUCCESS) {
                    Log.w(TAG, "speak() for sub-chunk failed: $result")
                    triggerEngineRecovery(resumeOnReady = true, reason = "sub-chunk speak() returned failure code $result")
                }
                return
            }

            currentSubChunkIndex = 0
            currentSubChunks = emptyList()
            reinitRetryCount = 0 // Utterance completed successfully; reset retry counter

            val bookId = _mediaMetadata.value.bookId.ifBlank { null }
            var nextIndex = _currentParagraphIndex.value + 1
            while (nextIndex < paragraphs.size - 1 && textProcessor.process(paragraphs[nextIndex].trim(), bookId).isBlank()) {
                nextIndex++
            }
            if (nextIndex < paragraphs.size) {
                _currentParagraphIndex.value = nextIndex
                notifyAndPersistPosition(
                    nextIndex,
                    TtsState.PLAYING,
                    subChunkIndex = 0,
                    wasActivelyPlaying = true,
                    interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
                )
                speakCurrentParagraph(0)
            } else {
                handleChapterEnd()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in handleUtteranceDone", e)
            triggerEngineRecovery(resumeOnReady = true, reason = "Exception in handleUtteranceDone: ${e.message}")
        }
    }

    private fun getResolvedChapterTransitionProvider(): ChapterTransitionProvider? {
        chapterTransitionProvider?.let { return it }
        val app = context.applicationContext as? com.example.TranslatorApplication
        if (app != null) {
            val provider = DefaultChapterTransitionProvider(app) { app.database }
            chapterTransitionProvider = provider
            return provider
        }
        return null
    }

    private fun handleChapterEnd() {
        activeUtteranceId = null
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()

        if (!_autoAdvanceChapter.value || _ttsState.value != TtsState.PLAYING) {
            stop()
            return
        }

        val provider = getResolvedChapterTransitionProvider()
        if (provider != null) {
            val bookId = _mediaMetadata.value.bookId
            val chapterId = _mediaMetadata.value.chapterId
            if (bookId.isBlank() || chapterId.isBlank()) {
                try {
                    if (onChapterComplete != null) {
                        onChapterComplete?.invoke()
                    } else {
                        stop()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Error during chapter end with blank IDs", e)
                    stop()
                }
                return
            }

            val currentEpoch = ++playbackSessionEpoch
            isAdvancingChapter = true

            launchSafe("handleChapterEnd") {
                val nextData = try {
                    provider.getNextChapterContent(bookId, chapterId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "Error loading next chapter content", e)
                    null
                }

                if (playbackSessionEpoch != currentEpoch || _ttsState.value != TtsState.PLAYING || !isAdvancingChapter) {
                    isAdvancingChapter = false
                    return@launchSafe
                }

                if (nextData != null && nextData.paragraphs.isNotEmpty()) {
                    setChapterAndParagraphs(
                        chapterId = nextData.nextChapterId,
                        newParagraphs = nextData.paragraphs,
                        continuePlaying = true,
                        startIndex = 0,
                        startSubChunk = 0,
                        bookId = bookId,
                        novelTitle = _mediaMetadata.value.novelTitle,
                        chapterTitle = nextData.nextChapterTitle,
                        chapterOrder = nextData.nextChapterOrder
                    )
                    try {
                        onChapterComplete?.invoke()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error invoking onChapterComplete", e)
                    }
                } else {
                    // End of novel or no speakable content in next chapter
                    stop()
                    notifyAndPersistPosition(
                        _currentParagraphIndex.value,
                        TtsState.STOPPED,
                        subChunkIndex = 0,
                        wasActivelyPlaying = false,
                        interruptionReason = TtsInterruptionReason.NATURALLY_FINISHED
                    )
                    try {
                        onChapterComplete?.invoke()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error invoking onChapterComplete", e)
                    }
                }
                isAdvancingChapter = false
            }
        } else {
            try {
                if (onChapterComplete != null) {
                    onChapterComplete?.invoke()
                } else {
                    stop()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Error during chapter end fallback", e)
                stop()
            }
        }
    }

    private fun handleUtteranceError(utteranceId: String?, errorCode: Int) {
        try {
            if (_ttsState.value != TtsState.PLAYING) return
            if (utteranceId == null || utteranceId != activeUtteranceId) return
            val genPrefix = "utt_g${engineGeneration}_"
            if (!utteranceId.startsWith(genPrefix)) {
                Log.w(TAG, "Discarding onError from stale engine generation: $utteranceId (current=$engineGeneration)")
                return
            }
            activeUtteranceId = null
            Log.w(TAG, "Utterance error for $utteranceId, code=$errorCode")
            when (errorCode) {
                TextToSpeech.ERROR_SERVICE -> {
                    Log.w(TAG, "TTS service crashed/disconnected (ERROR_SERVICE); initiating engine recovery")
                    triggerEngineRecovery(resumeOnReady = true, reason = "TTS service disconnected (ERROR_SERVICE)")
                }
                TextToSpeech.ERROR_SYNTHESIS -> {
                    Log.w(TAG, "Speech synthesis error (ERROR_SYNTHESIS) for paragraph ${_currentParagraphIndex.value}, advancing to next paragraph...")
                    advanceToNextParagraphOnError()
                }
                TextToSpeech.ERROR_INVALID_REQUEST -> {
                    Log.w(TAG, "Utterance ERROR_INVALID_REQUEST; triggering engine recovery")
                    triggerEngineRecovery(resumeOnReady = true, reason = "Utterance ERROR_INVALID_REQUEST")
                }
                TextToSpeech.ERROR_NOT_INSTALLED_YET -> {
                    _errorMessage.value = "Voice data is not installed yet"
                    _ttsState.value = TtsState.ERROR
                    audioFocusManager.abandonAudioFocus()
                    notifyAndPersistPosition(_currentParagraphIndex.value, TtsState.ERROR)
                }
                TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> {
                    _errorMessage.value = "Voice requires network connection"
                    _ttsState.value = TtsState.ERROR
                    audioFocusManager.abandonAudioFocus()
                    notifyAndPersistPosition(_currentParagraphIndex.value, TtsState.ERROR)
                }
                else -> {
                    Log.w(TAG, "Speech synthesis error ($errorCode) for paragraph ${_currentParagraphIndex.value}, advancing to next paragraph...")
                    advanceToNextParagraphOnError()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Exception in handleUtteranceError", e)
        }
    }

    private fun advanceToNextParagraphOnError() {
        activeUtteranceId = null
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        val bookId = _mediaMetadata.value.bookId.ifBlank { null }
        var nextIndex = _currentParagraphIndex.value + 1
        while (nextIndex < paragraphs.size - 1 && textProcessor.process(paragraphs[nextIndex].trim(), bookId).isBlank()) {
            nextIndex++
        }
        if (nextIndex < paragraphs.size) {
            _currentParagraphIndex.value = nextIndex
            notifyAndPersistPosition(
                nextIndex,
                TtsState.PLAYING,
                subChunkIndex = 0,
                wasActivelyPlaying = true,
                interruptionReason = TtsInterruptionReason.UNEXPECTED_INTERRUPTION
            )
            speakCurrentParagraph(0)
        } else {
            handleChapterEnd()
        }
    }

    /**
     * Releases TTS resources cleanly.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        isEngineReady.set(false)
        isRecovering.set(false)
        playbackSessionEpoch++
        engineGeneration++
        activeUtteranceId = null
        recoveryJob?.cancel()
        recoveryJob = null
        audioFocusManager.release()
        teardownBrokenEngine()
        _ttsState.value = TtsState.STOPPED
    }
}
