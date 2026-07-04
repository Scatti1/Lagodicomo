package com.lagodicomo.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * WebView fullscreen per https://lagodicomoapp.lovable.app
 *
 * Include tutto ciò che serve per l'esperienza di navigazione mobile:
 *  • GPS (geolocation HTML5 → permesso Android) — indispensabile per gli STEP
 *  • Microfono (comandi vocali via getUserMedia)
 *  • Fullscreen immersivo + schermo sempre acceso durante l'uso
 *  • Upload file, link esterni (tel:, mailto:, maps, intent:) gestiti
 *  • Tasto indietro = history del sito
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val APP_URL = "https://lagodicomoapp.lovable.app"
    }

    private lateinit var webView: WebView
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null
    private var pendingWebPermission: PermissionRequest? = null
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // ── Richiesta permessi Android runtime ──────────────────────────
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            // GPS per la geolocation HTML5
            val locOk = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            pendingGeoCallback?.invoke(pendingGeoOrigin, locOk, locOk)
            pendingGeoCallback = null; pendingGeoOrigin = null
            // Microfono / camera richiesti dalla pagina
            pendingWebPermission?.let { req ->
                val granted = req.resources.filter { res ->
                    when (res) {
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            grants[Manifest.permission.RECORD_AUDIO] == true
                        else -> false
                    }
                }
                if (granted.isEmpty()) req.deny() else req.grant(granted.toTypedArray())
            }
            pendingWebPermission = null
        }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            fileChooserCallback?.onReceiveValue(uris)
            fileChooserCallback = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Schermo sempre acceso (navigazione) + fullscreen edge-to-edge
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        setContentView(webView)
        applyImmersiveMode()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)                    // ← STEP navigazione
            mediaPlaybackRequiresUserGesture = false       // TTS/audio senza tap
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        // Cookie (login/sessioni)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {

            // GPS HTML5 → chiedi il permesso Android e poi concedi al sito
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                if (hasLocationPermission()) {
                    callback.invoke(origin, true, true)
                } else {
                    pendingGeoOrigin = origin
                    pendingGeoCallback = callback
                    requestAppPermissions()
                }
            }

            // Microfono/camera richiesti dalla pagina (comandi vocali)
            override fun onPermissionRequest(request: PermissionRequest) {
                val needsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (needsAudio && !hasAudioPermission()) {
                    pendingWebPermission = request
                    requestAppPermissions()
                } else {
                    runOnUiThread {
                        val ok = request.resources.filter {
                            it == PermissionRequest.RESOURCE_AUDIO_CAPTURE && hasAudioPermission()
                        }
                        if (ok.isEmpty()) request.deny() else request.grant(ok.toTypedArray())
                    }
                }
            }

            // Upload file (foto profilo, allegati…)
            override fun onShowFileChooser(
                view: WebView, callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                return try {
                    fileChooserLauncher.launch(params.createIntent()); true
                } catch (e: Exception) {
                    fileChooserCallback = null; false
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val scheme = url.scheme ?: return false
                // Schemi esterni → app di sistema
                if (scheme in listOf("tel", "mailto", "sms", "geo", "whatsapp", "intent", "market")) {
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, url)); true
                    } catch (e: Exception) { true }
                }
                // Resta nella WebView per il dominio dell'app, browser per il resto
                val host = url.host ?: ""
                return if (host.endsWith("lovable.app") || host.contains("google") ||
                           host.contains("gstatic") || host.contains("openstreetmap")) {
                    false
                } else {
                    try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (_: Exception) {}
                    true
                }
            }
        }

        // Tasto indietro = indietro nella history del sito
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack()
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })

        // Chiedi subito i permessi principali al primo avvio
        if (!hasLocationPermission() || !hasAudioPermission()) requestAppPermissions()

        webView.loadUrl(APP_URL)
    }

    // ── Fullscreen immersivo (barre di sistema nascoste, swipe per mostrarle) ──
    private fun applyImmersiveMode() {
        val c = WindowInsetsControllerCompat(window, webView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasAudioPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestAppPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        permissionLauncher.launch(perms.toTypedArray())
    }

    override fun onPause() { webView.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); webView.onResume(); applyImmersiveMode() }
    override fun onDestroy() { webView.destroy(); super.onDestroy() }
}
