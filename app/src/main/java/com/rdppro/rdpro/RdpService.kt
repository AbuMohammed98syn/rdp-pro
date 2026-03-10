package com.rdppro.rdpro

import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

class RdpService(val host: String, val port: Int) {

    val baseUrl get() = "http://$host:$port"
    val wsUrl   get() = "ws://$host:$port"

    var screenW = 1920
    var screenH = 1080
    var fps = 0
    var latencyMs = 0
    private var fpsCount = 0
    private var fpsTime = System.currentTimeMillis()

    private var screenWs:  WebSocketClient? = null
    private var controlWs: WebSocketClient? = null

    var onFrame:      ((ByteArray) -> Unit)? = null
    var onConnected:  (() -> Unit)?          = null
    var onDisconnect: (() -> Unit)?          = null

    private val ui = Handler(Looper.getMainLooper())

    fun connectScreen(quality: Int = 65, targetFps: Int = 30) {
        screenWs?.close()
        try {
            screenWs = object : WebSocketClient(URI("$wsUrl/ws/screen")) {
                override fun onOpen(h: ServerHandshake) {
                    send(JSONObject().apply {
                        put("quality", quality)
                        put("fps", targetFps)
                        put("monitor", 1)
                    }.toString())
                }
                override fun onMessage(msg: String) {
                    try {
                        val j = JSONObject(msg)
                        if (j.getString("t") == "frame") {
                            val bytes = Base64.decode(j.getString("d"), Base64.DEFAULT)
                            fpsCount++
                            val now = System.currentTimeMillis()
                            if (j.has("ts")) latencyMs = (now - j.getLong("ts")).toInt()
                            if (now - fpsTime >= 1000) { fps = fpsCount; fpsCount = 0; fpsTime = now }
                            ui.post { onFrame?.invoke(bytes); onConnected?.invoke() }
                        }
                    } catch (_: Exception) {}
                }
                override fun onClose(c: Int, r: String?, remote: Boolean) {
                    ui.post { onDisconnect?.invoke() }
                    ui.postDelayed({ connectScreen(quality, targetFps) }, 2000)
                }
                override fun onError(e: Exception) {
                    ui.postDelayed({ connectScreen(quality, targetFps) }, 2000)
                }
            }.apply { connect() }
        } catch (_: Exception) {
            ui.postDelayed({ connectScreen(quality, targetFps) }, 2000)
        }
    }

    fun connectControl() {
        controlWs?.close()
        try {
            controlWs = object : WebSocketClient(URI("$wsUrl/ws/control")) {
                override fun onOpen(h: ServerHandshake) {}
                override fun onMessage(msg: String) {}
                override fun onClose(c: Int, r: String?, remote: Boolean) {
                    ui.postDelayed({ connectControl() }, 1000)
                }
                override fun onError(e: Exception) {
                    ui.postDelayed({ connectControl() }, 1000)
                }
            }.apply { connect() }
        } catch (_: Exception) {
            ui.postDelayed({ connectControl() }, 1000)
        }
    }

    fun send(cmd: JSONObject) {
        try { controlWs?.send(cmd.toString()) } catch (_: Exception) {}
    }

    fun mouseMove(x: Int, y: Int)      = send(JSONObject().put("cmd","mv").put("x",x).put("y",y))
    fun mouseClick(b: String = "left") = send(JSONObject().put("cmd","cl").put("b",b))
    fun mouseDbl()                     = send(JSONObject().put("cmd","dcl"))
    fun mouseRight()                   = send(JSONObject().put("cmd","rcl"))
    fun mouseMiddle()                  = send(JSONObject().put("cmd","mcl"))
    fun mouseScroll(dir: String, amount: Int = 3) =
        send(JSONObject().put("cmd","scrl").put("d",dir).put("a",amount))
    fun keyType(text: String)  = send(JSONObject().put("cmd","kt").put("t",text))
    fun keyPress(key: String)  = send(JSONObject().put("cmd","kp").put("k",key))
    fun hotkey(vararg keys: String) {
        val arr = JSONArray().apply { keys.forEach { put(it) } }
        send(JSONObject().put("cmd","hk").put("ks",arr))
    }

    fun get(path: String, cb: (JSONObject?) -> Unit) {
        Thread {
            try {
                val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                val resp = conn.inputStream.bufferedReader().readText()
                ui.post { cb(JSONObject(resp)) }
            } catch (_: Exception) { ui.post { cb(null) } }
        }.start()
    }

    fun post(path: String, body: JSONObject? = null, cb: ((JSONObject?) -> Unit)? = null) {
        Thread {
            try {
                val conn = URL("$baseUrl$path").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.connectTimeout = 6000; conn.readTimeout = 6000
                if (body != null) { conn.doOutput = true; conn.outputStream.write(body.toString().toByteArray()) }
                val resp = conn.inputStream.bufferedReader().readText()
                ui.post { cb?.invoke(JSONObject(resp)) }
            } catch (_: Exception) { ui.post { cb?.invoke(null) } }
        }.start()
    }

    fun encodeUrl(s: String) = URLEncoder.encode(s, "UTF-8")!!

    fun dispose() { screenWs?.close(); controlWs?.close() }
}
