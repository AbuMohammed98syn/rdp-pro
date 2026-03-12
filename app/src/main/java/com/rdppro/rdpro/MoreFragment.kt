package com.rdppro.rdpro

import android.content.*
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject

/**
 * MoreFragment v5.0 — 11 tabs
 * Terminal | Clipboard | WoL | Whiteboard | Security | Macro | FileSync | صوت | WebRTC | إعدادات | BG
 */
class MoreFragment : Fragment() {

    private lateinit var rdp: RdpService

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_more, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val tabs  = v.findViewById<TabLayout>(R.id.moreTabs)
        val pager = v.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.morePager)

        val frags = listOf(
            TerminalSubFragment(),
            ClipboardSubFragment(),
            WolSubFragment(),
            WhiteboardFragment(),
            SecurityFragment(),
            MacroFragment(),
            FileSyncFragment(),
            AudioSubFragment(),
            WebRtcFragment(),
            SettingsFragment(),
            BackgroundSubFragment(),
        )
        val labels = listOf(
            "Terminal","حافظة","WoL",
            "✏ Whiteboard","🔐 أمان","⚙ Macro","🔄 Sync",
            "🎵 صوت","📹 WebRTC","⚙ إعدادات","🔕 BG"
        )

        pager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = frags.size
            override fun createFragment(pos: Int): Fragment = frags[pos]
        }
        pager.offscreenPageLimit = 2
        TabLayoutMediator(tabs, pager) { tab, i -> tab.text = labels[i] }.attach()
    }

    // ── Terminal ──────────────────────────────────────────────────────────────
    inner class TerminalSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_terminal, c, false)
        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            TerminalFragment().also { it.rdp = rdp }.setupTerminal(v)
        }
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────
    inner class ClipboardSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_clipboard, c, false)
        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val etClip   = v.findViewById<EditText>(R.id.etClipboard)
            val tvStatus = v.findViewById<TextView>(R.id.tvClipStatus)
            val btnPush  = v.findViewById<android.widget.Button>(R.id.btnPushClip)
            val btnPull  = v.findViewById<android.widget.Button>(R.id.btnPullClip)
            val btnCopy  = v.findViewById<android.widget.Button>(R.id.btnCopyLocal)
            rdp.get("/api/clipboard") { j -> activity?.runOnUiThread { etClip.setText(j?.optString("text","")) } }
            btnPull.setOnClickListener {
                rdp.get("/api/clipboard") { j -> activity?.runOnUiThread { etClip.setText(j?.optString("text","")); tvStatus.text="✓ سُحب" } }
            }
            btnPush.setOnClickListener {
                rdp.post("/api/clipboard", JSONObject().put("text", etClip.text.toString())) { j ->
                    activity?.runOnUiThread { tvStatus.text = if(j?.optBoolean("ok")==true) "✓ دُفع" else "✗ فشل" }
                }
            }
            btnCopy.setOnClickListener {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("clip", etClip.text))
                tvStatus.text="✓ نُسخ للهاتف"
            }
        }
    }

    // ── WoL ───────────────────────────────────────────────────────────────────
    inner class WolSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_wol, c, false)
        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val etMac   = v.findViewById<EditText>(R.id.etWolMac)
            val etBcast = v.findViewById<EditText>(R.id.etWolBroadcast)
            val btnWake = v.findViewById<android.widget.Button>(R.id.btnWolWake)
            val tvRes   = v.findViewById<TextView>(R.id.tvWolResult)
            val prefs   = requireActivity().getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)
            etMac.setText(prefs.getString("mac",""))
            etBcast.setText(prefs.getString("wol_broadcast","255.255.255.255"))
            btnWake.setOnClickListener {
                val mac=etMac.text.toString().trim(); val bc=etBcast.text.toString().trim().ifEmpty{"255.255.255.255"}
                if(mac.isEmpty()){tvRes.text="أدخل MAC";return@setOnClickListener}
                btnWake.isEnabled=false; tvRes.text="⟳ إرسال..."
                prefs.edit().putString("mac",mac).putString("wol_broadcast",bc).apply()
                rdp.sendWakeOnLan(mac,bc){ok,msg->activity?.runOnUiThread{btnWake.isEnabled=true;tvRes.text=msg;tvRes.setTextColor(if(ok)0xFF10B981.toInt() else 0xFFEF4444.toInt())}}
            }
        }
    }

    // ── Audio ─────────────────────────────────────────────────────────────────
    inner class AudioSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_audio, c, false)
        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val btnStart=v.findViewById<android.widget.Button>(R.id.btnAudioStart)
            val btnStop =v.findViewById<android.widget.Button>(R.id.btnAudioStop)
            val tvViz   =v.findViewById<TextView>(R.id.tvAudioViz)
            btnStart.setOnClickListener{rdp.connectAudio();rdp.onAudioChunk={_,_->activity?.runOnUiThread{tvViz.text="🎵 بث مباشر..."}};btnStart.isEnabled=false;btnStop.isEnabled=true}
            btnStop.setOnClickListener{rdp.disconnectAudio();rdp.onAudioChunk=null;tvViz.text="—";btnStart.isEnabled=true;btnStop.isEnabled=false}
        }
    }

    // ── Background Service toggle ─────────────────────────────────────────────
    inner class BackgroundSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_background_service, c, false)
        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val btnStart = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBgStart)
            val btnStop  = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnBgStop)
            val tvBgInfo = v.findViewById<TextView>(R.id.tvBgInfo)
            val swBgBoot = v.findViewById<Switch>(R.id.swBgStartOnBoot)
            val prefs    = requireActivity().getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)

            swBgBoot.isChecked = prefs.getBoolean("bg_start_on_boot", false)
            swBgBoot.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("bg_start_on_boot",c).apply() }

            btnStart.setOnClickListener {
                val host  = prefs.getString("ip","") ?: ""
                val port  = prefs.getString("port","8000")?.toIntOrNull() ?: 8000
                val token = prefs.getString("token","") ?: ""
                val https = prefs.getBoolean("https",false)
                if (host.isEmpty()) { tvBgInfo.text = "✗ لا يوجد اتصال محفوظ"; return@setOnClickListener }
                RdpBackgroundService.start(requireContext(), host, port, token, https)
                tvBgInfo.text = "● الخدمة تعمل في الخلفية\n$host:$port"
                tvBgInfo.setTextColor(0xFF10B981.toInt())
                btnStart.isEnabled = false; btnStop.isEnabled = true
            }
            btnStop.setOnClickListener {
                RdpBackgroundService.stop(requireContext())
                tvBgInfo.text = "⏹ الخدمة متوقفة"
                tvBgInfo.setTextColor(0xFF4A5568.toInt())
                btnStart.isEnabled = true; btnStop.isEnabled = false
            }
        }
    }
}
