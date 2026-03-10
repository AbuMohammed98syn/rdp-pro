package com.rdppro.rdpro

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebRtcFragment v3.1
 * بث فيديو WebRTC بين الهاتف والحاسوب
 *
 * يعمل كـ Signaling client — يرسل/يستقبل offer/answer/ice عبر WebSocket
 * ثم يتولى WebRTC الاتصال المباشر P2P
 *
 * ملاحظة: الـ WebRTC الحقيقي يحتاج
 *   implementation 'org.webrtc:google-webrtc:1.0.32006'
 * هذا الملف يُجهّز البنية الكاملة للاستخدام معه.
 */
class WebRtcFragment : Fragment() {

    private lateinit var rdp: RdpService
    private var signalingWs: WebSocket? = null
    private var myId: String = ""
    private var peerId: String = ""
    private var inCall = false

    private val wsClient = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // Views
    private var tvStatus:   TextView? = null
    private var tvLog:      TextView? = null
    private var btnCall:    Button?   = null
    private var btnHangup:  Button?   = null
    private var btnMute:    Button?   = null
    private var etRoomId:   EditText? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_webrtc, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        tvStatus  = v.findViewById(R.id.tvRtcStatus)
        tvLog     = v.findViewById(R.id.tvRtcLog)
        btnCall   = v.findViewById(R.id.btnCall)
        btnHangup = v.findViewById(R.id.btnHangup)
        btnMute   = v.findViewById(R.id.btnMute)
        etRoomId  = v.findViewById(R.id.etRoomId)

        btnCall?.setOnClickListener   { connectSignaling() }
        btnHangup?.setOnClickListener { hangup() }
        btnMute?.setOnClickListener   { toggleMute() }

        appendLog("جاهز — أدخل Room ID واضغط اتصال")
    }

    private fun connectSignaling() {
        val room = etRoomId?.text?.toString()?.trim()?.ifEmpty { "rdp-default" } ?: "rdp-default"
        appendLog("⟳ جاري الاتصال بـ room: $room")

        val req = Request.Builder()
            .url("ws://${rdp.host}:${rdp.port}/ws/rtc/$room?token=${rdp.token}")
            .build()

        signalingWs = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, r: Response) {
                ui("● متصل بالـ signaling server")
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleSignaling(JSONObject(text))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                ui("✗ فشل الاتصال: ${t.message}")
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                ui("● انقطع الاتصال")
                inCall = false
            }
        })
    }

    private fun handleSignaling(msg: JSONObject) {
        when (msg.optString("type")) {
            "your_id" -> {
                myId = msg.optString("id")
                val peers = msg.optJSONArray("peers")
                ui("🔑 معرّفك: $myId")
                if (peers != null && peers.length() > 0) {
                    peerId = peers.getString(0)
                    ui("👥 Peer متاح: $peerId")
                    sendOffer()
                } else {
                    ui("⏳ في انتظار peer آخر...")
                }
            }
            "peer_joined" -> {
                peerId = msg.optString("id")
                ui("✅ Peer انضم: $peerId")
                sendOffer()
            }
            "peer_left" -> {
                ui("⚠️ Peer غادر: ${msg.optString("id")}")
                if (inCall) hangup()
            }
            "offer" -> {
                peerId = msg.optString("from")
                ui("📞 Offer مستلم من $peerId")
                handleOffer(msg.optJSONObject("sdp"))
            }
            "answer" -> {
                ui("✅ Answer مستلم")
                handleAnswer(msg.optJSONObject("sdp"))
            }
            "ice" -> {
                handleIce(msg.optJSONObject("candidate"))
            }
        }
    }

    /** يُرسل SDP offer للـ peer */
    private fun sendOffer() {
        if (peerId.isEmpty()) return
        ui("📤 إرسال offer لـ $peerId")
        inCall = true
        // في التطبيق الحقيقي: peerConnection.createOffer() ثم setLocalDescription
        // هنا نحاكي البنية
        val fakeOffer = JSONObject().apply {
            put("type", "offer")
            put("to", peerId)
            put("sdp", JSONObject().apply {
                put("type", "offer")
                put("sdp", "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\n...")  // placeholder
            })
        }
        signalingWs?.send(fakeOffer.toString())
        activity?.runOnUiThread {
            tvStatus?.text  = "● في مكالمة مع $peerId"
            tvStatus?.setTextColor(0xFF10B981.toInt())
            btnHangup?.isEnabled = true
            btnCall?.isEnabled   = false
        }
    }

    private fun handleOffer(sdp: JSONObject?) {
        // setRemoteDescription(offer) → createAnswer → setLocalDescription → sendAnswer
        val answer = JSONObject().apply {
            put("type", "answer")
            put("to", peerId)
            put("sdp", JSONObject().apply {
                put("type", "answer")
                put("sdp", "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\n...")
            })
        }
        signalingWs?.send(answer.toString())
        inCall = true
        activity?.runOnUiThread {
            tvStatus?.text = "● في مكالمة مع $peerId"
            tvStatus?.setTextColor(0xFF10B981.toInt())
        }
    }

    private fun handleAnswer(sdp: JSONObject?) {
        // setRemoteDescription(answer)
        ui("✅ الاتصال مكتمل P2P")
    }

    private fun handleIce(candidate: JSONObject?) {
        // addIceCandidate(candidate)
    }

    private fun sendIce(candidate: JSONObject) {
        signalingWs?.send(JSONObject().apply {
            put("type", "ice")
            put("to", peerId)
            put("candidate", candidate)
        }.toString())
    }

    private fun hangup() {
        inCall  = false
        peerId  = ""
        signalingWs?.close(1000, "hangup")
        signalingWs = null
        activity?.runOnUiThread {
            tvStatus?.text = "● منفصل"
            tvStatus?.setTextColor(0xFF4A5568.toInt())
            btnCall?.isEnabled   = true
            btnHangup?.isEnabled = false
        }
        appendLog("📵 انتهت المكالمة")
    }

    private var muted = false
    private fun toggleMute() {
        muted = !muted
        btnMute?.text = if (muted) "🔇 كتم" else "🎤 صوت"
        appendLog(if (muted) "🔇 الميكروفون مكتوم" else "🎤 الميكروفون مفعّل")
    }

    private fun ui(msg: String) {
        activity?.runOnUiThread { appendLog(msg) }
    }

    private fun appendLog(msg: String) {
        tvLog?.append("$msg\n")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        signalingWs?.close(1000, "destroy")
        signalingWs = null
        tvStatus = null; tvLog = null
    }
}
