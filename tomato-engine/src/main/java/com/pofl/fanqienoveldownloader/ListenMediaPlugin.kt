package com.pofl.fanqienoveldownloader

import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSArray
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

/**
 * Stand-in for the downloader app's listening controls plugin (media notification and lock-screen
 * controls for its audiobooks; engine builds from 2026-10-29 on register it at startup). Fanqie
 * Translate doesn't use the engine for listening, so the controls are reported as unsupported.
 *
 * Written for Fanqie Translate (it does not reuse the original app's code).
 */
@TauriPlugin
class ListenMediaPlugin(activity: Activity) : Plugin(activity) {

    @Command
    fun probe(invoke: Invoke) {
        val result = JSObject()
        result.put("supported", false)
        result.put("controls", false)
        result.put("notifications", false)
        invoke.resolve(result)
    }

    @Command
    fun poll(invoke: Invoke) {
        val result = JSObject()
        result.put("actions", JSArray())
        result.put("interruption", null)
        result.put("active", false)
        invoke.resolve(result)
    }

    @Command
    fun update(invoke: Invoke) {
        invoke.reject("Listening controls aren't available inside Fanqie Translate")
    }

    @Command
    fun stop(invoke: Invoke) {
        invoke.resolve()
    }
}
