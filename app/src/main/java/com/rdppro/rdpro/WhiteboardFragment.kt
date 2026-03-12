package com.rdppro.rdpro

import android.graphics.*
import android.os.*
import android.view.*
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject

/**
 * WhiteboardFragment v4.1
 * ─────────────────────────────────────────────
 * • Canvas overlay على شاشة الحاسوب
 * • أقلام: خط حر، خط مستقيم، دائرة، مستطيل، نص
 * • ألوان + سماكات متعددة
 * • مشاركة الرسم مع الحاسوب عبر WebSocket
 * • Undo / Clear
 */
class WhiteboardFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())

    // Tools
    enum class Tool { PEN, LINE, RECT, CIRCLE, ERASER }

    private var board: BoardView? = null
    private var currentTool   = Tool.PEN
    private var currentColor  = 0xFF3B82F6.toInt()
    private var currentStroke = 3f

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_whiteboard, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        board = v.findViewById(R.id.boardView)

        // Toolbar: tools
        val toolMap = mapOf(
            R.id.tbPen     to Tool.PEN,
            R.id.tbLine    to Tool.LINE,
            R.id.tbRect    to Tool.RECT,
            R.id.tbCircle  to Tool.CIRCLE,
            R.id.tbEraser  to Tool.ERASER,
        )
        toolMap.forEach { (id, tool) ->
            v.findViewById<View>(id)?.setOnClickListener {
                currentTool = tool
                board?.setTool(tool)
                toolMap.forEach { (btnId, _) ->
                    v.findViewById<View>(btnId)?.isSelected = (btnId == id)
                }
            }
        }

        // Colors
        mapOf(
            R.id.colBlue   to 0xFF3B82F6.toInt(),
            R.id.colRed    to 0xFFEF4444.toInt(),
            R.id.colGreen  to 0xFF10B981.toInt(),
            R.id.colYellow to 0xFFF59E0B.toInt(),
            R.id.colWhite  to 0xFFFFFFFF.toInt(),
            R.id.colBlack  to 0xFF000000.toInt(),
        ).forEach { (id, color) ->
            v.findViewById<View>(id)?.setOnClickListener {
                currentColor = color
                board?.setColor(color)
            }
        }

        // Stroke size
        v.findViewById<SeekBar>(R.id.seekStroke)?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                currentStroke = 1f + p * 0.5f
                board?.setStroke(currentStroke)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        // Undo / Clear / Send
        v.findViewById<View>(R.id.btnUndo)?.setOnClickListener  { board?.undo() }
        v.findViewById<View>(R.id.btnClear)?.setOnClickListener { board?.clear() }
        v.findViewById<View>(R.id.btnSendBoard)?.setOnClickListener { sendToServer() }

        // Board stroke callback → send to server
        board?.onStroke = { path -> sendStroke(path) }

        // Set defaults
        board?.setTool(Tool.PEN)
        board?.setColor(currentColor)
        board?.setStroke(currentStroke)
    }

    private fun sendStroke(data: JSONObject) {
        rdp.post("/api/whiteboard/stroke", data)
    }

    private fun sendToServer() {
        board?.let { b ->
            val snapshot = b.getSnapshot()
            rdp.post("/api/whiteboard/snapshot", JSONObject().put("data", snapshot))
            Toast.makeText(context, "✓ تم إرسال الرسم للحاسوب", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        board?.onStroke = null
        board = null
    }

    // ── BoardView ─────────────────────────────────────────────────────────────
    class BoardView(ctx: android.content.Context, attrs: android.util.AttributeSet? = null) :
        android.view.View(ctx, attrs) {

        var onStroke: ((JSONObject) -> Unit)? = null

        private val paths      = mutableListOf<Pair<Path, Paint>>()
        private val undoStack  = mutableListOf<Pair<Path, Paint>>()
        private var curPath    = Path()
        private var curPaint   = newPaint()
        private var tool       = Tool.PEN
        private var startX     = 0f
        private var startY     = 0f
        private var tempBitmap: Bitmap? = null
        private var tempCanvas: Canvas? = null
        private var bgPaint    = Paint().also { it.color = 0x00000000; it.style = Paint.Style.FILL }

        private fun newPaint() = Paint().also {
            it.style       = Paint.Style.STROKE
            it.color       = 0xFF3B82F6.toInt()
            it.strokeWidth = 3f
            it.isAntiAlias = true
            it.strokeCap   = Paint.Cap.ROUND
            it.strokeJoin  = Paint.Join.ROUND
        }

        fun setTool(t: Tool)     { tool = t }
        fun setColor(c: Int)     { curPaint = newPaint().also { it.color=c; it.strokeWidth=curPaint.strokeWidth } }
        fun setStroke(s: Float)  { curPaint = newPaint().also { it.color=curPaint.color; it.strokeWidth=s } }

        fun undo() {
            if (paths.isNotEmpty()) {
                undoStack.add(paths.removeLast())
                invalidate()
            }
        }

        fun clear() {
            paths.clear()
            undoStack.clear()
            curPath = Path()
            invalidate()
        }

        fun getSnapshot(): String {
            val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp))
            val buf = java.io.ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 90, buf)
            return android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            tempBitmap = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            tempCanvas = Canvas(tempBitmap!!)
        }

        override fun onDraw(canvas: Canvas) {
            paths.forEach { (p, paint) -> canvas.drawPath(p, paint) }
            if (tool == Tool.PEN || tool == Tool.ERASER) {
                canvas.drawPath(curPath, curPaint)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x = e.x; val y = e.y
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    curPath = Path()
                    when (tool) {
                        Tool.PEN, Tool.ERASER -> { curPath.moveTo(x, y); startX=x; startY=y }
                        else -> { startX=x; startY=y }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    when (tool) {
                        Tool.PEN, Tool.ERASER -> { curPath.lineTo(x, y); invalidate() }
                        else -> {}
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val p = Path()
                    val paint = Paint(curPaint)
                    when (tool) {
                        Tool.PEN, Tool.ERASER -> {
                            p.set(curPath)
                            if (tool == Tool.ERASER) paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                        }
                        Tool.LINE -> { p.moveTo(startX,startY); p.lineTo(x,y) }
                        Tool.RECT -> p.addRect(startX, startY, x, y, Path.Direction.CW)
                        Tool.CIRCLE -> {
                            val r = Math.hypot((x-startX).toDouble(), (y-startY).toDouble()).toFloat()
                            p.addCircle(startX, startY, r, Path.Direction.CW)
                        }
                    }
                    paths.add(p to paint)
                    curPath = Path()
                    invalidate()

                    // Notify stroke
                    onStroke?.invoke(JSONObject().apply {
                        put("tool", tool.name.lowercase())
                        put("color", String.format("#%08X", paint.color))
                        put("stroke", paint.strokeWidth)
                        put("x1", startX); put("y1", startY)
                        put("x2", x);      put("y2", y)
                        put("vw", width);  put("vh", height)
                    })
                }
            }
            return true
        }
    }
}

// Simple BytesIO helper
