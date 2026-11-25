package com.noordrive.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.view.WindowManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Home-screen (launcher) shell around the Noor Drive web UI in assets/index.html.
 * - Registered as a HOME app, so the Home button always returns here.
 * - Back button never leaves the app (it only closes panels inside the UI).
 */
class MainActivity : Activity() {

    private lateinit var web: WebView
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null

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
            setGeolocationEnabled(true)
            allowFileAccess = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)

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

        web.addJavascriptInterface(Bridge(), "NoorNative")
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
        web.destroy()
        super.onDestroy()
    }
}
