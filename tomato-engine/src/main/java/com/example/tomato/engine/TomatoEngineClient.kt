package com.example.tomato.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The engine process isn't available (not installed, crashed, or couldn't be started). */
class EngineUnavailableException(message: String) : Exception(message)

/** Engine status as reported by the engine process. */
data class EngineStatus(val state: String, val message: String?, val loadedPath: String?)

/**
 * App-side handle to the engine process. Binds on first use, survives engine crashes (the next call
 * starts a fresh engine process) and never throws anything but [EngineUnavailableException] and
 * [EngineCallException].
 */
class TomatoEngineClient(
    context: Context,
    /** Engine library to load, or null when no engine is installed. */
    private val enginePath: () -> String?
) {
    private val appContext = context.applicationContext
    private val replyThread = HandlerThread("tomato-engine-replies").apply { start() }
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
    private val ids = AtomicInteger(0)
    private val connectLock = Mutex()
    private val replies = Messenger(Handler(replyThread.looper) { message ->
        pending.remove(message.arg1)?.complete(Bundle(message.data))
        true
    })

    @Volatile
    private var service: Messenger? = null
    private var connection: ServiceConnection? = null
    @Volatile
    private var boundPath: String? = null

    /** Calls `dispatch(action, payload)` in the engine and returns the JSON reply. */
    suspend fun call(action: String, payloadJson: String = "{}", timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        val messenger = connect()
        val data = Bundle().apply {
            putString(EngineProtocol.KEY_ACTION, action)
            putString(EngineProtocol.KEY_PAYLOAD, payloadJson)
            putLong(EngineProtocol.KEY_TIMEOUT_MS, timeoutMs)
        }
        // The first call also starts the engine, which can take a while on slow phones.
        val reply = send(messenger, EngineProtocol.MSG_CALL, data, timeoutMs + ENGINE_START_ALLOWANCE_MS)
        if (!reply.getBoolean(EngineProtocol.KEY_OK, false)) {
            throw EngineCallException(reply.getString(EngineProtocol.KEY_ERROR) ?: "The Tomato engine failed")
        }
        reply.getString(EngineProtocol.KEY_RESULT)?.let { return it }
        val file = reply.getString(EngineProtocol.KEY_RESULT_FILE)?.let(::File)
            ?: throw EngineCallException("The Tomato engine sent an empty reply")
        return withContext(Dispatchers.IO) {
            try {
                file.readText()
            } finally {
                file.delete()
            }
        }
    }

    suspend fun status(): EngineStatus? {
        val messenger = service ?: return null
        return try {
            val reply = send(messenger, EngineProtocol.MSG_STATUS, Bundle(), 5_000L)
            EngineStatus(
                state = reply.getString(EngineProtocol.KEY_STATE) ?: "UNKNOWN",
                message = reply.getString(EngineProtocol.KEY_MESSAGE),
                loadedPath = reply.getString(EngineProtocol.KEY_LOADED_PATH)
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Stops the engine process (needed before switching engine versions). */
    suspend fun shutdown() {
        connectLock.withLock {
            val messenger = service
            if (messenger != null) {
                try {
                    send(messenger, EngineProtocol.MSG_SHUTDOWN, Bundle(), 3_000L)
                } catch (_: Exception) {
                    // The process may already be gone.
                }
            }
            disconnect("The Tomato engine was stopped")
        }
    }

    private suspend fun send(messenger: Messenger, what: Int, data: Bundle, timeoutMs: Long): Bundle {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<Bundle>()
        pending[id] = reply
        try {
            messenger.send(Message.obtain(null, what, id, 0).apply {
                this.data = data
                replyTo = replies
            })
        } catch (e: RemoteException) {
            pending.remove(id)
            disconnect("The Tomato engine stopped")
            throw EngineUnavailableException("The Tomato engine stopped. Try again.")
        }
        return try {
            withTimeout(timeoutMs) { reply.await() }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw EngineCallException("The Tomato engine took too long to answer")
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun connect(): Messenger = connectLock.withLock {
        val path = enginePath() ?: throw EngineUnavailableException("The Tomato engine isn't installed")
        service?.let { existing ->
            if (existing.binder.isBinderAlive && boundPath == path) return existing
            disconnect("Switching engines")
        }
        val connected = CompletableDeferred<Messenger?>()
        val newConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                if (binder == null) {
                    connected.complete(null)
                    return
                }
                try {
                    binder.linkToDeath({ onEngineDied() }, 0)
                } catch (e: RemoteException) {
                    connected.complete(null)
                    return
                }
                connected.complete(Messenger(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                onEngineDied()
            }

            override fun onBindingDied(name: ComponentName?) {
                onEngineDied()
            }

            override fun onNullBinding(name: ComponentName?) {
                connected.complete(null)
            }
        }
        val intent = Intent(appContext, TomatoEngineService::class.java)
            .putExtra(EngineProtocol.EXTRA_ENGINE_PATH, path)
        val bound = try {
            appContext.bindService(intent, newConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't bind the engine service", e)
            false
        }
        if (!bound) throw EngineUnavailableException("The Tomato engine couldn't be started")
        val messenger = withTimeoutOrNull(BIND_TIMEOUT_MS) { connected.await() }
        if (messenger == null) {
            try {
                appContext.unbindService(newConnection)
            } catch (_: Exception) {
            }
            throw EngineUnavailableException("The Tomato engine couldn't be started")
        }
        connection = newConnection
        service = messenger
        boundPath = path
        messenger
    }

    private fun onEngineDied() {
        Log.w(TAG, "Engine process died")
        disconnect("The Tomato engine stopped unexpectedly. Try again.")
    }

    private fun disconnect(reason: String) {
        val old = connection
        connection = null
        service = null
        boundPath = null
        if (old != null) {
            try {
                appContext.unbindService(old)
            } catch (_: Exception) {
            }
        }
        val waiting = pending.values.toList()
        pending.clear()
        waiting.forEach { it.completeExceptionally(EngineUnavailableException(reason)) }
    }

    companion object {
        private const val TAG = "TomatoEngineClient"
        const val DEFAULT_TIMEOUT_MS = 60_000L
        private const val ENGINE_START_ALLOWANCE_MS = 110_000L
        private const val BIND_TIMEOUT_MS = 15_000L
    }
}
