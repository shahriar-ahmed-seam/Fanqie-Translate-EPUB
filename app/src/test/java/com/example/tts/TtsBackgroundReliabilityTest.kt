package com.example.tts

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.ServiceCompat
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.SettingsRepository
import com.example.diagnostics.AppDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.Locale

/** Screen-off / background behaviour added for the "TTS stops when the phone is locked" reports. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsBackgroundReliabilityTest {

    private lateinit var context: Context
    private val testDispatcher = StandardTestDispatcher()

    private class FakeClient : TextToSpeechClient {
        val spokenTexts = mutableListOf<String>()
        var lastUtteranceId: String? = null
        var listener: UtteranceProgressListener? = null
        var failSpeak = false
        var mockVoices: Set<Voice>? = null
        var currentVoice: Voice? = null
        override fun speak(text: CharSequence, queueMode: Int, params: Bundle?, utteranceId: String?): Int {
            if (failSpeak) return TextToSpeech.ERROR
            spokenTexts.add(text.toString())
            lastUtteranceId = utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop(): Int = TextToSpeech.SUCCESS
        override fun setSpeechRate(speechRate: Float): Int = TextToSpeech.SUCCESS
        override fun setVoice(voice: Voice): Int {
            currentVoice = voice
            return TextToSpeech.SUCCESS
        }
        override fun getVoices(): Set<Voice>? = mockVoices
        override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener?): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun shutdown() {}
    }

    private class FakeProvider(var chapters: Map<String, ChapterTransitionData>) : ChapterTransitionProvider {
        override suspend fun getNextChapterContent(bookId: String, currentChapterId: String) = chapters[currentChapterId]
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = ApplicationProvider.getApplicationContext()
        SettingsRepository(context).clearTtsSessionState()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun kotlinx.coroutines.test.TestScope.manager(
        client: FakeClient,
        inForeground: Boolean = false,
        autoRetry: Boolean = true
    ) = ReaderTtsManager(
        context = context,
        scope = this,
        clientFactory = { client },
        watchdogEnabled = false,
        autoRetryInBackground = autoRetry,
        isAppInForeground = { inForeground },
        backgroundRetryDelaysMs = listOf(1_000L, 2_000L)
    )

    /** Drives the three fast engine recoveries to exhaustion (speak keeps failing). */
    private fun kotlinx.coroutines.test.TestScope.exhaustFastRetries(manager: ReaderTtsManager) {
        repeat(3) {
            testScheduler.advanceTimeBy(5_000)
            testScheduler.runCurrent()
            manager.onInit(TextToSpeech.SUCCESS)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun backgroundFailureKeepsRetryingAndResumesWhenTheEngineRecovers() = runTest(testDispatcher) {
        val client = FakeClient().apply { failSpeak = true }
        val manager = manager(client)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs("ch_1", listOf("Para 0", "Para 1"), bookId = "book_1")
        manager.play(1)
        exhaustFastRetries(manager)

        // Screen off: no dead end, an automatic retry is scheduled and the CPU is kept awake for it.
        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertTrue(manager.autoRetryPending.value)
        assertTrue(manager.wantsCpuAwake())
        assertTrue(manager.hasActiveSession())
        assertTrue(manager.errorMessage.value!!.contains("Tap Play to retry"))

        // The engine comes back (e.g. finished updating) before the wave fires.
        client.failSpeak = false
        testScheduler.advanceTimeBy(1_001)
        testScheduler.runCurrent()
        assertEquals(TtsState.RECOVERING, manager.ttsState.value)
        manager.onInit(TextToSpeech.SUCCESS)
        testScheduler.runCurrent()

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals(1, manager.currentParagraphIndex.value)
        assertEquals("Para 1", client.spokenTexts.last())
        assertFalse(manager.autoRetryPending.value)
    }

    @Test
    fun visibleFailureStillStopsWithTapToRetry() = runTest(testDispatcher) {
        val client = FakeClient().apply { failSpeak = true }
        val manager = manager(client, inForeground = true)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0"))
        manager.play(0)
        exhaustFastRetries(manager)

        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertFalse(manager.autoRetryPending.value)
        assertFalse(manager.wantsCpuAwake())
        assertTrue(manager.errorMessage.value!!.contains("Tap Play to retry"))
    }

    @Test
    fun retryWavesAreBoundedThenWaitForTheUser() = runTest(testDispatcher) {
        val client = FakeClient().apply { failSpeak = true }
        val manager = manager(client)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0"))
        manager.play(0)
        exhaustFastRetries(manager)
        assertTrue(manager.autoRetryPending.value)

        // Two waves configured: each restarts the engine, then three fast attempts keep failing.
        repeat(2) {
            testScheduler.advanceTimeBy(3_000)
            testScheduler.runCurrent()
            manager.onInit(TextToSpeech.SUCCESS)
            testScheduler.runCurrent()
            exhaustFastRetries(manager)
        }
        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertFalse(manager.autoRetryPending.value)
        assertFalse(manager.wantsCpuAwake())
    }

    @Test
    fun pauseDuringAutomaticRetryCancelsIt() = runTest(testDispatcher) {
        val client = FakeClient().apply { failSpeak = true }
        val manager = manager(client)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0"))
        manager.play(0)
        exhaustFastRetries(manager)
        assertTrue(manager.autoRetryPending.value)

        manager.pause()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertFalse(manager.autoRetryPending.value)
        testScheduler.advanceTimeBy(10_000)
        testScheduler.runCurrent()
        assertEquals(TtsState.PAUSED, manager.ttsState.value)
    }

    @Test
    fun networkVoiceFailureSwitchesToOfflineVoiceAndRepeatsTheSameParagraph() = runTest(testDispatcher) {
        val network = Voice("en-us-x-network", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        val offline = Voice("en-us-x-offline", Locale.US, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet())
        val client = FakeClient().apply { mockVoices = setOf(network, offline) }
        val manager = manager(client, inForeground = true)
        manager.savedVoiceId = "en-us-x-network"
        manager.onInit(TextToSpeech.SUCCESS)
        assertEquals("en-us-x-network", manager.selectedVoice.value?.id)

        manager.setParagraphs(listOf("Para 0", "Para 1"))
        manager.play(0)
        client.listener?.onError(client.lastUtteranceId, TextToSpeech.ERROR_NETWORK)
        testScheduler.runCurrent()

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("en-us-x-offline", manager.selectedVoice.value?.id)
        assertEquals(offline, client.currentVoice)
        assertEquals(0, manager.currentParagraphIndex.value)
        assertEquals(listOf("Para 0", "Para 0"), client.spokenTexts)
        // The user's preferred voice is kept for next time.
        assertEquals("en-us-x-network", manager.savedVoiceId)
    }

    @Test
    fun networkFailureWithoutOfflineVoiceRetriesInBackground() = runTest(testDispatcher) {
        val network = Voice("en-us-x-network", Locale.US, Voice.QUALITY_VERY_HIGH, Voice.LATENCY_NORMAL, true, emptySet())
        val client = FakeClient().apply { mockVoices = setOf(network) }
        val manager = manager(client)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0"))
        manager.play(0)
        client.listener?.onError(client.lastUtteranceId, TextToSpeech.ERROR_NETWORK_TIMEOUT)
        testScheduler.runCurrent()

        assertEquals(TtsState.ERROR, manager.ttsState.value)
        assertTrue(manager.autoRetryPending.value)
    }

    @Test
    fun untranslatedNextChapterPausesInsteadOfReadingThePlaceholder() = runTest(testDispatcher) {
        val client = FakeClient()
        val manager = manager(client, inForeground = true)
        val provider = FakeProvider(
            mapOf(
                "ch_1" to ChapterTransitionData(
                    "ch_2", "Chapter 2", 1,
                    listOf("This chapter has not been translated yet."),
                    ChapterContentStatus.UNTRANSLATED
                )
            )
        )
        manager.chapterTransitionProvider = provider
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs("ch_1", listOf("Ch 1 Para 0"), continuePlaying = true, bookId = "book_1")

        client.listener?.onDone(client.lastUtteranceId)
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertEquals("ch_1", manager.getCurrentChapterId())
        assertTrue(manager.errorMessage.value!!.contains("isn't translated yet"))
        assertEquals(listOf("Ch 1 Para 0"), client.spokenTexts)

        // Translation finished: Play continues into the chapter.
        provider.chapters = mapOf("ch_1" to ChapterTransitionData("ch_2", "Chapter 2", 1, listOf("Ch 2 Para 0")))
        manager.resume()
        testScheduler.advanceUntilIdle()

        assertEquals(TtsState.PLAYING, manager.ttsState.value)
        assertEquals("ch_2", manager.getCurrentChapterId())
        assertEquals("Ch 2 Para 0", client.spokenTexts.last())
    }

    @Test
    fun emptyChaptersAreSkippedDuringAutoAdvance() = runTest(testDispatcher) {
        val client = FakeClient()
        val manager = manager(client, inForeground = true)
        manager.chapterTransitionProvider = FakeProvider(
            mapOf(
                "ch_1" to ChapterTransitionData("ch_2", "Illustrations", 1, listOf("This chapter is empty."), ChapterContentStatus.EMPTY),
                "ch_2" to ChapterTransitionData("ch_3", "Chapter 3", 2, listOf("Ch 3 Para 0"))
            )
        )
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs("ch_1", listOf("Ch 1 Para 0"), continuePlaying = true, bookId = "book_1")

        client.listener?.onDone(client.lastUtteranceId)
        testScheduler.advanceUntilIdle()

        assertEquals("ch_3", manager.getCurrentChapterId())
        assertEquals(listOf("Ch 1 Para 0", "Ch 3 Para 0"), client.spokenTexts)
    }

    @Test
    fun reapplyingUnchangedRateOrVoiceDoesNotRestartTheParagraph() = runTest(testDispatcher) {
        val client = FakeClient()
        val manager = manager(client, inForeground = true)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setParagraphs(listOf("Para 0"))
        manager.play(0)
        val voice = TtsVoiceInfo("v1", "Voice 1", Locale.US, isNetworkRequired = false)
        manager.selectVoice(voice)
        val spokenAfterVoice = client.spokenTexts.size

        manager.setSpeechRate(1.0f)
        manager.selectVoice(voice)

        assertEquals(spokenAfterVoice, client.spokenTexts.size)
        assertEquals(TtsState.PLAYING, manager.ttsState.value)
    }

    @Test
    fun manualChapterChangePausesInsteadOfLeavingSilentPlayback() = runTest(testDispatcher) {
        val client = FakeClient()
        val manager = manager(client, inForeground = true)
        manager.onInit(TextToSpeech.SUCCESS)
        manager.setChapterAndParagraphs("ch_1", listOf("Ch 1 Para 0"), continuePlaying = true, bookId = "book_1")
        assertTrue(manager.wantsCpuAwake())

        manager.prepareForChapterChange()

        assertEquals(TtsState.PAUSED, manager.ttsState.value)
        assertFalse(manager.wantsCpuAwake())
    }

    @Test
    fun backgroundingAnIdleReaderDoesNotOverwriteSavedProgress() = runTest(testDispatcher) {
        val repo = SettingsRepository(context)
        val client = FakeClient()
        val manager = manager(client, inForeground = true)
        manager.onInit(TextToSpeech.SUCCESS)
        // The reader prepared chapter 1 at paragraph 2 but the user never pressed Play.
        manager.setChapterAndParagraphs("ch_1", listOf("A", "B", "C", "D"), startIndex = 2, bookId = "book_1")
        repo.setLastReadParagraphIndex("book_1", "ch_1", 3)

        manager.onAppBackgrounded()

        assertNull(repo.getTtsSessionState())
        assertEquals(3, repo.getLastReadParagraphIndex("book_1", "ch_1"))
        assertFalse(manager.hasActiveSession())
    }

    @Test
    fun playbackServiceAnswersEveryStartWithStartForeground() {
        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()
        ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_REMOVE)
        assertTrue(shadowOf(service).isForegroundStopped)

        service.onStartCommand(Intent(context, TtsPlaybackService::class.java).setAction(TtsPlaybackService.ACTION_START), 0, 1)

        assertFalse(shadowOf(service).isForegroundStopped)
        assertEquals(TtsPlaybackService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        controller.destroy()
    }

    @Test
    fun notificationOpensTheLiveSessionInsteadOfFixedIds() {
        val controller = Robolectric.buildService(TtsPlaybackService::class.java)
        val service = controller.create().get()
        val notification = shadowOf(service).lastForegroundNotification
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertTrue(intent.getBooleanExtra(TtsPlaybackService.EXTRA_OPEN_TTS_SESSION, false))
        controller.destroy()
    }

    @Test
    fun diagnosticsLogEndsUpInTheSharedReport() {
        AppDiagnostics.init(context)
        AppDiagnostics.log("test", "engine recovery marker 42")
        val report: File? = AppDiagnostics.buildReport(context)
        assertNotNull(report)
        val text = report!!.readText()
        assertTrue(text.contains("engine recovery marker 42"))
        assertTrue(text.contains("Battery optimization ignored"))
        assertEquals("killed for low memory", AppDiagnostics.exitReasonName(android.app.ApplicationExitInfo.REASON_LOW_MEMORY))
    }
}
