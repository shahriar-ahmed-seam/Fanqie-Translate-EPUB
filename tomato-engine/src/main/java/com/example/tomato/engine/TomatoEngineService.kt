package com.example.tomato.engine

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Engine process entry point. The app binds to it and sends [EngineProtocol.MSG_CALL] messages;
 * each call starts the engine on first use and is answered with [EngineProtocol.MSG_CALL_RESULT].
 */
class TomatoEngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
            handle(message)
            true
        })
    }

    override fun onBind(intent: Intent): IBinder {
        intent.getStringExtra(EngineProtocol.EXTRA_ENGINE_PATH)?.let { EngineLibrary.select(it) }
        return messenger.binder
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun handle(message: Message) {
        // Messages are recycled after this returns: copy what the coroutines need.
        val replyTo = message.replyTo
        val requestId = message.arg1
        val data = Bundle(message.data)
        when (message.what) {
            EngineProtocol.MSG_CALL -> scope.launch { answerCall(replyTo, requestId, data) }
            EngineProtocol.MSG_STATUS -> reply(replyTo, EngineProtocol.MSG_STATUS_RESULT, requestId, statusBundle())
            EngineProtocol.MSG_SHUTDOWN -> {
                Log.i(TAG, "Engine shutdown requested")
                EngineHost.shutdown()
                reply(replyTo, EngineProtocol.MSG_STATUS_RESULT, requestId, statusBundle())
                // The native engine can't be unloaded; ending the process is the only clean stop.
                Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
            }
        }
    }

    private suspend fun answerCall(replyTo: Messenger?, requestId: Int, data: Bundle) {
        val action = data.getString(EngineProtocol.KEY_ACTION).orEmpty()
        val payload = data.getString(EngineProtocol.KEY_PAYLOAD) ?: "{}"
        val timeoutMs = data.getLong(EngineProtocol.KEY_TIMEOUT_MS, 60_000L)
        val result = Bundle()
        if (EngineLibrary.isOtherEngineLoaded()) {
            // The app switched engine versions but reached this process before it ended. A loaded
            // engine can't be replaced: tell the app to call again, and end so a new process starts.
            Log.i(TAG, "Another engine version was selected; restarting the engine process")
            result.putBoolean(EngineProtocol.KEY_OK, false)
            result.putBoolean(EngineProtocol.KEY_RESTART, true)
            result.putString(EngineProtocol.KEY_ERROR, "The Tomato engine is restarting")
            reply(replyTo, EngineProtocol.MSG_CALL_RESULT, requestId, result)
            EngineHost.shutdown()
            Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 100)
            return
        }
        try {
            EngineHost.ensureRunning(this)
            val json = EngineBridge.call(action, payload, timeoutMs)
            result.putBoolean(EngineProtocol.KEY_OK, true)
            if (json.length > EngineProtocol.INLINE_RESULT_LIMIT) {
                val file = withContext(Dispatchers.IO) { writeLargeResult(requestId, json) }
                result.putString(EngineProtocol.KEY_RESULT_FILE, file.absolutePath)
            } else {
                result.putString(EngineProtocol.KEY_RESULT, json)
            }
        } catch (e: TimeoutCancellationException) {
            result.putBoolean(EngineProtocol.KEY_OK, false)
            result.putString(EngineProtocol.KEY_ERROR, "The Tomato engine took too long to answer")
        } catch (e: Exception) {
            result.putBoolean(EngineProtocol.KEY_OK, false)
            result.putString(EngineProtocol.KEY_ERROR, e.message ?: "The Tomato engine failed")
        }
        reply(replyTo, EngineProtocol.MSG_CALL_RESULT, requestId, result)
    }

    private fun writeLargeResult(requestId: Int, json: String): File {
        val dir = File(cacheDir, "tomato_ipc").apply { mkdirs() }
        val file = File(dir, "r${System.currentTimeMillis()}_$requestId.json")
        file.writeText(json)
        return file
    }

    private fun statusBundle(): Bundle = Bundle().apply {
        putString(EngineProtocol.KEY_STATE, EngineBridge.state.value.name)
        putString(EngineProtocol.KEY_MESSAGE, EngineBridge.failure)
        putString(EngineProtocol.KEY_LOADED_PATH, EngineLibrary.loadedPath)
    }

    private fun reply(replyTo: Messenger?, what: Int, requestId: Int, data: Bundle) {
        if (replyTo == null) return
        try {
            replyTo.send(Message.obtain(null, what, requestId, 0).apply { this.data = data })
        } catch (e: RemoteException) {
            Log.w(TAG, "App went away before the engine replied", e)
        }
    }

    private companion object {
        const val TAG = "TomatoEngine"
    }
}
