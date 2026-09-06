package com.local.gptwebnative

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Message
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        configureEdgeToEdge()
        buildViewHierarchy()
        configureMainWebView(webView)
        configureInsets()

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
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
            setBackgroundColor(Color.WHITE)
        }

        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusScrim = View(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { gravity = android.view.Gravity.TOP }
        }

        navigationScrim = View(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0
            ).apply { gravity = android.view.Gravity.BOTTOM }
        }

        root.addView(webView)
        root.addView(statusScrim)
        root.addView(navigationScrim)
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
                if (isInternalHttpUrl(Uri.parse(url))) {
                    installPageAppearanceObserver(view)
                }
            }
        }

        view.webChromeClient = object : WebChromeClient() {
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

    private fun installPageAppearanceObserver(view: WebView) {
        view.evaluateJavascript(
            """
            (function() {
              if (window.__gptNativeThemeObserverInstalled) return;
              window.__gptNativeThemeObserverInstalled = true;

              function parseColor(input) {
                var m = String(input || '').match(/rgba?\\((\\d+),\\s*(\\d+),\\s*(\\d+)/i);
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
                val parsed = try {
                    Color.parseColor(color)
                } catch (_: Exception) {
                    if (dark) Color.rgb(33, 33, 33) else Color.WHITE
                }
                root.setBackgroundColor(parsed)
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
        when {
            popupWebView != null -> closePopup()
            webView.canGoBack() -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    override fun onDestroy() {
        pendingWebPermission?.deny()
        pendingWebPermission = null
        fileChooserCallback?.onReceiveValue(null)
        fileChooserCallback = null
        closePopup()
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
