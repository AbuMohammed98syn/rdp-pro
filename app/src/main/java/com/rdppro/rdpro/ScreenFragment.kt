package com.rdppro.rdpro

import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import kotlin.math.*

/**
 * ScreenFragment v3 — بث الشاشة + التحكم الكامل
 *
 * أوضاع التحكم:
 *   TRACKPAD  — حركة نسبية مثل لوحة اللمس
 *   DIRECT    — النقر مباشر على الشاشة مع تحويل الإحداثيات
 *   GESTURE   — إيماءات: ضغطة=كليك، اثنان=يمين، ثلاثة=وسط، قرصة=تمرير
 *
 * ميزات جديدة:
 *   - Pinch to Zoom على معاينة الشاشة
 *   - Two-finger scroll
 *   - BitmapFactory على Thread منفصل (لا UI thread blocking)
 *   - onFrame = null في onDestroyView (لا memory leak)
 */
class ScreenFragment : Fragment() {

    enum class Mode { TRACKPAD, DIRECT, GESTURE }

    private lateinit var rdp: RdpService

    // Views
    private var imgScreen: ImageView? = null
    private var trackpad:  View?      = null
    private var btnLeft:   View?      = null
    private var btnRight:  View?      = null
    private var btnDouble: View?      = null
    private var btnScrollUp:   View?  = null
    private var btnScrollDown: View?  = null
    private var tvMode:    TextView?  = null
    private var btnTrackpad: View?    = null
    private var btnDirect:   View?    = null
    private var btnGesture:  View?    = null

    // State
    private var mode = Mode.TRACKPAD
    private var absX = 960f;  private var absY = 540f
    private var lx   = 0f;   private var ly   = 0f
    private var sens = 2.8f
    private var tapT = 0L;   private var tapX = 0f; private var tapY = 0f
    private var rightHeld = false

    // Pinch-to-zoom
    private var scaleDetector: ScaleGestureDetector? = null
    private var currentScale = 1f

    // Screen decoding thread
    private val decodeThread = android.os.HandlerThread("frame-decoder").also { it.start() }
    private val decodeHandler = android.os.Handler(decodeThread.looper)

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_screen, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        imgScreen      = v.findViewById(R.id.imgScreen)
        trackpad       = v.findViewById(R.id.trackpad)
        btnLeft        = v.findViewById(R.id.btnLeft)
        btnRight       = v.findViewById(R.id.btnRight)
        btnDouble      = v.findViewById(R.id.btnDouble)
        btnScrollUp    = v.findViewById(R.id.btnScrollUp)
        btnScrollDown  = v.findViewById(R.id.btnScrollDown)
        tvMode         = v.findViewById(R.id.tvMode)
        btnTrackpad    = v.findViewById(R.id.btnTrackpad)
        btnDirect      = v.findViewById(R.id.btnDirect)
        btnGesture     = v.findViewById(R.id.btnGesture)

