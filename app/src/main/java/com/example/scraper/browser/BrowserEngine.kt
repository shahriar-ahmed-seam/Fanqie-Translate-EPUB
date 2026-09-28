package com.example.scraper.browser

import kotlinx.coroutines.flow.StateFlow

data class BrowserUiState(
    val url: String = "",
    val title: String = "",
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Incremented on every main-frame navigation (page start or same-document URL change). */
    val navigationCount: Long = 0L,
    /** HTTP status of the main document when it was an error (>= 400), else 0. */
    val httpStatus: Int = 0,
    /** Network-level error of the main document (DNS, offline, cleartext blocked...). */
    val mainFrameError: String? = null,
    val rendererRestarts: Int = 0
)

/**
 * The browser the scraper drives. The production implementation wraps a single long-lived WebView;
 * tests use a scripted fake so the scrape loop can be exercised without a device.
 */
interface BrowserEngine {
    val state: StateFlow<BrowserUiState>

    /** Loads [url]. Must be safe to call from any thread. */
    fun loadUrl(url: String)
    fun reload()
    fun stopLoading()
    fun goBack(): Boolean
    fun goForward(): Boolean

    /** Runs [script] in the main frame and returns the raw `evaluateJavascript` callback value, or null. */
    suspend fun evaluate(script: String): String?
}
