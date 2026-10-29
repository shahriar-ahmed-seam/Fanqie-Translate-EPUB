package com.pofl.fanqienoveldownloader

import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

/**
 * Stand-in for the downloader app's read-aloud plugin (engine builds from 2026-10-29 on register it
 * at startup and refuse to start without the class). Fanqie Translate has its own audiobook player,
 * so the engine's read-aloud is reported as unsupported.
 *
 * Written for Fanqie Translate (it does not reuse the original app's code).
 */
@TauriPlugin
class NativeTtsPlugin(activity: Activity) : Plugin(activity) {

    @Command
    fun probe(invoke: Invoke) {
        val result = JSObject()
        result.put("supported", false)
        result.put("reason", NOT_AVAILABLE)
        invoke.resolve(result)
    }

    @Command
    fun status(invoke: Invoke) {
        val result = JSObject()
        result.put("speaking", false)
        result.put("done_id", null)
        result.put("error_id", null)
        invoke.resolve(result)
    }

    @Command
    fun speak(invoke: Invoke) {
        invoke.reject(NOT_AVAILABLE)
    }

    @Command
    fun stop(invoke: Invoke) {
        invoke.resolve()
    }

    private companion object {
        const val NOT_AVAILABLE = "Read-aloud isn't available inside Fanqie Translate"
    }
}
