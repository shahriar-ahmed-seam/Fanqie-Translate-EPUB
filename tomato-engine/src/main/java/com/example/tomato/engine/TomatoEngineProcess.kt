package com.example.tomato.engine

import android.app.Application
import android.os.Build
import android.util.Log
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
        // ProcessLifecycleOwner is started by TomatoEngineStartupProvider (it can't be started lazily);
        // this only confirms it, so a broken setup shows up in the log instead of a silent engine.
        try {
            AppInitializer.getInstance(application).initializeComponent(ProcessLifecycleInitializer::class.java)
        } catch (e: Exception) {
            Log.e(TAG, "Process lifecycle isn't available in the engine process", e)
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
