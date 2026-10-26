package com.example.tomato.engine

import android.util.Log

/**
 * Loads the downloaded engine library (`libfanqie_novel_downloader_tauri.so`). The library isn't
 * shipped in the APK: the app downloads it from the upstream project's releases and verifies it, and
 * the engine process is told which file to load when it is started.
 *
 * A native library can't be unloaded, so switching to another engine version means restarting the
 * engine process (see [EngineProtocol.MSG_SHUTDOWN]).
 */
object EngineLibrary {
    const val SO_FILE_NAME = "libfanqie_novel_downloader_tauri.so"
    private const val TAG = "TomatoEngine"

    @Volatile
    private var selectedPath: String? = null

    @Volatile
    var loadedPath: String? = null
        private set

    fun select(path: String) {
        selectedPath = path
    }

    /** Called by the generated `Rust` glue object before any native method runs. */
    @JvmStatic
    @Synchronized
    fun loadSelected() {
        val path = selectedPath ?: throw UnsatisfiedLinkError("No Tomato engine selected for this process")
        val loaded = loadedPath
        if (loaded == path) return
        if (loaded != null) {
            throw UnsatisfiedLinkError("Another engine version is already loaded; restart the engine process")
        }
        Log.i(TAG, "Loading engine $path")
        System.load(path)
        loadedPath = path
    }
}
