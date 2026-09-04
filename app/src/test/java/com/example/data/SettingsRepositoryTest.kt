package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRepositoryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Clear prefs before each test
        context.getSharedPreferences("epub_translator_settings", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun testDefaultSettingsMatchGithubRepository() {
        val repo = SettingsRepository(context)
        val settings = repo.settings.value

        assertEquals("shahriar-ahmed-seam", settings.githubOwner)
        assertEquals("Fanqie-Translate-EPUB", settings.githubRepo)
    }

    @Test
    fun testLegacySettingsAreMigratedAutomatically() {
        // Pre-populate with old default values
        context.getSharedPreferences("epub_translator_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("github_owner", "shahriarseam")
            .putString("github_repo", "epub-translator")
            .commit()

        val repo = SettingsRepository(context)
        val settings = repo.settings.value

        assertEquals("shahriar-ahmed-seam", settings.githubOwner)
        assertEquals("Fanqie-Translate-EPUB", settings.githubRepo)
    }

    @Test
    fun testCustomUserSpecifiedRepoIsNotOverwritten() {
        context.getSharedPreferences("epub_translator_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("github_owner", "custom-org")
            .putString("github_repo", "custom-novel-repo")
            .commit()

        val repo = SettingsRepository(context)
        val settings = repo.settings.value

        assertEquals("custom-org", settings.githubOwner)
        assertEquals("custom-novel-repo", settings.githubRepo)
    }
}
