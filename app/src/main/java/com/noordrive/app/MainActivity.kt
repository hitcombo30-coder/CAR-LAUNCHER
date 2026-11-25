package com.noordrive.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home-screen (launcher) shell around the Noor Drive web UI in assets/index.html.
 * - Registered as a HOME app, so the Home button always returns here.
 * - Back button never leaves the app (it only closes panels inside the UI).
 * - Shows what other apps (YouTube, YT Music...) are playing, via media sessions.
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

    @Volatile
    private var lastController: MediaController? = null

    @Volatile
    private var appsJson: String? = null

    @Volatile
    private var nowCache: String = """{"ok":true,"has":false}"""
    @Volatile private var uiVisible = false
    @Volatile private var pollerOn = false
    private val bridge by lazy { Bridge() }
    private var artKey = ""
    private var artType = ""
    private var artUrlCache = ""

    private fun startPoller() {
        if (pollerOn) return
        pollerOn = true
        Thread {
            while (pollerOn) {
                try {
                    if (uiVisible) nowCache = bridge.computeNow()
                } catch (e: Exception) {
                }
                try { Thread.sleep(2000) } catch (e: InterruptedException) { }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
        uiVisible = true
        startPoller()
    }

    override fun onPause() {
        uiVisible = false
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#03130F"))
        setContentView(web)
        setupWebView()
        hideBars()
        askPermissions()
        startKeepAlive()
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // many Malaysian radio streams are plain http
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setGeolocationEnabled(true)
            allowFileAccess = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? {
                val u = request.url
                val path = u.path ?: ""
                if (path.startsWith("/appicon/")) return appIcon(path.removePrefix("/appicon/").removeSuffix(".png"))
                return loader.shouldInterceptRequest(u)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (url.startsWith("intent:")) {
                    try {
                        startActivity(Intent.parseUri(url, Intent.URI_INTENT_SCHEME))
                    } catch (e: Exception) {
                    }
                    return true
                }
                // keep the UI page; ignore other top-level navigations
                return !url.startsWith("https://appassets.androidplatform.net")
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String, callback: GeolocationPermissions.Callback
            ) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    callback.invoke(origin, true, false)
                } else {
                    pendingGeo = Pair(origin, callback)
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                }
            }
        }

        web.addJavascriptInterface(bridge, "NoorNative")
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private fun hasNotifAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.split(":").any {
            ComponentName.unflattenFromString(it)?.packageName == packageName
        }
    }

    /** The media session to show: a playing one if any, otherwise the most recent one. */
    private fun pickSession(): MediaController? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val list = msm.getActiveSessions(ComponentName(this, NoorNotificationListener::class.java))
            .filter { it.packageName != packageName }
        val playing = list.firstOrNull {
            val s = it.playbackState?.state
            s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_BUFFERING
        }
        return playing ?: list.firstOrNull { it.metadata != null }
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun scaleToJpegBase64(src: Bitmap): String {
        val max = 240
        val ratio = minOf(max.toFloat() / src.width, max.toFloat() / src.height, 1f)
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        val bmp = if (ratio < 1f) Bitmap.createScaledBitmap(src, w, h, true) else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun bitmapFrom(md: MediaMetadata): Bitmap? {
        return md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    private fun artUriFrom(md: MediaMetadata): String? {
        return md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
    }

    private fun pushToJs(js: String) {
        runOnUiThread { web.evaluateJavascript(js, null) }
    }

    private fun startSafe(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    /** Functions the web UI can call as window.NoorNative.* */
    inner class Bridge {

        @JavascriptInterface
        fun openApp(pkg: String) {
            runOnUiThread {
                val launch = packageManager.getLaunchIntentForPackage(pkg)
                try {
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launch)
                    } else {
                        startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                } catch (e: Exception) {
                }
            }
        }

        @JavascriptInterface
        fun listApps(): String {
            val pm = packageManager
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            val list = pm.queryIntentActivities(main, 0)
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
            for (ri in list) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName) continue
                val o = JSONObject()
                o.put("n", ri.loadLabel(pm).toString())
                o.put("p", pkg)
                o.put("i", iconToBase64(ri.loadIcon(pm)))
                arr.put(o)
            }
            return arr.toString()
        }

        /** Builds the app list (with icons) on a background thread so the screen never freezes. */
        @JavascriptInterface
        fun requestApps() {
            val cached = appsJson
            if (cached != null) {
                pushToJs("window.__appsDone&&window.__appsDone(${JSONObject.quote(cached)})")
                return
            }
            Thread {
                var out = "[]"
                try {
                    val pm = packageManager
                    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                    val arr = JSONArray()
                    val named = pm.queryIntentActivities(main, 0)
                        .filter { it.activityInfo.packageName != packageName }
                        .map { Pair(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
                        .sortedBy { it.first.lowercase() }
                    // names only: icons are loaded one by one when they scroll into view
                    for ((name, pkg) in named) {
                        val o = JSONObject()
                        o.put("n", name)
                        o.put("p", pkg)
                        arr.put(o)
                    }
                    out = arr.toString()
                    appsJson = out
                } catch (e: Exception) {
                }
                pushToJs("window.__appsDone&&window.__appsDone(${JSONObject.quote(out)})")
            }.start()
        }

        /** Wi-Fi / Bluetooth tethering / mobile data, and whether the internet really works. */
        @JavascriptInterface
        fun netInfo(): String {
            return try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val t = when {
                    caps == null -> "none"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bt"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "eth"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                    else -> "other"
                }
                val ok = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                """{"t":"$t","ok":$ok}"""
            } catch (e: Exception) {
                """{"t":"none","ok":false}"""
            }
        }

        /** Async GET for the official JAKIM prayer-time server (no browser CORS limits here). */
        @JavascriptInterface
        fun fetchNative(id: String, url: String) {
            if (!url.startsWith("https://www.e-solat.gov.my/")) {
                pushToJs("window.__natDone&&window.__natDone(${JSONObject.quote(id)},false,\"blocked\")")
                return
            }
            Thread {
                var ok = true
                var out: String
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 10000
                    c.setRequestProperty("User-Agent", "NoorDrive/1.0")
                    out = c.inputStream.bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    ok = false
                    out = e.toString()
                }
                pushToJs("window.__natDone&&window.__natDone(${JSONObject.quote(id)},$ok,${JSONObject.quote(out)})")
            }.start()
        }

        // ----- Now Playing (other apps) -----

        @JavascriptInterface
        fun hasNotifAccess(): Boolean = hasNotifAccess()

        @JavascriptInterface
        fun openNotifSettings(): Boolean =
            startSafe(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

        @JavascriptInterface
        fun openAppSettings(): Boolean = startSafe(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
        )

        /** Instant: returns the last result made by the background thread (never blocks the screen). */
        @JavascriptInterface
        fun nowPlaying(): String = nowCache

        fun computeNow(): String {
            val o = JSONObject()
            if (!hasNotifAccess()) {
                o.put("ok", false)
                o.put("reason", "access")
                return o.toString()
            }
            try {
                val c = pickSession()
                lastController = c
                o.put("ok", true)
                if (c == null) {
                    o.put("has", false)
                    return o.toString()
                }
                val md = c.metadata
                val st = c.playbackState
                val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
                val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
                val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
                val dur = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                var pos = 0L
                if (st != null) {
                    pos = st.position
                    if (st.state == PlaybackState.STATE_PLAYING && st.lastPositionUpdateTime > 0) {
                        pos += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                    }
                }
                if (pos < 0) pos = 0
                val playing = st?.state == PlaybackState.STATE_PLAYING ||
                        st?.state == PlaybackState.STATE_BUFFERING
                o.put("has", true)
                o.put("pkg", c.packageName)
                o.put("app", appLabel(c.packageName))
                o.put("title", title)
                o.put("artist", artist)
                o.put("album", album)
                o.put("dur", if (dur > 0) dur else 0)
                o.put("pos", pos)
                o.put("playing", playing)
                o.put("key", "${c.packageName}|$title|$artist")
                val key = "${c.packageName}|$title|$artist"
                if (key == artKey) {
                    o.put("art", artType)
                    if (artType == "url") o.put("artUrl", artUrlCache)
                } else if (md != null && bitmapFrom(md) != null) {
                    o.put("art", "bmp")
                    artKey = key; artType = "bmp"
                } else {
                    val u = if (md != null) artUriFrom(md) else null
                    if (u != null && u.startsWith("https://")) {
                        o.put("art", "url")
                        o.put("artUrl", u)
                    } else if (u != null && (u.startsWith("content://") || u.startsWith("file://"))) {
                        o.put("art", "bmp")
                    } else {
                        o.put("art", "")
                    }
                    artKey = key; artType = o.optString("art", ""); artUrlCache = o.optString("artUrl", "")
                }
            } catch (e: SecurityException) {
                o.put("ok", false)
                o.put("reason", "access")
            } catch (e: Exception) {
                o.put("ok", true)
                o.put("has", false)
            }
            return o.toString()
        }

        /** Cover art as a small JPEG (base64), for bitmap / content:// art. */
        @JavascriptInterface
        fun nowArt(key: String): String {
            return try {
                val md = lastController?.metadata ?: return ""
                var bmp = bitmapFrom(md)
                if (bmp == null) {
                    val u = artUriFrom(md)
                    if (u != null && (u.startsWith("content://") || u.startsWith("file://"))) {
                        bmp = contentResolver.openInputStream(Uri.parse(u))?.use {
                            BitmapFactory.decodeStream(it)
                        }
                    }
                }
                if (bmp == null) "" else scaleToJpegBase64(bmp)
            } catch (e: Exception) {
                ""
            }
        }

        /** Cover art that is only available as an https link: download off the UI thread. */
        @JavascriptInterface
        fun fetchArt(key: String, url: String) {
            if (!url.startsWith("https://")) return
            Thread {
                var b64 = ""
                try {
                    val c = URL(url).openConnection() as HttpURLConnection
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("User-Agent", "NoorDrive/1.0")
                    val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) b64 = scaleToJpegBase64(bmp)
                } catch (e: Exception) {
                }
                pushToJs("window.__artDone&&window.__artDone(${JSONObject.quote(key)},${JSONObject.quote(b64)})")
            }.start()
        }

        @JavascriptInterface
        fun mediaCmd(cmd: String) {
            try {
                val c = lastController ?: pickSession() ?: return
                val tc = c.transportControls
                when (cmd) {
                    "toggle" ->
                        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                    "play" -> tc.play()
                    "pause" -> tc.pause()
                    "next" -> tc.skipToNext()
                    "prev" -> tc.skipToPrevious()
                }
            } catch (e: Exception) {
            }
        }
    }

    private val iconCache = HashMap<String, ByteArray>()

    /** One app icon as a small PNG, made when the page asks for it. */
    private fun appIcon(pkg: String): WebResourceResponse? {
        return try {
            val bytes = synchronized(iconCache) { iconCache[pkg] } ?: run {
                val d = packageManager.getApplicationIcon(pkg)
                val size = 64
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, size, size)
                d.draw(Canvas(bmp))
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
                bmp.recycle()
                val b = out.toByteArray()
                synchronized(iconCache) { iconCache[pkg] = b }
                b
            }
            WebResourceResponse("image/png", null, bytes.inputStream())
        } catch (e: Exception) {
            null
        }
    }

    private fun iconToBase64(d: Drawable): String {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun askPermissions() {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 2)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        pendingGeo?.let { (origin, cb) ->
            cb.invoke(
                origin,
                checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED,
                false
            )
            pendingGeo = null
        }
    }

    private fun startKeepAlive() {
        try {
            val i = Intent(this, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
        } catch (e: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun hideBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    /** Pressing Home while this is the launcher just brings us to the front. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        hideBars()
    }

    /** Back never exits: it only closes panels inside the UI. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript("window.noorBack&&window.noorBack()", null)
    }

    // Do NOT pause the WebView, so music keeps playing in the background.
    override fun onDestroy() {
        pollerOn = false
        web.destroy()
        super.onDestroy()
    }
}
