package com.example.tomato.engine

import android.app.Application
import android.os.Build
import android.util.Log
import android.webkit.WebView
import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.startup.AppInitializer
import java.io.File

/** Setup for the separate engine process (":tomato"). */
object TomatoEngineProcess {
    private const val TAG = "TomatoEngine"

    /** True in the engine process; the app must skip its normal start-up work there. */
    fun isEngineProcess(application: Application): Boolean =
        currentProcessName(application)?.endsWith(EngineProtocol.PROCESS_SUFFIX) == true

    fun init(application: Application) {
        useOwnWebViewDataDirectory()
        // ProcessLifecycleOwner is started by TomatoEngineStartupProvider (it can't be started lazily);
        // this only confirms it, so a broken setup shows up in the log instead of a silent engine.
        try {
            AppInitializer.getInstance(application).initializeComponent(ProcessLifecycleInitializer::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Process lifecycle isn't available in the engine process", e)
        }
    }

    /**
     * Android 9+ allows only one process per WebView data directory. The engine's WebView and the
     * Scraper browser (main process) used the same default directory, so opening the browser while
     * the engine ran crashed the app ("Using WebView from more than one process at once").
     * The engine gets its own directory; the main process keeps the default one, so the Scraper's
     * logins are kept. Must run before anything in this process touches android.webkit.
     */
    private fun useOwnWebViewDataDirectory() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            WebView.setDataDirectorySuffix(EngineProtocol.WEBVIEW_DATA_SUFFIX)
        } catch (e: Exception) {
            // Thrown only if WebView was already used in this process; the engine still works.
            Log.e(TAG, "Couldn't give the engine its own WebView data directory", e)
        }
    }

    private fun currentProcessName(application: Application): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return try {
            File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
        } catch (e: Exception) {
            null
        }
    }
}
