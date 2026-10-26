/* Generated from the wry 0.57.1 template (Apache-2.0 OR MIT) for the Tomato engine host. */

// Copyright 2020-2023 Tauri Programme within The Commons Conservancy
// SPDX-License-Identifier: Apache-2.0
// SPDX-License-Identifier: MIT

@file:Suppress("unused")

package com.pofl.fanqienoveldownloader

import android.webkit.*

class Ipc(val webView: RustWebView, val webViewClient: RustWebViewClient) {
    @JavascriptInterface
    fun postMessage(message: String?) {
        message?.let {m ->
            // we're not using WebView::getUrl() here because it needs to be executed on the main thread
            // and it would slow down the Ipc
            // so instead we track the current URL on the webview client
            Rust.ipc(webView.id, webViewClient.currentUrl, m)
        }
    }

    
}
