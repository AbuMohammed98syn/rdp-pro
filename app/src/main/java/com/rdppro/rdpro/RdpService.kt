package com.rdppro.rdpro

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

/**
 * RdpService v4.1
 * ─────────────────────────────────────────────
 * • HTTP / HTTPS (self-signed cert bypass)
 * • WS  / WSS
 * • Exponential Backoff reconnect
 * • Wake-on-LAN
 * • Chat + Audio + Notifications WebSockets
 * • Temp Tokens + Perm Tokens
 * • 2FA verify
 * • Network bandwidth history
 * • measureLatency
 */
class RdpService(
    val host:  String,
    val port:  Int,
    val token: String  = "rdppro-secret-2024",
    val https: Boolean = false,      // true → HTTPS/WSS
) {
    companion object {
        private const val TAG         = "RdpService"
        private const val MAX_RECONNECT = 12
        private val BACKOFF = longArrayOf(1000,2000,4000,8000,15000,30000)
    }

    // ── State ─────────────────────────────────────────────────────────────────
    var screenW = 1920; var screenH = 1080
    var fps = 0; var latencyMs = 0L; var isConnected = false
    var permLevel = 0   // 0=full,1=view-only,2=files,3=terminal

    // ── Callbacks ─────────────────────────────────────────────────────────────
    var onConnected:   (() -> Unit)?              = null
    var onDisconnect:  (() -> Unit)?              = null
    var onFrame:       ((ByteArray) -> Unit)?     = null
    var onAudioChunk:  ((String, Int) -> Unit)?   = null
    var onChatMessage: ((JSONObject) -> Unit)?    = null
    var onNotify:      ((JSONObject) -> Unit)?    = null

    // ── URL helpers ───────────────────────────────────────────────────────────
    private val scheme   = if (https) "https" else "http"
    private val wsScheme = if (https) "wss"   else "ws"
    private val baseHttp = "$scheme://$host:$port"
    private val baseWs   = "$wsScheme://$host:$port"

    // ── OkHttp — trust self-signed certs when HTTPS ───────────────────────────
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client: OkHttpClient = buildClient()

    private fun buildClient(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)

        if (https) {
            // Trust self-signed cert from server
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = Unit
                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val sslCtx = SSLContext.getInstance("TLS").also {
                it.init(null, arrayOf(trustAll), SecureRandom())
            }
            builder
                .sslSocketFactory(sslCtx.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }

    // ── WebSocket refs ────────────────────────────────────────────────────────
    private var screenWs:  WebSocket? = null
    private var controlWs: WebSocket? = null
    private var audioWs:   WebSocket? = null
    private var chatWs:    WebSocket? = null
    private var notifyWs:  WebSocket? = null

    private var screenRC = 0; private var controlRC = 0
    private var _connected = false
    private var frameCount = 0; private var fpsTimer = System.currentTimeMillis()

    // ── HTTP helpers ──────────────────────────────────────────────────────────
    fun get(path: String, cb: ((JSONObject?) -> Unit)? = null) {
        val req = Request.Builder()
            .url("$baseHttp$path")
            .header("Authorization", "Bearer $token")
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(c: Call, e: java.io.IOException) { Log.e(TAG,"GET $path",e); cb?.invoke(null) }
            override fun onResponse(c: Call, r: Response) {
                val body = r.body?.string()
                cb?.invoke(body?.let { runCatching { JSONObject(it) }.getOrNull() })
            }
        })
    }

    fun post(path: String, body: JSONObject? = null, cb: ((JSONObject?) -> Unit)? = null) {
        val rb = (body?.toString() ?: "{}").toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseHttp$path")
            .header("Authorization", "Bearer $token")
            .post(rb).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(c: Call, e: java.io.IOException) { Log.e(TAG,"POST $path",e); cb?.invoke(null) }
            override fun onResponse(c: Call, r: Response) {
                val b = r.body?.string()
                cb?.invoke(b?.let { runCatching { JSONObject(it) }.getOrNull() })
            }
        })
    }

    // shorthand without body
    fun post(path: String, cb: ((JSONObject?) -> Unit)? = null) = post(path, null, cb)

    // ── Screen WebSocket ──────────────────────────────────────────────────────
    fun connectScreen() {
        if (_connected) return
        val url = "$baseWs/ws/screen?token=$token"
        screenWs = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: Response) {
                    _connected = true; isConnected = true; screenRC = 0
                    onConnected?.invoke()
                }
                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                    frameCount++
                    val now = System.currentTimeMillis()
                    if (now - fpsTimer >= 1000) {
                        fps = frameCount; frameCount = 0; fpsTimer = now
                    }
                    onFrame?.invoke(bytes.toByteArray())
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    runCatching { JSONObject(text) }.getOrNull()?.let { j ->
                        if (j.has("quality_adjust")) Log.d(TAG, "quality: ${j.optInt("quality_adjust")}")
                    }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = scheduleReconnectScreen()
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    _connected = false; isConnected = false; onDisconnect?.invoke()
                    if (code != 1000) scheduleReconnectScreen()
                }
            })
    }

    private fun scheduleReconnectScreen() {
        if (screenRC >= MAX_RECONNECT) { Log.e(TAG,"Screen max reconnects"); return }
        val delay = BACKOFF.getOrElse(screenRC) { 30000 }
        screenRC++
        scope.launch { delay(delay); connectScreen() }
    }

    // ── Control WebSocket ─────────────────────────────────────────────────────
    fun connectControl() {
        val url = "$baseWs/ws/control?token=$token"
        controlWs = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    val d = BACKOFF.getOrElse(controlRC){ 30000 }; controlRC++
                    scope.launch { delay(d); connectControl() }
                }
                override fun onClosed(ws: WebSocket, c: Int, r: String) {
                    if (c != 1000) { scope.launch { delay(3000); connectControl() } }
                }
            })
    }

    fun sendControl(msg: JSONObject) { controlWs?.send(msg.toString()) }

    // ── Chat ──────────────────────────────────────────────────────────────────
    fun connectChat() {
        val url = "$baseWs/ws/chat?token=$token"
        chatWs = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    runCatching { JSONObject(text) }.getOrNull()?.let { onChatMessage?.invoke(it) }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    scope.launch { delay(3000); connectChat() }
                }
            })
    }

    fun sendChatMessage(msg: String) =
        chatWs?.send(JSONObject().put("type","chat").put("text",msg).toString())

    fun disconnectChat() { chatWs?.close(1000,"done"); chatWs = null }

    // ── Notifications ─────────────────────────────────────────────────────────
    fun connectNotifications() {
        val url = "$baseWs/ws/notify?token=$token"
        notifyWs = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, text: String) {
                    runCatching { JSONObject(text) }.getOrNull()?.let { onNotify?.invoke(it) }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                    scope.launch { delay(5000); connectNotifications() }
                }
            })
    }

    // ── Audio ─────────────────────────────────────────────────────────────────
    fun connectAudio() {
        val url = "$baseWs/ws/audio?token=$token"
        audioWs = client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onMessage(ws: WebSocket, bytes: okio.ByteString) {
                    onAudioChunk?.invoke(android.util.Base64.encodeToString(bytes.toByteArray(),0), bytes.size)
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = Unit
            })
    }

    fun disconnectAudio() { audioWs?.close(1000,"done"); audioWs = null }

    // ── Wake-on-LAN ───────────────────────────────────────────────────────────
    fun sendWakeOnLan(mac: String, broadcast: String = "255.255.255.255",
                      port: Int = 9, cb: (Boolean, String) -> Unit = { _,_ -> }) {
        scope.launch(Dispatchers.IO) {
            try {
                val macBytes = mac.split("[:-]".toRegex()).map { it.toInt(16).toByte() }.toByteArray()
                if (macBytes.size != 6) { cb(false,"MAC غير صحيح"); return@launch }
                val packet = ByteArray(102)
                repeat(6) { packet[it] = 0xFF.toByte() }
                repeat(16) { i -> macBytes.copyInto(packet, 6 + i * 6) }
                val socket = DatagramSocket(); socket.broadcast = true
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), port))
                // Also try port 7
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(broadcast), 7))
                socket.close()
                Log.i(TAG,"WoL sent → $broadcast (MAC $mac)")
                cb(true,"✓ Magic Packet أُرسل إلى $broadcast")
            } catch (e: Exception) {
                Log.e(TAG,"WoL",e); cb(false,"✗ ${e.message}")
            }
        }
    }

    // ── Temp / Perm Tokens ────────────────────────────────────────────────────
    fun createTempToken(minutes: Int = 60, label: String = "", cb: (JSONObject?) -> Unit) =
        post("/api/token/temp",
            JSONObject().put("expires_minutes", minutes).put("label", label), cb)

    fun createPermToken(minutes: Int, permLevel: Int, label: String = "", cb: (JSONObject?) -> Unit) =
        post("/api/token/perm",
            JSONObject().put("expires_minutes", minutes)
                        .put("perm_level", permLevel)
                        .put("label", label), cb)

    // ── 2FA ───────────────────────────────────────────────────────────────────
    fun verify2FA(code: String, cb: (Boolean, String) -> Unit) =
        post("/api/2fa/verify", JSONObject().put("code", code)) { j ->
            val ok = j?.optBoolean("ok") == true
            cb(ok, if (ok) "✓ تم التحقق" else "✗ رمز خاطئ")
        }

    fun get2FAStatus(cb: (Boolean) -> Unit) =
        get("/api/2fa/status") { j -> cb(j?.optBoolean("enabled", false) == true) }

    // ── Recording ─────────────────────────────────────────────────────────────
    fun startRecording(cb: (Boolean) -> Unit) =
        post("/api/recording/start") { cb(it?.optBoolean("ok") == true) }

    fun stopRecording(cb: (String) -> Unit) =
        post("/api/recording/stop") { cb(it?.optString("path","") ?: "") }

    // ── Screen utils ──────────────────────────────────────────────────────────
    fun setQuality(q: Int, fps: Int? = null) {
        val body = JSONObject().put("quality", q)
        fps?.let { body.put("fps", it) }
        post("/api/screen/quality", body)
    }

    // ── Network bandwidth history ─────────────────────────────────────────────
    fun getNetworkHistory(cb: (List<JSONObject>) -> Unit) =
        get("/api/network/history") { j ->
            val arr = j?.optJSONArray("history") ?: run { cb(emptyList()); return@get }
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            cb(list)
        }

    // ── Audit log ─────────────────────────────────────────────────────────────
    fun getAuditLog(limit: Int = 50, cb: (List<JSONObject>) -> Unit) =
        get("/api/audit/log?limit=$limit") { j ->
            val arr = j?.optJSONArray("entries") ?: run { cb(emptyList()); return@get }
            val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            cb(list)
        }

    // ── Latency measurement ───────────────────────────────────────────────────
    fun measureLatency(cb: (Long) -> Unit) {
        val t0 = System.currentTimeMillis()
        get("/api/ping") { latencyMs = System.currentTimeMillis() - t0; cb(latencyMs) }
    }

    // ── File upload via WebSocket ─────────────────────────────────────────────
    fun uploadFile(file: File, destDir: String,
                   onProgress: (Int) -> Unit, onDone: (Boolean, String) -> Unit) {
        val url = "$baseWs/ws/upload?token=$token"
        client.newWebSocket(Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, r: Response) {
                    ws.send(JSONObject().put("name",file.name).put("size",file.length()).put("dest",destDir).toString())
                    scope.launch(Dispatchers.IO) {
                        val buf=ByteArray(65536); var sent=0L; var read:Int
                        file.inputStream().use { inp ->
                            while(inp.read(buf).also{read=it}!=-1){
                                ws.send(okio.ByteString.of(*buf.copyOf(read)))
                                sent+=read; onProgress(((sent*100)/file.length()).toInt())
                            }
                        }
                        ws.send(okio.ByteString.of(*"EOF".toByteArray()))
                    }
                }
                override fun onMessage(ws: WebSocket, text: String) {
                    val j=JSONObject(text)
                    if(j.optBoolean("done")){ onDone(true,j.optString("path","")); ws.close(1000,"done") }
                    else if(j.has("error")){ onDone(false,j.optString("error","")); ws.close(1000,"err") }
                }
                override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) = onDone(false,t.message?:"فشل")
            })
    }

    fun encodeUrl(s: String): String = URLEncoder.encode(s,"UTF-8")

    fun dispose() {
        screenWs?.close(1000,"dispose");  controlWs?.close(1000,"dispose")
        audioWs?.close(1000,"dispose");   chatWs?.close(1000,"dispose")
        notifyWs?.close(1000,"dispose")
        scope.cancel(); client.dispatcher.executorService.shutdown()
    }
}
