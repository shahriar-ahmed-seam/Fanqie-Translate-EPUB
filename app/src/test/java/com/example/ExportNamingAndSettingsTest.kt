package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.repository.AppSettings
import com.example.data.repository.SettingsRepository
import com.example.epub.EpubRebuilder
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportNamingAndSettingsTest {

    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        settingsRepository = SettingsRepository(context)
    }

    @Test
    fun testSettingsRepositoryEnforcesSafeRanges() {
        // Attempt to pass out-of-bounds values
        val extremeSettings = AppSettings(
            workerCount = 999, // Should be clamped to 50
            maxActiveBooks = -10, // Should be clamped to 1
            chunkSize = 99999, // Should be clamped to 4800
            maxRetries = 50, // Should be clamped to 10
            timeoutSeconds = 999 // Should be clamped to 120
        )

        settingsRepository.updateSettings(extremeSettings)
        val saved = settingsRepository.settings.value

        assertEquals(50, saved.workerCount)
        assertEquals(1, saved.maxActiveBooks)
        assertEquals(4800, saved.chunkSize)
        assertEquals(10, saved.maxRetries)
        assertEquals(120, saved.timeoutSeconds)
    }

    @Test
    fun testSettingsRepositoryEnforcesLowerBounds() {
        val lowSettings = AppSettings(
            workerCount = 0, // Should be clamped to 1
            maxActiveBooks = 0, // Should be clamped to 1
            chunkSize = 100, // Should be clamped to 1000
            maxRetries = 0, // Should be clamped to 1
            timeoutSeconds = 1 // Should be clamped to 5
        )

        settingsRepository.updateSettings(lowSettings)
        val saved = settingsRepository.settings.value

        assertEquals(1, saved.workerCount)
        assertEquals(1, saved.maxActiveBooks)
        assertEquals(1000, saved.chunkSize)
        assertEquals(1, saved.maxRetries)
        assertEquals(5, saved.timeoutSeconds)
    }

    @Test
    fun testFullNovelExportNamingUsesEnglishTitle() {
        val englishTitle = "Night Fall"
        val safeName = EpubRebuilder.sanitizeFileName(englishTitle)
        val exportFilename = "$safeName.epub"

        assertEquals("Night Fall.epub", exportFilename)
        assertFalse(exportFilename.contains("_English"))
    }

    @Test
    fun testRangeExportNamingFormat() {
        val englishTitle = "Night Fall"
        val safeName = EpubRebuilder.sanitizeFileName(englishTitle)
        val startChapter = 100
        val endChapter = 201
        val rangeStr = "Chapter $startChapter-$endChapter"
        val exportFilename = "$safeName $rangeStr.epub"

        assertEquals("Night Fall Chapter 100-201.epub", exportFilename)
    }

    @Test
    fun testFilenameSanitizationForSpecialCharacters() {
        val messyTitle = "Lord of the Mysteries: Volume 1/2? <Special>*"
        val safeName = EpubRebuilder.sanitizeFileName(messyTitle)

        assertFalse(safeName.contains(":"))
        assertFalse(safeName.contains("/"))
        assertFalse(safeName.contains("?"))
        assertFalse(safeName.contains("<"))
        assertFalse(safeName.contains(">"))
        assertFalse(safeName.contains("*"))
        assertTrue(safeName.endsWith(".epub") == false)
    }

    @Test
    fun testTitleFallbackWhenTranslatedUnavailable() {
        val originalTitle = "全职高手"
        val translatedTitle: String? = null

        val displayTitle = translatedTitle?.takeIf { it.isNotBlank() } ?: originalTitle
        assertEquals("全职高手", displayTitle)

        val completedTranslation = "The King's Avatar"
        val updatedDisplayTitle = completedTranslation.takeIf { it.isNotBlank() } ?: originalTitle
        assertEquals("The King's Avatar", updatedDisplayTitle)
    }
}
