package com.pofl.fanqie.accountauth

import android.app.Activity
import app.tauri.annotation.Command
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin

/**
 * Stand-in for the downloader app's Fanqie account login plugin. The engine registers it at startup;
 * Fanqie Translate doesn't sign in to Fanqie accounts (free books download without one), so login
 * is reported as unavailable and the clean-up commands succeed without doing anything.
 *
 * Written for Fanqie Translate (it does not reuse the original app's code).
 */
@TauriPlugin
class AccountAuthPlugin(activity: Activity) : Plugin(activity) {

    @Command
    fun startLogin(invoke: Invoke) {
        invoke.reject("Fanqie account sign-in isn't available inside Fanqie Translate")
    }

    @Command
    fun closeLogin(invoke: Invoke) {
        invoke.resolve()
    }

    @Command
    fun clearCookies(invoke: Invoke) {
        invoke.resolve()
    }
}
