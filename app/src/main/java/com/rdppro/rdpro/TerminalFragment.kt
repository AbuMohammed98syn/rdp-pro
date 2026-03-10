package com.rdppro.rdpro

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import org.json.JSONObject

class TerminalFragment : Fragment() {
    private lateinit var rdp: RdpService

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_terminal, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val tvOut  = v.findViewById<TextView>(R.id.tvTermOutput)
        val etCmd  = v.findViewById<EditText>(R.id.etCommand)
        val btnRun = v.findViewById<Button>(R.id.btnRun)
        val scroll = v.findViewById<ScrollView>(R.id.termScroll)
        val quick  = v.findViewById<LinearLayout>(R.id.termQuick)

        listOf("ipconfig","tasklist","dir","whoami","systeminfo","ver","hostname","netstat -an").forEach { cmd ->
            Button(requireContext()).apply {
                text = cmd; textSize = 9f; setPadding(14,4,14,4)
                setOnClickListener { etCmd.setText(cmd); exec(cmd, tvOut, scroll) }
                quick.addView(this)
            }
        }

        fun run() { val cmd=etCmd.text.toString().trim(); if(cmd.isNotEmpty()){ etCmd.setText(""); exec(cmd,tvOut,scroll) } }
        btnRun.setOnClickListener { run() }
        etCmd.setOnEditorActionListener { _,_,_ -> run(); true }
    }

    private fun exec(cmd: String, tv: TextView, scroll: ScrollView) {
        tv.append("\n\$ $cmd\n")
        rdp.post("/api/terminal/exec", JSONObject().put("command",cmd).put("timeout",60)) { j ->
            val out = j?.optString("stdout","")?.trim() ?: ""
            val err = j?.optString("stderr","")?.trim() ?: ""
            val cwd = j?.optString("cwd","") ?: ""
            if(out.isNotEmpty()) tv.append("$out\n")
            if(err.isNotEmpty()) tv.append("ERR: $err\n")
            if(cwd.isNotEmpty()) tv.append("[$cwd]\n")
            if(j==null) tv.append("فشل الاتصال\n")
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }
}
