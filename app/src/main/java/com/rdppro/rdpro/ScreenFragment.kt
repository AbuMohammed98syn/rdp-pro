package com.rdppro.rdpro

import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import kotlin.math.*

/**
 * ScreenFragment v4.0
 * + Chat overlay (دردشة نصية أثناء الجلسة)
 * + Floating cursor dot (مؤشر الماوس)
 * + Recording indicator
 * + Quality adaptive control
 */
class ScreenFragment : Fragment() {

    enum class Mode { TRACKPAD, DIRECT, GESTURE }

    private lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())

    // ── Screen views ──────────────────────────────────────────────────────────
    private var imgScreen:     ImageView?  = null
    private var trackpad:      View?       = null
    private var btnLeft:       View?       = null
    private var btnRight:      View?       = null
    private var btnDouble:     View?       = null
    private var btnScrollUp:   View?       = null
    private var btnScrollDown: View?       = null
    private var tvMode:        TextView?   = null
    private var btnTrackpad:   View?       = null
    private var btnDirect:     View?       = null
    private var btnGesture:    View?       = null

    // ── Floating cursor dot ───────────────────────────────────────────────────
    private var cursorDot:     View?       = null

    // ── Chat overlay ──────────────────────────────────────────────────────────
    private var chatPanel:     View?       = null
    private var chatInput:     EditText?   = null
    private var btnChatSend:   View?       = null
    private var btnChatToggle: View?       = null
    private var rvChat:        RecyclerView? = null
    private var chatAdapter:   ChatAdapter? = null
    private var chatVisible    = false

    // ── Recording indicator ────────────────────────────────────────────────────
    private var tvRecIndicator: TextView?  = null
    private var btnRecord:      View?      = null
    private var isRecording     = false

    // ── State ─────────────────────────────────────────────────────────────────
    private var mode        = Mode.TRACKPAD
    private var absX        = 960f;  private var absY = 540f
    private var lx          = 0f;    private var ly   = 0f
    private var sens        = 2.8f
    private var tapT        = 0L;    private var tapX = 0f; private var tapY = 0f
    private var scaleDetector: ScaleGestureDetector? = null
    private var currentScale = 1f

    // Decode on background thread
    private val decodeThread = HandlerThread("frame-decoder").also { it.start() }
    private val decodeHandler = Handler(decodeThread.looper)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_screen, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        imgScreen     = v.findViewById(R.id.imgScreen)
        trackpad      = v.findViewById(R.id.trackpad)
        btnLeft       = v.findViewById(R.id.btnLeft)
        btnRight      = v.findViewById(R.id.btnRight)
        btnDouble     = v.findViewById(R.id.btnDouble)
        btnScrollUp   = v.findViewById(R.id.btnScrollUp)
        btnScrollDown = v.findViewById(R.id.btnScrollDown)
        tvMode        = v.findViewById(R.id.tvMode)
        btnTrackpad   = v.findViewById(R.id.btnTrackpad)
        btnDirect     = v.findViewById(R.id.btnDirect)
        btnGesture    = v.findViewById(R.id.btnGesture)
        cursorDot     = v.findViewById(R.id.cursorDot)
        chatPanel     = v.findViewById(R.id.chatPanel)
        chatInput     = v.findViewById(R.id.chatInput)
        btnChatSend   = v.findViewById(R.id.btnChatSend)
        btnChatToggle = v.findViewById(R.id.btnChatToggle)
        rvChat        = v.findViewById(R.id.rvChat)
        tvRecIndicator= v.findViewById(R.id.tvRecIndicator)
        btnRecord     = v.findViewById(R.id.btnRecord)

        setupPinchZoom()
        setupModeButtons()
        setupBottomButtons()
        setupChat()
        setupRecording()
        setupFrameReceiver()

        setMode(Mode.TRACKPAD)
    }

    // ── Pinch to zoom ─────────────────────────────────────────────────────────
    private fun setupPinchZoom() {
        scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    currentScale = (currentScale * d.scaleFactor).coerceIn(0.5f, 3f)
                    imgScreen?.scaleX = currentScale
                    imgScreen?.scaleY = currentScale
                    return true
                }
            })
    }

    // ── Mode buttons ──────────────────────────────────────────────────────────
    private fun setupModeButtons() {
        btnTrackpad?.setOnClickListener { setMode(Mode.TRACKPAD) }
        btnDirect?.setOnClickListener   { setMode(Mode.DIRECT)   }
        btnGesture?.setOnClickListener  { setMode(Mode.GESTURE)  }

        imgScreen?.setOnTouchListener { view, e ->
            scaleDetector?.onTouchEvent(e)
            if (mode == Mode.DIRECT) handleDirectTouch(view, e)
            true
        }

        trackpad?.setOnTouchListener { view, e ->
            when (mode) {
                Mode.TRACKPAD -> handleTrackpadTouch(view, e)
                Mode.GESTURE  -> handleGestureTouch(view, e)
                else          -> {}
            }
            true
        }
    }

    // ── Bottom buttons ────────────────────────────────────────────────────────
    private fun setupBottomButtons() {
        btnLeft?.setOnClickListener       { rdp.mouseClick(); vib() }
        btnRight?.setOnClickListener      { rdp.mouseRight(); vib() }
        btnDouble?.setOnClickListener     { rdp.mouseDbl();   vib() }
        btnScrollUp?.setOnClickListener   { rdp.mouseScroll("up",   3) }
        btnScrollDown?.setOnClickListener { rdp.mouseScroll("down", 3) }
    }

    // ── Chat setup ────────────────────────────────────────────────────────────
    private fun setupChat() {
        chatAdapter = ChatAdapter()
        rvChat?.layoutManager = LinearLayoutManager(requireContext()).also {
            it.stackFromEnd = true
        }
        rvChat?.adapter = chatAdapter

        btnChatToggle?.setOnClickListener {
            chatVisible = !chatVisible
            chatPanel?.visibility = if (chatVisible) View.VISIBLE else View.GONE
            if (chatVisible) {
                rdp.connectChat()
                rdp.onChatMessage = { msg ->
                    ui.post {
                        val text = msg.optString("text", "")
                        val from = msg.optString("from", "pc")
                        if (text.isNotEmpty()) {
                            chatAdapter?.addMessage(ChatMessage(text, from == "mobile", System.currentTimeMillis()))
                            rvChat?.scrollToPosition((chatAdapter?.itemCount ?: 1) - 1)
                        }
                    }
                }
            } else {
                rdp.disconnectChat()
                rdp.onChatMessage = null
            }
        }

        btnChatSend?.setOnClickListener {
            val text = chatInput?.text?.toString()?.trim() ?: ""
            if (text.isNotEmpty()) {
                rdp.sendChatMessage(text)
                chatAdapter?.addMessage(ChatMessage(text, true, System.currentTimeMillis()))
                rvChat?.scrollToPosition((chatAdapter?.itemCount ?: 1) - 1)
                chatInput?.text?.clear()
            }
        }
    }

    // ── Recording setup ───────────────────────────────────────────────────────
    private fun setupRecording() {
        btnRecord?.setOnClickListener {
            isRecording = !isRecording
            if (isRecording) {
                rdp.startRecording { ok ->
                    ui.post {
                        if (!ok) Toast.makeText(context, "✗ فشل بدء التسجيل", Toast.LENGTH_SHORT).show()
                    }
                }
                tvRecIndicator?.visibility = View.VISIBLE
                tvRecIndicator?.text = "⏺ تسجيل"
                Toast.makeText(context, "بدأ التسجيل", Toast.LENGTH_SHORT).show()
            } else {
                rdp.stopRecording { path ->
                    ui.post {
                        tvRecIndicator?.visibility = View.GONE
                        Toast.makeText(context, "✓ تم الحفظ: $path", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // ── Frame receiver ────────────────────────────────────────────────────────
    private fun setupFrameReceiver() {
        rdp.onFrame = { bytes ->
            decodeHandler.post {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@post
                requireActivity().runOnUiThread {
                    imgScreen?.setImageBitmap(bmp)
                }
            }
        }
    }

    // ── Touch: Direct mode ────────────────────────────────────────────────────
    private fun handleDirectTouch(view: View, e: MotionEvent) {
        val vw = view.width.toFloat(); val vh = view.height.toFloat()
        val px = (e.x / vw * rdp.screenW).toInt().coerceIn(0, rdp.screenW)
        val py = (e.y / vh * rdp.screenH).toInt().coerceIn(0, rdp.screenH)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapT = System.currentTimeMillis(); tapX = e.x; tapY = e.y
                rdp.mouseMove(px, py)
                moveCursorDot(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> {
                rdp.mouseMove(px, py)
                moveCursorDot(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                if (System.currentTimeMillis() - tapT < 200 &&
                    hypot(e.x - tapX, e.y - tapY) < 15f) {
                    rdp.mouseClick(px, py); vib()
                }
            }
        }
    }

    // ── Touch: Trackpad mode ──────────────────────────────────────────────────
    private fun handleTrackpadTouch(view: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                tapT = System.currentTimeMillis(); tapX = e.x; tapY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lx; val dy = e.y - ly; lx = e.x; ly = e.y
                val d  = hypot(dx, dy)
                val ac = when { d > 12 -> 2.4f; d > 6 -> 1.7f; else -> 1f }
                absX = (absX + dx * sens * ac).coerceIn(0f, rdp.screenW.toFloat())
                absY = (absY + dy * sens * ac).coerceIn(0f, rdp.screenH.toFloat())
                rdp.mouseMove(absX.toInt(), absY.toInt())
                // Move cursor dot proportionally on screen preview
                imgScreen?.let { img ->
                    val dotX = (absX / rdp.screenW) * img.width
                    val dotY = (absY / rdp.screenH) * img.height
                    moveCursorDotOnScreen(dotX, dotY)
                }
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot(e.x - tapX, e.y - tapY)
                val dt    = System.currentTimeMillis() - tapT
                when {
                    dt < 180 && moved < 18 -> { rdp.mouseClick(); vib() }
                    dt in 400..1200        -> { rdp.mouseRight(); vib() }
                }
            }
        }
    }

    // ── Touch: Gesture mode ───────────────────────────────────────────────────
    private fun handleGestureTouch(view: View, e: MotionEvent) {
        val fingers = e.pointerCount
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapT = System.currentTimeMillis(); tapX = e.x; tapY = e.y
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (fingers == 1) {
                    val dx = e.x - lx; val dy = e.y - ly; lx = e.x; ly = e.y
                    absX = (absX + dx * sens).coerceIn(0f, rdp.screenW.toFloat())
                    absY = (absY + dy * sens).coerceIn(0f, rdp.screenH.toFloat())
                    rdp.mouseMove(absX.toInt(), absY.toInt())
                } else if (fingers == 2) {
                    val dy = e.getY(0) - ly
                    if (dy > 5)  { rdp.mouseScroll("down", 1); ly = e.getY(0) }
                    if (dy < -5) { rdp.mouseScroll("up",   1); ly = e.getY(0) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val dt = System.currentTimeMillis() - tapT
                val moved = hypot(e.x - tapX, e.y - tapY)
                if (dt < 200 && moved < 20) when (fingers) {
                    1 -> { rdp.mouseClick();  vib() }
                    2 -> { rdp.mouseRight();  vib() }
                    3 -> { rdp.mouseMiddle(); vib() }
                }
            }
        }
    }

    // ── Floating cursor dot ───────────────────────────────────────────────────
    private fun moveCursorDot(x: Float, y: Float) {
        cursorDot?.let {
            it.visibility = View.VISIBLE
            it.translationX = x - it.width / 2
            it.translationY = y - it.height / 2
        }
    }

    private fun moveCursorDotOnScreen(x: Float, y: Float) {
        imgScreen?.let { img ->
            cursorDot?.let { dot ->
                dot.visibility = View.VISIBLE
                val imgRect = Rect()
                img.getGlobalVisibleRect(imgRect)
                val dotRect = Rect()
                (view as? View)?.getGlobalVisibleRect(dotRect)
                dot.translationX = imgRect.left - dotRect.left + x - dot.width / 2
                dot.translationY = imgRect.top  - dotRect.top  + y - dot.height / 2
            }
        }
    }

    // ── Mode management ───────────────────────────────────────────────────────
    private fun setMode(m: Mode) {
        mode = m
        val labels = listOf("Trackpad", "مباشر", "إيماءة")
        val modes  = listOf(Mode.TRACKPAD, Mode.DIRECT, Mode.GESTURE)
        tvMode?.text = "وضع: ${labels[modes.indexOf(m)]}"
        listOf(btnTrackpad, btnDirect, btnGesture).forEachIndexed { i, v ->
            v?.isSelected = (modes[i] == m)
        }
        trackpad?.visibility = if (m != Mode.DIRECT) View.VISIBLE else View.GONE
    }

    // ── Vibration ─────────────────────────────────────────────────────────────
    private fun vib() {
        try {
            requireActivity().window.decorView.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } catch (_: Exception) {}
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rdp.onFrame       = null
        rdp.onChatMessage = null
        imgScreen = null; trackpad = null; scaleDetector = null
        chatPanel = null; chatInput = null; rvChat = null
    }

    // ─────────────────────────── Chat Data ────────────────────────────────────
    data class ChatMessage(val text: String, val isMine: Boolean, val ts: Long)

    inner class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {
        private val msgs = mutableListOf<ChatMessage>()

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvText: TextView = v.findViewById(R.id.tvChatText)
            val tvTime: TextView = v.findViewById(R.id.tvChatTime)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_chat_message, parent, false))

        override fun getItemCount() = msgs.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = msgs[pos]
            h.tvText.text = m.text
            h.tvText.setBackgroundResource(
                if (m.isMine) R.drawable.bg_chat_me else R.drawable.bg_chat_other
            )
            val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            h.tvTime.text = sdf.format(java.util.Date(m.ts))
        }

        fun addMessage(msg: ChatMessage) {
            msgs.add(msg)
            notifyItemInserted(msgs.size - 1)
        }
    }
}
