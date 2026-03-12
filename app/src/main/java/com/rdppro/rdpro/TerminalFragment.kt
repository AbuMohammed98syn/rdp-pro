package com.rdppro.rdpro

import android.os.*
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment

/**
 * TerminalFragment v4.0
 * PowerShell / CMD with session CWD + categorized command buttons
 */
class TerminalFragment : Fragment() {

    var rdp: RdpService? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_terminal, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        if (rdp == null) rdp = (activity as? MainActivity)?.rdp ?: return
        setupTerminal(v)
    }

    fun setupTerminal(v: View) {
        val r = rdp ?: return
        val etCmd   = v.findViewById<EditText>(R.id.etCmd) ?: return
        val tvOut   = v.findViewById<TextView>(R.id.tvOutput) ?: return
        val tvCwd   = v.findViewById<TextView>(R.id.tvCwd) ?: return
        val btnSend = v.findViewById<Button>(R.id.btnSendCmd) ?: return
        val btnClear= v.findViewById<Button>(R.id.btnClearOutput) ?: return
        val sv      = v.findViewById<ScrollView>(R.id.svOutput)

        val output = StringBuilder()

        fun exec(cmd: String) {
            if (cmd.isBlank()) return
            output.appendLine("▶ $cmd")
            tvOut.text = output
            r.post("/api/terminal/exec", org.json.JSONObject().put("cmd", cmd)) { j ->
                ui.post {
                    val out = j?.optString("output", "") ?: ""
                    val err = j?.optString("error", "") ?: ""
                    val cwd = j?.optString("cwd", "") ?: ""
                    if (out.isNotEmpty()) output.appendLine(out.trimEnd())
                    if (err.isNotEmpty()) output.appendLine("✗ $err".trimEnd())
                    tvOut.text = output
                    if (cwd.isNotEmpty()) tvCwd.text = "📂 $cwd"
                    sv?.post { sv.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }

        btnSend.setOnClickListener {
            val cmd = etCmd.text.toString().trim()
            exec(cmd)
            etCmd.text.clear()
        }

        btnClear.setOnClickListener {
            output.clear()
            tvOut.text = ""
        }

        // Categorized quick commands
        data class Btn(val id: Int, val cmd: String)
        listOf(
            // Networking
            Btn(R.id.tcmdIpconfig,  "ipconfig"),
            Btn(R.id.tcmdPing,      "ping -n 3 8.8.8.8"),
            Btn(R.id.tcmdNetstat,   "netstat -an | head -20"),
            Btn(R.id.tcmdTracert,   "tracert -h 8 8.8.8.8"),
            // System
            Btn(R.id.tcmdSysteminfo,"systeminfo | head -20"),
            Btn(R.id.tcmdTasklist,  "tasklist | head -20"),
            Btn(R.id.tcmdDf,        "df -h"),
            Btn(R.id.tcmdWhoami,    "whoami"),
            // Files
            Btn(R.id.tcmdDir,       "dir"),
            Btn(R.id.tcmdLs,        "ls -la"),
            Btn(R.id.tcmdPwd,       "pwd"),
            // Advanced
            Btn(R.id.tcmdGpupdate,  "gpupdate /force"),
            Btn(R.id.tcmdSfc,       "sfc /scannow"),
            Btn(R.id.tcmdDiskclean, "cleanmgr /sagerun:1"),
        ).forEach { btn ->
            v.findViewById<View>(btn.id)?.setOnClickListener { exec(btn.cmd) }
        }
    }
}
