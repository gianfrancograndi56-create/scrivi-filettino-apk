package it.filettino.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    inner class NativeBridge {
        @JavascriptInterface
        fun isNativeAndroid(): Boolean = true

        @JavascriptInterface
        fun getFcmToken(): String {
            return getSharedPreferences(CallSignalConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(CallSignalConstants.KEY_FCM_TOKEN, "") ?: ""
        }

        @JavascriptInterface
        fun syncUserId(userId: String) {
            getSharedPreferences(CallSignalConstants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(CallSignalConstants.KEY_USER_ID, userId)
                .apply()
            fetchAndDispatchFcmToken()
        }

        @JavascriptInterface
        fun consumePendingCallAction(): String {
            val prefs = getSharedPreferences(CallSignalConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(CallSignalConstants.KEY_PENDING_CALL_JSON, "") ?: ""
            if (raw.isNotBlank()) {
                prefs.edit().remove(CallSignalConstants.KEY_PENDING_CALL_JSON).apply()
            }
            return raw
        }

        @JavascriptInterface
        fun stopNativeRingtone() {
            RingtoneForegroundService.stopRinging(this@MainActivity)
        }

        @JavascriptInterface
        fun canUseFullScreenIntent(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val nm = getSystemService(NotificationManager::class.java)
                return nm.canUseFullScreenIntent()
            }
            return true
        }

        @JavascriptInterface
        fun openFullScreenPermissionSettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleIncomingCallExtras(intent)
        requestRuntimePermissions()

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.userAgentString = settings.userAgentString + " FilettinoNativeAPK/33.2"

        webView.addJavascriptInterface(NativeBridge(), "FilettinoNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                    url.startsWith("https://api.whatsapp.com") || url.startsWith("https://wa.me") ||
                    url.startsWith("whatsapp:")
                ) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {
                    }
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                fetchAndDispatchFcmToken()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { req ->
                    runOnUiThread {
                        req.grant(req.resources)
                    }
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(buildStartUrl())
        fetchAndDispatchFcmToken()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallExtras(intent)
        val callId = intent.getStringExtra(CallSignalConstants.EXTRA_CALL_ID) ?: ""
        val callAction = intent.getStringExtra(CallSignalConstants.EXTRA_CALL_ACTION) ?: ""
        if (callId.isNotBlank() && callAction.isNotBlank()) {
            webView.loadUrl(buildStartUrl(callId, callAction))
        }
    }

    private fun handleIncomingCallExtras(intent: Intent?) {
        val callId = intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_ID) ?: ""
        val callAction = intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_ACTION) ?: ""
        if (callId.isNotBlank() && callAction.isNotBlank()) {
            RingtoneForegroundService.stopRinging(this)
            CallSignalConstants.savePendingCallAction(
                this,
                callId,
                callAction,
                intent?.getStringExtra(CallSignalConstants.EXTRA_CALL_TYPE) ?: "audio",
                intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_ID) ?: "",
                intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_NAME) ?: "Iscritto Filettino",
                intent?.getStringExtra(CallSignalConstants.EXTRA_CALLER_EMAIL) ?: ""
            )
        }
    }

    private fun buildStartUrl(callId: String = "", callAction: String = ""): String {
        val uriBuilder = Uri.parse(CallSignalConstants.APP_URL).buildUpon()
            .appendQueryParameter("nativeApk", "1")
        if (callId.isNotBlank() && callAction.isNotBlank()) {
            uriBuilder.appendQueryParameter("incomingCallId", callId)
            uriBuilder.appendQueryParameter("callAction", callAction)
        }
        return uriBuilder.build().toString()
    }

    private fun requestRuntimePermissions() {
        val needed = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun fetchAndDispatchFcmToken() {
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
            }
            if (FirebaseApp.getApps(this).isEmpty()) return
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                if (!token.isNullOrBlank()) {
                    val prefs = getSharedPreferences(CallSignalConstants.PREFS_NAME, Context.MODE_PRIVATE)
                    prefs.edit().putString(CallSignalConstants.KEY_FCM_TOKEN, token).apply()

                    val savedUserId = prefs.getString(CallSignalConstants.KEY_USER_ID, "") ?: ""
                    if (savedUserId.isNotBlank()) {
                        Thread {
                            CallSignalConstants.patchUserFcmToken(savedUserId, token)
                        }.start()
                    }

                    runOnUiThread {
                        if (::webView.isInitialized) {
                            val escaped = JSONObject.quote(token)
                            webView.evaluateJavascript("window.__onFilettinoFcmToken && window.__onFilettinoFcmToken($escaped);", null)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
