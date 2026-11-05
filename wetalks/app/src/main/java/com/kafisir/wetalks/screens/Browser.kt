package com.kafisir.wetalks.screens

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kafisir.wetalks.ui.*

/** In-app browser so links never leave WeTalks. Cookies/cache are wiped on exit; no saved forms or passwords. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(url: String) {
    var title by remember { mutableStateOf(android.net.Uri.parse(url).host ?: url) }
    var progress by remember { mutableIntStateOf(0) }
    var web by remember { mutableStateOf<WebView?>(null) }
    androidx.activity.compose.BackHandler(enabled = web?.canGoBack() == true) { web?.goBack() }
    DisposableEffect(Unit) { onDispose { web?.let { it.stopLoading(); it.clearCache(true); it.destroy() }; CookieManager.getInstance().removeAllCookies(null); WebStorage.getInstance().deleteAllData() } }
    Column(Modifier.fillMaxSize().background(We.bg)) {
        TopBar(title, onBack = { Nav.pop() }, subtitle = android.net.Uri.parse(url).host)
        if (progress in 1..99) androidx.compose.material3.LinearProgressIndicator({ progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = We.primary)
        AndroidView(factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = true; settings.domStorageEnabled = true; settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.setSupportMultipleWindows(false); settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW; settings.saveFormData = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest): Boolean = r.url.scheme !in listOf("http", "https")
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(v: WebView, p: Int) { progress = p }
                    override fun onReceivedTitle(v: WebView, t: String?) { if (!t.isNullOrEmpty()) title = t }
                }
                loadUrl(url); web = this
            }
        }, modifier = Modifier.weight(1f).fillMaxWidth())
    }
}
