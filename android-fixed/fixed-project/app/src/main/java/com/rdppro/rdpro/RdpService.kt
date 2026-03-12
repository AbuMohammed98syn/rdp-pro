package com.rdppro.rdpro

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * RdpService v3 — طبقة الشبكة الكاملة
 * OkHttp WebSocket | Coroutines | Auto-reconnect | Token Auth
 */
class RdpService(val host: String, val port: Int, val token: String = "rdppro-secret-2024") {

    companion object {
        private const val TAG = "RdpService"
        private const val RECONNECT_DELAY = 3000L
        private const val MAX_RECONNECT   = 10
    }

    // ── Public state ──────────────────────────────────────────────────────────
    var screenW  = 1920
    var screenH  = 1080
    var fps      = 0
    var latencyMs = 0L
    var isConnected = false

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onConnected:    (() -> Unit)? = null
    var onDisconnect:   (() -> Unit)? = null
    var onFrame:        ((ByteArray) -> Unit)? = null
    var onAudioChunk:   ((String, Int) -> Unit)? = null  // (base64_pcm16, sampleRate)
    var onUploadProgress: ((Int) -> Unit)? = null

    // ── Private ───────────────────────────────────────────────────────────────
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var screenWs:  WebSocket? = null
    private var controlWs: WebSocket? = null
    private var audioWs:   WebSocket? = null

    private var screenReconnects  = 0
    private var controlReconnects = 0
    private var _connected        = false

    // FPS counter
    private var frameCount = 0
    private var fpsTimer   = System.currentTimeMillis()

    // ── Base URL ──────────────────────────────────────────────────────────────
    private val baseHttp = "http://$host:$port"
    private val baseWs   = "ws://$host:$port"

    // ── Auth header ───────────────────────────────────────────────────────────
    private fun authHeaders() = mapOf("X-Token" to token)

    // ── Screen WebSocket ──────────────────────────────────────────────────────
    fun connectScreen() {
        val req = Request.Builder()
            .url("$baseWs/ws/screen?token=$token")
            .header("X-Token", token)
            .build()

        screenWs = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                screenReconnects = 0
                Log.d(TAG, "Screen WS connected")
                if (!_connected) {
                    _connected = true
                    isConnected = true
                    onConnected?.invoke()
                }
            }

