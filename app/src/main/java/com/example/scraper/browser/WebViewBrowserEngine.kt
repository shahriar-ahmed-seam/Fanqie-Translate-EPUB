package com.example.scraper.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import kotlin.coroutines.resume

/**
 * One WebView for the whole process. It is created lazily, survives screen changes and activity
 * recreation (the page, cookies and back/forward history stay), and is re-parented into whatever
 * container the UI currently shows. While no screen shows it, it keeps its last layout size so the
 * page still has a real viewport for scripts that depend on layout.
 */
class WebViewBrowserEngine(
    private val appContext: Context,
    private val startUrl: () -> String,
    private val onPageFinished: (url: String, title: String) -> Unit
) : BrowserEngine {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val contextWrapper = MutableContextWrapper(appContext)
    private var webView: WebView? = null
    private var container: FrameLayout? = null
    private var lastWidth = 0
    private var lastHeight = 0

    private val _state = MutableStateFlow(BrowserUiState())
    override val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    val isAttached: Boolean get() = container != null

    /** Shows the browser inside [target]. Creates the WebView (and loads the start page) on first use. */
    @MainThread
    fun attach(target: FrameLayout, activityContext: Context) {
        contextWrapper.baseContext = activityContext
        val view = ensureWebView(loadStartPage = true)
        container = target
        if (view == null || view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Removes the browser from [target] without destroying the page. */
    @MainThread
    fun detach(target: FrameLayout) {
        if (container !== target) return
        val view = webView
        if (view != null && view.parent === target) target.removeView(view)
        container = null
        contextWrapper.baseContext = appContext
        view?.let { keepLaidOut(it) }
    }

    override fun loadUrl(url: String) = onMain {
        val view = ensureWebView(loadStartPage = false) ?: return@onMain
        showInContainer(view)
        view.loadUrl(toLoadableUrl(url))
    }

    override fun reload() = onMain {
        val view = webView
        if (view != null) {
            view.reload()
        } else {
            // The browser couldn't start earlier: try again (Reload on the error card).
            ensureWebView(loadStartPage = true)?.let { showInContainer(it) }
        }
    }

    override fun stopLoading() = onMain {
        webView?.stopLoading()
        _state.update { it.copy(isLoading = false) }
    }

    @MainThread
    override fun goBack(): Boolean {
        val view = webView ?: return false
        if (!view.canGoBack()) return false
        view.goBack()
        return true
    }

    @MainThread
    override fun goForward(): Boolean {
        val view = webView ?: return false
        if (!view.canGoForward()) return false
        view.goForward()
        return true
    }

    override suspend fun evaluate(script: String): String? = withContext(Dispatchers.Main.immediate) {
        val view = webView ?: return@withContext null
        withTimeoutOrNull(EVAL_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                try {
                    view.evaluateJavascript(script) { result -> if (cont.isActive) cont.resume(result) }
                } catch (t: Throwable) {
                    Log.w(TAG, "evaluateJavascript failed", t)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    /**
     * Returns the WebView, creating it on first use. Returns null (and shows the reason on the
     * browser screen) if the system WebView can't start, e.g. while it is being updated, missing,
     * or its data directory is locked. That used to crash the whole app.
     */
    @MainThread
    private fun ensureWebView(loadStartPage: Boolean): WebView? {
        webView?.let { return it }
        val view = try {
            createWebView()
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            Log.e(TAG, "WebView couldn't be created", t)
            _state.update { it.copy(isLoading = false, mainFrameError = webViewStartError(t)) }
            return null
        }
        webView = view
        if (_state.value.mainFrameError?.startsWith(BROWSER_START_ERROR) == true) {
            _state.update { it.copy(mainFrameError = null) }
        }
        keepLaidOut(view)
        if (loadStartPage) {
            val initial = startUrl().trim()
            if (initial.isNotEmpty()) view.loadUrl(toLoadableUrl(initial))
        }
        return view
    }

    /** Puts a (re)created WebView into the container the UI currently shows, if any. */
    @MainThread
    private fun showInContainer(view: WebView) {
        val target = container ?: return
        if (view.parent === target) return
        (view.parent as? ViewGroup)?.removeView(view)
        target.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun keepLaidOut(view: WebView) {
        val metrics = appContext.resources.displayMetrics
        val w = if (lastWidth > 0) lastWidth else metrics.widthPixels
        val h = if (lastHeight > 0) lastHeight else metrics.heightPixels
        if (w <= 0 || h <= 0) return
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, w, h)
    }

    @SuppressLint("SetJavaScriptEnabled")
    @MainThread
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(com.example.BuildConfig.DEBUG)
        val view = WebView(contextWrapper)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            allowFileAccess = false
            allowContentAccess = false
            // The default user agent is kept on purpose: sites behind Cloudflare trust a real WebView UA.
        }
        view.webViewClient = client
        view.webChromeClient = chromeClient
        view.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            if (right - left > 0 && bottom - top > 0 && container != null) {
                lastWidth = right - left
                lastHeight = bottom - top
            }
        }
        return view
    }

    private fun updateNav(view: WebView) {
        _state.update { it.copy(canGoBack = view.canGoBack(), canGoForward = view.canGoForward()) }
    }

    private val client = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            _state.update {
                it.copy(
                    url = url ?: it.url,
                    isLoading = true,
                    progress = 0,
                    httpStatus = 0,
                    mainFrameError = null,
                    navigationCount = it.navigationCount + 1
                )
            }
            updateNav(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            val finalUrl = url ?: view.url ?: _state.value.url
            val title = view.title.orEmpty()
            _state.update { it.copy(url = finalUrl, title = title.ifBlank { it.title }, isLoading = false, progress = 100) }
            updateNav(view)
            CookieManager.getInstance().flush()
            if (finalUrl.startsWith("http://") || finalUrl.startsWith("https://")) {
                try {
                    onPageFinished(finalUrl, title)
                } catch (t: Throwable) {
                    Log.w(TAG, "onPageFinished listener failed", t)
                }
            }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            if (url == null) return
            _state.update { st ->
                if (st.url == url) st else st.copy(url = url, navigationCount = st.navigationCount + 1)
            }
            updateNav(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                _state.update { it.copy(mainFrameError = error.description?.toString() ?: "Network error", isLoading = false) }
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
            if (request.isForMainFrame) {
                _state.update { it.copy(httpStatus = errorResponse.statusCode) }
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url?.scheme?.lowercase() ?: return false
            // Keep navigation inside the scraper browser; block intent://, market:// and app schemes.
            return scheme !in ALLOWED_SCHEMES
        }

        @RequiresApi(Build.VERSION_CODES.O)
        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            Log.w(TAG, "WebView renderer gone (crash=${detail.didCrash()}); recreating")
            recoverFromRendererCrash(view)
            return true
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            _state.update { it.copy(progress = newProgress) }
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            _state.update { it.copy(title = title.orEmpty()) }
        }

        // JS dialogs need an activity window. Without one (page running in background) dismiss them.
        override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (container != null) return false
            result.confirm()
            return true
        }

        override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            if (container != null) return false
            result.cancel()
            return true
        }

        override fun onJsPrompt(view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
            if (container != null) return false
            result.cancel()
            return true
        }

        override fun onJsBeforeUnload(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
            result.confirm()
            return true
        }
    }

    private fun recoverFromRendererCrash(dead: WebView) {
        val url = _state.value.url
        val parent = dead.parent as? ViewGroup
        parent?.removeView(dead)
        try {
            dead.destroy()
        } catch (t: Throwable) {
            Log.w(TAG, "Destroying crashed WebView failed", t)
        }
        if (webView === dead) webView = null
        val fresh = ensureWebView(loadStartPage = false) ?: return
        val target = container ?: (parent as? FrameLayout)
        if (target != null && fresh.parent == null) {
            target.addView(fresh, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        if (url.startsWith("http")) fresh.loadUrl(url)
        _state.update { it.copy(rendererRestarts = it.rendererRestarts + 1, mainFrameError = null) }
    }

    companion object {
        private const val TAG = "ScraperBrowser"
        private const val EVAL_TIMEOUT_MS = 15_000L
        private const val BROWSER_START_ERROR = "the browser couldn't start"

        /** Message shown on the browser screen when the system WebView can't be created. */
        internal fun webViewStartError(error: Throwable): String {
            val text = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" ")
            val reason = when {
                text.contains("more than one process", ignoreCase = true) ||
                    text.contains("data directory", ignoreCase = true) ->
                    "another part of the app is using it. Tap Reload"
                text.contains("webview package", ignoreCase = true) ||
                    text.contains("MissingWebViewPackage", ignoreCase = true) ||
                    error.javaClass.name.contains("MissingWebViewPackage") ->
                    "Android System WebView is missing or being updated. Update or enable it, then tap Reload"
                else -> "Android System WebView failed to load. Tap Reload"
            }
            return "$BROWSER_START_ERROR ($reason)"
        }
        private val ALLOWED_SCHEMES = setOf("http", "https", "about", "data", "blob", "javascript")

        /** Turns address-bar input into a URL: adds https:// to hosts, searches anything else. */
        fun toLoadableUrl(input: String): String {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return "about:blank"
            val lower = trimmed.lowercase()
            if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("about:")) return trimmed
            val looksLikeHost = !trimmed.contains(' ') && (trimmed.contains('.') || lower.startsWith("localhost"))
            return if (looksLikeHost) "https://$trimmed" else "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, "UTF-8")
        }
    }
}
