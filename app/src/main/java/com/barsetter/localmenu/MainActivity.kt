package com.barsetter.localmenu

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Activity
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var webView: WebView
    private lateinit var launcherView: LinearLayout
    private lateinit var launcherButton: Button
    private lateinit var launcherStatusText: TextView

    private val menuDir: File by lazy { File(filesDir, "menu") }
    private val menuFile: File by lazy { File(menuDir, "menu.json") }
    private val legacyTlsSocketFactory: SSLSocketFactory by lazy { createLegacyTlsSocketFactory() }
    @Volatile private var isDownloading = false
    private var webViewLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureWebView()
        showLauncher()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        webView.destroy()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreenMode()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyFullscreenMode()
    }

    @Deprecated("Deprecated in Android API, still needed for minSdk 21 back behavior.")
    override fun onBackPressed() {
        if (webView.visibility == View.VISIBLE && webView.canGoBack()) {
            webView.goBack()
            return
        }
        super.onBackPressed()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(251, 250, 247))
        }

        webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            visibility = View.GONE
        }

        launcherView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(251, 250, 247))
            setPadding(dp(28), dp(28), dp(28), dp(28))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val titleText = TextView(this).apply {
            text = "BARO"
            textSize = 36f
            setTextColor(Color.rgb(23, 21, 18))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitleText = TextView(this).apply {
            text = "로컬 메뉴판"
            textSize = 14f
            setTextColor(Color.rgb(123, 115, 104))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(22))
        }

        launcherButton = Button(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(23, 21, 18))
            minHeight = dp(50)
            setPadding(dp(22), 0, dp(22), 0)
            setOnClickListener {
                if (menuFile.exists()) {
                    showMenu()
                } else {
                    downloadMenu(openWhenDone = true)
                }
            }
        }

        launcherStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(123, 115, 104))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }

        launcherView.addView(titleText)
        launcherView.addView(subtitleText)
        launcherView.addView(
            launcherButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        launcherView.addView(launcherStatusText)

        root.addView(webView)
        root.addView(launcherView)
        setContentView(root)
        updateLauncherCopy()
        applyFullscreenMode()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(AndroidBridge(), "BarsetterAndroid")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return intercept(request.url)
            }
        }
    }

    private fun showLauncher() {
        updateLauncherCopy()
        launcherView.visibility = View.VISIBLE
        webView.visibility = View.GONE
        applyFullscreenMode()
    }

    private fun showMenu(reload: Boolean = false) {
        launcherView.visibility = View.GONE
        webView.visibility = View.VISIBLE
        applyFullscreenMode()
        if (!webViewLoaded) {
            webViewLoaded = true
            webView.loadUrl("$LOCAL_ORIGIN/index.html?source=local")
        } else if (reload) {
            webView.reload()
        }
    }

    private fun updateLauncherCopy(message: String? = null) {
        launcherButton.text = if (menuFile.exists()) "바로 메뉴판 보기" else "바로 메뉴판 다운로드 하기"
        launcherStatusText.text = message ?: if (menuFile.exists()) {
            "저장된 메뉴판이 있습니다."
        } else {
            "다운로드 후 전체화면 메뉴판이 열립니다."
        }
    }

    private fun applyFullscreenMode() {
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun intercept(uri: Uri): WebResourceResponse? {
        if (uri.scheme != "https" || uri.host != LOCAL_HOST) return null
        val path = normalizePath(uri.path ?: "/")
        return when {
            path == "/" || path == "/index.html" -> assetResponse("www/index.html")
            path.startsWith("/assets/") -> assetResponse("www$path")
            path.startsWith("/json/") -> assetResponse("www$path")
            path == "/local/menu.json" -> localMenuResponse()
            else -> notFoundResponse()
        }
    }

    private fun localMenuResponse(): WebResourceResponse {
        if (menuFile.exists()) {
            return WebResourceResponse("application/json", "utf-8", FileInputStream(menuFile))
        }
        return assetResponse("www/json/baro.json")
    }

    private fun assetResponse(assetPath: String): WebResourceResponse {
        val mimeType = mimeType(assetPath)
        return try {
            WebResourceResponse(mimeType, "utf-8", assets.open(assetPath))
        } catch (_: Exception) {
            notFoundResponse()
        }
    }

    private fun notFoundResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            404,
            "Not Found",
            emptyMap(),
            ByteArrayInputStream("Not Found".toByteArray(StandardCharsets.UTF_8))
        )
    }

    private fun downloadMenu(openWhenDone: Boolean) {
        if (isDownloading) {
            Toast.makeText(this, "메뉴판을 다운로드하는 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        isDownloading = true
        launcherButton.isEnabled = false
        if (launcherView.visibility == View.VISIBLE) {
            launcherStatusText.text = "메뉴판을 다운로드하고 있습니다."
        } else {
            Toast.makeText(this, "메뉴판을 갱신하고 있습니다.", Toast.LENGTH_SHORT).show()
        }

        executor.execute {
            val result = runCatching {
                val jsonText = downloadText(DEFAULT_MENU_SOURCE_URL)
                val parsed = JSONObject(jsonText)
                writeMenu(jsonText)
                val barName = parsed.optJSONObject("bar")?.optString("name").orEmpty()
                val version = parsed.optInt("version", 0)
                if (barName.isNotBlank() && version > 0) "$barName v$version 저장됨" else "메뉴판 저장됨"
            }

            runOnUiThread {
                isDownloading = false
                launcherButton.isEnabled = true
                result.fold(
                    onSuccess = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                        updateLauncherCopy(message)
                        if (openWhenDone || webView.visibility == View.VISIBLE) {
                            showMenu(reload = webViewLoaded)
                        }
                    },
                    onFailure = { error ->
                        val message = "다운로드 실패: ${error.message ?: "알 수 없는 오류"}"
                        updateLauncherCopy(message)
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                )
            }
        }
    }

    private fun confirmMenuRefresh() {
        if (isFinishing) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("메뉴판 갱신")
            .setMessage("BARO 메뉴판을 다시 다운로드할까요?")
            .setNegativeButton("취소", null)
            .setPositiveButton("갱신") { _, _ -> downloadMenu(openWhenDone = true) }
            .create()
        dialog.setOnDismissListener { applyFullscreenMode() }
        dialog.show()
    }

    private fun downloadText(sourceUrl: String): String {
        return try {
            downloadText(sourceUrl, sslSocketFactory = null)
        } catch (error: SSLHandshakeException) {
            downloadText(sourceUrl, sslSocketFactory = legacyTlsSocketFactory)
        }
    }

    private fun downloadText(sourceUrl: String, sslSocketFactory: SSLSocketFactory?): String {
        val connection = (URL(sourceUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        if (connection is HttpsURLConnection && sslSocketFactory != null) {
            connection.sslSocketFactory = sslSocketFactory
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader -> reader.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun writeMenu(jsonText: String) {
        menuDir.mkdirs()
        val tmpFile = File(menuDir, "menu.json.tmp")
        tmpFile.writeText(jsonText, StandardCharsets.UTF_8)
        if (!tmpFile.renameTo(menuFile)) {
            tmpFile.copyTo(menuFile, overwrite = true)
            tmpFile.delete()
        }
    }

    private fun readMenuText(): String {
        if (menuFile.exists()) return menuFile.readText(StandardCharsets.UTF_8)
        return String(assets.open("www/json/baro.json").use(InputStream::readBytes), StandardCharsets.UTF_8)
    }

    private fun mimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".svg") -> "image/svg+xml"
            path.endsWith(".png") -> "image/png"
            path.endsWith(".webp") -> "image/webp"
            else -> "application/octet-stream"
        }
    }

    private fun normalizePath(path: String): String {
        return if (path.isBlank()) "/" else path.replace(Regex("/{2,}"), "/")
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun createLegacyTlsSocketFactory(): SSLSocketFactory {
        val certificateFactory = CertificateFactory.getInstance("X.509")
        val certificate = resources.openRawResource(R.raw.isrg_root_x1).use { stream ->
            certificateFactory.generateCertificate(stream)
        }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("isrg_root_x1", certificate)
        }
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }
        return SSLContext.getInstance("TLS").apply {
            init(null, trustManagerFactory.trustManagers, null)
        }.socketFactory
    }

    inner class AndroidBridge {
        @JavascriptInterface
        fun menuSlug(): String = "__barsetter_local__"

        @JavascriptInterface
        fun readMenuJson(): String = readMenuText()

        @JavascriptInterface
        fun notifyMenuLoaded(version: String) {
            runOnUiThread {
                if (version.isNotBlank()) updateLauncherCopy("메뉴판 v$version 로드됨")
            }
        }

        @JavascriptInterface
        fun requestMenuRefresh() {
            runOnUiThread { confirmMenuRefresh() }
        }
    }

    companion object {
        private const val LOCAL_HOST = "barsetter.local"
        private const val LOCAL_ORIGIN = "https://barsetter.local"
        private const val DEFAULT_MENU_SOURCE_URL = "https://barsetter-client.pages.dev/json/baro.json"
    }
}