            override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                val data = bytes.toByteArray()
                // FPS counter
                frameCount++
                val now = System.currentTimeMillis()
                if (now - fpsTimer >= 1000) {
                    fps = frameCount
                    frameCount = 0
                    fpsTimer   = now
                }
                onFrame?.invoke(data)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                Log.w(TAG, "Screen WS failure: ${t.message}")
                _connected  = false
                isConnected = false
                onDisconnect?.invoke()
                scheduleScreenReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                _connected  = false
                isConnected = false
                onDisconnect?.invoke()
                if (code != 1000) scheduleScreenReconnect()
            }
        })
    }

    private fun scheduleScreenReconnect() {
        if (screenReconnects >= MAX_RECONNECT) return
        screenReconnects++
        scope.launch {
            delay(RECONNECT_DELAY * screenReconnects)
            Log.d(TAG, "Reconnecting screen (attempt $screenReconnects)...")
            connectScreen()
        }
    }

    // ── Control WebSocket ─────────────────────────────────────────────────────
    fun connectControl() {
        val req = Request.Builder()
            .url("$baseWs/ws/control?token=$token")
            .header("X-Token", token)
            .build()

        controlWs = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                controlReconnects = 0
                Log.d(TAG, "Control WS connected")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                Log.w(TAG, "Control WS failure: ${t.message}")
                scheduleControlReconnect()
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (code != 1000) scheduleControlReconnect()
            }
        })
    }

    private fun scheduleControlReconnect() {
        if (controlReconnects >= MAX_RECONNECT) return
        controlReconnects++
        scope.launch {
            delay(RECONNECT_DELAY)
            connectControl()
        }
    }

    // ── Audio WebSocket ───────────────────────────────────────────────────────
    fun connectAudio() {
        val req = Request.Builder()
            .url("$baseWs/ws/audio?token=$token")
            .header("X-Token", token)
            .build()

        audioWs = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                Log.d(TAG, "Audio WS connected")
                ws.send(JSONObject().put("action","start").toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val j    = JSONObject(text)
                    val b64  = j.optString("audio")
                    val rate = j.optInt("rate", 44100)
                    if (b64.isNotEmpty()) onAudioChunk?.invoke(b64, rate)
                } catch (_: Exception) {}
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                Log.w(TAG, "Audio WS failure: ${t.message}")
            }
        })
    }

    fun disconnectAudio() {
        audioWs?.send(JSONObject().put("action","stop").toString())
        audioWs?.close(1000, "stop")
        audioWs = null
    }

    // ── Control helpers ───────────────────────────────────────────────────────
    private fun send(obj: JSONObject) {
        controlWs?.send(obj.toString())
    }

    fun mouseMove(x: Int, y: Int) =
        send(JSONObject().put("type","mouse_move").put("x",x).put("y",y))

    fun mouseClick(x: Int? = null, y: Int? = null) =
        send(JSONObject().put("type","mouse_click").apply { x?.let { put("x",it) }; y?.let { put("y",it) } })

    fun mouseRight(x: Int? = null, y: Int? = null) =
        send(JSONObject().put("type","mouse_right").apply { x?.let { put("x",it) }; y?.let { put("y",it) } })

    fun mouseDbl(x: Int? = null, y: Int? = null) =
        send(JSONObject().put("type","mouse_dbl").apply { x?.let { put("x",it) }; y?.let { put("y",it) } })

    fun mouseMiddle() =
        send(JSONObject().put("type","mouse_middle"))

    fun mouseScroll(dir: String, amount: Int = 3) =
        send(JSONObject().put("type","mouse_scroll").put("dir",dir).put("amount",amount))

    fun mouseDrag(x1: Int, y1: Int, x2: Int, y2: Int) =
        send(JSONObject().put("type","mouse_drag").put("x1",x1).put("y1",y1).put("x2",x2).put("y2",y2))

    fun keyPress(key: String) =
        send(JSONObject().put("type","key_press").put("key",key))

    fun keyType(text: String) =
        send(JSONObject().put("type","key_type").put("text",text))

    fun hotkey(vararg keys: String) =
        send(JSONObject().put("type","hotkey").put("keys", org.json.JSONArray(keys)))

    fun setQuality(q: Int) =
        send(JSONObject().put("type","screen_quality").put("quality",q))

    fun setScale(s: Float) =
        send(JSONObject().put("type","screen_scale").put("scale",s))

    // ── HTTP helpers ──────────────────────────────────────────────────────────
    fun get(path: String, callback: (JSONObject?) -> Unit) {
        val req = Request.Builder()
            .url("$baseHttp$path")
            .header("X-Token", token)
            .get().build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "GET $path failed: ${e.message}")
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string()
                callback(body?.runCatching { JSONObject(this) }?.getOrNull())
            }
        })
    }

    fun post(path: String, body: JSONObject = JSONObject(), callback: ((JSONObject?) -> Unit)? = null) {
        val req = Request.Builder()
            .url("$baseHttp$path")
            .header("X-Token", token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.w(TAG, "POST $path failed: ${e.message}")
                callback?.invoke(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val b = response.body?.string()
                callback?.invoke(b?.runCatching { JSONObject(this) }?.getOrNull())
            }
        })
    }

    // ── Latency ping ──────────────────────────────────────────────────────────
    fun measureLatency(callback: (Long) -> Unit) {
        val t0 = System.currentTimeMillis()
        get("/api/ping") {
            latencyMs = System.currentTimeMillis() - t0
            callback(latencyMs)
        }
    }

    // ── File Upload via WebSocket ─────────────────────────────────────────────
    fun uploadFile(
        file: File,
        destDir: String = "",
        onProgress: (Int) -> Unit = {},
        onDone: (Boolean, String) -> Unit = {_,_ ->}
    ) {
        val req = Request.Builder()
            .url("$baseWs/ws/upload?token=$token")
            .header("X-Token", token)
            .build()

        client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, resp: Response) {
                val meta = JSONObject()
                    .put("name",  file.name)
                    .put("size",  file.length())
                    .put("dest",  destDir)
                ws.send(meta.toString())

                // إرسال الملف في chunks
                scope.launch(Dispatchers.IO) {
                    val CHUNK = 64 * 1024
                    file.inputStream().use { input ->
                        val buf  = ByteArray(CHUNK)
                        var sent = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            ws.send(okio.ByteString.of(*buf.copyOf(read)))
                            sent += read
                            onProgress(((sent * 100) / file.length()).toInt())
                        }
                    }
                    ws.send(okio.ByteString.of(*"EOF".toByteArray()))
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val j = JSONObject(text)
                if (j.optBoolean("done")) {
                    onDone(true, j.optString("path",""))
                    ws.close(1000, "done")
                } else if (j.has("error")) {
                    onDone(false, j.optString("error",""))
                    ws.close(1000, "error")
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, resp: Response?) {
                onDone(false, t.message ?: "فشل الاتصال")
            }
        })
    }

    // ── URL encode ────────────────────────────────────────────────────────────
    fun encodeUrl(s: String): String = URLEncoder.encode(s, "UTF-8")

    // ── Dispose ───────────────────────────────────────────────────────────────
    fun dispose() {
        screenWs?.close(1000, "dispose")
        controlWs?.close(1000, "dispose")
        audioWs?.close(1000, "dispose")
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }
}
