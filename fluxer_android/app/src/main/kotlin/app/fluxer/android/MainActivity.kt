package app.fluxer.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
 * Main activity hosting a full-screen WebView that loads the bundled Fluxer web app.
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

    /** Pending file chooser callback from WebChromeClient */
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    /** Pending WebRTC permission request */
    private var pendingPermissionRequest: PermissionRequest? = null

    // Activity result launchers
    private lateinit var fileChooserLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var webRtcPermissionLauncher: ActivityResultLauncher<Array<String>>

    // TODO: make configurable — points at local dev server or self-hosted instance
    companion object {
        /** Base URL for loading the web app. Set to asset loader for bundled builds. */
        const val ASSET_BASE_URL = "https://appassets.androidplatform.net"

        /** Remote instance URL — used when assets aren't bundled */
        const val REMOTE_URL = "https://app.fluxer.chat"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Register launchers before setContentView
        registerLaunchers()

        // Edge-to-edge via WindowInsetsController (avoids deprecated statusBarColor/navigationBarColor)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        // Keep screen on during voice/video calls
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            id = View.generateViewId()
        }
        setContentView(webView)

        configureWebView()
        requestNotificationPermission()

        // Load the web app — check if bundled assets exist, otherwise use remote URL
        val url = if (hasAssetIndex()) {
            // TODO: use WebViewAssetLoader for proper asset serving with correct MIME types
            "file:///android_asset/www/index.html"
        } else {
            REMOTE_URL
        }
        webView.loadUrl(url)
    }

    /**
     * Check whether bundled web assets exist in assets/www/.
     */
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
     * WebViewClient — handles navigation within the app, opens external links in browser.
     */
    private inner class FluxerWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView, request: WebResourceRequest
        ): Boolean {
            val url = request.url
            val host = url.host ?: return false

            // Keep navigation within the app for known domains
            if (host.endsWith("fluxer.chat") || host.endsWith("fluxer.app") ||
                host == "appassets.androidplatform.net" ||
                url.scheme == "fluxer"
            ) {
                return false
            }

            // Open external links in system browser
            startActivity(Intent(Intent.ACTION_VIEW, url))
            return true
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
        intent.data?.let { uri ->
            if (uri.scheme == "fluxer") {
                // Convert fluxer://path to https://app.fluxer.chat/path
                val webUrl = uri.toString().replaceFirst("fluxer://", "$REMOTE_URL/")
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