        // Pinch to zoom (على معاينة الشاشة)
        scaleDetector = ScaleGestureDetector(requireContext(),
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    currentScale = (currentScale * d.scaleFactor).coerceIn(0.5f, 3f)
                    imgScreen?.scaleX = currentScale
                    imgScreen?.scaleY = currentScale
                    return true
                }
            })

        // Mode buttons
        btnTrackpad?.setOnClickListener { setMode(Mode.TRACKPAD) }
        btnDirect?.setOnClickListener   { setMode(Mode.DIRECT)   }
        btnGesture?.setOnClickListener  { setMode(Mode.GESTURE)  }
        setMode(Mode.TRACKPAD)

        // Bottom buttons
        btnLeft?.setOnClickListener        { rdp.mouseClick(); vib() }
        btnRight?.setOnClickListener       { rdp.mouseRight(); vib() }
        btnDouble?.setOnClickListener      { rdp.mouseDbl();   vib() }
        btnScrollUp?.setOnClickListener    { rdp.mouseScroll("up",   3) }
        btnScrollDown?.setOnClickListener  { rdp.mouseScroll("down", 3) }

        // Touch handler على الـ image (Direct mode + pinch zoom)
        imgScreen?.setOnTouchListener { view, e ->
            scaleDetector?.onTouchEvent(e)
            if (mode == Mode.DIRECT) handleDirectTouch(view, e)
            true
        }

        // Touch handler على الـ trackpad
        trackpad?.setOnTouchListener { view, e ->
            when (mode) {
                Mode.TRACKPAD -> handleTrackpadTouch(view, e)
                Mode.GESTURE  -> handleGestureTouch(view, e)
                else          -> {}
            }
            true
        }

        // إعداد استقبال الفريمات
        rdp.onFrame = { bytes ->
            decodeHandler.post {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@post
                // عرض على UI thread
                requireActivity().runOnUiThread {
                    imgScreen?.setImageBitmap(bmp)
                }
            }
        }
    }

    // ── Direct Mode ───────────────────────────────────────────────────────────
    private fun handleDirectTouch(view: View, e: MotionEvent) {
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        val px = (e.x / vw * rdp.screenW).toInt().coerceIn(0, rdp.screenW)
        val py = (e.y / vh * rdp.screenH).toInt().coerceIn(0, rdp.screenH)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapT = System.currentTimeMillis(); tapX = e.x; tapY = e.y
                rdp.mouseMove(px, py)
            }
            MotionEvent.ACTION_MOVE -> rdp.mouseMove(px, py)
            MotionEvent.ACTION_UP   -> {
                if (System.currentTimeMillis() - tapT < 200 &&
                    hypot(e.x - tapX, e.y - tapY) < 15f) {
                    rdp.mouseClick(px, py); vib()
                }
            }
        }
    }

    // ── Trackpad Mode ─────────────────────────────────────────────────────────
    private fun handleTrackpadTouch(view: View, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                tapT = System.currentTimeMillis()
                tapX = e.x; tapY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lx; val dy = e.y - ly
                lx = e.x; ly = e.y
                val d  = hypot(dx, dy)
                val ac = when { d > 12 -> 2.4f; d > 6 -> 1.7f; else -> 1f }
                absX = (absX + dx * sens * ac).coerceIn(0f, rdp.screenW.toFloat())
                absY = (absY + dy * sens * ac).coerceIn(0f, rdp.screenH.toFloat())
                rdp.mouseMove(absX.toInt(), absY.toInt())
            }
            MotionEvent.ACTION_UP -> {
                val moved = hypot(e.x - tapX, e.y - tapY)
                val dt    = System.currentTimeMillis() - tapT
                when {
                    dt < 180 && moved < 18 -> { rdp.mouseClick(); vib() }
                    dt in 400..1200        -> { rdp.mouseRight(); vib() }   // ضغط طويل = كليك يمين
                }
            }
        }
    }

    // ── Gesture Mode (multi-touch) ────────────────────────────────────────────
    private fun handleGestureTouch(view: View, e: MotionEvent) {
        val fingers = e.pointerCount
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapT = System.currentTimeMillis()
                tapX = e.x; tapY = e.y
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (fingers == 1) {
                    val dx = e.x - lx; val dy = e.y - ly
                    lx = e.x; ly = e.y
                    absX = (absX + dx * sens).coerceIn(0f, rdp.screenW.toFloat())
                    absY = (absY + dy * sens).coerceIn(0f, rdp.screenH.toFloat())
                    rdp.mouseMove(absX.toInt(), absY.toInt())
                } else if (fingers == 2) {
                    // two-finger scroll
                    val dy = e.getY(0) - ly
                    if (dy > 5)  { rdp.mouseScroll("down", 1); ly = e.getY(0) }
                    if (dy < -5) { rdp.mouseScroll("up",   1); ly = e.getY(0) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val dt    = System.currentTimeMillis() - tapT
                val moved = hypot(e.x - tapX, e.y - tapY)
                if (dt < 200 && moved < 20) {
                    when (fingers) {
                        1 -> { rdp.mouseClick();  vib() }     // نقر واحد = كليك يسار
                        2 -> { rdp.mouseRight();  vib() }     // إصبعان = كليك يمين
                        3 -> { rdp.mouseMiddle(); vib() }     // ثلاثة = كليك وسط
                    }
                }
            }
        }
    }

    private fun setMode(m: Mode) {
        mode = m
        val views   = listOf(btnTrackpad, btnDirect, btnGesture)
        val modes   = listOf(Mode.TRACKPAD, Mode.DIRECT, Mode.GESTURE)
        val labels  = listOf("Trackpad","مباشر","إيماءة")
        tvMode?.text = "وضع: ${labels[modes.indexOf(m)]}"
        views.forEachIndexed { i, v ->
            v?.isSelected = (modes[i] == m)
        }
        // إظهار/إخفاء الـ trackpad
        trackpad?.visibility = if (m != Mode.DIRECT) View.VISIBLE else View.GONE
    }

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
        rdp.onFrame  = null  // منع memory leak
        imgScreen    = null
        trackpad     = null
        scaleDetector = null
    }
}
