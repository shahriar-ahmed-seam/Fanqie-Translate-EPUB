package com.example.tomato.engine

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** The engine answered a call with an error (the message is the engine's own, often Chinese). */
class EngineCallException(message: String) : Exception(message)

/**
 * Runs inside the engine process. Talks to the engine through the same IPC the downloader's own
 * page uses: `window.__TAURI__.core.invoke('dispatch', {action, payload})` evaluated in the engine's
 * WebView, with replies coming back through a JavaScript interface.
 */
object EngineBridge {
    private const val TAG = "TomatoEngine"
    private const val HOST_OBJECT = "TomatoHost"
    private const val READY_TIMEOUT_MS = 90_000L
    private const val PROBE_INTERVAL_MS = 400L

    enum class State { IDLE, STARTING, READY, FAILED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    var failure: String? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var webView: WeakReference<WebView>? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val ids = AtomicInteger(0)
    private var probeStartedAt = 0L
    private const val PROBE_REPLY_TIMEOUT_MS = 15_000L

    fun isAttached(): Boolean = webView?.get() != null

    /** Called from the engine activity when the runtime created its WebView (before the page loads). */
    @SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun attach(view: WebView) {
        webView = WeakReference(view)
        failure = null
        _state.value = State.STARTING
        view.addJavascriptInterface(Host, HOST_OBJECT)
        probeStartedAt = System.currentTimeMillis()
        main.postDelayed(::probe, PROBE_INTERVAL_MS)
        main.postDelayed({
            if (_state.value == State.STARTING) markFailed("The Tomato engine didn't start in time")
        }, READY_TIMEOUT_MS)
    }

    fun detach(view: WebView?) {
        if (view != null && webView?.get() !== view) return
        webView = null
        main.removeCallbacksAndMessages(null)
        failAll("The Tomato engine stopped")
        if (_state.value != State.FAILED) _state.value = State.IDLE
    }

    fun markFailed(message: String) {
        failure = message
        _state.value = State.FAILED
        failAll(message)
    }

    /** Sends `dispatch(action, payload)` to the engine and returns the JSON reply. */
    suspend fun call(action: String, payloadJson: String, timeoutMs: Long): String {
        if (_state.value != State.READY) throw EngineCallException("The Tomato engine isn't ready")
        return send(action, payloadJson, timeoutMs)
    }

    private suspend fun send(action: String, payloadJson: String, timeoutMs: Long): String {
        val id = ids.incrementAndGet()
        val reply = CompletableDeferred<String>()
        pending[id] = reply
        // The payload is JSON, which is also a valid JavaScript literal; the action is quoted.
        val script = "window.__tomatoDispatch && window.__tomatoDispatch($id, ${JSONObject.quote(action)}, $payloadJson);"
        main.post {
            val view = webView?.get()
            if (view == null) {
                pending.remove(id)?.completeExceptionally(EngineCallException("The Tomato engine stopped"))
            } else {
                view.evaluateJavascript(script, null)
            }
        }
        return try {
            withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending.remove(id)
        }
    }

    /** Waits for the page's Tauri API, installs the dispatch helper, then checks the engine answers. */
    private fun probe() {
        val view = webView?.get() ?: return
        if (System.currentTimeMillis() - probeStartedAt > READY_TIMEOUT_MS) {
            markFailed("The Tomato engine didn't start in time")
            return
        }
        view.evaluateJavascript(HELPER_SCRIPT) { installed ->
            if (installed != "true") {
                main.postDelayed(::probe, PROBE_INTERVAL_MS)
                return@evaluateJavascript
            }
            // The engine may still be initialising; list_jobs is cheap and tells us when it answers.
            val id = ids.incrementAndGet()
            val reply = CompletableDeferred<String>()
            pending[id] = reply
            main.postDelayed({
                pending.remove(id)?.completeExceptionally(EngineCallException("No reply yet"))
            }, PROBE_REPLY_TIMEOUT_MS)
            reply.invokeOnCompletion { error ->
                main.post {
                    if (error == null) {
                        Log.i(TAG, "Engine ready")
                        _state.value = State.READY
                    } else if (webView?.get() != null && _state.value == State.STARTING) {
                        main.postDelayed(::probe, PROBE_INTERVAL_MS * 2)
                    }
                }
            }
            view.evaluateJavascript("window.__tomatoDispatch($id, 'list_jobs', {});", null)
        }
    }

    private fun failAll(message: String) {
        val calls = pending.values.toList()
        pending.clear()
        calls.forEach { it.completeExceptionally(EngineCallException(message)) }
    }

    /** Receives replies from the engine page (runs on a WebView binder thread). */
    private object Host {
        @JavascriptInterface
        fun onResult(id: Int, json: String?) {
            pending.remove(id)?.complete(json ?: "null")
        }

        @JavascriptInterface
        fun onError(id: Int, message: String?) {
            pending.remove(id)?.completeExceptionally(EngineCallException(cleanError(message)))
        }
    }

    /** Engine errors arrive as strings or JSON objects ({"message": ...}). */
    internal fun cleanError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "The Tomato engine reported an error"
        if (text.startsWith("{")) {
            try {
                val obj = JSONObject(text)
                val message = obj.optString("message").ifBlank { obj.optString("detail") }.ifBlank { obj.optString("error") }
                if (message.isNotBlank()) return message
            } catch (_: Exception) {
                // Fall through to the raw text.
            }
        }
        return text.removeSurrounding("\"")
    }

    private val HELPER_SCRIPT = """
        (function () {
          var tauri = window.__TAURI__;
          if (!tauri || !tauri.core || typeof tauri.core.invoke !== 'function' || !window.$HOST_OBJECT) return false;
          if (!window.__tomatoDispatch) {
            window.__tomatoDispatch = function (id, action, payload) {
              var host = window.$HOST_OBJECT;
              try {
                tauri.core.invoke('dispatch', { action: action, payload: payload || {} }).then(function (result) {
                  host.onResult(id, JSON.stringify(result === undefined ? null : result));
                }, function (error) {
                  host.onError(id, typeof error === 'string' ? error : JSON.stringify(error));
                });
              } catch (error) {
                host.onError(id, String((error && error.message) || error));
              }
            };
            // The downloader's own page runs alongside; keep it from opening blocking dialogs.
            window.alert = function () {};
            window.confirm = function () { return false; };
            window.prompt = function () { return null; };
          }
          return true;
        })();
    """.trimIndent()
}
