package com.example.tts

import android.content.Context
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

        // Should transition to ERROR instead of throwing or crashing
        manager.play()
        assertEquals(TtsState.ERROR, manager.ttsState.value)
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
}
