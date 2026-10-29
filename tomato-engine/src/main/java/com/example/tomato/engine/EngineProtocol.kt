package com.example.tomato.engine

/**
 * Messages between the app (main process) and the Tomato engine process (":tomato").
 *
 * The engine is the Fanqie Novel Downloader's Rust core. It only runs inside a Tauri runtime, so it
 * lives in its own process: a native crash there can never take the app (or audiobook playback) down.
 */
object EngineProtocol {
    const val MSG_CALL = 1
    const val MSG_CALL_RESULT = 2
    const val MSG_STATUS = 3
    const val MSG_STATUS_RESULT = 4
    const val MSG_SHUTDOWN = 5

    const val KEY_ACTION = "action"
    const val KEY_PAYLOAD = "payload"
    const val KEY_TIMEOUT_MS = "timeout_ms"
    const val KEY_OK = "ok"
    const val KEY_RESULT = "result"
    /** Large results travel as a file in the shared cache dir (binder transactions are capped near 1 MB). */
    const val KEY_RESULT_FILE = "result_file"
    const val KEY_ERROR = "error"
    const val KEY_STATE = "state"
    const val KEY_MESSAGE = "message"
    const val KEY_LOADED_PATH = "loaded_path"
    /** In a call reply: this process has another engine version loaded and ends now; call again. */
    const val KEY_RESTART = "restart"

    /** Engine library the process should load (passed when binding). */
    const val EXTRA_ENGINE_PATH = "engine_path"

    /** Results bigger than this go through a file. */
    const val INLINE_RESULT_LIMIT = 200_000

    const val PROCESS_SUFFIX = ":tomato"

    /** The engine process's WebView data directory is "app_webview_" + this (see TomatoEngineProcess). */
    const val WEBVIEW_DATA_SUFFIX = "tomato"
}
