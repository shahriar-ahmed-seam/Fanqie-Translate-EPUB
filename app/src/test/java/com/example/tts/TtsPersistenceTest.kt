package com.example.tts

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TtsPlaybackSessionState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TtsPersistenceTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        settingsRepository = SettingsRepository(context)
        settingsRepository.clearTtsSessionState()
    }

    @Test
    fun testDefaultAutoResumePlaybackIsDisabled() {
        // Requirement 7: Do NOT automatically start speech after a normal app launch unless explicitly enabled
        assertFalse(settingsRepository.isTtsAutoResumePlaybackEnabled())
    }

    @Test
    fun testEnableAndDisableAutoResumePlayback() {
        settingsRepository.setTtsAutoResumePlaybackEnabled(true)
        assertTrue(settingsRepository.isTtsAutoResumePlaybackEnabled())

        settingsRepository.setTtsAutoResumePlaybackEnabled(false)
        assertFalse(settingsRepository.isTtsAutoResumePlaybackEnabled())
    }

    @Test
    fun testSaveAndRestoreTtsSessionState() {
        val state = TtsPlaybackSessionState(
            bookId = "book_123",
            chapterId = "chap_456",
            chapterOrder = 5,
            paragraphIndex = 14,
            playbackState = "PLAYING",
            speechRate = 1.25f,
            voiceId = "en-us-x-sfg"
        )

        settingsRepository.saveTtsSessionState(state)

        val restored = settingsRepository.getTtsSessionState()
        assertNotNull(restored)
        assertEquals("book_123", restored?.bookId)
        assertEquals("chap_456", restored?.chapterId)
        assertEquals(5, restored?.chapterOrder)
        assertEquals(14, restored?.paragraphIndex)
        assertEquals("PLAYING", restored?.playbackState)
        assertEquals(1.25f, restored?.speechRate ?: 1.0f, 0.01f)
        assertEquals("en-us-x-sfg", restored?.voiceId)

        // Verify chapter-scoped persistence is synchronized
        assertEquals(14, settingsRepository.getLastReadParagraphIndex("book_123", "chap_456"))
        assertEquals("chap_456", settingsRepository.getLastReadChapterId("book_123"))
        assertEquals("book_123", settingsRepository.getLastActiveBookId())
    }

    @Test
    fun testNeverRestoreParagraphBelongingToAnotherChapterOrBook() {
        // Requirement 9: Never restore a paragraph belonging to another chapter/book
        val state = TtsPlaybackSessionState(
            bookId = "book_A",
            chapterId = "chap_1",
            chapterOrder = 0,
            paragraphIndex = 25,
            playbackState = "PAUSED",
            speechRate = 1.0f,
            voiceId = null
        )
        settingsRepository.saveTtsSessionState(state)

        // Checking another chapter in the same book returns 0, not chapter 1's position
        val otherChapIndex = settingsRepository.getLastReadParagraphIndex("book_A", "chap_2")
        assertEquals(0, otherChapIndex)

        // Checking another book returns 0, not book A's position
        val otherBookIndex = settingsRepository.getLastReadParagraphIndex("book_B", "chap_1")
        assertEquals(0, otherBookIndex)
    }

    @Test
    fun testClearTtsSessionState() {
        val state = TtsPlaybackSessionState(
            bookId = "book_999",
            chapterId = "chap_888",
            chapterOrder = 2,
            paragraphIndex = 10,
            playbackState = "STOPPED",
            speechRate = 1.5f,
            voiceId = "en-gb-voice"
        )
        settingsRepository.saveTtsSessionState(state)
        assertNotNull(settingsRepository.getTtsSessionState())

        settingsRepository.clearTtsSessionState()
        assertNull(settingsRepository.getTtsSessionState())
    }
}
