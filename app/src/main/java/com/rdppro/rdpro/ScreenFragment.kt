package com.rdppro.rdpro

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import kotlin.math.*

class ScreenFragment : Fragment() {
    private lateinit var rdp: RdpService
    private lateinit var img: ImageView
    private var mode = 0 // 0=trackpad 1=direct 2=gesture
    private var absX = 960f; private var absY = 540f; private var sens = 2.8f
    private var lx = 0f; private var ly = 0f
    private var tapT = 0L; private var tapX = 0f; private var tapY = 0f

    override fun onCreateView(inf: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        inf.inflate(R.layout.fragment_screen, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp
        img = v.findViewById(R.id.imgScreen)

        rdp.onFrame = { bytes ->
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            img.setImageBitmap(bmp)
        }

        val btnTP = v.findViewById<Button>(R.id.btnTrackpad)
        val btnDR = v.findViewById<Button>(R.id.btnDirect)
        val btnGS = v.findViewById<Button>(R.id.btnGesture)
        val pad   = v.findViewById<View>(R.id.trackpad)

        fun setMode(m: Int) {
            mode = m
            val on = 0xFF1E3A8A.toInt(); val off = 0xFF0D1322.toInt()
            btnTP.setBackgroundColor(if(m==0) on else off)
            btnDR.setBackgroundColor(if(m==1) on else off)
            btnGS.setBackgroundColor(if(m==2) on else off)
            pad.visibility = if(m==0) View.VISIBLE else View.GONE
        }
        btnTP.setOnClickListener { setMode(0) }
        btnDR.setOnClickListener { setMode(1) }
        btnGS.setOnClickListener { setMode(2) }
        setMode(0)

        // Trackpad touch
        pad.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx=e.x; ly=e.y; tapT=System.currentTimeMillis(); tapX=e.x; tapY=e.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx=e.x-lx; val dy=e.y-ly; lx=e.x; ly=e.y
                    val d=sqrt(dx*dx+dy*dy); val ac=if(d>12)2.4f else if(d>6)1.7f else 1f
                    absX=(absX+dx*sens*ac).coerceIn(0f,rdp.screenW.toFloat())
                    absY=(absY+dy*sens*ac).coerceIn(0f,rdp.screenH.toFloat())
                    rdp.mouseMove(absX.toInt(), absY.toInt())
                }
                MotionEvent.ACTION_UP -> {
                    if(System.currentTimeMillis()-tapT<230 && sqrt((e.x-tapX).pow(2)+(e.y-tapY).pow(2))<20) {
                        rdp.mouseClick(); vib()
                    }
                }
            }; true
        }

        // Screen touch for direct/gesture
        img.setOnTouchListener { vw, e ->
            when (mode) {
                1 -> { // direct
                    val sw=rdp.screenW.toFloat(); val sh=rdp.screenH.toFloat()
                    val sc=min(vw.width/sw, vw.height/sh)
                    val ox=(vw.width-sw*sc)/2; val oy=(vw.height-sh*sc)/2
                    val px=((e.x-ox)/(sw*sc)*sw).coerceIn(0f,sw-1)
                    val py=((e.y-oy)/(sh*sc)*sh).coerceIn(0f,sh-1)
                    rdp.mouseMove(px.toInt(), py.toInt())
                    if(e.actionMasked==MotionEvent.ACTION_UP){ rdp.mouseClick(); vib() }
                    true
                }
                2 -> { // gesture
                    when(e.actionMasked){
                        MotionEvent.ACTION_DOWN -> { tapT=System.currentTimeMillis(); tapX=e.x; tapY=e.y }
                        MotionEvent.ACTION_MOVE -> {
                            absX=(absX+(e.x-tapX)*2.5f).coerceIn(0f,rdp.screenW.toFloat())
                            absY=(absY+(e.y-tapY)*2.5f).coerceIn(0f,rdp.screenH.toFloat())
                            rdp.mouseMove(absX.toInt(), absY.toInt())
                            tapX=e.x; tapY=e.y
                        }
                        MotionEvent.ACTION_UP -> if(System.currentTimeMillis()-tapT<200) { rdp.mouseClick(); vib() }
                    }; true
                }
                else -> false
            }
        }

        // Bottom buttons
        v.findViewById<Button>(R.id.btnLeft).setOnClickListener   { rdp.mouseClick(); vib() }
        v.findViewById<Button>(R.id.btnDouble).setOnClickListener { rdp.mouseDbl(); vib() }
        v.findViewById<Button>(R.id.btnRight).setOnClickListener  { rdp.mouseRight(); vib() }
        v.findViewById<Button>(R.id.btnScrollUp).setOnClickListener   { rdp.mouseScroll("up") }
        v.findViewById<Button>(R.id.btnScrollDown).setOnClickListener { rdp.mouseScroll("down") }
    }

    private fun vib() {
        try {
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(20, 80))
            else @Suppress("DEPRECATION") v.vibrate(20)
        } catch (_: Exception) {}
    }
}
