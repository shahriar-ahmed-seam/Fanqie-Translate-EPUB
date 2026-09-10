package com.example.tts

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale
import com.example.data.repository.SettingsRepository
import com.example.tts.rule.TtsRule
import com.example.tts.rule.TtsRuleType
import com.example.tts.rule.TtsTextProcessor

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderTtsManagerTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class FakeTtsClient : TextToSpeechClient {
        val spokenTexts = mutableListOf<String>()
        var lastUtteranceId: String? = null
        var isStopped = false
        var currentSpeechRate = 1.0f
        var currentVoice: Voice? = null
        var mockVoices: Set<Voice>? = null
        var listener: UtteranceProgressListener? = null
        var isShutdown = false
        var returnFailureOnSpeak = false

        override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            if (returnFailureOnSpeak) return TextToSpeech.ERROR
            spokenTexts.add(text.toString())
            lastUtteranceId = utteranceId
            isStopped = false
            return TextToSpeech.SUCCESS
        }

        override fun stop(): Int {
            isStopped = true
            return TextToSpeech.SUCCESS
        }

        override fun setSpeechRate(speechRate: Float): Int {
            currentSpeechRate = speechRate
            return TextToSpeech.SUCCESS
        }

        override fun setVoice(voice: Voice): Int {
            currentVoice = voice
            return TextToSpeech.SUCCESS
        }

        override fun getVoices(): Set<Voice>? = mockVoices

        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }

        override fun shutdown() {
            isShutdown = true
        }
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testInitializationSuccessTransitionsToIdle() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )

        assertEquals(TtsState.INITIALIZING, manager.ttsState.value)

        // Trigger onInit success
        manager.onInit(TextToSpeech.SUCCESS)

        assertEquals(TtsState.IDLE, manager.ttsState.value)
        assertNull(manager.errorMessage.value)
        assertNotNull(fakeClient.listener)
    }

    @Test
    fun testInitializationFailureTransitionsToErrorWithoutCrashing() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )

        // Trigger onInit error
        manager.onInit(TextToSpeech.ERROR)

        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue(manager.errorMessage.value!!.contains("unavailable"))
    }

    @Test
    fun testPlayPauseResumeAndStop() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val sampleParagraphs = listOf("Paragraph 1: Once upon a time.", "Paragraph 2: The story continues.", "Paragraph 3: The end.")
        manager.setParagraphs(sampleParagraphs)

        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals(TtsState.IDLE, manager.ttsState.value)

        // Play
        manager.play()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(1, fakeClient.spokenTexts.size)
        assertEquals("Paragraph 1: Once upon a time.", fakeClient.spokenTexts.first())

        // Pause
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertTrue(fakeClient.isStopped)
        assertEquals(0, manager.currentParagraphIndex.value) // Position retained

        // Resume
        manager.resume()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(2, fakeClient.spokenTexts.size)
        assertEquals("Paragraph 1: Once upon a time.", fakeClient.spokenTexts.last())

        // Stop
        manager.stop()
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
        assertTrue(fakeClient.isStopped)
    }

    @Test
    fun testParagraphNavigationAndBoundaries() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val paragraphs = listOf("Para 0", "Para 1", "Para 2")
        manager.setParagraphs(paragraphs)

        // Start playing at index 0
        manager.play(0)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Para 0", fakeClient.spokenTexts.last())

        // Previous paragraph while at 0 should stay at 0
        manager.previousParagraph()
        assertEquals(0, manager.currentParagraphIndex.value)

        // Next paragraph moves to 1 and speaks
        manager.nextParagraph()
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Para 1", fakeClient.spokenTexts.last())

        // Next paragraph moves to 2 and speaks
        manager.nextParagraph()
        assertEquals(2, manager.currentParagraphIndex.value)
        assertEquals("Para 2", fakeClient.spokenTexts.last())

        // Next paragraph when at the end stops playback and does NOT go out of bounds
        manager.nextParagraph()
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
        assertEquals(2, manager.currentParagraphIndex.value)

        // Previous moves back to 1
        manager.previousParagraph()
        assertEquals(1, manager.currentParagraphIndex.value)
    }

    @Test
    fun testUtteranceProgressionThroughParagraphs() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val paragraphs = listOf("First chapter line", "Second chapter line")
        manager.setParagraphs(paragraphs)

        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
        val firstUtteranceId = fakeClient.lastUtteranceId

        // Simulate utterance completion for first line
        fakeClient.listener?.onDone(firstUtteranceId)
        testScheduler.advanceUntilIdle()

        // Should automatically advance to second line
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Second chapter line", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        val secondUtteranceId = fakeClient.lastUtteranceId

        // Simulate utterance completion for second (final) line
        fakeClient.listener?.onDone(secondUtteranceId)
        testScheduler.advanceUntilIdle()

        // At end of chapter: stops without auto-advancing chapter
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
    }

    @Test
    fun testSpeechRateClamping() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setSpeechRate(1.5f)
        assertEquals(1.5f, manager.speechRate.value, 0.01f)
        assertEquals(1.5f, fakeClient.currentSpeechRate, 0.01f)

        // Test lower bound clamping (0.5f)
        manager.setSpeechRate(0.1f)
        assertEquals(0.5f, manager.speechRate.value, 0.01f)
        assertEquals(0.5f, fakeClient.currentSpeechRate, 0.01f)

        // Test upper bound clamping (2.5f)
        manager.setSpeechRate(5.0f)
        assertEquals(2.5f, manager.speechRate.value, 0.01f)
        assertEquals(2.5f, fakeClient.currentSpeechRate, 0.01f)
    }

    @Test
    fun testReleaseShutsDownClientCleanly() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.release()
        assertTrue(fakeClient.isShutdown)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)

        // Subsequent calls are no-ops
        manager.play(0)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
    }

    @Test
    fun testNeverCrashesOnSpeechFailure() {
        val fakeClient = FakeTtsClient().apply {
            returnFailureOnSpeak = true
        }
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Test paragraph"))

        // Should transition to RECOVERING/ERROR instead of throwing or crashing
        manager.play()
        assertTrue(manager.ttsState.value == TtsState.RECOVERING || manager.ttsState.value == TtsState.ERROR)
        assertNotNull(manager.errorMessage.value)
    }

    @Test
    fun testDoubleTapStopsPreviousSpeechAndStartsNewParagraph() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        val paragraphs = listOf("Para 0", "Para 1", "Para 2", "Para 3")
        manager.setParagraphs(paragraphs)

        // Start playing paragraph 0
        manager.play(0)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Para 0", fakeClient.spokenTexts.last())

        // Double-tap paragraph 2: previously playing speech must stop before starting new paragraph
        manager.play(2)
        assertEquals(2, manager.currentParagraphIndex.value)
        assertEquals("Para 2", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
    }

    @Test
    fun testNeverRepeatOrSkipOnDuplicateOnDone() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        val paragraphs = listOf("Line 0", "Line 1", "Line 2")
        manager.setParagraphs(paragraphs)

        manager.play(0)
        assertEquals(0, manager.currentParagraphIndex.value)
        val firstUtteranceId = fakeClient.lastUtteranceId

        // First onDone callback advances to Line 1
        fakeClient.listener?.onDone(firstUtteranceId)
        testScheduler.advanceUntilIdle()
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Line 1", fakeClient.spokenTexts.last())

        // Duplicate onDone callback with the same old utteranceId MUST BE IGNORED
        fakeClient.listener?.onDone(firstUtteranceId)
        testScheduler.advanceUntilIdle()

        // Still at Line 1 (never skip to Line 2!)
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals(2, fakeClient.spokenTexts.size)
    }

    @Test
    fun testAutoAdvanceChapterTriggeredAtChapterEndWhenEnabled() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        var chapterCompleteTriggered = false
        manager.onChapterComplete = {
            chapterCompleteTriggered = true
        }

        manager.setParagraphs(listOf("Only Line"))
        manager.play(0)
        val utteranceId = fakeClient.lastUtteranceId

        fakeClient.listener?.onDone(utteranceId)
        testScheduler.advanceUntilIdle()

        assertTrue(chapterCompleteTriggered)
    }

    @Test
    fun testAutoAdvanceChapterStopsSafelyWhenDisabled() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(false)

        var chapterCompleteTriggered = false
        manager.onChapterComplete = {
            chapterCompleteTriggered = true
        }

        manager.setParagraphs(listOf("Only Line"))
        manager.play(0)
        val utteranceId = fakeClient.lastUtteranceId

        fakeClient.listener?.onDone(utteranceId)
        testScheduler.advanceUntilIdle()

        assertFalse(chapterCompleteTriggered)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
    }

    @Test
    fun testSetChapterAndParagraphsSynchronizesPlayback() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Seamless transition with continuePlaying = true
        val chapter2Paras = listOf("Chapter 2 Line 0", "Chapter 2 Line 1")
        manager.setChapterAndParagraphs(
            chapterId = "ch_2",
            newParagraphs = chapter2Paras,
            continuePlaying = true,
            startIndex = 0
        )

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Chapter 2 Line 0", fakeClient.spokenTexts.last())
    }

    @Test
    fun testAppBackgroundedAndForegroundedRestoresPlaybackState() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0", "Para 1"))

        // Start playing
        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // App is backgrounded - TTS playback continues uninterrupted
        manager.onAppBackgrounded()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertFalse(fakeClient.isStopped)

        // App is foregrounded
        manager.onAppForegrounded(autoResume = true)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)

        // Pause explicitly
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        manager.onAppBackgrounded()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        // Foregrounding when paused stays paused
        manager.onAppForegrounded(autoResume = false)
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
    }

    @Test
    fun testTtsMasterEnableAndDisable() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Line 1", "Line 2"))

        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Disable TTS master switch while playing
        manager.setTtsEnabled(false)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
        assertTrue(fakeClient.isStopped)
        assertFalse(manager.isTtsEnabled.value)

        // Play requests while disabled are ignored
        manager.play(1)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)

        // Re-enabling allows playback again
        manager.setTtsEnabled(true)
        assertTrue(manager.isTtsEnabled.value)
        manager.play(1)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(1, manager.currentParagraphIndex.value)
    }

    @Test
    fun testPlaybackSpeedChangeWhilePlayingRestartsCurrentParagraphSafely() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Speed test paragraph"))

        manager.play(0)
        assertEquals(1, fakeClient.spokenTexts.size)

        // Changing speech rate while playing applies clamped rate and speaks current paragraph
        manager.setSpeechRate(1.75f)
        assertEquals(1.75f, fakeClient.currentSpeechRate, 0.01f)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(2, fakeClient.spokenTexts.size)
        assertEquals("Speed test paragraph", fakeClient.spokenTexts.last())
    }

    @Test
    fun testVoiceChangeWhilePlayingAppliesSafely() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Voice test paragraph"))

        val voiceA = TtsVoiceInfo(id = "en-us-x-sfg", displayName = "English Voice", locale = Locale.US, isNetworkRequired = false)
        manager.selectVoice(voiceA)
        assertEquals("en-us-x-sfg", manager.savedVoiceId)
        assertEquals(voiceA, manager.selectedVoice.value)

        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        val speakCountBefore = fakeClient.spokenTexts.size

        val voiceB = TtsVoiceInfo(id = "en-gb-x-rjs", displayName = "British Voice", locale = Locale.UK, isNetworkRequired = false)
        manager.selectVoice(voiceB)
        assertEquals("en-gb-x-rjs", manager.savedVoiceId)
        assertEquals(voiceB, manager.selectedVoice.value)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(speakCountBefore + 1, fakeClient.spokenTexts.size)
    }

    @Test
    fun testReinitializationAfterInvalidRequestOrServiceDisconnect() {
        var createCount = 0
        var activeClient: FakeTtsClient? = null
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = {
                createCount++
                val client = FakeTtsClient()
                activeClient = client
                client
            }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        assertEquals(1, createCount)
        assertEquals(TtsState.IDLE, manager.ttsState.value)

        // Reinitialize safely
        manager.reinitialize()
        manager.onInit(TextToSpeech.SUCCESS)
        assertEquals(2, createCount)
        assertEquals(TtsState.IDLE, manager.ttsState.value)
    }

    @Test
    fun testErrorMessageContainsNoTechnicalJargonOrRawCodes() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Test line"))
        manager.play(0)

        val utteranceId = fakeClient.lastUtteranceId

        // Test ERROR_INVALID_REQUEST
        fakeClient.listener?.onError(utteranceId, TextToSpeech.ERROR_INVALID_REQUEST)
        testScheduler.advanceUntilIdle()

        val msg1 = manager.errorMessage.value
        assertNotNull(msg1)
        assertFalse(msg1!!.contains("code", ignoreCase = true))
        assertFalse(msg1.contains("ERROR_", ignoreCase = true))
        assertFalse(msg1.contains("-8"))

        // Re-initialize and test ERROR_NOT_INSTALLED_YET
        manager.onInit(TextToSpeech.SUCCESS)
        manager.play(0)
        val utt2 = fakeClient.lastUtteranceId
        fakeClient.listener?.onError(utt2, TextToSpeech.ERROR_NOT_INSTALLED_YET)
        testScheduler.advanceUntilIdle()

        val msg2 = manager.errorMessage.value
        assertNotNull(msg2)
        assertFalse(msg2!!.contains("code", ignoreCase = true))
        assertFalse(msg2.contains("ERROR_", ignoreCase = true))
        assertFalse(msg2.contains("-9"))
    }

    @Test
    fun testSetChapterWhilePausedResetsStateToIdleSafely() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs("ch_1", listOf("Chapter 1 Para 1"), continuePlaying = false)
        manager.play(0)
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        // Switch to ch_2 manually without continuing
        manager.setChapterAndParagraphs("ch_2", listOf("Chapter 2 Para 1"), continuePlaying = false)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
        assertTrue(fakeClient.isStopped)
    }

    @Test
    fun testLongParagraphChunkingAndSequentialPlayback() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Build a 4000-character paragraph
        val longPara = "This is a very long paragraph sentence that will be repeated many times to exceed the chunk limit. ".repeat(40)
        assertTrue(longPara.length > 3500)

        manager.setChapterAndParagraphs("ch_long", listOf(longPara, "Second paragraph"), continuePlaying = false)
        manager.play(0)

        // First subchunk should be spoken
        assertEquals(1, fakeClient.spokenTexts.size)
        assertTrue(fakeClient.spokenTexts[0].length <= 2600)
        val firstUttId = fakeClient.lastUtteranceId

        // Complete first subchunk
        fakeClient.listener?.onDone(firstUttId)
        testScheduler.advanceUntilIdle()

        // Second subchunk should be spoken without changing paragraph index
        assertEquals(2, fakeClient.spokenTexts.size)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        val secondUttId = fakeClient.lastUtteranceId

        // Complete second subchunk
        fakeClient.listener?.onDone(secondUttId)
        testScheduler.advanceUntilIdle()

        // Now it moves to the second paragraph
        assertEquals(3, fakeClient.spokenTexts.size)
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Second paragraph", fakeClient.spokenTexts[2])
    }

    @Test
    fun testRapidPlaybackNavigationDoesNotRepeatOrSkip() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val paras = (1..10).map { "Paragraph $it" }
        manager.setChapterAndParagraphs("ch_rapid", paras, continuePlaying = false)

        manager.play(0)
        val utt0 = fakeClient.lastUtteranceId

        // Rapid next, next, prev
        manager.nextParagraph()
        manager.nextParagraph()
        manager.previousParagraph()

        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Stale callback from utt0 arrives
        fakeClient.listener?.onDone(utt0)
        testScheduler.advanceUntilIdle()

        // Stale callback must NOT have advanced the paragraph!
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
    }

    @Test
    fun testEndOfNovelTransitionsToStoppedState() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setChapterAndParagraphs("ch_end", listOf("Last paragraph"), continuePlaying = false)
        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        var chapterCompleteInvoked = false
        manager.onChapterComplete = {
            chapterCompleteInvoked = true
            // No next chapter -> stop
            manager.stop()
        }

        val lastUtt = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(lastUtt)
        testScheduler.advanceUntilIdle()

        assertTrue(chapterCompleteInvoked)
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
    }

    @Test
    fun testVoiceFallbackWhenSelectedVoiceMissing() {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Attempting to select a nonexistent voice ID
        manager.selectVoiceById("non_existent_voice_id_xyz")

        // Should not crash, and should keep manager in valid state
        assertNotEquals(TtsState.ERROR, manager.ttsState.value)
        assertNull(manager.errorMessage.value)
    }

    @Test
    fun testTextProcessorAppliesSkipAndReplaceRulesBeforeSpeaking() {
        val fakeClient = FakeTtsClient()
        val processor = TtsTextProcessor()
        processor.setRules(listOf(
            TtsRule(pattern = "Tomato", ruleType = TtsRuleType.SKIP, wholeWord = true),
            TtsRule(pattern = "cultivation", replacement = "training", ruleType = TtsRuleType.REPLACE)
        ))
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient },
            textProcessor = processor
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val originalText = "Tomato brings deep cultivation techniques."
        val inputList = listOf(originalText)
        manager.setParagraphs(inputList)
        manager.play(0)

        // Android TTS receives processed text
        assertEquals(1, fakeClient.spokenTexts.size)
        assertEquals("brings deep training techniques.", fakeClient.spokenTexts.first())

        // Input text remains 100% untouched
        assertEquals(originalText, inputList[0])
    }

    @Test
    fun testParagraphEntirelySkippedByRuleAutoAdvances() {
        val fakeClient = FakeTtsClient()
        val processor = TtsTextProcessor()
        processor.setRules(listOf(
            TtsRule(pattern = "\\[.*?\\]", ruleType = TtsRuleType.SKIP_REGEX, isRegex = true)
        ))
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient },
            textProcessor = processor
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Paragraph 0 is an ad that regex skips completely
        manager.setParagraphs(listOf("[Advertisement: Download our novel app]", "Chapter 1: The Adventure Begins"))
        manager.play(0)

        // Should automatically advance to paragraph 1
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals(1, fakeClient.spokenTexts.size)
        assertEquals("Chapter 1: The Adventure Begins", fakeClient.spokenTexts.first())
    }

    @Test
    fun testPreviousParagraphSkipsParagraphsOmittedByRules() {
        val fakeClient = FakeTtsClient()
        val processor = TtsTextProcessor()
        processor.setRules(listOf(
            TtsRule(pattern = "SkipMe", ruleType = TtsRuleType.SKIP, wholeWord = true)
        ))
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient },
            textProcessor = processor
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setParagraphs(listOf("Valid paragraph zero", "SkipMe", "Valid paragraph two"))
        manager.play(2)
        assertEquals(2, manager.currentParagraphIndex.value)

        manager.previousParagraph()

        // Should skip index 1 because it becomes blank, landing on index 0
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Valid paragraph zero", fakeClient.spokenTexts.last())
    }

    @Test
    fun testPauseResumePreservesSubChunkPosition() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Build text > 2500 chars so it splits into 2 chunks
        val sentence = "This is a detailed narrative sentence that occupies space. "
        val longParagraph = sentence.repeat(60) // ~3600 chars
        manager.setParagraphs(listOf(longParagraph))

        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(1, fakeClient.spokenTexts.size)
        val firstChunk = fakeClient.spokenTexts.first()

        // Simulate subchunk 0 completing -> advances to subchunk 1
        val firstUttId = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(firstUttId)
        testScheduler.advanceUntilIdle()

        assertEquals(2, fakeClient.spokenTexts.size)
        val secondChunk = fakeClient.spokenTexts.last()
        assertNotEquals(firstChunk, secondChunk)

        // Pause
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        // Resume: must resume speaking the second chunk, NOT restarting from chunk 0
        manager.resume()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(3, fakeClient.spokenTexts.size)
        assertEquals(secondChunk, fakeClient.spokenTexts.last())
    }

    @Test
    fun testDynamicRuleUpdateAppliesToNextParagraphWithoutBreakingCurrent() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val processor = TtsTextProcessor()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient },
            textProcessor = processor
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setParagraphs(listOf("First paragraph plays unchanged.", "Second paragraph mentions Tomato harvest."))
        manager.play(0)
        assertEquals("First paragraph plays unchanged.", fakeClient.spokenTexts.first())

        // Dynamically add a rule in memory
        processor.setRules(listOf(
            TtsRule(pattern = "Tomato", ruleType = TtsRuleType.SKIP, wholeWord = true)
        ))

        // Complete utterance for paragraph 0
        val utt0 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt0)
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Second paragraph mentions harvest.", fakeClient.spokenTexts.last())
    }

    @Test
    fun testAutoAdvanceToNextChapterViaTransitionProvider() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                return if (currentChapterId == "ch_1") {
                    ChapterTransitionData(
                        nextChapterId = "ch_2",
                        nextChapterTitle = "Chapter 2: The Next Step",
                        nextChapterOrder = 1,
                        paragraphs = listOf("Chapter 2 Line 0", "Chapter 2 Line 1")
                    )
                } else null
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Chapter 1 Line 0", "Chapter 1 Line 1"),
            continuePlaying = false,
            startIndex = 0,
            bookId = "book_123",
            novelTitle = "My Novel",
            chapterTitle = "Chapter 1: The Beginning",
            chapterOrder = 0
        )

        // Start playing Chapter 1
        manager.play(0)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals("Chapter 1 Line 0", fakeClient.spokenTexts.last())

        // Complete line 0
        val utt0 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt0)
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Chapter 1 Line 1", fakeClient.spokenTexts.last())

        // Complete line 1 (end of Chapter 1)
        val utt1 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt1)
        testScheduler.advanceUntilIdle()

        // Verify automatic transition to Chapter 2
        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Chapter 2: The Next Step", manager.mediaMetadata.value.chapterTitle)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("Chapter 2 Line 0", fakeClient.spokenTexts.last())
    }

    @Test
    fun testMultipleConsecutiveChapterAutoAdvancements() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                return when (currentChapterId) {
                    "ch_1" -> ChapterTransitionData("ch_2", "Chapter 2", 1, listOf("Ch 2 Para 0"))
                    "ch_2" -> ChapterTransitionData("ch_3", "Chapter 3", 2, listOf("Ch 3 Para 0"))
                    else -> null
                }
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Para 0"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1",
            novelTitle = "Epic",
            chapterTitle = "Chapter 1",
            chapterOrder = 0
        )

        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals("Ch 1 Para 0", fakeClient.spokenTexts.last())

        // Finish Chapter 1
        val utt1 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt1)
        testScheduler.advanceUntilIdle()

        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals("Ch 2 Para 0", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Finish Chapter 2
        val utt2 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt2)
        testScheduler.advanceUntilIdle()

        assertEquals("ch_3", manager.getCurrentChapterId())
        assertEquals("Ch 3 Para 0", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Finish Chapter 3 (end of novel)
        val utt3 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt3)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.STOPPED, manager.ttsState.value)
    }

    @Test
    fun testEndOfNovelStopsCleanlyWithoutError() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_last",
            newParagraphs = listOf("Final paragraph of novel"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )

        val utt = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.STOPPED, manager.ttsState.value)
        assertNull(manager.errorMessage.value)
    }

    @Test
    fun testPauseBeforeChapterEndDoesNotAutoAdvance() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        var providerCalled = false
        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                providerCalled = true
                return ChapterTransitionData("ch_2", "Chapter 2", 1, listOf("Ch 2 P 0"))
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Only paragraph"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )

        val pendingUtt = fakeClient.lastUtteranceId

        // User pauses
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        // Late callback arrives from engine after pause
        fakeClient.listener?.onDone(pendingUtt)
        testScheduler.advanceUntilIdle()

        // Should not have auto-advanced because state was PAUSED and epoch changed
        assertFalse(providerCalled)
        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
    }

    @Test
    fun testManualChapterChangeWhilePlayingCancelsStaleCallbacks() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Line 0", "Ch 1 Line 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )
        val staleUtt = fakeClient.lastUtteranceId

        // User clicks "Next Chapter" manually
        manager.prepareForChapterChange()
        manager.setChapterAndParagraphs(
            chapterId = "ch_2",
            newParagraphs = listOf("Ch 2 Line 0"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )

        // Now late callback arrives for stale utterance from Ch 1
        fakeClient.listener?.onDone(staleUtt)
        testScheduler.advanceUntilIdle()

        // Still in Ch 2, at line 0, playing Ch 2 Line 0
        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Ch 2 Line 0", fakeClient.spokenTexts.last())
    }

    @Test
    fun testUtteranceErrorSkipsProblematicParagraphAndContinuesPlayback() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Para 0", "Para 1 Problematic", "Para 2 Good"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )

        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Para 0", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Paragraph 0 finishes cleanly
        val utt0 = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(utt0)
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Para 1 Problematic", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Paragraph 1 fails synthesis asynchronously with ERROR_SYNTHESIS
        val utt1 = fakeClient.lastUtteranceId
        fakeClient.listener?.onError(utt1, TextToSpeech.ERROR_SYNTHESIS)
        testScheduler.advanceUntilIdle()

        // It should skip Paragraph 1 without crashing or stopping, advancing to Paragraph 2
        assertEquals(2, manager.currentParagraphIndex.value)
        assertEquals("Para 2 Good", fakeClient.spokenTexts.last())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
    }

    @Test
    fun testAudioFocusLossPausesPlaybackAndGainResumes() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Para 0", "Para 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )

        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Simulate external audio interruption (call or navigation audio focus loss)
        manager.audioFocusManager.abandonAudioFocus()
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        // External audio finishes, focus regained, playback resumes
        manager.resume()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
    }

    @Test
    fun testBoundedEngineReinitializationRetryLimit() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient().apply {
            returnFailureOnSpeak = true
        }
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0", "Para 1"))

        // First failure -> triggers attempt 1
        manager.play()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)
        testScheduler.advanceUntilIdle()

        // Advance reinit 1 -> onInit -> speak fails again
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        // Reinit 2 -> onInit -> speak fails again
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        // Reinit 3 -> onInit -> speak fails again
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        // Exhausted max 3 retries -> stays in ERROR with retry message, no infinite loop
        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertTrue(manager.errorMessage.value?.contains("Tap Play to retry") == true)
    }

    @Test
    fun testRecoverFromErrorRecoversTtsStateAndResumesPlayback() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient().apply {
            returnFailureOnSpeak = true
        }
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0", "Para 1"))
        manager.play()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        // Exhaust retries to reach ERROR state
        testScheduler.advanceUntilIdle()
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.ERROR, manager.ttsState.value)

        // Engine heals
        fakeClient.returnFailureOnSpeak = false

        // User or UI calls recoverFromError
        manager.recoverFromError(resumePlaying = true, startIndex = 0)
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        // Native engine becomes ready
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Para 0", fakeClient.spokenTexts.last())
    }

    @Test
    fun testPlayInErrorStateTriggersAutomaticRecovery() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient().apply {
            returnFailureOnSpeak = true
        }
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0", "Para 1"))
        manager.play()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        // Exhaust retries to reach ERROR state
        testScheduler.advanceUntilIdle()
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.ERROR, manager.ttsState.value)

        // Engine heals
        fakeClient.returnFailureOnSpeak = false

        // Tapping play while in ERROR state automatically initiates recovery
        manager.play(startIndex = 1)
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Para 1", fakeClient.spokenTexts.last())
    }

    @Test
    fun testIntrinsicPositionPersistenceWithoutOnPositionChangedCallback() {
        val repo = SettingsRepository(context)
        repo.clearTtsSessionState()

        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = testScope,
            clientFactory = { fakeClient },
            settingsRepository = repo
        )
        manager.onInit(TextToSpeech.SUCCESS)
        // Ensure onPositionChanged is null
        manager.onPositionChanged = null

        manager.setChapterAndParagraphs(
            chapterId = "chap_int_1",
            newParagraphs = listOf("Para 0", "Para 1", "Para 2"),
            continuePlaying = false,
            startIndex = 0,
            bookId = "book_int_1"
        )

        // Play paragraph 0
        manager.play(0)
        assertEquals(0, repo.getLastReadParagraphIndex("book_int_1", "chap_int_1"))
        assertEquals("chap_int_1", repo.getLastReadChapterId("book_int_1"))

        // Advance to next paragraph
        manager.nextParagraph()
        assertEquals(1, repo.getLastReadParagraphIndex("book_int_1", "chap_int_1"))
        val session = repo.getTtsSessionState()
        assertNotNull(session)
        assertEquals(1, session?.paragraphIndex)
        assertEquals("book_int_1", session?.bookId)
        assertEquals("chap_int_1", session?.chapterId)
    }

    @Test
    fun testChapterTransitionExceptionDoesNotCancelScopeOrCrash() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Faulty transition provider that throws RuntimeException (e.g. SQLite error)
        manager.chapterTransitionProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                throw java.lang.IllegalStateException("Simulated SQLite disk corruption")
            }
            override suspend fun getPreviousChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
        }

        manager.setChapterAndParagraphs(
            chapterId = "chap_1",
            newParagraphs = listOf("Single paragraph"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )

        // Complete the single paragraph -> triggers handleChapterEnd -> throws inside launchSafe
        val uttId = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(uttId)
        testScheduler.advanceUntilIdle()

        // Playback stops gracefully instead of crashing
        assertEquals(TtsState.STOPPED, manager.ttsState.value)

        // Verify scope is STILL active and can play new chapter
        manager.setChapterAndParagraphs(
            chapterId = "chap_2",
            newParagraphs = listOf("New chapter paragraph"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("New chapter paragraph", fakeClient.spokenTexts.last())
    }

    @Test
    fun testStaleCallbackFromOlderEngineGenerationIsIgnored() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        val initialGen = manager.getEngineGeneration()

        manager.setChapterAndParagraphs(
            chapterId = "chap_1",
            newParagraphs = listOf("Paragraph 0", "Paragraph 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_1"
        )
        val oldUtteranceId = fakeClient.lastUtteranceId
        assertNotNull(oldUtteranceId)
        assertTrue(oldUtteranceId!!.startsWith("utt_g${initialGen}_"))

        // Reinitialize engine -> engineGeneration increments
        val newFakeClient = FakeTtsClient()
        manager.reinitialize(resumeOnReady = false)
        manager.onInit(TextToSpeech.SUCCESS)
        assertTrue(manager.getEngineGeneration() > initialGen)

        // Stale callback from old engine generation arrives
        fakeClient.listener?.onDone(oldUtteranceId)
        testScheduler.advanceUntilIdle()

        // Verify it was ignored: paragraph was NOT advanced
        assertEquals(0, manager.currentParagraphIndex.value)
    }

    @Test
    fun testExplicitPauseAndStopPersistCorrectReasonAndFlags() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val repo = SettingsRepository(context)
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient },
            settingsRepository = repo
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setChapterAndParagraphs(
            chapterId = "chap_reason_1",
            newParagraphs = listOf("First paragraph", "Second paragraph"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_reason_1"
        )
        testScheduler.advanceUntilIdle()

        // Actively playing -> UNEXPECTED_INTERRUPTION and wasActivelyPlaying = true
        var session = repo.getTtsSessionState()
        assertNotNull(session)
        assertTrue(session?.wasActivelyPlaying == true)
        assertEquals(TtsInterruptionReason.UNEXPECTED_INTERRUPTION, session?.interruptionReason)
        assertEquals("PLAYING", session?.playbackState)

        // User explicitly pauses
        manager.pause()
        session = repo.getTtsSessionState()
        assertNotNull(session)
        assertFalse(session?.wasActivelyPlaying ?: true)
        assertEquals(TtsInterruptionReason.EXPLICIT_PAUSE, session?.interruptionReason)
        assertEquals("PAUSED", session?.playbackState)

        // User resumes
        manager.resume()
        session = repo.getTtsSessionState()
        assertNotNull(session)
        assertTrue(session?.wasActivelyPlaying ?: false)
        assertEquals(TtsInterruptionReason.UNEXPECTED_INTERRUPTION, session?.interruptionReason)
        assertEquals("PLAYING", session?.playbackState)

        // User explicitly stops
        manager.stop()
        session = repo.getTtsSessionState()
        assertNotNull(session)
        assertFalse(session?.wasActivelyPlaying ?: true)
        assertEquals(TtsInterruptionReason.EXPLICIT_STOP, session?.interruptionReason)
        assertEquals("STOPPED", session?.playbackState)
    }

    @Test
    fun testSubchunkRestorationPreservesExactSubchunk() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val repo = SettingsRepository(context)
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient },
            settingsRepository = repo
        )
        manager.onInit(TextToSpeech.SUCCESS)

        // Create a long paragraph that chunks into at least 2 subchunks
        val part1 = "A".repeat(2400) + ". "
        val part2 = "B".repeat(1000)
        val longParagraph = part1 + part2

        // Set chapter with long paragraph and start playing from subchunk 1
        manager.setChapterAndParagraphs(
            chapterId = "chap_sub_1",
            newParagraphs = listOf(longParagraph),
            continuePlaying = true,
            startIndex = 0,
            startSubChunk = 1,
            bookId = "book_sub_1"
        )
        testScheduler.advanceUntilIdle()

        assertEquals(1, manager.getCurrentSubChunkIndex())
        assertEquals(part2, fakeClient.spokenTexts.last())

        val session = repo.getTtsSessionState()
        assertNotNull(session)
        assertEquals(1, session?.subChunkIndex)
        assertEquals(0, session?.paragraphIndex)
        assertTrue(session?.wasActivelyPlaying == true)
    }

    @Test
    fun testEngineFailureWithServiceErrorTriggersRecoveringAndRecreation() = runTest(testDispatcher) {
        var createCount = 0
        var activeClient: FakeTtsClient? = null
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = {
                createCount++
                val client = FakeTtsClient()
                activeClient = client
                client
            }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        assertEquals(1, createCount)
        assertTrue(manager.isEngineReady())

        // Start playback
        manager.setChapterAndParagraphs(
            chapterId = "chap_recover",
            newParagraphs = listOf("Paragraph 1", "Paragraph 2"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_recover"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        val initialClient = activeClient
        assertNotNull(initialClient)

        // Simulate native TTS service death (ERROR_SERVICE)
        val activeUttId = initialClient?.lastUtteranceId
        assertNotNull(activeUttId)
        initialClient?.listener?.onError(activeUttId, TextToSpeech.ERROR_SERVICE)
        testScheduler.runCurrent()

        // Broken engine was shut down immediately and state moved to RECOVERING
        assertTrue(initialClient?.isShutdown == true)
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)
        assertFalse(manager.isEngineReady())

        // Advance time through exponential backoff
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()

        // New engine instantiated
        assertEquals(2, createCount)
        assertNotSame(initialClient, activeClient)

        // Complete initialization of the new engine
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        // State resumed to PLAYING automatically from preserved position
        assertTrue(manager.isEngineReady())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals("Paragraph 1", activeClient?.spokenTexts?.last())
    }

    @Test
    fun testInitGateBlocksBlindSpeak() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        // Note: onInit NOT called yet!
        assertFalse(manager.isEngineReady())
        assertEquals(TtsState.INITIALIZING, manager.ttsState.value)

        // Attempt to play before initialization gate opens
        manager.setChapterAndParagraphs(
            chapterId = "chap_gate",
            newParagraphs = listOf("Gate Paragraph"),
            continuePlaying = false,
            startIndex = 0
        )
        manager.play()
        testScheduler.advanceUntilIdle()

        // Speak must NOT be called on unready engine
        assertTrue(fakeClient.spokenTexts.isEmpty())

        // Now open the initialization gate
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        // Speech starts cleanly once the gate opened
        assertTrue(manager.isEngineReady())
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("Gate Paragraph", fakeClient.spokenTexts.last())
    }

    @Test
    fun testPauseDuringRecoveryPreservesPausedStateWithoutAutoplay() = runTest(testDispatcher) {
        var createCount = 0
        var activeClient: FakeTtsClient? = null
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = {
                createCount++
                val client = FakeTtsClient()
                activeClient = client
                client
            }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setChapterAndParagraphs(
            chapterId = "chap_pause_rec",
            newParagraphs = listOf("Para 1", "Para 2"),
            continuePlaying = true,
            startIndex = 1,
            bookId = "book_pause_rec"
        )
        testScheduler.advanceUntilIdle()

        // Trigger failure
        val firstClient = activeClient
        firstClient?.listener?.onError(firstClient.lastUtteranceId, TextToSpeech.ERROR_SERVICE)
        testScheduler.runCurrent()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        // User explicitly presses Pause while recovering
        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        // Complete backoff and recreate engine
        testScheduler.advanceTimeBy(1000L)
        testScheduler.runCurrent()
        assertEquals(2, createCount)

        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        // Engine is ready, but playback remains PAUSED without auto-speaking!
        assertTrue(manager.isEngineReady())
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals(1, manager.currentParagraphIndex.value)
        assertTrue(activeClient?.spokenTexts?.isEmpty() == true)
    }

    @Test
    fun testRepeatedFailuresExhaustRetriesAndEnterErrorState() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient().apply {
            returnFailureOnSpeak = true
        }
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        manager.setChapterAndParagraphs(
            chapterId = "chap_fail_limit",
            newParagraphs = listOf("Fail Para 1"),
            continuePlaying = false,
            startIndex = 0,
            bookId = "book_fail_limit"
        )

        // Attempt 1 fails
        manager.play()
        testScheduler.runCurrent()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        // Advance through retry 1
        testScheduler.advanceTimeBy(600L)
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.runCurrent()

        // Attempt 2 fails
        testScheduler.advanceTimeBy(1600L)
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.runCurrent()

        // Attempt 3 fails
        testScheduler.advanceTimeBy(3200L)
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.runCurrent()

        // Exhausted bounded retries -> enters ERROR state
        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)

        // Test recoverFromError() recovers without clearing data
        fakeClient.returnFailureOnSpeak = false
        manager.recoverFromError(resumePlaying = true)
        testScheduler.runCurrent()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)

        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("Fail Para 1", fakeClient.spokenTexts.last())
    }

    @Test
    fun testPhoneCallTransientLossPausesAndRegainResumes() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs(
            chapterId = "chap_call",
            newParagraphs = listOf("Paragraph 0", "Paragraph 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_call"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)

        // Incoming phone call triggers transient audio focus loss
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        testScheduler.runCurrent()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertTrue(manager.audioFocusManager.pausedDueToTransientLoss)

        // Phone call ends -> AUDIOFOCUS_GAIN
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        testScheduler.advanceUntilIdle()

        // Automatically resumed
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertFalse(manager.audioFocusManager.pausedDueToTransientLoss)
    }

    @Test
    fun testUserPauseDuringPhoneCallPreventsAutoResume() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs(
            chapterId = "chap_user_pause",
            newParagraphs = listOf("Paragraph 0", "Paragraph 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_user_pause"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Incoming call causes transient loss
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        testScheduler.runCurrent()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertTrue(manager.audioFocusManager.pausedDueToTransientLoss)

        // User explicitly taps Pause while on the call
        manager.pause(isExplicitUserAction = true)
        testScheduler.runCurrent()
        assertFalse(manager.audioFocusManager.pausedDueToTransientLoss)

        // Phone call ends -> AUDIOFOCUS_GAIN
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        testScheduler.advanceUntilIdle()

        // MUST stay paused because user explicitly paused!
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals(0, manager.currentParagraphIndex.value)
    }

    @Test
    fun testPermanentAudioFocusLossPausesAndAbandonsFocusWithoutAutoResume() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs(
            chapterId = "chap_perm_loss",
            newParagraphs = listOf("Paragraph 0", "Paragraph 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_perm_loss"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // Spotify or YouTube starts -> AUDIOFOCUS_LOSS
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_LOSS)
        testScheduler.runCurrent()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertFalse(manager.audioFocusManager.hasFocus)
        assertFalse(manager.audioFocusManager.pausedDueToTransientLoss)

        // Focus returns later -> must NOT auto-resume
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
    }

    @Test
    fun testBecomingNoisyPausesPlaybackAndPreventsAutoResume() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs(
            chapterId = "chap_noisy",
            newParagraphs = listOf("Paragraph 0", "Paragraph 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_noisy"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)

        // User unplugs wired headphones or disconnects Bluetooth
        manager.audioFocusManager.simulateAudioBecomingNoisyForTesting()
        testScheduler.runCurrent()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertFalse(manager.audioFocusManager.hasFocus)
        assertFalse(manager.audioFocusManager.pausedDueToTransientLoss)

        // Headphones reconnected -> focus gain must NOT auto-resume
        manager.audioFocusManager.handleAudioFocusChangeForTesting(AudioManager.AUDIOFOCUS_GAIN)
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
    }

    @Test
    fun testPreviousParagraphAtStartOfChapterTransitionsToPreviousChapterFinalParagraphWhenPlaying() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
            override suspend fun getPreviousChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                return if (currentChapterId == "ch_2") {
                    ChapterTransitionData(
                        nextChapterId = "ch_1",
                        nextChapterTitle = "Chapter 1",
                        nextChapterOrder = 0,
                        paragraphs = listOf("Ch 1 Line 0", "Ch 1 Line 1", "Ch 1 Line 2")
                    )
                } else null
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_2",
            newParagraphs = listOf("Ch 2 Line 0", "Ch 2 Line 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_nav"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)

        // At index 0, invoking previous line must navigate to the last paragraph of Chapter 1
        manager.previousParagraph()
        testScheduler.advanceUntilIdle()

        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals(2, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("Ch 1 Line 2", fakeClient.spokenTexts.last())
    }

    @Test
    fun testPreviousParagraphAtStartOfChapterTransitionsToPreviousChapterFinalParagraphWhenPaused() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
            override suspend fun getPreviousChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                return if (currentChapterId == "ch_2") {
                    ChapterTransitionData(
                        nextChapterId = "ch_1",
                        nextChapterTitle = "Chapter 1",
                        nextChapterOrder = 0,
                        paragraphs = listOf("Ch 1 Line 0", "Ch 1 Line 1")
                    )
                } else null
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_2",
            newParagraphs = listOf("Ch 2 Line 0", "Ch 2 Line 1"),
            continuePlaying = false,
            startIndex = 0,
            bookId = "book_nav",
            targetState = TtsState.PAUSED
        )
        testScheduler.advanceUntilIdle()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)

        val spokenBefore = fakeClient.spokenTexts.size

        // In paused state at index 0, invoking previous line transitions to Ch 1, line 1, remaining PAUSED
        manager.previousParagraph()
        testScheduler.advanceUntilIdle()

        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals(spokenBefore, fakeClient.spokenTexts.size)
    }

    @Test
    fun testPreviousParagraphAtStartOfBookStaysAtFirstParagraph() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
            override suspend fun getPreviousChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Line 0", "Ch 1 Line 1"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_nav"
        )
        testScheduler.advanceUntilIdle()
        assertEquals(0, manager.currentParagraphIndex.value)

        // At beginning of novel, previousParagraph stays at line 0
        manager.previousParagraph()
        testScheduler.advanceUntilIdle()

        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
    }

    @Test
    fun testNextParagraphAtEndOfChapterTransitionsToNextChapterWhenPaused() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                return if (currentChapterId == "ch_1") {
                    ChapterTransitionData(
                        nextChapterId = "ch_2",
                        nextChapterTitle = "Chapter 2",
                        nextChapterOrder = 1,
                        paragraphs = listOf("Ch 2 Line 0", "Ch 2 Line 1")
                    )
                } else null
            }
            override suspend fun getPreviousChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? = null
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Line 0", "Ch 1 Line 1"),
            continuePlaying = false,
            startIndex = 1,
            bookId = "book_nav",
            targetState = TtsState.PAUSED
        )
        testScheduler.advanceUntilIdle()
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PAUSED, manager.ttsState.value)

        val spokenBefore = fakeClient.spokenTexts.size

        // In paused state at last line, nextParagraph advances to Ch 2 Line 0, remaining PAUSED
        manager.nextParagraph()
        testScheduler.advanceUntilIdle()

        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals(spokenBefore, fakeClient.spokenTexts.size)
    }

    @Test
    fun testDuplicateOnDoneCallbacksDoNotAdvanceTwice() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        var providerCallCount = 0
        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                providerCallCount++
                return if (currentChapterId == "ch_1") {
                    ChapterTransitionData("ch_2", "Chapter 2", 1, listOf("Ch 2 Line 0", "Ch 2 Line 1"))
                } else if (currentChapterId == "ch_2") {
                    ChapterTransitionData("ch_3", "Chapter 3", 2, listOf("Ch 3 Line 0"))
                } else null
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Final Line"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_dedup"
        )
        testScheduler.advanceUntilIdle()

        val uttId = fakeClient.lastUtteranceId
        assertNotNull(uttId)

        // Fire onDone twice for the same utterance ID
        fakeClient.listener?.onDone(uttId)
        fakeClient.listener?.onDone(uttId)
        testScheduler.advanceUntilIdle()

        // Verify chapter advanced exactly once to ch_2, not ch_3
        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals(1, providerCallCount)
    }

    @Test
    fun testStaleEngineGenerationOnDoneDoesNotAdvanceChapter() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                return ChapterTransitionData("ch_2", "Chapter 2", 1, listOf("Ch 2 Line 0"))
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Final Line"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_gen"
        )
        testScheduler.advanceUntilIdle()

        // Stale utterance from generation 999
        val staleUttId = "utt_g999_e1_ch_1_0_0_1"
        fakeClient.listener?.onDone(staleUttId)
        testScheduler.advanceUntilIdle()

        // Verify chapter remains on ch_1
        assertEquals("ch_1", manager.getCurrentChapterId())
        assertEquals(0, manager.currentParagraphIndex.value)
    }

    @Test
    fun testNextChapterUnavailableSetsErrorAndEntersRecoverableState() = runTest(testDispatcher) {
        val fakeClient = FakeTtsClient()
        val manager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { fakeClient }
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setAutoAdvanceChapter(true)

        val fakeProvider = object : ChapterTransitionProvider {
            override suspend fun getNextChapterContent(bookId: String, currentChapterId: String): ChapterTransitionData? {
                throw java.io.IOException("EPUB file corrupted or disk read error")
            }
        }
        manager.chapterTransitionProvider = fakeProvider

        manager.setChapterAndParagraphs(
            chapterId = "ch_1",
            newParagraphs = listOf("Ch 1 Final Line"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "book_err"
        )
        testScheduler.advanceUntilIdle()

        val uttId = fakeClient.lastUtteranceId
        fakeClient.listener?.onDone(uttId)
        testScheduler.advanceUntilIdle()

        // Playback stops gracefully in a recoverable error state
        assertEquals(TtsState.STOPPED, manager.ttsState.value)
        assertNotNull(manager.errorMessage.value)
        assertTrue(manager.errorMessage.value!!.contains("EPUB file corrupted"))
    }
}



