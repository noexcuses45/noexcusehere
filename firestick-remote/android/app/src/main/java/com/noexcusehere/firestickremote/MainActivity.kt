package com.noexcusehere.firestickremote

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var tv: FireTv
    private val worker = Executors.newCachedThreadPool()
    private val prefs by lazy { getSharedPreferences("remote", Context.MODE_PRIVATE) }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tv = FireTv(filesDir)
        web = WebView(this).apply {
            setBackgroundColor(0xFF0D0E11.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "AndroidRemote")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
    }

    inner class Bridge {
        @JavascriptInterface
        fun haptic(ms: Int) {
            web.post {
                web.performHapticFeedback(
                    if (ms >= 25) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
                )
            }
        }

        /** Mirrors the HTTP API of server.py; replies via window.__nativeReply(id, json). */
        @JavascriptInterface
        fun call(id: Int, path: String, body: String) {
            worker.execute {
                val result = try {
                    handle(path, if (body.isBlank()) JSONObject() else JSONObject(body))
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("msg", e.message ?: e.toString())
                }
                val js = "window.__nativeReply($id, ${JSONObject.quote(result.toString())})"
                web.post { web.evaluateJavascript(js, null) }
            }
        }
    }

    private fun ok() = JSONObject().put("ok", true)
    private fun fail(msg: String) = JSONObject().put("ok", false).put("msg", msg)

    private fun handle(path: String, data: JSONObject): JSONObject = when (path) {
        "status" -> {
            // Reconnect to the last Firestick automatically.
            val saved = prefs.getString("tv", "") ?: ""
            if (tv.target == null && saved.isNotBlank()) tv.connect(saved)
            JSONObject()
                .put("status", if (tv.isConnected()) "connected" else "disconnected")
                .put("tv", tv.target ?: saved)
                .put("apps", JSONArray(FireTv.APPS.keys.toList()))
        }

        "connect" -> {
            val host = data.optString("host").ifBlank { prefs.getString("tv", "") ?: "" }
            val (success, msg) = tv.connect(host)
            if (success) prefs.edit().putString("tv", host.trim()).apply()
            JSONObject().put("ok", success).put("msg", msg)
        }

        "scan" -> JSONObject().put("ok", true).put("found", JSONArray(tv.scan()))

        "key" -> {
            val code = FireTv.KEYS[data.optString("key")]
            if (code == null) fail("Unknown key") else {
                val flag = if (data.optBoolean("long")) "--longpress " else ""
                tv.send("input keyevent $flag$code")
                ok()
            }
        }

        "text" -> {
            val text = data.optString("text")
            if (text.isNotEmpty()) {
                val escaped = text.replace("%", "\\%").replace(" ", "%s")
                tv.send("input text ${FireTv.shellQuote(escaped)}")
            }
            ok()
        }

        "app" -> {
            val name = data.optString("name")
            val pkg = FireTv.APPS[name]
            if (pkg == null) fail("Unknown app") else {
                val out = tv.query("monkey -p $pkg -c android.intent.category.LAUNCHER 1")
                if (out.contains("No activities found") || out.contains("aborted", ignoreCase = true))
                    fail("$name isn't installed") else ok()
            }
        }

        "settings" -> {
            tv.send("am start -a android.settings.SETTINGS")
            ok()
        }

        else -> fail("Unknown command")
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }
}
