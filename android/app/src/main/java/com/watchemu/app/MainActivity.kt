package com.watchemu.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

class MainActivity : ComponentActivity() {

    private var webView: WebView? = null

    private val romPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val bytes = contentResolver.openInputStream(uri)?.readBytes() ?: return@registerForActivityResult
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        webView?.evaluateJavascript("loadRomFromAndroid('$b64')", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WatchEmuScreen(
                onWebViewCreated = { wv ->
                    webView = wv
                    setupWebView(wv)
                }
            )
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(wv: WebView) {
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        wv.webChromeClient = WebChromeClient()
        wv.webViewClient = WebViewClient()
        wv.setBackgroundColor(0xFF000000.toInt())
        wv.addJavascriptInterface(RomBridge(), "AndroidBridge")
        wv.loadUrl("file:///android_asset/emu/index.html")
    }

    inner class RomBridge {
        @JavascriptInterface
        fun pickRom() {
            romPicker.launch("*/*")
        }
    }
}

@Composable
fun WatchEmuScreen(onWebViewCreated: (WebView) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onWebViewCreated(this)
            }
        }
    )
}
