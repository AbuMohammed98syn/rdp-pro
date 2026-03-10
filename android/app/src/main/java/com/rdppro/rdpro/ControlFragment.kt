package com.rdppro.rdpro

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import org.json.JSONObject
import kotlin.math.*

/**
 * ControlFragment v3 — تاب "تحكم" الموحّد
 * Sub-tabs: ماوس | كيبورد | اختصارات | حافظة
 */
class ControlFragment : Fragment() {

    private lateinit var rdp: RdpService

    // Mouse state
    private var absX = 960f; private var absY = 540f
    private var sens  = 2.8f
    private var lx    = 0f;  private var ly = 0f
    private var tapT  = 0L;  private var tapX = 0f; private var tapY = 0f

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_control, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val tabs    = v.findViewById<TabLayout>(R.id.controlTabs)
        val pager   = v.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.controlPager)

        val subFrags = listOf(
            MouseSubFragment(),
            KeyboardSubFragment(),
            ShortcutsSubFragment(),
            ClipboardSubFragment(),
        )
        pager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = subFrags.size
            override fun createFragment(pos: Int): Fragment = subFrags[pos]
        }
        pager.offscreenPageLimit = 3

        com.google.android.material.tabs.TabLayoutMediator(tabs, pager) { tab, i ->
            tab.text = listOf("ماوس","كيبورد","اختصارات","حافظة")[i]
        }.attach()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse Sub-Fragment
    // ─────────────────────────────────────────────────────────────────────────
    inner class MouseSubFragment : Fragment() {
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
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    sens = 0.5f + p * 0.1f
                    tvSens.text = "%.1f".format(sens)
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })

            pad.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lx = e.x; ly = e.y
                        tapT = System.currentTimeMillis()
                        tapX = e.x; tapY = e.y
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.x - lx; val dy = e.y - ly
                        lx = e.x; ly = e.y
                        val d = hypot(dx, dy)
                        val ac = when { d > 12 -> 2.4f; d > 6 -> 1.7f; else -> 1f }
                        absX = (absX + dx * sens * ac).coerceIn(0f, rdp.screenW.toFloat())
                        absY = (absY + dy * sens * ac).coerceIn(0f, rdp.screenH.toFloat())
                        rdp.mouseMove(absX.toInt(), absY.toInt())
                        tvPos.text = "${absX.toInt()}, ${absY.toInt()}"
                    }
                    MotionEvent.ACTION_UP -> {
                        val dt    = System.currentTimeMillis() - tapT
                        val moved = hypot(e.x - tapX, e.y - tapY)
                        when {
                            dt < 200 && moved < 18 -> {
                                rdp.mouseClick(); vib()
                                tvAction.text = "نقر يسار"
                            }
                            dt in 400..1200 -> {
                                rdp.mouseRight(); vib()
                                tvAction.text = "نقر يمين"
                            }
                        }
                    }
                }
                true
            }

            fun btn(id: Int, label: String, action: () -> Unit) =
                v.findViewById<View>(id)?.setOnClickListener {
                    action(); vib(); tvAction.text = label
                }

            btn(R.id.btnML,  "يسار")    { rdp.mouseClick() }
            btn(R.id.btnMR,  "يمين")    { rdp.mouseRight() }
            btn(R.id.btnMD,  "مزدوج")   { rdp.mouseDbl() }
            btn(R.id.btnMM,  "وسط")     { rdp.mouseMiddle() }
            btn(R.id.btnSU,  "تمرير↑") { rdp.mouseScroll("up",   5) }
            btn(R.id.btnSD,  "تمرير↓") { rdp.mouseScroll("down", 5) }

            fun sh(id: Int, vararg keys: String) =
                v.findViewById<View>(id)?.setOnClickListener { rdp.hotkey(*keys); vib() }

            sh(R.id.btnAltF4,  "alt","f4")
            sh(R.id.btnWinD,   "win","d")
            sh(R.id.btnAltTab, "alt","tab")
            sh(R.id.btnCtrlZ,  "ctrl","z")
            sh(R.id.btnWinE,   "win","e")
            sh(R.id.btnWinL,   "win","l")
        }

        private fun vib() { vibrate(requireContext()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyboard Sub-Fragment
    // ─────────────────────────────────────────────────────────────────────────
    inner class KeyboardSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_keyboard, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            rdp = (activity as MainActivity).rdp

            val etText = v.findViewById<EditText>(R.id.etTypeText)
            val tvSt   = v.findViewById<TextView>(R.id.tvKbStatus)

            v.findViewById<View>(R.id.btnSendText)?.setOnClickListener {
                val t = etText.text.toString()
                if (t.isNotEmpty()) {
                    rdp.keyType(t)
                    etText.setText("")
                    tvSt.text = "✓ تم الإرسال"
                }
            }

            fun key(id: Int, k: String) =
                v.findViewById<View>(id)?.setOnClickListener { rdp.keyPress(k); vibrate(requireContext()) }

            key(R.id.kEnter,    "enter");     key(R.id.kBackspace,"backspace")
            key(R.id.kDelete,   "delete");    key(R.id.kTab,      "tab")
            key(R.id.kEsc,      "escape");    key(R.id.kSpace,    "space")
            key(R.id.kUp,       "up");        key(R.id.kDown,     "down")
            key(R.id.kLeft,     "left");      key(R.id.kRight,    "right")
            key(R.id.kHome,     "home");      key(R.id.kEnd,      "end")
            key(R.id.kF1,       "f1");        key(R.id.kF2,       "f2")
            key(R.id.kF4,       "f4");        key(R.id.kF5,       "f5")
            key(R.id.kF11,      "f11");       key(R.id.kF12,      "f12")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shortcuts Sub-Fragment
    // ─────────────────────────────────────────────────────────────────────────
    inner class ShortcutsSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_shortcuts, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            rdp = (activity as MainActivity).rdp

            // 20 اختصار جاهز
            data class Hk(val id: Int, val label: String, val keys: Array<String>)
            val hotkeys = listOf(
                Hk(R.id.hkCtrlC,      "Ctrl+C",          arrayOf("ctrl","c")),
                Hk(R.id.hkCtrlV,      "Ctrl+V",          arrayOf("ctrl","v")),
                Hk(R.id.hkCtrlX,      "Ctrl+X",          arrayOf("ctrl","x")),
                Hk(R.id.hkCtrlZ,      "Ctrl+Z",          arrayOf("ctrl","z")),
                Hk(R.id.hkCtrlA,      "Ctrl+A",          arrayOf("ctrl","a")),
                Hk(R.id.hkCtrlS,      "Ctrl+S",          arrayOf("ctrl","s")),
                Hk(R.id.hkCtrlF,      "Ctrl+F",          arrayOf("ctrl","f")),
                Hk(R.id.hkCtrlW,      "Ctrl+W",          arrayOf("ctrl","w")),
                Hk(R.id.hkCtrlT,      "Ctrl+T",          arrayOf("ctrl","t")),
                Hk(R.id.hkCtrlN,      "Ctrl+N",          arrayOf("ctrl","n")),
                Hk(R.id.hkAltF4,      "Alt+F4",          arrayOf("alt","f4")),
                Hk(R.id.hkAltTab,     "Alt+Tab",         arrayOf("alt","tab")),
                Hk(R.id.hkWinD,       "Win+D",           arrayOf("win","d")),
                Hk(R.id.hkWinE,       "Win+E",           arrayOf("win","e")),
                Hk(R.id.hkWinL,       "Win+L",           arrayOf("win","l")),
                Hk(R.id.hkWinR,       "Win+R",           arrayOf("win","r")),
                Hk(R.id.hkTaskMgr,    "Ctrl+Shift+Esc",  arrayOf("ctrl","shift","escape")),
                Hk(R.id.hkPrintScr,   "PrintScreen",     arrayOf("printscreen")),
                Hk(R.id.hkCtrlShiftN, "Ctrl+Shift+N",   arrayOf("ctrl","shift","n")),
                Hk(R.id.hkAltPrtSc,   "Alt+PrtSc",       arrayOf("alt","printscreen")),
            )

            hotkeys.forEach { hk ->
                v.findViewById<View>(hk.id)?.setOnClickListener {
                    rdp.hotkey(*hk.keys)
                    vibrate(requireContext())
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Clipboard Sub-Fragment
    // ─────────────────────────────────────────────────────────────────────────
    inner class ClipboardSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_clipboard, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            rdp = (activity as MainActivity).rdp

            val etClip = v.findViewById<EditText>(R.id.etClipboard)
            val tvSt   = v.findViewById<TextView>(R.id.tvClipStatus)

            v.findViewById<View>(R.id.btnPushClip)?.setOnClickListener {
                val text = etClip.text.toString()
                rdp.post("/api/clipboard", JSONObject().put("text", text)) { j ->
                    activity?.runOnUiThread {
                        tvSt?.text = if (j?.optBoolean("ok") == true) "✓ دُفعت للحاسوب" else "✗ فشل"
                    }
                }
            }

            v.findViewById<View>(R.id.btnPullClip)?.setOnClickListener {
                rdp.get("/api/clipboard") { j ->
                    activity?.runOnUiThread {
                        val text = j?.optString("text","") ?: ""
                        etClip?.setText(text)
                        tvSt?.text = if (text.isNotEmpty()) "✓ تم السحب" else "الحافظة فارغة"
                    }
                }
            }

            // نسخ للحافظة المحلية للهاتف
            v.findViewById<View>(R.id.btnCopyLocal)?.setOnClickListener {
                val text = etClip?.text?.toString() ?: ""
                val clip = requireActivity().getSystemService(Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                clip.setPrimaryClip(android.content.ClipData.newPlainText("rdp", text))
                tvSt?.text = "✓ نُسخ للهاتف"
            }
        }
    }

    companion object {
        fun vibrate(ctx: Context) {
            try {
                val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26)
                    v.vibrate(VibrationEffect.createOneShot(18, 80))
                else
                    @Suppress("DEPRECATION") v.vibrate(18)
            } catch (_: Exception) {}
        }
    }
}

private fun View?.setOnClickListener(block: () -> Unit) =
    this?.setOnClickListener { block() }
