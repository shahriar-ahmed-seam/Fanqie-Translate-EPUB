package com.example.tomato

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// A plain Application: the app's own (queue, TTS) isn't needed and would keep running after the test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TomatoEngineInstallerTest {
    private lateinit var context: Context
    private lateinit var installer: TomatoEngineInstaller

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "tomato_engine").deleteRecursively()
        installer = TomatoEngineInstaller(context)
    }

    private fun engineFile(tag: String): InstalledEngine {
        val dir = File(context.filesDir, "tomato_engine/engines/$tag/x86_64").apply { mkdirs() }
        val library = File(dir, "libfanqie_novel_downloader_tauri.so").apply { writeText(tag) }
        return InstalledEngine(tag, "x86_64", library.absolutePath, "sha-$tag", 1L)
    }

    @Test
    fun aFailedUpdateGoesBackToTheEngineThatWorked() {
        val old = engineFile("unsigned-v2026.10.25-1124-r760")
        installer.makeCurrent(old)
        val candidate = engineFile("unsigned-v2026.10.29-1422-r774")

        // While the new engine is tried, the old one stays on the phone.
        installer.makeCurrent(candidate, deletePrevious = false)
        assertEquals(candidate.tag, installer.installed()!!.tag)
        assertTrue(File(old.path).isFile)

        // It didn't start: back to the old engine, the new one is removed.
        installer.makeCurrent(old, deletePrevious = false)
        installer.discard(candidate)
        assertEquals(old.tag, installer.installed()!!.tag)
        assertTrue(File(old.path).isFile)
        assertFalse(File(candidate.path).exists())
    }

    @Test
    fun aSuccessfulUpdateRemovesTheOldEngine() {
        val old = engineFile("unsigned-v2026.10.25-1124-r760")
        installer.makeCurrent(old)
        val candidate = engineFile("unsigned-v2026.10.29-1422-r774")
        installer.makeCurrent(candidate, deletePrevious = false)
        installer.discard(old)
        assertEquals(candidate.tag, installer.installed()!!.tag)
        assertFalse(File(old.path).exists())
        // The engine in use is never discarded.
        installer.discard(candidate)
        assertTrue(File(candidate.path).isFile)
    }

    @Test
    fun anEngineWhoseFileIsGoneIsNotInstalled() {
        val engine = engineFile("unsigned-v2026.10.29-1422-r774")
        installer.makeCurrent(engine)
        File(engine.path).delete()
        assertNull(installer.installed())
    }
}
