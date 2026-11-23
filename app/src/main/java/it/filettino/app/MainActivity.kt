package it.filettino.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.provider.ContactsContract
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
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
import androidx.core.content.FileProvider
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var pendingUpdateApkFile: File? = null
    @Volatile
    private var isDownloadingApkUpdate: Boolean = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val pendingReq = pendingWebPermissionRequest
        pendingWebPermissionRequest = null
        if (pendingReq != null) {
            runOnUiThread {
                try {
                    val hasAudio = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCamera = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    val grantable = pendingReq.resources.filter { res ->
                        when (res) {
                            PermissionRequest.RESOURCE_AUDIO_CAPTURE -> hasAudio
                            PermissionRequest.RESOURCE_VIDEO_CAPTURE -> hasCamera
                            else -> true
                        }
                    }
                    if (grantable.isNotEmpty()) {
                        pendingReq.grant(grantable.toTypedArray())
                    } else {
                        pendingReq.deny()
                    }
                } catch (_: Exception) {
                }
            }
        }
        val contactsGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED
        if (contactsGranted && ::webView.isInitialized) {
            runOnUiThread {
                webView.evaluateJavascript(
                    "window.__onFilettinoContactsPermissionResult && window.__onFilettinoContactsPermissionResult(true);",
                    null
                )
            }
        }
    }

    private val contactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        runOnUiThread {
            if (::webView.isInitialized) {
                val boolLiteral = if (granted) "true" else "false"
                webView.evaluateJavascript(
                    "window.__onFilettinoContactsPermissionResult && window.__onFilettinoContactsPermissionResult($boolLiteral);",
                    null
                )
            }
        }
    }

    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val pendingFile = pendingUpdateApkFile
        if (pendingFile != null && pendingFile.exists()) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
                pendingUpdateApkFile = null
                launchApkInstaller(pendingFile)
            } else {
                notifyApkUpdateProgress(
                    "permission",
                    100,
                    "Attiva 'Consenti da questa origine' nelle impostazioni per completare l'aggiornamento."
                )
            }
        }
    }

    inner class NativeBridge {
        @JavascriptInterface
        fun isNativeAndroid(): Boolean = true

        @JavascriptInterface
        fun supportsInAppApkUpdate(): Boolean = true

        @JavascriptInterface
        fun downloadAndInstallApk(url: String) {
            val safeUrl = if (url.startsWith("https://") || url.startsWith("http://")) url else return
            startInAppApkUpdate(safeUrl)
        }

        @JavascriptInterface
        fun getApkVersionCode(): Int {
            return try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    info.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION")
                    info.versionCode
                }
            } catch (_: Exception) {
                80
            }
        }

        @JavascriptInterface
        fun getApkVersionName(): String {
            return try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, 0)
                }
                info.versionName ?: "80.5"
            } catch (_: Exception) {
                "80.5"
            }
        }

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
        fun syncAuthToken(idToken: String) {
            CallSignalConstants.saveAuthToken(this@MainActivity, idToken)
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

        @JavascriptInterface
        fun openExternalBrowser(url: String) {
            runOnUiThread {
                val safeUrl = if (url.startsWith("https://") || url.startsWith("http://")) url else CallSignalConstants.APP_URL
                launchExternalUrl(safeUrl)
            }
        }

        @JavascriptInterface
        fun clearCacheAndReload(targetUrl: String) {
            runOnUiThread {
                try {
                    if (::webView.isInitialized) {
                        webView.clearCache(true)
                        val safeUrl = if (targetUrl.startsWith("https://") || targetUrl.startsWith("http://")) targetUrl else buildStartUrl()
                        val noCacheHeaders = mapOf(
                            "Cache-Control" to "no-cache, no-store, must-revalidate",
                            "Pragma" to "no-cache"
                        )
                        webView.loadUrl(safeUrl, noCacheHeaders)
                    }
                } catch (_: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun hasContactsPermission(): Boolean {
            return ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_CONTACTS
            ) == PackageManager.PERMISSION_GRANTED
        }

        @JavascriptInterface
        fun requestContactsPermission() {
            runOnUiThread {
                if (hasContactsPermission()) {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.__onFilettinoContactsPermissionResult && window.__onFilettinoContactsPermissionResult(true);",
                            null
                        )
                    }
                    return@runOnUiThread
                }
                try {
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                } catch (_: Exception) {
                    if (::webView.isInitialized) {
                        webView.evaluateJavascript(
                            "window.__onFilettinoContactsPermissionResult && window.__onFilettinoContactsPermissionResult(false);",
                            null
                        )
                    }
                }
            }
        }

        @JavascriptInterface
        fun getPhoneContactsJson(): String {
            if (!hasContactsPermission()) return "[]"
            val results = JSONArray()
            val seenPhones = HashSet<String>()
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext() && results.length() < 2500) {
                        val rawName = if (nameIdx >= 0) cursor.getString(nameIdx)?.trim() ?: "" else ""
                        val rawPhone = if (numIdx >= 0) cursor.getString(numIdx)?.trim() ?: "" else ""
                        val digits = rawPhone.replace(Regex("[^0-9]"), "")
                        if (digits.length < 7) continue
                        val key = if (digits.length >= 9) digits.takeLast(9) else digits
                        if (!seenPhones.add(key)) continue
                        val obj = JSONObject()
                        obj.put("name", rawName.ifBlank { rawPhone })
                        obj.put("phone", rawPhone)
                        results.put(obj)
                    }
                }
            } catch (_: Exception) {
            }
            return results.toString()
        }
    }

    private fun isAppOrAuthHost(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase() ?: return false
        return host.contains("google.") ||
            host.contains("googleapis.") ||
            host.contains("gstatic.") ||
            host.contains("googleusercontent.") ||
            host.contains("firebaseapp.com") ||
            host.contains("web.app") ||
            host.contains("filettino-app") ||
            host.contains("ais-dev-") ||
            host.contains("ais-pre-") ||
            host == "localhost" ||
            host == "127.0.0.1"
    }

    private fun notifyApkUpdateProgress(status: String, percent: Int, message: String) {
        runOnUiThread {
            if (::webView.isInitialized) {
                val s = JSONObject.quote(status)
                val m = JSONObject.quote(message)
                webView.evaluateJavascript(
                    "window.__onFilettinoApkUpdateProgress && window.__onFilettinoApkUpdateProgress($s, $percent, $m);",
                    null
                )
            }
        }
    }

    private fun startInAppApkUpdate(downloadUrl: String) {
        if (isDownloadingApkUpdate) return
        isDownloadingApkUpdate = true
        notifyApkUpdateProgress("downloading", 2, "Avvio scaricamento aggiornamento…")

        Thread {
            var conn: HttpURLConnection? = null
            try {
                var currentUrl = downloadUrl
                var redirects = 0
                while (redirects < 8) {
                    val urlObj = URL(currentUrl)
                    val c = (urlObj.openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20000
                        readTimeout = 30000
                        instanceFollowRedirects = false
                        setRequestProperty("User-Agent", "FilettinoApp/78.5 (Android)")
                        setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
                    }
                    conn = c
                    val code = c.responseCode
                    if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                        code == HttpURLConnection.HTTP_MOVED_TEMP ||
                        code == HttpURLConnection.HTTP_SEE_OTHER ||
                        code == 307 || code == 308
                    ) {
                        val location = c.getHeaderField("Location")
                        c.disconnect()
                        conn = null
                        if (location.isNullOrBlank()) {
                            throw Exception("Redirect senza destinazione")
                        }
                        currentUrl = URL(urlObj, location).toString()
                        redirects++
                    } else {
                        break
                    }
                }

                val activeConn = conn ?: throw Exception("Connessione non riuscita")
                if (activeConn.responseCode !in 200..299) {
                    throw Exception("Server ha risposto ${activeConn.responseCode}")
                }

                val totalBytes = activeConn.contentLengthLong
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val apkFile = File(updatesDir, "filettino-update.apk")
                if (apkFile.exists()) apkFile.delete()

                activeConn.inputStream.use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
                        var lastReportedPct = 2
                        var lastReportMs = 0L
                        var read = input.read(buffer)
                        while (read != -1) {
                            output.write(buffer, 0, read)
                            downloaded += read
                            val now = System.currentTimeMillis()
                            val pct = if (totalBytes > 0) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(2, 99)
                            } else {
                                (lastReportedPct + 1).coerceAtMost(95)
                            }
                            if (pct >= lastReportedPct + 3 || now - lastReportMs > 350L) {
                                lastReportedPct = pct
                                lastReportMs = now
                                notifyApkUpdateProgress("downloading", pct, "Scaricamento aggiornamento… $pct%")
                            }
                            read = input.read(buffer)
                        }
                        output.flush()
                    }
                }

                if (!apkFile.exists() || apkFile.length() < 100_000L) {
                    throw Exception("File aggiornamento non valido")
                }

                notifyApkUpdateProgress("downloading", 100, "Scaricamento completato!")
                runOnUiThread {
                    launchApkInstaller(apkFile)
                }
            } catch (_: Exception) {
                notifyApkUpdateProgress(
                    "error",
                    0,
                    "Apro il download nel browser…"
                )
                runOnUiThread {
                    launchExternalUrl(downloadUrl)
                }
            } finally {
                try {
                    conn?.disconnect()
                } catch (_: Exception) {
                }
                isDownloadingApkUpdate = false
            }
        }.start()
    }

    private fun launchApkInstaller(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                pendingUpdateApkFile = apkFile
                notifyApkUpdateProgress(
                    "permission",
                    100,
                    "Attiva 'Consenti da questa origine' e torna indietro: l'aggiornamento partirà subito!"
                )
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
                installPermissionLauncher.launch(settingsIntent)
                return
            }

            notifyApkUpdateProgress("installing", 100, "Apertura aggiornamento… tocca Aggiorna!")
            val apkUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(installIntent)
        } catch (_: Exception) {
            notifyApkUpdateProgress("error", 0, "Impossibile aprire l'installazione automatica.")
        }
    }

    private fun launchExternalUrl(url: String) {
        try {
            if (url.startsWith("intent://") || url.startsWith("intent:")) {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                intent.addCategory(Intent.CATEGORY_BROWSABLE)
                startActivity(intent)
                return
            }
            val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(viewIntent)
        } catch (_: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(Intent.createChooser(fallback, "Apri con"))
            } catch (_: Exception) {
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

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val cleanUa = webView.settings.userAgentString
            .replace("; wv", "")
            .replace("wv)", ")")
            .replace(Regex("""Version/\d+(\.\d+)?\s*"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setSupportMultipleWindows(true)
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.userAgentString = cleanUa

        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
        }

        webView.addJavascriptInterface(NativeBridge(), "FilettinoNative")

        webView.setDownloadListener { url, _, _, _, _ ->
            if (!url.isNullOrBlank()) {
                launchExternalUrl(url)
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                    url.startsWith("https://api.whatsapp.com") || url.startsWith("https://wa.me") ||
                    url.startsWith("whatsapp:") || url.startsWith("intent:")
                ) {
                    launchExternalUrl(url)
                    return true
                }
                if (request.isForMainFrame && (url.startsWith("https://") || url.startsWith("http://")) && !isAppOrAuthHost(url)) {
                    launchExternalUrl(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                fetchAndDispatchFcmToken()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { req ->
                    runOnUiThread {
                        val needed = mutableListOf<String>()
                        val resources = req.resources ?: emptyArray()
                        if (resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                        ) {
                            needed.add(Manifest.permission.RECORD_AUDIO)
                        }
                        if (resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE) &&
                            ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                        ) {
                            needed.add(Manifest.permission.CAMERA)
                        }
                        if (needed.isEmpty()) {
                            req.grant(resources)
                        } else {
                            try {
                                pendingWebPermissionRequest?.deny()
                            } catch (_: Exception) {}
                            pendingWebPermissionRequest = req
                            permissionLauncher.launch(needed.toTypedArray())
                        }
                    }
                }
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val hitHref = view?.hitTestResult?.extra
                if (!hitHref.isNullOrBlank() && (hitHref.startsWith("https://") || hitHref.startsWith("http://")) && !isAppOrAuthHost(hitHref)) {
                    launchExternalUrl(hitHref)
                    return false
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                val popupWebView = WebView(this@MainActivity)
                popupWebView.setBackgroundColor(android.graphics.Color.WHITE)
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(popupWebView, true)

                val popupSettings = popupWebView.settings
                popupSettings.javaScriptEnabled = true
                popupSettings.domStorageEnabled = true
                popupSettings.databaseEnabled = true
                popupSettings.useWideViewPort = true
                popupSettings.loadWithOverviewMode = true
                popupSettings.builtInZoomControls = true
                popupSettings.displayZoomControls = false
                popupSettings.mediaPlaybackRequiresUserGesture = false
                popupSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                popupSettings.setSupportMultipleWindows(true)
                popupSettings.javaScriptCanOpenWindowsAutomatically = true
                popupSettings.userAgentString = cleanUa

                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                    @Suppress("DEPRECATION")
                    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(popupSettings, emptySet())
                }

                val dialog = Dialog(this@MainActivity, android.R.style.Theme_Material_Light_NoActionBar)
                dialog.setContentView(
                    popupWebView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )

                var initialHostChecked = false

                popupWebView.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                        val url = req?.url?.toString() ?: return false
                        if (url.startsWith("tel:") || url.startsWith("mailto:") ||
                            url.startsWith("https://api.whatsapp.com") || url.startsWith("https://wa.me") ||
                            url.startsWith("whatsapp:") || url.startsWith("intent:")
                        ) {
                            launchExternalUrl(url)
                            dialog.dismiss()
                            return true
                        }
                        if (!initialHostChecked && (url.startsWith("https://") || url.startsWith("http://"))) {
                            initialHostChecked = true
                            if (!isAppOrAuthHost(url)) {
                                launchExternalUrl(url)
                                dialog.dismiss()
                                return true
                            }
                        }
                        return false
                    }

                    override fun onPageStarted(v: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(v, url, favicon)
                        if (!initialHostChecked && url != null && (url.startsWith("https://") || url.startsWith("http://"))) {
                            initialHostChecked = true
                            if (!isAppOrAuthHost(url)) {
                                v?.stopLoading()
                                launchExternalUrl(url)
                                dialog.dismiss()
                            }
                        }
                    }

                    override fun onPageFinished(v: WebView?, url: String?) {
                        super.onPageFinished(v, url)
                        CookieManager.getInstance().flush()
                    }
                }
                popupWebView.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        childView: WebView?,
                        isChildDialog: Boolean,
                        isChildUserGesture: Boolean,
                        childResultMsg: Message?
                    ): Boolean {
                        val href = childView?.hitTestResult?.extra
                        if (!href.isNullOrBlank() && (href.startsWith("https://") || href.startsWith("http://"))) {
                            launchExternalUrl(href)
                            return false
                        }
                        val childTransport = childResultMsg?.obj as? WebView.WebViewTransport ?: return false
                        val redirector = WebView(this@MainActivity)
                        redirector.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(rv: WebView?, rReq: WebResourceRequest?): Boolean {
                                val rUrl = rReq?.url?.toString()
                                if (!rUrl.isNullOrBlank() && (rUrl.startsWith("https://") || rUrl.startsWith("http://"))) {
                                    launchExternalUrl(rUrl)
                                }
                                redirector.destroy()
                                return true
                            }

                            override fun onPageStarted(rv: WebView?, rUrl: String?, fav: android.graphics.Bitmap?) {
                                if (!rUrl.isNullOrBlank() && (rUrl.startsWith("https://") || rUrl.startsWith("http://"))) {
                                    rv?.stopLoading()
                                    launchExternalUrl(rUrl)
                                }
                                redirector.destroy()
                            }
                        }
                        childTransport.webView = redirector
                        childResultMsg.sendToTarget()
                        return true
                    }

                    override fun onCloseWindow(window: WebView?) {
                        CookieManager.getInstance().flush()
                        dialog.dismiss()
                        popupWebView.destroy()
                    }
                }
                dialog.setOnDismissListener {
                    try {
                        CookieManager.getInstance().flush()
                        popupWebView.destroy()
                    } catch (_: Exception) {}
                }
                dialog.show()

                transport.webView = popupWebView
                resultMsg.sendToTarget()
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript(
                        "(function(){ try { return typeof window.__handleFilettinoBackButton === 'function' ? window.__handleFilettinoBackButton() : false; } catch(e) { return false; } })()"
                    ) { result ->
                        if (result != "true") {
                            if (webView.canGoBack()) {
                                webView.goBack()
                            } else {
                                finish()
                            }
                        }
                    }
                } else {
                    finish()
                }
            }
        })

        val noCacheHeaders = mapOf(
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "Pragma" to "no-cache"
        )
        webView.loadUrl(buildStartUrl(), noCacheHeaders)
        fetchAndDispatchFcmToken()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingCallExtras(intent)
        val callId = intent.getStringExtra(CallSignalConstants.EXTRA_CALL_ID) ?: ""
        val callAction = intent.getStringExtra(CallSignalConstants.EXTRA_CALL_ACTION) ?: ""
        if (callId.isNotBlank() && callAction.isNotBlank()) {
            val currentHost = if (::webView.isInitialized) {
                Uri.parse(webView.url ?: "").host?.lowercase() ?: ""
            } else {
                ""
            }
            if (::webView.isInitialized && currentHost.contains("filettino-app")) {
                val payload = JSONObject().apply {
                    put("callId", callId)
                    put("action", callAction)
                    put("callType", intent.getStringExtra(CallSignalConstants.EXTRA_CALL_TYPE) ?: "audio")
                    put("callerId", intent.getStringExtra(CallSignalConstants.EXTRA_CALLER_ID) ?: "")
                    put("callerName", intent.getStringExtra(CallSignalConstants.EXTRA_CALLER_NAME) ?: "Iscritto Filettino")
                    put("callerEmail", intent.getStringExtra(CallSignalConstants.EXTRA_CALLER_EMAIL) ?: "")
                }
                val escapedJson = JSONObject.quote(payload.toString())
                webView.evaluateJavascript(
                    "(function(){ if (typeof window.__onFilettinoNativeCallAction === 'function') { window.__onFilettinoNativeCallAction($escapedJson); return true; } return false; })()"
                ) { handled ->
                    if (handled != "true") {
                        webView.loadUrl(buildStartUrl(callId, callAction))
                    }
                }
            } else if (::webView.isInitialized) {
                webView.loadUrl(buildStartUrl(callId, callAction))
            }
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
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS
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
                            CallSignalConstants.patchUserFcmToken(savedUserId, token, this@MainActivity)
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
