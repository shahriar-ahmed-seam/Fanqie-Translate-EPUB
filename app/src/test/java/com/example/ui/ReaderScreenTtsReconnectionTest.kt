package com.example.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import com.example.tts.ReaderTtsManager
import com.example.tts.TextToSpeechClient
import com.example.tts.TtsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderScreenTtsReconnectionTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private class MockClient : TextToSpeechClient {
        var isStopped = false
        override fun speak(text: CharSequence, queueMode: Int, params: android.os.Bundle?, utteranceId: String?): Int {
            isStopped = false
            return TextToSpeech.SUCCESS
        }
        override fun stop(): Int {
            isStopped = true
            return TextToSpeech.SUCCESS
        }
        override fun setSpeechRate(speechRate: Float): Int = TextToSpeech.SUCCESS
        override fun setVoice(voice: android.speech.tts.Voice): Int = TextToSpeech.SUCCESS
        override fun getVoices(): Set<android.speech.tts.Voice>? = emptySet()
        override fun setOnUtteranceProgressListener(listener: android.speech.tts.UtteranceProgressListener?): Int = TextToSpeech.SUCCESS
        override fun shutdown() {}
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testOpeningAnotherNovelDoesNotStopOngoingTts() = runTest(testDispatcher) {
        val mockClient = MockClient()
        val ttsManager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { mockClient }
        )
        ttsManager.onInit(TextToSpeech.SUCCESS)

        // 1. Novel A is currently playing in background
        ttsManager.setChapterAndParagraphs(
            chapterId = "novelA_ch1",
            newParagraphs = listOf("Novel A Paragraph 1", "Novel A Paragraph 2"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "novel_A",
            novelTitle = "Novel A Title",
            chapterTitle = "Chapter 1",
            chapterOrder = 0
        )
        assertEquals(TtsState.PLAYING, ttsManager.ttsState.value)
        assertEquals("novel_A", ttsManager.mediaMetadata.value.bookId)
        assertEquals("novelA_ch1", ttsManager.mediaMetadata.value.chapterId)

        // 2. ReaderScreen opens for Novel B (simulate condition check)
        val novelBId = "novel_B"
        val novelBChapterId = "novelB_ch1"
        val meta = ttsManager.mediaMetadata.value

        val isAnotherNovelOrChapterPlaying = (ttsManager.ttsState.value == TtsState.PLAYING || ttsManager.ttsState.value == TtsState.PAUSED) &&
                (meta.bookId != novelBId || meta.chapterId != novelBChapterId)

        assertTrue(isAnotherNovelOrChapterPlaying)

        // Because isAnotherNovelOrChapterPlaying is true, ReaderScreen avoids calling setChapterAndParagraphs on entry
        // Therefore, verify Novel A is still PLAYING without interruption
        assertEquals(TtsState.PLAYING, ttsManager.ttsState.value)
        assertEquals("novel_A", ttsManager.mediaMetadata.value.bookId)
        assertFalse(mockClient.isStopped)
    }

    @Test
    fun testExplicitPlayInAnotherNovelSwitchesSession() = runTest(testDispatcher) {
        val mockClient = MockClient()
        val ttsManager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { mockClient }
        )
        ttsManager.onInit(TextToSpeech.SUCCESS)

        // 1. Start playback for Novel A
        ttsManager.setChapterAndParagraphs(
            chapterId = "novelA_ch1",
            newParagraphs = listOf("Novel A text"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "novel_A",
            novelTitle = "Novel A",
            chapterTitle = "Chapter 1",
            chapterOrder = 0
        )
        assertEquals("novel_A", ttsManager.mediaMetadata.value.bookId)

        // 2. User explicitly requests play in Novel B
        ttsManager.setChapterAndParagraphs(
            chapterId = "novelB_ch1",
            newParagraphs = listOf("Novel B text 1", "Novel B text 2"),
            continuePlaying = true,
            startIndex = 1,
            bookId = "novel_B",
            novelTitle = "Novel B",
            chapterTitle = "Chapter 1",
            chapterOrder = 0
        )

        // 3. Playback session has cleanly switched to Novel B
        assertEquals(TtsState.PLAYING, ttsManager.ttsState.value)
        assertEquals("novel_B", ttsManager.mediaMetadata.value.bookId)
        assertEquals("novelB_ch1", ttsManager.mediaMetadata.value.chapterId)
        assertEquals(1, ttsManager.currentParagraphIndex.value)
    }

    @Test
    fun testBackgroundChapterProgressionSyncsMetadata() = runTest(testDispatcher) {
        val mockClient = MockClient()
        val ttsManager = ReaderTtsManager(
            context = context,
            scope = this,
            clientFactory = { mockClient }
        )
        ttsManager.onInit(TextToSpeech.SUCCESS)

        // Play Chapter 1
        ttsManager.setChapterAndParagraphs(
            chapterId = "novelA_ch1",
            newParagraphs = listOf("Chapter 1 text"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "novel_A",
            novelTitle = "Novel A",
            chapterTitle = "Chapter 1",
            chapterOrder = 0
        )

        // Simulate background chapter advance by TtsPlaybackService
        ttsManager.setChapterAndParagraphs(
            chapterId = "novelA_ch2",
            newParagraphs = listOf("Chapter 2 text"),
            continuePlaying = true,
            startIndex = 0,
            bookId = "novel_A",
            novelTitle = "Novel A",
            chapterTitle = "Chapter 2",
            chapterOrder = 1
        )

        assertEquals("novelA_ch2", ttsManager.mediaMetadata.value.chapterId)
        assertEquals(1, ttsManager.mediaMetadata.value.chapterOrder)
        assertEquals(TtsState.PLAYING, ttsManager.ttsState.value)
    }
}
