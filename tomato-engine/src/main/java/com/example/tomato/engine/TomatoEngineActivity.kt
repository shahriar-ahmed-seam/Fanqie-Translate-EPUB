package com.example.tomato.engine

import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import com.pofl.fanqienoveldownloader.TauriActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the engine's Tauri runtime (the runtime needs an Activity and a WebView). The window is
 * transparent, never takes touches or focus (taps go straight to the app underneath), and moves
 * itself behind the app as soon as its WebView exists. The engine starts and keeps working there.
 */
class TomatoEngineActivity : TauriActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engineWebView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        super.onCreate(savedInstanceState)
        EngineHost.onActivityCreated(this)
        scope.launch {
            EngineBridge.state.first { it == EngineBridge.State.READY || it == EngineBridge.State.FAILED }
            moveTaskToBack(true)
        }
    }

    override fun onWebViewCreate(webView: WebView) {
        super.onWebViewCreate(webView)
        engineWebView = webView
        // The downloader's own page keeps running but is never shown.
        webView.alpha = 0f
        EngineBridge.attach(webView)
        // Step behind the app as soon as the WebView exists; the engine keeps starting there. While
        // this window is in front, Android has no window to give key input to (this one takes none),
        // so a Back press during the seconds the engine needs to start made Android report "not
        // responding" and kill the engine, which left running downloads paused.
        scope.launch {
            delay(MOVE_BACK_DELAY_MS)
            if (!isFinishing) moveTaskToBack(true)
        }
    }

    private companion object {
        /** Lets the first frame happen (the runtime starts the page after the window is shown). */
        const val MOVE_BACK_DELAY_MS = 300L
    }

    override fun onDestroy() {
        scope.cancel()
        EngineBridge.detach(engineWebView)
        engineWebView = null
        EngineHost.onActivityDestroyed(this)
        super.onDestroy()
    }
}
