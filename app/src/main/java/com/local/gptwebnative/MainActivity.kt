package com.local.gptwebnative

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.os.SystemClock
import android.util.Log
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ProgressBar
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val HOME_URL = "https://chatgpt.com/"
        private const val FILE_CHOOSER_REQUEST = 1001
        private const val WEB_PERMISSION_REQUEST = 1002

        private val INTERNAL_HOSTS = setOf(
            "chatgpt.com",
            "openai.com",
            "auth.openai.com",
            "auth0.openai.com",
            "setup.auth.openai.com",
            "setup.workos.com",
            "forwarder.workos.com"
        )
    }

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var statusScrim: View
    private lateinit var navigationScrim: View

    private var popupWebView: WebView? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var cameraOutputUri: Uri? = null

    private var pendingWebPermission: PermissionRequest? = null
    private var pendingWebResources: Array<String> = emptyArray()

    private val preferences by lazy { getSharedPreferences("resume_state", Context.MODE_PRIVATE) }
    private val homeBannerScript by lazy {
        assets.open("home-banner.js").bufferedReader().use { it.readText() }
    }
    private var pageColor = Color.WHITE
    private var darkPage = false
    private var mainWebViewAlive = true
    private var loadFailed = false
    private var pageStartedAt = 0L
    private var navigationGeneration = 0L
    private lateinit var loadingView: TextView
    private lateinit var progressView: ProgressBar
    private val slowLoad = Runnable {
        if (!isDestroyed && loadingView.visibility == View.VISIBLE && !loadFailed) {
            showLoadMessage("Still loading. Tap to retry.")
        }
    }

    // Event names and timing only: never log URLs, cookies, page text or titles.
    private fun trace(event: String) {
        Log.i("GptWebLifecycle", "${SystemClock.elapsedRealtime()} $event")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        trace(if (savedInstanceState == null) "activity_create_fresh" else "activity_create_restore")
        darkPage = preferences.getBoolean("dark", resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        pageColor = preferences.getInt("color", if (darkPage) Color.rgb(33, 33, 33) else Color.WHITE)
        window.setBackgroundDrawable(ColorDrawable(pageColor))

        configureEdgeToEdge()
        buildViewHierarchy()
        configureMainWebView(webView)
        configureInsets()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) { handleBack() }
        }

        updateSystemBarIconAppearance(darkPage)
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null || restored.size == 0) webView.loadUrl(resumeUrl())
        loadingView.postDelayed(slowLoad, 15000)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (mainWebViewAlive) webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        trace("activity_resume")
        if (mainWebViewAlive) webView.onResume()
    }

    override fun onPause() {
        if (mainWebViewAlive) {
            rememberPage(webView.url)
            webView.onPause()
        }
        trace("activity_pause")
        super.onPause()
    }

    override fun onStop() {
        trace("activity_stop")
        super.onStop()
    }

    private fun restorableUrl(raw: String?): String? {
        val uri = raw?.let(Uri::parse) ?: return null
        if (uri.scheme != "https" || uri.host != "chatgpt.com" ||
            uri.userInfo != null || uri.port !in listOf(-1, 443)) return null
        val path = uri.path ?: "/"
        // Save only home/conversation routes, never OAuth codes or temporary-chat flags.
        if (uri.getQueryParameter("temporary-chat") == "true") return HOME_URL
        if (path != "/" && !Regex("^/(?:g/[A-Za-z0-9_-]+/)?c/[A-Za-z0-9_-]+/?$").matches(path)) return null
        return uri.buildUpon().clearQuery().fragment(null).build().toString()
    }

    private fun resumeUrl(): String =
        restorableUrl(preferences.getString("url", HOME_URL)) ?: HOME_URL

    private fun rememberPage(raw: String?) {
        val uri = raw?.let(Uri::parse) ?: return
        if (uri.host == "chatgpt.com" && uri.path.orEmpty().startsWith("/auth/")) {
            preferences.edit().remove("url").apply()
            return
        }
        val url = restorableUrl(raw) ?: return
        if (preferences.getString("url", null) != url) preferences.edit().putString("url", url).apply()
    }

    private fun showLoadMessage(message: String) {
        loadingView.animate().cancel()
        loadingView.alpha = 1f
        loadingView.text = message
        loadingView.visibility = View.VISIBLE
        loadingView.isClickable = true
        loadingView.setOnClickListener { retryPage() }
    }

    private fun retryPage() {
        trace("retry")
        if (!mainWebViewAlive) {
            webView = WebView(this).apply {
                setBackgroundColor(pageColor)
                layoutParams = FrameLayout.LayoutParams(-1, -1)
            }
            mainWebViewAlive = true
            configureMainWebView(webView)
            root.addView(webView, 0)
            root.requestApplyInsets()
            webView.loadUrl(resumeUrl())
        } else {
            webView.reload()
        }
    }

    private fun revealPage() {
        if (loadFailed) return
        loadingView.removeCallbacks(slowLoad)
        // Keep the live WebView visible during subsequent conversation navigation.
        loadingView.visibility = View.GONE
        trace("page_visible")
    }

    private fun configureEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun buildViewHierarchy() {
        root = FrameLayout(this).apply {
            setBackgroundColor(pageColor)
        }

        webView = WebView(this).apply {
            setBackgroundColor(pageColor)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusScrim = View(this).apply {
            setBackgroundColor(pageColor)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { gravity = android.view.Gravity.TOP }
        }

        navigationScrim = View(this).apply {
            setBackgroundColor(pageColor)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { gravity = android.view.Gravity.BOTTOM }
        }

        root.addView(webView)
        root.addView(statusScrim)
        root.addView(navigationScrim)
        loadingView = TextView(this).apply {
            text = "Loading…"
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(pageColor)
            setTextColor(if (darkPage) Color.WHITE else Color.DKGRAY)
            layoutParams = FrameLayout.LayoutParams(-1, -1)
        }
        progressView = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(-1, (2 * resources.displayMetrics.density).toInt())
        }
        root.addView(loadingView)
        root.addView(progressView)
        setContentView(root)
    }

    private fun configureInsets() {
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top
                bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }

            val contentBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                maxOf(bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)
            } else {
                bottom
            }

            (webView.layoutParams as FrameLayout.LayoutParams).apply {
                // Draw the WebView behind the transparent status bar.
                // ChatGPT already handles its own top safe area.
                topMargin = 0
                bottomMargin = contentBottom
                webView.layoutParams = this
            }

            popupWebView?.let { popup ->
                val params = popup.layoutParams as FrameLayout.LayoutParams
                if (params.bottomMargin != contentBottom) {
                    params.bottomMargin = contentBottom
                    popup.layoutParams = params
                }
            }
            statusScrim.layoutParams = (statusScrim.layoutParams as FrameLayout.LayoutParams).apply {
                height = top
            }
            navigationScrim.layoutParams =
                (navigationScrim.layoutParams as FrameLayout.LayoutParams).apply {
                    height = bottom
                }
            (loadingView.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = top
                bottomMargin = contentBottom
                loadingView.layoutParams = this
            }
            (progressView.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = top
                progressView.layoutParams = this
            }
            insets
        }
        root.requestApplyInsets()
    }

    private fun configureMainWebView(view: WebView) {
        configureCommonWebSettings(view)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }

        view.addJavascriptInterface(PageUiBridge(), "NativeUi")

        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                navigationGeneration++
                loadFailed = false
                pageStartedAt = SystemClock.elapsedRealtime()
                trace("main_document_start")
                if (loadingView.visibility == View.VISIBLE) {
                    loadingView.text = "Loading…"
                    loadingView.isClickable = false
                    loadingView.removeCallbacks(slowLoad)
                    loadingView.postDelayed(slowLoad, 15000)
                }
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                rememberPage(url)
                applyHomeBannerRule(view, url)
                trace(if (isReload) "history_reload" else "history_update")
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                revealPage()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!request.isForMainFrame) return
                loadFailed = true
                loadingView.removeCallbacks(slowLoad)
                progressView.visibility = View.GONE
                trace("main_document_error_${error.errorCode}")
                showLoadMessage("Unable to load page. Check your connection and tap to retry.")
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (request.isForMainFrame) trace("main_http_${response.statusCode}")
                // Preserve server-rendered sign-in/challenge/error pages.
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                trace(if (detail.didCrash()) "renderer_crash" else "renderer_reclaimed")
                mainWebViewAlive = false
                pendingWebPermission = null
                pendingWebResources = emptyArray()
                fileChooserCallback = null
                root.removeView(view)
                view.destroy()
                loadingView.removeCallbacks(slowLoad)
                progressView.visibility = View.GONE
                showLoadMessage("Page was closed by Android. Tap to reopen.")
                return true
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleNavigation(request.url)

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return handleNavigation(Uri.parse(url))
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                applyHomeBannerRule(view, url)
                trace("main_document_finish_${SystemClock.elapsedRealtime() - pageStartedAt}ms")
                rememberPage(url)
                val generation = navigationGeneration
                view.postVisualStateCallback(generation, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (!isDestroyed && mainWebViewAlive && view === webView &&
                            requestId == navigationGeneration) revealPage()
                    }
                })
                if (isInternalHttpUrl(Uri.parse(url))) {
                    installPageAppearanceObserver(view)
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressView.progress = newProgress
                progressView.visibility = if (!loadFailed && newProgress in 1..99) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                this@MainActivity.fileChooserCallback?.onReceiveValue(null)
                this@MainActivity.fileChooserCallback = filePathCallback
                launchFileChooser(fileChooserParams)
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermissionRequest(request) }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                openPopup(resultMsg)
                return true
            }

            override fun onCloseWindow(window: WebView) {
                closePopup()
            }
        }

        view.setDownloadListener(createDownloadListener())
    }

    private fun configureCommonWebSettings(view: WebView) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            allowFileAccess = false
            allowContentAccess = true
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }

        WebView.setWebContentsDebuggingEnabled(false)
    }

    private fun handleNavigation(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase().orEmpty()

        if (scheme == "about" && uri.toString() == "about:blank") return false
        if (scheme == "https" && isAllowedHost(uri.host)) return false

        if (scheme == "http") {
            // Never load cleartext pages inside the app.
            openExternal(uri)
            return true
        }

        if (scheme == "https" || scheme in setOf("mailto", "tel", "geo", "market", "intent")) {
            openExternal(uri)
            return true
        }

        return true
    }

    private fun isInternalHttpUrl(uri: Uri): Boolean {
        return uri.scheme == "https" && isAllowedHost(uri.host)
    }

    private fun isAllowedHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return INTERNAL_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun openExternal(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            Toast.makeText(this, "No app can open this link", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openPopup(resultMsg: Message) {
        closePopup()

        val popup = WebView(this)
        configureCommonWebSettings(popup)
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        popup.webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                trace("popup_renderer_gone")
                if (popupWebView === view) popupWebView = null
                root.removeView(view)
                view.destroy()
                return true
            }
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = handleNavigation(request.url)
        }
        popup.webChromeClient = object : WebChromeClient() {
            override fun onCloseWindow(window: WebView) = closePopup()
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { handleWebPermissionRequest(request) }
            }
        }

        popup.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            val currentBottom =
                (webView.layoutParams as FrameLayout.LayoutParams).bottomMargin
            topMargin = 0
            bottomMargin = currentBottom
        }

        root.addView(popup)
        popupWebView = popup

        val transport = resultMsg.obj as WebView.WebViewTransport
        transport.webView = popup
        resultMsg.sendToTarget()
    }

    private fun closePopup() {
        popupWebView?.let { popup ->
            root.removeView(popup)
            popup.stopLoading()
            popup.destroy()
        }
        popupWebView = null
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams) {
        val contentIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = params.acceptTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
            if (params.acceptTypes.size > 1) {
                putExtra(Intent.EXTRA_MIME_TYPES, params.acceptTypes)
                type = "*/*"
            }
        }

        val initialIntents = mutableListOf<Intent>()
        if (params.isCaptureEnabled || acceptsImages(params.acceptTypes)) {
            createCameraIntent()?.let(initialIntents::add)
        }

        val chooser = Intent.createChooser(contentIntent, "Select file").apply {
            if (initialIntents.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, initialIntents.toTypedArray())
            }
        }
        startActivityForResult(chooser, FILE_CHOOSER_REQUEST)
    }

    private fun acceptsImages(types: Array<String>): Boolean {
        return types.isEmpty() || types.any { it.isBlank() || it == "*/*" || it.startsWith("image/") }
    }

    private fun createCameraIntent(): Intent? {
        val values = android.content.ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "gpt_capture_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        cameraOutputUri = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    @Deprecated("Deprecated in Android API; retained for WebView file chooser compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST) return

        val callback = fileChooserCallback ?: return
        fileChooserCallback = null

        if (resultCode != RESULT_OK) {
            cameraOutputUri?.let { contentResolver.delete(it, null, null) }
            cameraOutputUri = null
            callback.onReceiveValue(null)
            return
        }

        val result = mutableListOf<Uri>()
        data?.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                result += clip.getItemAt(i).uri
            }
        }
        data?.data?.let { result += it }

        if (result.isEmpty()) {
            cameraOutputUri?.let { result += it }
        } else {
            cameraOutputUri?.let { contentResolver.delete(it, null, null) }
        }
        cameraOutputUri = null

        callback.onReceiveValue(result.takeIf { it.isNotEmpty() }?.toTypedArray())
    }

    private fun handleWebPermissionRequest(request: PermissionRequest) {
        val requested = request.resources.toSet()
        val allowedWebResources = requested.filter {
            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_VIDEO_CAPTURE
        }

        if (allowedWebResources.size != requested.size) {
            request.deny()
            return
        }

        val androidPermissions = mutableListOf<String>()
        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in requested &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            androidPermissions += Manifest.permission.RECORD_AUDIO
        }
        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in requested &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            androidPermissions += Manifest.permission.CAMERA
        }

        if (androidPermissions.isEmpty()) {
            request.grant(allowedWebResources.toTypedArray())
            return
        }

        pendingWebPermission?.deny()
        pendingWebPermission = request
        pendingWebResources = allowedWebResources.toTypedArray()
        requestPermissions(androidPermissions.toTypedArray(), WEB_PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != WEB_PERMISSION_REQUEST) return

        val request = pendingWebPermission ?: return
        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) request.grant(pendingWebResources) else request.deny()
        pendingWebPermission = null
        pendingWebResources = emptyArray()
    }

    private fun createDownloadListener() = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        val uri = Uri.parse(url)
        if (uri.scheme != "https") {
            Toast.makeText(this, "Unsupported download URL", Toast.LENGTH_SHORT).show()
            return@DownloadListener
        }

        try {
            val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(uri)
                .setTitle(filename)
                .setMimeType(mimeType)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)

            CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
            if (!userAgent.isNullOrBlank()) request.addRequestHeader("User-Agent", userAgent)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
            } else {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename)
            }

            val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            Toast.makeText(this, "Downloading $filename", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun applyHomeBannerRule(view: WebView, url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme == "https" && uri.host == "chatgpt.com" && uri.port in listOf(-1, 443)) {
            view.evaluateJavascript(homeBannerScript, null)
        }
    }

    private fun installPageAppearanceObserver(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
              if (window.__gptNativeThemeObserverInstalled) return;
              window.__gptNativeThemeObserverInstalled = true;

              function parseColor(input) {
                if (!input || input === 'transparent' || /^rgba\([^)]*,\s*0(?:\.0+)?\s*\)$/.test(input)) return null;
                var m = String(input).match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i);
                if (!m) return null;
                var r = parseInt(m[1]), g = parseInt(m[2]), b = parseInt(m[3]);
                var hex = '#' + [r,g,b].map(function(v){ return v.toString(16).padStart(2,'0'); }).join('');
                var lum = (0.299*r + 0.587*g + 0.114*b);
                return { hex: hex, dark: lum < 140 };
              }

              function report() {
                try {
                  var bg = getComputedStyle(document.documentElement).backgroundColor;
                  var parsed = parseColor(bg);
                  if (!parsed) parsed = parseColor(getComputedStyle(document.body).backgroundColor);
                  if (!parsed) {
                    var dark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
                    parsed = { hex: dark ? '#212121' : '#ffffff', dark: dark };
                  }
                  NativeUi.setPageBackground(parsed.hex, parsed.dark);
                } catch (e) {}
              }

              report();
              new MutationObserver(report).observe(document.documentElement, {
                attributes: true,
                attributeFilter: ['class','style']
              });
              if (document.body) {
                new MutationObserver(report).observe(document.body, {
                  attributes: true,
                  attributeFilter: ['class','style']
                });
              }
              if (window.matchMedia) {
                var mq = window.matchMedia('(prefers-color-scheme: dark)');
                if (mq.addEventListener) mq.addEventListener('change', report);
              }
            })();
            """.trimIndent(),
            null
        )
    }

    inner class PageUiBridge {
        @JavascriptInterface
        fun setPageBackground(color: String, dark: Boolean) {
            runOnUiThread {
                if (isDestroyed || !mainWebViewAlive) return@runOnUiThread
                val parsed = try {
                    Color.parseColor(color)
                } catch (_: Exception) {
                    if (dark) Color.rgb(33, 33, 33) else Color.WHITE
                }
                root.setBackgroundColor(parsed)
                pageColor = parsed
                darkPage = dark
                if (mainWebViewAlive) webView.setBackgroundColor(parsed)
                window.setBackgroundDrawable(ColorDrawable(parsed))
                loadingView.setBackgroundColor(parsed)
                loadingView.setTextColor(if (dark) Color.WHITE else Color.DKGRAY)
                if (preferences.getInt("color", 0) != parsed || preferences.getBoolean("dark", !dark) != dark) {
                    preferences.edit().putInt("color", parsed).putBoolean("dark", dark).apply()
                }
                statusScrim.setBackgroundColor(parsed)
                navigationScrim.setBackgroundColor(parsed)
                updateSystemBarIconAppearance(dark)
            }
        }
    }

    private fun updateSystemBarIconAppearance(darkPage: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            var appearance = 0
            var mask = 0
            mask = mask or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
            mask = mask or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            if (!darkPage) {
                appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                appearance = appearance or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            }
            controller.setSystemBarsAppearance(appearance, mask)
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            if (!darkPage) {
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                }
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    override fun onBackPressed() {
        handleBack()
    }

    private fun handleBack() {
        when {
            popupWebView != null -> closePopup()
            mainWebViewAlive && webView.canGoBack() -> webView.goBack()
            else -> moveTaskToBack(true)
        }
    }

    override fun onDestroy() {
        trace("activity_destroy")
        loadingView.removeCallbacks(slowLoad)
        pendingWebPermission?.deny()
        pendingWebPermission = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        closePopup()
        if (mainWebViewAlive) {
            webView.stopLoading()
            webView.destroy()
            mainWebViewAlive = false
        }
        super.onDestroy()
    }
}
