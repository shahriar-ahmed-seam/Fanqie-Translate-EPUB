package com.example.tomato.engine

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.ref.WeakReference

/**
 * Starts and stops the engine runtime inside the engine process.
 *
 * The runtime needs an Activity. Android doesn't let ordinary apps put their own activities on an
 * off-screen display (Android 10+ requires a system permission for untrusted displays), so the host
 * activity starts on the main screen as an invisible window that lets every touch through, and
 * steps behind the app as soon as the engine answers (about two seconds).
 */
object EngineHost {
    private const val TAG = "TomatoEngine"
    private const val START_TIMEOUT_MS = 100_000L

    private val startLock = Mutex()
    private var activity: WeakReference<Activity>? = null

    fun onActivityCreated(activity: Activity) {
        this.activity = WeakReference(activity)
    }

    fun onActivityDestroyed(activity: Activity) {
        if (this.activity?.get() === activity) this.activity = null
    }

    /** Makes sure the engine is running and answering; throws with a readable reason otherwise. */
    suspend fun ensureRunning(context: Context) {
        if (EngineBridge.state.value == EngineBridge.State.READY) return
        startLock.withLock {
            if (EngineBridge.state.value == EngineBridge.State.READY) return
            try {
                EngineLibrary.loadSelected()
            } catch (e: Throwable) {
                Log.e(TAG, "Engine library failed to load", e)
                throw EngineCallException("The Tomato engine file couldn't be loaded. Reinstall the engine. (${e.message})")
            }
            if (EngineBridge.state.value == EngineBridge.State.FAILED || activity?.get() == null) {
                finishActivity()
                launch(context.applicationContext)
            }
            val state = withTimeoutOrNull(START_TIMEOUT_MS) {
                EngineBridge.state.first { it == EngineBridge.State.READY || it == EngineBridge.State.FAILED }
            }
            when (state) {
                EngineBridge.State.READY -> return
                EngineBridge.State.FAILED -> throw EngineCallException(EngineBridge.failure ?: "The Tomato engine failed to start")
                else -> {
                    EngineBridge.markFailed("The Tomato engine didn't start in time")
                    throw EngineCallException("The Tomato engine didn't start. Open the app and try again.")
                }
            }
        }
    }

    private fun launch(context: Context) {
        val intent = Intent(context, TomatoEngineActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        context.startActivity(intent)
        Log.i(TAG, "Engine host started")
    }

    private fun finishActivity() {
        activity?.get()?.let {
            try {
                it.finish()
            } catch (_: Exception) {
            }
        }
        activity = null
    }

    fun shutdown() {
        finishActivity()
    }
}
