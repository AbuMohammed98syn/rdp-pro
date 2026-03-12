package com.rdppro.rdpro

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import java.util.*

/**
 * LiveChartView — رسم بياني خطي مباشر
 * Canvas-based بدون مكتبات خارجية
 * يدعم ألوان مختلفة وmax 60 نقطة
 */
class LiveChartView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(ctx, attrs, defStyle) {

    companion object { const val MAX_POINTS = 60 }

    private val data   = ArrayDeque<Float>()
    private var color  = 0xFF3B82F6.toInt()
    private var label  = ""
    private var maxVal = 100f
    private var unit   = "%"

    // Paints
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 2.5f; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
        strokeJoin  = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1AFFFFFF.toInt(); strokeWidth = 0.7f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 28f; isFakeBoldText = true
        typeface = Typeface.MONOSPACE
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 22f
    }

    fun setColor(c: Int)   { color = c; invalidate() }
    fun setLabel(s: String){ label = s; invalidate() }
    fun setMaxValue(v: Float){ maxVal = v; invalidate() }
    fun setUnit(s: String)  { unit = s; invalidate() }

    fun addPoint(value: Float) {
        data.addLast(value.coerceIn(0f, maxVal))
        while (data.size > MAX_POINTS) data.removeFirst()
        invalidate()
    }

    fun clear() { data.clear(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1 || h < 1 || data.isEmpty()) return

        val padL = 8f; val padR = 8f
        val padT = 20f; val padB = 24f
        val chartW = w - padL - padR
        val chartH = h - padT - padB

        // Background
        val bgPaint = Paint().apply {
            style = Paint.Style.FILL
            color = 0xFF0D1528.toInt()
        }
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bgPaint)

        // Grid lines (3 horizontal)
        for (i in 1..3) {
            val y = padT + chartH * (1f - i / 4f)
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
        }

        // Data path
        val pts   = data.toList()
        val stepX = if (pts.size > 1) chartW / (MAX_POINTS - 1) else chartW

        val linePath = Path()
        val fillPath = Path()

        pts.forEachIndexed { i, v ->
            val xOffset = (MAX_POINTS - pts.size) * stepX
            val x = padL + xOffset + i * stepX
            val y = padT + chartH * (1f - v / maxVal)
            if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, h - padB) }
            else         { linePath.lineTo(x, y) }
            fillPath.lineTo(x, y)
        }

        // Close fill path
        val lastX = padL + (MAX_POINTS - 1).toFloat() * stepX
        fillPath.lineTo(lastX.coerceAtMost(w - padR), h - padB)
        fillPath.close()

        // Draw fill (gradient)
        fillPaint.shader = LinearGradient(
            0f, padT, 0f, h - padB,
            intArrayOf(color and 0x00FFFFFF or 0x40000000 or (color and 0xFFFFFF), 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        ).also { fillPaint.color = color; fillPaint.alpha = 80 }
        // Actually just use a semi-transparent solid
        fillPaint.shader = null
        fillPaint.color  = color and 0xFFFFFF or 0x22000000
        val alphaColor = Color.argb(35,
            Color.red(color), Color.green(color), Color.blue(color))
        fillPaint.color = alphaColor
        canvas.drawPath(fillPath, fillPaint)

        // Draw line
        linePaint.color = color
        canvas.drawPath(linePath, linePaint)

        // Draw dot at last value
        if (pts.isNotEmpty()) {
            val last = pts.last()
            val xOffset = (MAX_POINTS - pts.size) * stepX
            val x = padL + xOffset + (pts.size - 1) * stepX
            val y = padT + chartH * (1f - last / maxVal)
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL; this.color = color
            }
            canvas.drawCircle(x, y, 4f, dotPaint)
            dotPaint.color = 0xFF0D1528.toInt()
            canvas.drawCircle(x, y, 2.5f, dotPaint)
        }

        // Current value text (top left)
        val current = pts.lastOrNull() ?: 0f
        val valColor = when {
            current / maxVal > 0.8f -> 0xFFEF4444.toInt()
            current / maxVal > 0.5f -> 0xFFF59E0B.toInt()
            else -> color
        }
        textPaint.color = valColor
        val valStr = if (unit == "%") "${current.toInt()}$unit" else "${"%.1f".format(current)}$unit"
        canvas.drawText(valStr, padL + 4, padT + 2, textPaint)

        // Label (top right)
        labelPaint.color = 0xFF64748B.toInt()
        val labelW = labelPaint.measureText(label)
        canvas.drawText(label, w - padR - labelW - 2, padT + 2, labelPaint)
    }
}
