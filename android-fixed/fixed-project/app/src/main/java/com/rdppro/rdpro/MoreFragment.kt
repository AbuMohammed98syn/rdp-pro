package com.rdppro.rdpro

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import org.json.JSONObject

/**
 * MoreFragment v3.1 — تاب "أكثر"
 * Sub-tabs: Terminal | صوت | نظام | WebRTC | إعدادات
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
            AudioSubFragment(),
            SystemSubFragment(),
            WebRtcFragment(),
            SettingsFragment(),
        )
        val labels = listOf("Terminal","صوت","نظام","WebRTC","إعدادات")

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
            rdp = (activity as MainActivity).rdp

            val tvOut  = v.findViewById<TextView>(R.id.tvTermOutput)
            val etCmd  = v.findViewById<EditText>(R.id.etCommand)
            val btnRun = v.findViewById<View>(R.id.btnRun)
            val scroll = v.findViewById<ScrollView>(R.id.termScroll)
            val quick  = v.findViewById<LinearLayout>(R.id.termQuick)

            val quickCmds = listOf(
                "ipconfig",  "netstat -an",   "dir",            "tasklist",
                "whoami",    "systeminfo",    "hostname",       "Get-Process",
                "Get-Service","Get-NetAdapter","Get-Disk",      "Get-Volume",
                "Get-HotFix","Get-LocalUser", "Get-EventLog -LogName System -Newest 5",
                "wmic cpu get name","wmic memorychip get capacity",
                "winver",    "ver",           "driverquery /fo csv | select -first 5"
            )

            quickCmds.forEach { cmd ->
                Button(requireContext()).apply {
                    text    = cmd.substringBefore(" ").take(14)
                    textSize = 8.5f
                    setPadding(12, 3, 12, 3)
                    setOnClickListener { etCmd.setText(cmd); exec(cmd, tvOut, scroll) }
                    quick.addView(this)
                }
            }

            fun run() {
                val cmd = etCmd.text?.toString()?.trim() ?: return
                if (cmd.isEmpty()) return
                etCmd.setText("")
                exec(cmd, tvOut, scroll)
            }

            btnRun?.setOnClickListener { run() }
            etCmd.setOnEditorActionListener { _, _, _ -> run(); true }
            tvOut.text = "RDP Pro Terminal v3.1\nPS> "
        }

        private fun exec(cmd: String, tv: TextView, scroll: ScrollView) {
            tv.append("\nPS> $cmd\n")
            rdp.post("/api/terminal/exec",
                JSONObject().put("command", cmd).put("timeout", 30)
            ) { j ->
                activity?.runOnUiThread {
                    val out  = j?.optString("stdout","")?.trim() ?: ""
                    val err  = j?.optString("stderr","")?.trim() ?: ""
                    val cwd  = j?.optString("cwd","") ?: ""
                    if (out.isNotEmpty()) tv.append("$out\n")
                    if (err.isNotEmpty()) tv.append("[ERR] $err\n")
                    tv.append("\nPS $cwd> ")
                    scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
    }

    // ── Audio ─────────────────────────────────────────────────────────────────
    inner class AudioSubFragment : Fragment() {
        private var audioTrack: android.media.AudioTrack? = null
        private var audioVolume = 0.8f

        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_audio, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            rdp = (activity as MainActivity).rdp

            val sw       = v.findViewById<Switch>(R.id.swPcAudio)
            val tvStatus = v.findViewById<TextView>(R.id.tvAudioStatus)
            val seekVol  = v.findViewById<SeekBar>(R.id.seekVolume)
            seekVol?.progress = 80

            seekVol?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) {
                    audioVolume = p / 100f
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })

            sw?.setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    rdp.connectAudio()
                    startPlayback()
                    tvStatus?.text = "● يبث صوت الحاسوب"
                    tvStatus?.setTextColor(0xFF10B981.toInt())
                } else {
                    rdp.disconnectAudio()
                    stopPlayback()
                    tvStatus?.text = "● متوقف"
                    tvStatus?.setTextColor(0xFF4A5568.toInt())
                }
            }
        }

        private fun startPlayback() {
            val RATE  = 44100
            val CHUNK = 4096
            val min   = android.media.AudioTrack.getMinBufferSize(
                RATE, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT)
            audioTrack = android.media.AudioTrack(
                android.media.AudioManager.STREAM_MUSIC, RATE,
                android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK * 4), android.media.AudioTrack.MODE_STREAM)
            audioTrack?.play()

            rdp.onAudioChunk = { b64, _ ->
                try {
                    val pcm = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    val out = if (audioVolume == 1f) pcm else {
                        val bb = java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        val ob = java.nio.ByteBuffer.allocate(pcm.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        while (bb.hasRemaining()) {
                            ob.putShort((bb.short * audioVolume).toInt().toShort())
                        }
                        ob.array()
                    }
                    audioTrack?.write(out, 0, out.size)
                } catch (_: Exception) {}
            }
        }

        private fun stopPlayback() {
            rdp.onAudioChunk = null
            audioTrack?.stop(); audioTrack?.release(); audioTrack = null
        }

        override fun onDestroyView() { super.onDestroyView(); stopPlayback() }
    }

    // ── System ────────────────────────────────────────────────────────────────
    inner class SystemSubFragment : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.fragment_system, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            rdp = (activity as MainActivity).rdp

            fun act(id: Int, ep: String, label: String, confirm: Boolean = true) =
                v.findViewById<View>(id)?.setOnClickListener {
                    if (confirm) {
                        androidx.appcompat.app.AlertDialog.Builder(requireContext())
                            .setTitle("تأكيد").setMessage("$label؟")
                            .setPositiveButton("تأكيد") { _, _ -> doAction(ep, label) }
                            .setNegativeButton("إلغاء", null).show()
                    } else {
                        doAction(ep, label)
                    }
                }

            act(R.id.btnLock,     "lock",         "قفل الشاشة",    false)
            act(R.id.btnTaskMgr,  "task_manager", "مدير المهام",   false)
            act(R.id.btnCmd,      "cmd",           "CMD",            false)
            act(R.id.btnSleep,    "sleep",         "السكون")
            act(R.id.btnRestart,  "restart",       "إعادة التشغيل")
            act(R.id.btnShutdown, "shutdown",      "الإيقاف")
        }

        private fun doAction(ep: String, label: String) {
            rdp.post("/api/system/$ep") { j ->
                activity?.runOnUiThread {
                    Toast.makeText(context,
                        if (j?.optBoolean("ok") == true) "✓ $label" else "✗ فشل",
                        Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun View?.setOnClickListener(block: () -> Unit) =
    this?.setOnClickListener { block() }
