package com.template.webapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/**
 * Универсальная WebView-обёртка шаблона.
 *
 * Все настройки (fullscreen / zoom / orientation / startPath) конвертер записывает
 * в assets/webapp_config.json, поэтому пересобирать шаблон под каждую настройку не нужно.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private var config = WebConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = loadConfig()
        applyOrientation(config.orientation)
        if (config.fullscreen) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
        }

        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSET_DOMAIN)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this)
        webView.setBackgroundColor(Color.BLACK)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        if (config.hardwareAcceleration) {
            webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null)
        }

        configureWebView(webView)
        setContentView(webView)

        val startUrl = "https://$ASSET_DOMAIN/assets/web/${config.startPath}"
        if (savedInstanceState == null) {
            webView.loadUrl(startUrl)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(view: WebView) {
        val settings: WebSettings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportZoom(config.zoom)
        settings.builtInZoomControls = config.zoom
        settings.displayZoomControls = false
        if (config.zoom) {
            settings.textZoom = 100
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                if (url.host == ASSET_DOMAIN) return false
                return try {
                    startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, url))
                    true
                } catch (_: Exception) {
                    true
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100) applyFullscreenIfNeeded()
            }
        }
    }

    private fun applyFullscreenIfNeeded() {
        if (!config.fullscreen) return
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyOrientation(orientation: String) {
        requestedOrientation = when (orientation.lowercase()) {
            "portrait" -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            "landscape" -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            "sensor" -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun loadConfig(): WebConfig {
        return try {
            val raw = assets.open(CONFIG_ASSET).bufferedReader().use { it.readText() }
            val json = JSONObject(raw)
            WebConfig(
                fullscreen = json.optBoolean("fullscreen", true),
                zoom = json.optBoolean("zoom", false),
                hardwareAcceleration = json.optBoolean("hardwareAcceleration", true),
                orientation = json.optString("orientation", "unspecified"),
                startPath = json.optString("startPath", "index.html")
            )
        } catch (_: Throwable) {
            WebConfig()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webView.isInitialized) webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private data class WebConfig(
        val fullscreen: Boolean = true,
        val zoom: Boolean = false,
        val hardwareAcceleration: Boolean = true,
        val orientation: String = "unspecified",
        val startPath: String = "index.html"
    )

    private companion object {
        const val ASSET_DOMAIN = "appassets.androidplatform.net"
        const val CONFIG_ASSET = "webapp_config.json"
    }
}
