package com.example.ui

import androidx.test.core.app.ApplicationProvider
import com.example.data.repository.LibraryViewMode
import com.example.data.repository.SettingsRepository
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryViewModeTest {

    @Test
    fun testDefaultViewModeIsGrid() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("epub_translator_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("library_view_mode").apply()

        val repo = SettingsRepository(context)
        assertEquals(LibraryViewMode.GRID, repo.getLibraryViewMode())
        assertEquals(LibraryViewMode.GRID, repo.libraryViewMode.value)
    }

    @Test
    fun testSetAndPersistViewMode() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val repo = SettingsRepository(context)

        repo.setLibraryViewMode(LibraryViewMode.LIST)
        assertEquals(LibraryViewMode.LIST, repo.getLibraryViewMode())
        assertEquals(LibraryViewMode.LIST, repo.libraryViewMode.value)

        // New repo instance should read persisted LIST mode from SharedPreferences
        val repo2 = SettingsRepository(context)
        assertEquals(LibraryViewMode.LIST, repo2.getLibraryViewMode())
        assertEquals(LibraryViewMode.LIST, repo2.libraryViewMode.value)

        // Switch back to GRID
        repo2.setLibraryViewMode(LibraryViewMode.GRID)
        assertEquals(LibraryViewMode.GRID, repo2.getLibraryViewMode())
        assertEquals(LibraryViewMode.GRID, repo2.libraryViewMode.value)
    }

    @Test
    fun testMalformedViewModeFallsBackToGrid() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = context.getSharedPreferences("epub_translator_settings", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("library_view_mode", "INVALID_MODE").apply()

        val repo = SettingsRepository(context)
        assertEquals(LibraryViewMode.GRID, repo.getLibraryViewMode())
    }
}
