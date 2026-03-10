package com.rdppro.rdpro

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import kotlin.math.*

class MouseFragment : Fragment() {
    private lateinit var rdp: RdpService
    private var absX=960f; private var absY=540f; private var sens=2.8f
    private var lx=0f; private var ly=0f; private var tapT=0L; private var tapX=0f; private var tapY=0f

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_mouse, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val tvPos    = v.findViewById<TextView>(R.id.tvPos)
        val tvAction = v.findViewById<TextView>(R.id.tvAction)
        val seek     = v.findViewById<SeekBar>(R.id.seekSens)
        val tvSens   = v.findViewById<TextView>(R.id.tvSens)
        val pad      = v.findViewById<View>(R.id.touchPad)

        seek.progress = 23
        seek.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                sens = 0.5f + p*0.1f; tvSens.text="%.1f".format(sens)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })

        pad.setOnTouchListener { _, e ->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN -> { lx=e.x; ly=e.y; tapT=System.currentTimeMillis(); tapX=e.x; tapY=e.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx=e.x-lx; val dy=e.y-ly; lx=e.x; ly=e.y
                    val d=sqrt(dx*dx+dy*dy); val ac=if(d>12)2.4f else if(d>6)1.7f else 1f
                    absX=(absX+dx*sens*ac).coerceIn(0f,rdp.screenW.toFloat())
                    absY=(absY+dy*sens*ac).coerceIn(0f,rdp.screenH.toFloat())
                    rdp.mouseMove(absX.toInt(), absY.toInt())
                    tvPos.text="${absX.toInt()}, ${absY.toInt()}"
                }
                MotionEvent.ACTION_UP -> {
                    if(System.currentTimeMillis()-tapT<230 && sqrt((e.x-tapX).pow(2)+(e.y-tapY).pow(2))<20){
                        rdp.mouseClick(); vib(); tvAction.text="نقر"
                    }
                }
            }; true
        }

        fun btn(id: Int, label: String, action: ()->Unit) =
            v.findViewById<Button>(id).setOnClickListener { action(); tvAction.text=label; vib() }

        btn(R.id.btnML,"يسار")  { rdp.mouseClick() }
        btn(R.id.btnMR,"يمين")  { rdp.mouseRight() }
        btn(R.id.btnMD,"مزدوج") { rdp.mouseDbl() }
        btn(R.id.btnMM,"وسط")   { rdp.mouseMiddle() }
        btn(R.id.btnSU,"↑ x5")  { rdp.mouseScroll("up",5) }
        btn(R.id.btnSD,"↓ x5")  { rdp.mouseScroll("down",5) }

        fun sh(id: Int, vararg keys: String) =
            v.findViewById<Button>(id).setOnClickListener { rdp.hotkey(*keys) }

        sh(R.id.btnAltF4,  "alt","f4")
        sh(R.id.btnWinD,   "win","d")
        sh(R.id.btnAltTab, "alt","tab")
        sh(R.id.btnCtrlZ,  "ctrl","z")
        sh(R.id.btnWinE,   "win","e")
        sh(R.id.btnWinL,   "win","l")
    }

    private fun vib() {
        try {
            val v = requireContext().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if(Build.VERSION.SDK_INT>=26) v.vibrate(VibrationEffect.createOneShot(18,80))
            else @Suppress("DEPRECATION") v.vibrate(18)
        } catch(_:Exception){}
    }
}
