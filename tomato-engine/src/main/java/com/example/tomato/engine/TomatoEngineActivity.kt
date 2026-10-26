package com.example.tomato.engine

import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebView
import com.pofl.fanqienoveldownloader.TauriActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts the engine's Tauri runtime (the runtime needs an Activity and a WebView). The window is
 * transparent, never takes touches or focus (taps go straight to the app underneath), and moves
 * itself behind the app once the engine answers. The engine keeps working from there.
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
    }

    override fun onDestroy() {
        scope.cancel()
        EngineBridge.detach(engineWebView)
        engineWebView = null
        EngineHost.onActivityDestroyed(this)
        super.onDestroy()
    }
}
