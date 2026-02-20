package app.fluxer.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

/**
 * Main activity hosting a full-screen WebView that loads a Fluxer instance.
 *
 * On first launch (or when no instance URL is configured), shows a setup screen
 * rendered as local HTML. The user enters their self-hosted Fluxer instance URL,
 * which is persisted in SharedPreferences.
 *
 * Supports:
 * - WebRTC (camera + microphone for LiveKit calls)
 * - File uploads via system picker
 * - Push notifications (POST_NOTIFICATIONS permission)
 * - Deep links (fluxer:// scheme)
 * - Dark mode aligned with system theme
 * - Edge-to-edge immersive display
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var prefs: SharedPreferences

    /** Pending file chooser callback from WebChromeClient */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** Pending WebRTC permission request */
    private var pendingPermissionRequest: PermissionRequest? = null

    // Activity result launchers
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var webRtcPermissionLauncher: ActivityResultLauncher<Array<String>>

    companion object {
        private const val PREFS_NAME = "fluxer_prefs"
        private const val KEY_INSTANCE_URL = "instance_url"
    }

    /** Returns the saved instance URL, or null if not configured yet. */
    private fun getSavedInstanceUrl(): String? =
        prefs.getString(KEY_INSTANCE_URL, null)?.takeIf { it.isNotBlank() }

    /** Persist the user's chosen instance URL. */
    private fun saveInstanceUrl(url: String) {
        prefs.edit().putString(KEY_INSTANCE_URL, url).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Register launchers before setContentView
        registerLaunchers()

        // Edge-to-edge via WindowInsetsController
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Keep screen on during voice/video calls
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val container = FrameLayout(this)
        webView = WebView(this).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        container.addView(webView)
        setContentView(container)

        configureWebView()
        requestNotificationPermission()

        // Determine what to load
        val url = when {
            hasAssetIndex() -> "file:///android_asset/www/index.html"
            getSavedInstanceUrl() != null -> getSavedInstanceUrl()!!
            else -> null
        }

        if (url != null) {
            webView.loadUrl(url)
        } else {
            showSetupScreen()
        }
    }

    /** Renders a local HTML setup screen for entering the instance URL. */
    private fun showSetupScreen() {
        val html = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
        <title>Fluxer</title>
        <style>
            * { margin: 0; padding: 0; box-sizing: border-box; }
            html, body { height: 100%; }
            body {
                background: #1a1a2e;
                color: #e0e0e0;
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                padding: 24px;
                -webkit-tap-highlight-color: transparent;
            }
            .logo {
                width: 80px; height: 80px;
                background: #5865f2;
                border-radius: 20px;
                display: flex;
                align-items: center;
                justify-content: center;
                font-size: 40px;
                font-weight: 700;
                color: #fff;
                margin-bottom: 24px;
            }
            h1 {
                font-size: 22px;
                font-weight: 600;
                color: #fff;
                margin-bottom: 8px;
            }
            p {
                font-size: 14px;
                color: #8e8ea0;
                text-align: center;
                line-height: 1.5;
                margin-bottom: 28px;
                max-width: 320px;
            }
            .field {
                width: 100%;
                max-width: 360px;
                position: relative;
            }
            input {
                width: 100%;
                padding: 14px 16px;
                background: #16213e;
                border: 2px solid #2a2a4a;
                border-radius: 12px;
                color: #fff;
                font-size: 16px;
                outline: none;
                transition: border-color 0.2s;
            }
            input:focus { border-color: #5865f2; }
            input::placeholder { color: #555; }
            .error {
                color: #f04747;
                font-size: 13px;
                margin-top: 8px;
                display: none;
            }
            button {
                width: 100%;
                max-width: 360px;
                padding: 14px;
                margin-top: 16px;
                background: #5865f2;
                color: #fff;
                font-size: 16px;
                font-weight: 600;
                border: none;
                border-radius: 12px;
                cursor: pointer;
                transition: background 0.2s;
            }
            button:active { background: #4752c4; }
            button:disabled {
                background: #3a3a5c;
                color: #777;
                cursor: not-allowed;
            }
            .divider {
                width: 100%;
                max-width: 360px;
                display: flex;
                align-items: center;
                margin: 20px 0;
                color: #555;
                font-size: 13px;
            }
            .divider::before, .divider::after {
                content: '';
                flex: 1;
                height: 1px;
                background: #2a2a4a;
            }
            .divider span { padding: 0 12px; white-space: nowrap; }
            button.secondary {
                background: #2a2a4a;
            }
            button.secondary:active { background: #3a3a5c; }
            .spinner {
                display: none;
                width: 20px; height: 20px;
                border: 2px solid #fff4;
                border-top-color: #fff;
                border-radius: 50%;
                animation: spin 0.6s linear infinite;
                margin: 0 auto;
            }
            @keyframes spin { to { transform: rotate(360deg); } }
        </style>
        </head>
        <body>
            <div class="logo">F</div>
            <h1>Connect to Fluxer</h1>
            <p>Use the official hosted instance or connect to your own self-hosted server.</p>
            <button id="official-btn" onclick="connectOfficial()">Use Official Instance</button>
            <div class="divider"><span>or connect to your own</span></div>
            <div class="field">
                <input id="url" type="url" placeholder="https://chat.example.com"
                       autocapitalize="none" autocomplete="url" spellcheck="false"
                       enterkeyhint="go">
                <div class="error" id="error"></div>
            </div>
            <button id="btn" class="secondary" onclick="connect()">Connect</button>
            <div class="spinner" id="spinner"></div>

            <script>
                const urlInput = document.getElementById('url');
                const btn = document.getElementById('btn');
                const error = document.getElementById('error');
                const spinner = document.getElementById('spinner');

                urlInput.addEventListener('keydown', (e) => {
                    if (e.key === 'Enter') connect();
                });

                urlInput.addEventListener('input', () => {
                    error.style.display = 'none';
                });

                function connectOfficial() {
                    window.FluxerBridge.saveAndConnect('https://web.fluxer.app');
                }

                function connect() {
                    let raw = urlInput.value.trim();
                    if (!raw) {
                        showError('Please enter an instance URL.');
                        return;
                    }
                    // Auto-add https:// if no scheme
                    if (!/^https?:\/\//i.test(raw)) {
                        raw = 'https://' + raw;
                        urlInput.value = raw;
                    }
                    try {
                        const parsed = new URL(raw);
                        if (!parsed.hostname.includes('.') && parsed.hostname !== 'localhost') {
                            showError('Enter a valid domain (e.g. chat.example.com).');
                            return;
                        }
                    } catch (_) {
                        showError('That doesn\'t look like a valid URL.');
                        return;
                    }

                    // Remove trailing slash for consistency
                    const url = raw.replace(/\/+$/, '');

                    btn.disabled = true;
                    spinner.style.display = 'block';
                    error.style.display = 'none';

                    // Probe the instance API to verify it's a Fluxer server
                    fetch(url + '/api/instance', { mode: 'cors' })
                        .then(r => {
                            if (r.ok) {
                                // Confirmed — save and navigate via Android bridge
                                window.FluxerBridge.saveAndConnect(url);
                            } else {
                                showError('Server responded but doesn\'t appear to be Fluxer (HTTP ' + r.status + ').');
                                reset();
                            }
                        })
                        .catch((_) => {
                            // Network error — still allow connecting (may be CORS blocking the probe)
                            window.FluxerBridge.saveAndConnect(url);
                        });
                }

                function showError(msg) {
                    error.textContent = msg;
                    error.style.display = 'block';
                }

                function reset() {
                    btn.disabled = false;
                    spinner.style.display = 'none';
                }
            </script>
        </body>
        </html>
        """.trimIndent()

        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    /** Check whether bundled web assets exist in assets/www/. */
    private fun hasAssetIndex(): Boolean {
        return try {
            assets.open("www/index.html").close()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun registerLaunchers() {
        fileChooserLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val uris = when {
                result.resultCode != Activity.RESULT_OK -> null
                result.data?.clipData != null -> {
                    val clip = result.data!!.clipData!!
                    Array(clip.itemCount) { clip.getItemAt(it).uri }
                }
                result.data?.data != null -> arrayOf(result.data!!.data!!)
                else -> null
            }
            filePathCallback?.onReceiveValue(uris)
            filePathCallback = null
        }

        notificationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { /* granted or not — web app handles UI feedback */ }

        webRtcPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val allGranted = results.values.all { it }
            if (allGranted) {
                pendingPermissionRequest?.grant(pendingPermissionRequest?.resources)
            } else {
                pendingPermissionRequest?.deny()
            }
            pendingPermissionRequest = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true

            // Media playback — required for LiveKit voice/video
            mediaPlaybackRequiresUserGesture = false

            // Allow mixed content for local asset loading with remote API calls
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // Cache for offline resilience
            cacheMode = WebSettings.LOAD_DEFAULT

            // File access for upload support
            allowFileAccess = true
            allowContentAccess = true

            // Responsive layout
            useWideViewPort = true
            loadWithOverviewMode = true

            // Text size follows system setting
            textZoom = 100
        }

        // Enable third-party cookies for auth
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // Algorithmic darkening for WebView content if available
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }

        // JavaScript bridge for the setup screen to save the instance URL
        webView.addJavascriptInterface(FluxerBridge(), "FluxerBridge")
        webView.webViewClient = FluxerWebViewClient()
        webView.webChromeClient = FluxerChromeClient()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * JavaScript interface exposed to the setup screen HTML.
     * Allows saving the instance URL and navigating to it.
     */
    private inner class FluxerBridge {
        @android.webkit.JavascriptInterface
        fun saveAndConnect(url: String) {
            saveInstanceUrl(url)
            runOnUiThread {
                webView.loadUrl(url)
            }
        }

        /** Called from the WebView to return to the setup screen (e.g. settings gear). */
        @android.webkit.JavascriptInterface
        fun showSetup() {
            runOnUiThread {
                prefs.edit().remove(KEY_INSTANCE_URL).apply()
                showSetupScreen()
            }
        }
    }

    /**
     * WebViewClient — handles navigation within the app, opens external links in browser.
     * On main-frame load errors, redirects back to the setup screen.
     */
    private inner class FluxerWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val host = url.host ?: return false
            val instanceHost = getSavedInstanceUrl()?.let { Uri.parse(it).host }

            // Keep navigation within the app for the configured instance and official domains
            if (host == instanceHost ||
                host.endsWith("fluxer.app") ||
                host.endsWith("fluxer.gg") ||
                host == "appassets.androidplatform.net" ||
                url.scheme == "fluxer"
            ) {
                return false
            }

            // Open external links in system browser
            startActivity(Intent(Intent.ACTION_VIEW, url))
            return true
        }

        override fun onReceivedError(
            view: WebView, request: WebResourceRequest, error: WebResourceError
        ) {
            // Only handle main frame errors — show setup screen so user can re-enter URL
            if (request.isForMainFrame) {
                showSetupScreen()
            }
        }
    }

    /**
     * ChromeClient — handles file uploads and WebRTC permission grants.
     */
    private inner class FluxerChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback

            val intent = params.createIntent()
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            fileChooserLauncher.launch(intent)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            val resources = request.resources
            val androidPermissions = mutableListOf<String>()

            for (resource in resources) {
                when (resource) {
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                        androidPermissions.add(Manifest.permission.RECORD_AUDIO)
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                        androidPermissions.add(Manifest.permission.CAMERA)
                }
            }

            if (androidPermissions.isEmpty()) {
                request.grant(resources)
                return
            }

            // Check if all needed permissions are already granted
            val allGranted = androidPermissions.all {
                ContextCompat.checkSelfPermission(this@MainActivity, it) ==
                    PackageManager.PERMISSION_GRANTED
            }

            if (allGranted) {
                request.grant(resources)
            } else {
                pendingPermissionRequest = request
                webRtcPermissionLauncher.launch(androidPermissions.toTypedArray())
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle deep links — forward to WebView
        val instanceUrl = getSavedInstanceUrl() ?: return
        intent.data?.let { uri ->
            if (uri.scheme == "fluxer") {
                val webUrl = uri.toString().replaceFirst("fluxer://", "$instanceUrl/")
                webView.loadUrl(webUrl)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
