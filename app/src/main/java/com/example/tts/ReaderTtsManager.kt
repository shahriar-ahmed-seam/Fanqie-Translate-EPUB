package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    ERROR
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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val clientFactory: ((TextToSpeech.OnInitListener) -> TextToSpeechClient?)? = null,
    val textProcessor: com.example.tts.rule.TtsTextProcessor =
        (context.applicationContext as? com.example.TranslatorApplication)?.ttsTextProcessor ?: com.example.tts.rule.TtsTextProcessor()
) : TextToSpeech.OnInitListener {

    private val TAG = "ReaderTtsManager"

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

    private var paragraphs: List<String> = emptyList()
    private var currentChapterId: String = ""
    private var utteranceSeq: Long = 0L
    private var activeUtteranceId: String? = null
    private val isInitializing = java.util.concurrent.atomic.AtomicBoolean(false)
    private var isReleased = false
    private var wasPlayingBeforeBackground = false
    var savedVoiceId: String? = null
    private var pendingInitCallback: (() -> Unit)? = null

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
        // We notify position listeners so state is saved.
        onPositionChanged?.invoke(_currentParagraphIndex.value, _ttsState.value)
    }

    fun onAppForegrounded(autoResume: Boolean = true) {
        // UI reattaches seamlessly to ongoing playback.
    }

    fun reinitialize(onSuccess: (() -> Unit)? = null) {
        if (isReleased) return
        try {
            activeUtteranceId = null
            ttsClient?.stop()
            ttsClient?.shutdown()
            nativeTts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning up before reinitialization", e)
        } finally {
            ttsClient = null
            nativeTts = null
            isInitializing.set(false)
        }
        initializeEngine(onSuccess)
    }

    private fun initializeEngine(onSuccess: (() -> Unit)? = null) {
        if (isReleased) return
        if (!isInitializing.compareAndSet(false, true)) return

        if (onSuccess != null) {
            pendingInitCallback = onSuccess
        }

        _ttsState.value = TtsState.INITIALIZING
        if (_errorMessage.value == null) {
            _errorMessage.value = null
        }

        try {
            if (clientFactory != null) {
                ttsClient = clientFactory.invoke(this)
            } else {
                nativeTts?.shutdown()
                nativeTts = TextToSpeech(context.applicationContext, this)
            }
        } catch (e: Exception) {
            isInitializing.set(false)
            Log.e(TAG, "Failed to instantiate Android TextToSpeech engine", e)
            _errorMessage.value = "Speech engine unavailable"
            _ttsState.value = TtsState.ERROR
        }
    }

    override fun onInit(status: Int) {
        isInitializing.set(false)
        if (isReleased) return

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
                            scope.launch {
                                handleUtteranceDone(utteranceId)
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            onError(utteranceId, -1)
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            if (isReleased) return
                            scope.launch {
                                handleUtteranceError(utteranceId, errorCode)
                            }
                        }
                    })

                    // Query and populate available voices
                    populateVoices(client)

                    // Apply speech rate
                    client.setSpeechRate(_speechRate.value)

                    _ttsState.value = TtsState.IDLE
                    _errorMessage.value = null
                    Log.i(TAG, "Android TTS engine initialized successfully.")

                    pendingInitCallback?.invoke()
                    pendingInitCallback = null
                } else {
                    _errorMessage.value = "Speech engine unavailable"
                    _ttsState.value = TtsState.ERROR
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error configuring TTS engine", e)
                _errorMessage.value = "Speech configuration failed"
                _ttsState.value = TtsState.ERROR
            }
        } else {
            Log.e(TAG, "TTS initialization failed with code: $status")
            _errorMessage.value = "Speech engine unavailable"
            _ttsState.value = TtsState.ERROR
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
     * Updates the chapter ID and paragraphs.
     * Optionally continues playing immediately from the specified start index.
     */
    fun setChapterAndParagraphs(
        chapterId: String,
        newParagraphs: List<String>,
        continuePlaying: Boolean = false,
        startIndex: Int = 0,
        bookId: String = _mediaMetadata.value.bookId,
        novelTitle: String = _mediaMetadata.value.novelTitle,
        chapterTitle: String = _mediaMetadata.value.chapterTitle,
        chapterOrder: Int = _mediaMetadata.value.chapterOrder
    ) {
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

        currentSubChunkIndex = 0
        currentSubChunks = emptyList()

        if (continuePlaying && paragraphs.isNotEmpty() && _isTtsEnabled.value) {
            play(_currentParagraphIndex.value)
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
     * Starts playback from given paragraph index or current index.
     * Previously playing speech is guaranteed to stop before starting the new paragraph.
     */
    fun play(startIndex: Int? = null) {
        if (isReleased) return
        if (!_isTtsEnabled.value) {
            Log.w(TAG, "Cannot play while TTS is disabled")
            return
        }
        if (_ttsState.value == TtsState.INITIALIZING || _ttsState.value == TtsState.ERROR) {
            Log.w(TAG, "Cannot play while in state: ${_ttsState.value}")
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
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()

        _ttsState.value = TtsState.PLAYING
        try {
            val meta = _mediaMetadata.value
            TtsPlaybackService.start(context.applicationContext, meta.bookId, meta.chapterId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to start TtsPlaybackService", e)
        }
        onPlaybackStarted?.invoke()
        onPositionChanged?.invoke(_currentParagraphIndex.value, TtsState.PLAYING)
        speakCurrentParagraph(0)
    }

    /**
     * Pauses the current playback, retaining paragraph position.
     */
    fun pause() {
        if (isReleased) return
        if (_ttsState.value == TtsState.PLAYING) {
            activeUtteranceId = null
            try {
                ttsClient?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping TTS on pause", e)
            }
            _ttsState.value = TtsState.PAUSED
            onPositionChanged?.invoke(_currentParagraphIndex.value, TtsState.PAUSED)
        }
    }

    /**
     * Resumes playback from the paused paragraph position without resetting processed subchunk position.
     */
    fun resume() {
        if (isReleased) return
        if (_ttsState.value == TtsState.PAUSED) {
            _ttsState.value = TtsState.PLAYING
            try {
                val meta = _mediaMetadata.value
                TtsPlaybackService.start(context.applicationContext, meta.bookId, meta.chapterId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to start TtsPlaybackService", e)
            }
            onPlaybackStarted?.invoke()
            onPositionChanged?.invoke(_currentParagraphIndex.value, TtsState.PLAYING)
            speakCurrentParagraph(currentSubChunkIndex)
        }
    }

    /**
     * Stops playback completely and resets state to STOPPED.
     */
    fun stop() {
        if (isReleased) return
        activeUtteranceId = null
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        try {
            ttsClient?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS", e)
        }
        _ttsState.value = TtsState.STOPPED
        onPositionChanged?.invoke(_currentParagraphIndex.value, TtsState.STOPPED)
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
        onPositionChanged?.invoke(_currentParagraphIndex.value, _ttsState.value)

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
            onPositionChanged?.invoke(_currentParagraphIndex.value, _ttsState.value)
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
            onPositionChanged?.invoke(index, TtsState.STOPPED)
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
                onPositionChanged?.invoke(nextIndex, TtsState.PLAYING)
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
                _errorMessage.value = "Speech engine unavailable"
                _ttsState.value = TtsState.ERROR
                reinitialize()
                return
            }

            client.setSpeechRate(_speechRate.value)
            _selectedVoice.value?.voice?.let { client.setVoice(it) }

            val utteranceId = "utt_${currentChapterId}_${index}_${currentSubChunkIndex}_${++utteranceSeq}"
            activeUtteranceId = utteranceId

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }

            val result = client.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "speak() returned failure code: $result")
                if (result == TextToSpeech.ERROR_INVALID_REQUEST || result == TextToSpeech.ERROR_SERVICE) {
                    _errorMessage.value = "Speech engine busy. Reconnecting..."
                    _ttsState.value = TtsState.ERROR
                    reinitialize()
                } else {
                    _errorMessage.value = "Speech playback encountered an issue"
                    _ttsState.value = TtsState.ERROR
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during speakCurrentParagraph", e)
            _errorMessage.value = "Speech playback encountered an issue"
            _ttsState.value = TtsState.ERROR
        }
    }

    private fun handleUtteranceDone(utteranceId: String?) {
        // Requirements: Never repeat a paragraph. Never skip a paragraph.
        if (_ttsState.value != TtsState.PLAYING) return
        if (utteranceId == null || utteranceId != activeUtteranceId) {
            // Outdated, canceled, or duplicate callback - safely discard
            return
        }
        activeUtteranceId = null

        // If there are remaining sub-chunks for a long paragraph, speak next sub-chunk
        if (currentSubChunkIndex < currentSubChunks.size - 1) {
            currentSubChunkIndex++
            val textToSpeak = currentSubChunks.getOrNull(currentSubChunkIndex) ?: ""
            val nextUtteranceId = "utt_${currentChapterId}_${_currentParagraphIndex.value}_${currentSubChunkIndex}_${++utteranceSeq}"
            activeUtteranceId = nextUtteranceId
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            ttsClient?.speak(textToSpeak, TextToSpeech.QUEUE_FLUSH, params, nextUtteranceId)
            return
        }

        currentSubChunkIndex = 0
        currentSubChunks = emptyList()

        val bookId = _mediaMetadata.value.bookId.ifBlank { null }
        var nextIndex = _currentParagraphIndex.value + 1
        while (nextIndex < paragraphs.size - 1 && textProcessor.process(paragraphs[nextIndex].trim(), bookId).isBlank()) {
            nextIndex++
        }
        if (nextIndex < paragraphs.size) {
            _currentParagraphIndex.value = nextIndex
            onPositionChanged?.invoke(nextIndex, TtsState.PLAYING)
            speakCurrentParagraph(0)
        } else {
            handleChapterEnd()
        }
    }

    private fun handleChapterEnd() {
        activeUtteranceId = null
        currentSubChunkIndex = 0
        currentSubChunks = emptyList()
        if (_autoAdvanceChapter.value && onChapterComplete != null) {
            onChapterComplete?.invoke()
        } else {
            stop()
        }
    }

    private fun handleUtteranceError(utteranceId: String?, errorCode: Int) {
        if (_ttsState.value == TtsState.PLAYING && utteranceId == activeUtteranceId) {
            activeUtteranceId = null
            Log.w(TAG, "Utterance error for $utteranceId, code=$errorCode")
            when (errorCode) {
                TextToSpeech.ERROR_INVALID_REQUEST, TextToSpeech.ERROR_SERVICE -> {
                    _errorMessage.value = "Speech engine busy. Reconnecting..."
                    _ttsState.value = TtsState.ERROR
                    reinitialize()
                }
                TextToSpeech.ERROR_NOT_INSTALLED_YET -> {
                    _errorMessage.value = "Voice data is not installed yet"
                    _ttsState.value = TtsState.ERROR
                }
                TextToSpeech.ERROR_NETWORK, TextToSpeech.ERROR_NETWORK_TIMEOUT -> {
                    _errorMessage.value = "Voice requires network connection"
                    _ttsState.value = TtsState.ERROR
                }
                else -> {
                    _errorMessage.value = "Speech playback encountered an issue"
                    _ttsState.value = TtsState.ERROR
                }
            }
        }
    }

    /**
     * Releases TTS resources cleanly.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        try {
            ttsClient?.stop()
            ttsClient?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing TTS client", e)
        } finally {
            ttsClient = null
            nativeTts = null
            _ttsState.value = TtsState.STOPPED
        }
    }
}
