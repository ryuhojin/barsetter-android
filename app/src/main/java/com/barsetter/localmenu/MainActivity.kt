package com.barsetter.localmenu

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Activity
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
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
import android.widget.EditText
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
import java.util.Locale
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
    private lateinit var barCodeInput: EditText

    private val preferences: SharedPreferences by lazy { getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE) }
    private val menuDir: File by lazy { File(filesDir, "menu") }
    private val menuFile: File by lazy { File(menuDir, "menu.json") }
    private val legacyTlsSocketFactory: SSLSocketFactory by lazy { createLegacyTlsSocketFactory() }
    @Volatile private var isDownloading = false
    private var webViewLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        configureWebView()
        if (hasStoredBarCode() && menuFile.exists()) {
            showMenu()
        } else {
            showLauncher()
        }
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
            text = "barsetter"
            textSize = 34f
            setTextColor(Color.rgb(23, 21, 18))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitleText = TextView(this).apply {
            text = "바 코드를 입력해 메뉴판을 설정하세요."
            textSize = 14f
            setTextColor(Color.rgb(123, 115, 104))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(18))
        }

        val inputLabel = TextView(this).apply {
            text = "바 코드"
            textSize = 13f
            setTextColor(Color.rgb(123, 115, 104))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6))
        }

        barCodeInput = EditText(this).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = DEFAULT_BAR_CODE
            setText(selectedBarCode().ifBlank { DEFAULT_BAR_CODE })
            setSelectAllOnFocus(true)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(23, 21, 18))
            setHintTextColor(Color.rgb(156, 146, 134))
            setPadding(dp(14), 0, dp(14), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    updateLauncherCopy()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        launcherButton = Button(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(23, 21, 18))
            minHeight = dp(50)
            setPadding(dp(22), 0, dp(22), 0)
            setOnClickListener { handleLauncherButtonClick() }
        }

        launcherStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.rgb(123, 115, 104))
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, 0)
        }

        launcherView.addView(titleText)
        launcherView.addView(subtitleText)
        launcherView.addView(inputLabel)
        launcherView.addView(
            barCodeInput,
            LinearLayout.LayoutParams(
                dp(260),
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(18)
            }
        )
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
        if (::barCodeInput.isInitialized && barCodeInput.text?.toString().orEmpty().isBlank()) {
            barCodeInput.setText(selectedBarCode().ifBlank { DEFAULT_BAR_CODE })
        }
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
        if (!::launcherButton.isInitialized || !::launcherStatusText.isInitialized) return

        val typedCode = normalizedBarCodeInput()
        val typedSlug = decodedSlugFromBarCode(typedCode)
        val isStoredSelection = typedCode.isNotBlank() &&
            typedCode == selectedBarCode() &&
            typedSlug.isNotBlank() &&
            typedSlug == selectedBarSlug()
        val canOpenStoredMenu = menuFile.exists() && hasStoredBarCode() && isStoredSelection

        launcherButton.text = if (canOpenStoredMenu) "메뉴판 보기" else "메뉴판 다운로드 하기"
        launcherStatusText.text = message ?: when {
            typedCode.isBlank() -> "바 코드를 입력하세요. 예: $DEFAULT_BAR_CODE"
            typedSlug.isBlank() -> "바 코드를 확인하세요. 예: $DEFAULT_BAR_CODE"
            canOpenStoredMenu -> "${currentMenuBarName().ifBlank { selectedBarSlug() }} 메뉴판이 저장되어 있습니다."
            menuFile.exists() && selectedBarSlug().isNotBlank() && typedSlug != selectedBarSlug() -> {
                "입력한 바 코드로 새 메뉴판을 다운로드합니다."
            }
            else -> "다운로드 후 전체화면 메뉴판이 열립니다."
        }
    }

    private fun handleLauncherButtonClick() {
        val selection = barSelectionFromInput() ?: run {
            updateLauncherCopy("바 코드를 확인하세요. 예: $DEFAULT_BAR_CODE")
            return
        }
        val previousSlug = selectedBarSlug()
        val selectionChanged = selection.slug != previousSlug || selection.code != selectedBarCode()

        if (selectionChanged) {
            storeBarSelection(selection)
            if (menuFile.exists()) menuFile.delete()
            webViewLoaded = false
        } else if (!hasStoredBarCode()) {
            storeBarSelection(selection)
        }

        if (menuFile.exists() && !selectionChanged) {
            showMenu()
        } else {
            downloadMenu(openWhenDone = true)
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
            path.startsWith("/fonts/") -> assetResponse("www$path")
            path.startsWith("/json/") -> assetResponse("www$path")
            path == "/local/menu.json" -> localMenuResponse()
            else -> notFoundResponse()
        }
    }

    private fun localMenuResponse(): WebResourceResponse {
        if (menuFile.exists()) {
            return WebResourceResponse("application/json", "utf-8", FileInputStream(menuFile))
        }
        return bundledMenuResponse()
    }

    private fun bundledMenuResponse(): WebResourceResponse {
        val slug = selectedBarSlug().ifBlank { DEFAULT_BAR_SLUG }
        val assetPath = "www/json/$slug.json"
        return try {
            WebResourceResponse("application/json", "utf-8", assets.open(assetPath))
        } catch (_: Exception) {
            assetResponse("www/json/$DEFAULT_BAR_SLUG.json")
        }
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
        if (!hasStoredBarCode()) {
            val selection = barSelectionFromInput()
            if (selection == null) {
                showLauncher()
                updateLauncherCopy("바 코드를 먼저 입력하세요. 예: $DEFAULT_BAR_CODE")
                return
            }
            storeBarSelection(selection)
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
                val jsonText = downloadText(menuSourceUrl())
                val parsed = JSONObject(jsonText)
                parsed.optJSONObject("bar")?.optString("slug").orEmpty().takeIf { isValidSlug(it) }?.let { slug ->
                    preferences.edit().putString(KEY_BAR_SLUG, slug).apply()
                }
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
        if (!hasStoredBarCode()) {
            showLauncher()
            updateLauncherCopy("바 코드를 먼저 입력하세요. 예: $DEFAULT_BAR_CODE")
            return
        }
        val barName = currentMenuBarName().ifBlank { selectedBarSlug() }
        val dialog = AlertDialog.Builder(this)
            .setTitle("메뉴판 갱신")
            .setMessage("${barName.ifBlank { "현재 바" }} 메뉴판을 다시 다운로드할까요?")
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
        return readBundledMenuText()
    }

    private fun readBundledMenuText(): String {
        val slug = selectedBarSlug().ifBlank { DEFAULT_BAR_SLUG }
        return runCatching {
            String(assets.open("www/json/$slug.json").use(InputStream::readBytes), StandardCharsets.UTF_8)
        }.getOrElse {
            String(assets.open("www/json/$DEFAULT_BAR_SLUG.json").use(InputStream::readBytes), StandardCharsets.UTF_8)
        }
    }

    private fun currentMenuBarName(): String {
        return runCatching {
            JSONObject(readMenuText()).optJSONObject("bar")?.optString("name").orEmpty()
        }.getOrDefault("")
    }

    private fun barSelectionFromInput(): BarSelection? {
        val code = normalizedBarCodeInput()
        val slug = decodedSlugFromBarCode(code)
        return if (code.isNotBlank() && slug.isNotBlank()) BarSelection(code, slug) else null
    }

    private fun normalizedBarCodeInput(): String {
        if (!::barCodeInput.isInitialized) return selectedBarCode()
        return normalizeBarCode(barCodeInput.text?.toString().orEmpty())
    }

    private fun normalizeBarCode(rawCode: String): String {
        var code = rawCode.trim()
        if (code.contains("://")) {
            code = runCatching { Uri.parse(code).lastPathSegment.orEmpty() }.getOrDefault(code)
        }
        return code
            .substringBefore("?")
            .substringBefore("#")
            .trim()
            .trim('/')
    }

    private fun decodedSlugFromBarCode(code: String): String {
        if (code.isBlank()) return ""
        val normalized = code.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return runCatching {
            val decoded = String(Base64.decode(padded, Base64.DEFAULT), StandardCharsets.UTF_8)
                .trim()
                .lowercase(Locale.US)
            decoded.takeIf { isValidSlug(it) }.orEmpty()
        }.getOrDefault("")
    }

    private fun selectedBarCode(): String {
        return preferences.getString(KEY_BAR_CODE, "").orEmpty()
    }

    private fun selectedBarSlug(): String {
        val storedSlug = preferences.getString(KEY_BAR_SLUG, "").orEmpty()
        if (isValidSlug(storedSlug)) return storedSlug
        return decodedSlugFromBarCode(selectedBarCode())
    }

    private fun hasStoredBarCode(): Boolean {
        return selectedBarCode().isNotBlank() && selectedBarSlug().isNotBlank()
    }

    private fun storeBarSelection(selection: BarSelection) {
        preferences.edit()
            .putString(KEY_BAR_CODE, selection.code)
            .putString(KEY_BAR_SLUG, selection.slug)
            .apply()
    }

    private fun menuSourceUrl(): String {
        return "$MENU_SOURCE_BASE_URL${Uri.encode(selectedBarSlug())}.json"
    }

    private fun isValidSlug(slug: String): Boolean {
        return slug.isNotBlank() && BAR_SLUG_PATTERN.matches(slug)
    }

    private fun mimeType(path: String): String {
        return when {
            path.endsWith(".html") -> "text/html"
            path.endsWith(".js") -> "application/javascript"
            path.endsWith(".css") -> "text/css"
            path.endsWith(".json") -> "application/json"
            path.endsWith(".woff2") -> "font/woff2"
            path.endsWith(".woff") -> "font/woff"
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
        fun menuSlug(): String = selectedBarSlug().ifBlank { DEFAULT_BAR_SLUG }

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

    private data class BarSelection(val code: String, val slug: String)

    companion object {
        private const val LOCAL_HOST = "barsetter.local"
        private const val LOCAL_ORIGIN = "https://barsetter.local"
        private const val PREFERENCES_NAME = "barsetter_local_menu"
        private const val KEY_BAR_CODE = "bar_code"
        private const val KEY_BAR_SLUG = "bar_slug"
        private const val DEFAULT_BAR_CODE = "YmFybw"
        private const val DEFAULT_BAR_SLUG = "baro"
        private const val MENU_SOURCE_BASE_URL = "https://barsetter-client.pages.dev/json/"
        private val BAR_SLUG_PATTERN = Regex("^[a-z0-9가-힣_-]+$")
    }
}
