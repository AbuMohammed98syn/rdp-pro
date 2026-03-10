package com.rdppro.rdpro

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import org.json.JSONObject

class KeyboardFragment : Fragment() {
    private lateinit var rdp: RdpService

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_keyboard, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp
        val etText  = v.findViewById<EditText>(R.id.etTypeText)
        val etClip  = v.findViewById<EditText>(R.id.etClipboard)
        val tvSt    = v.findViewById<TextView>(R.id.tvKbStatus)

        v.findViewById<Button>(R.id.btnSendText).setOnClickListener {
            val t=etText.text.toString(); if(t.isNotEmpty()){ rdp.keyType(t); etText.setText(""); tvSt.text="✓ تم الإرسال" }
        }

        fun key(id: Int, k: String) = v.findViewById<Button>(id).setOnClickListener { rdp.keyPress(k) }
        key(R.id.kEnter,"enter"); key(R.id.kBackspace,"backspace"); key(R.id.kDelete,"delete")
        key(R.id.kTab,"tab"); key(R.id.kEsc,"escape"); key(R.id.kSpace,"space")
        key(R.id.kUp,"up"); key(R.id.kDown,"down"); key(R.id.kLeft,"left"); key(R.id.kRight,"right")
        key(R.id.kHome,"home"); key(R.id.kEnd,"end")
        key(R.id.kF1,"f1"); key(R.id.kF2,"f2"); key(R.id.kF4,"f4")
        key(R.id.kF5,"f5"); key(R.id.kF11,"f11"); key(R.id.kF12,"f12")

        fun hk(id: Int, vararg keys: String) = v.findViewById<Button>(id).setOnClickListener { rdp.hotkey(*keys) }
        hk(R.id.hkCtrlC,"ctrl","c"); hk(R.id.hkCtrlV,"ctrl","v"); hk(R.id.hkCtrlX,"ctrl","x")
        hk(R.id.hkCtrlZ,"ctrl","z"); hk(R.id.hkCtrlA,"ctrl","a"); hk(R.id.hkCtrlS,"ctrl","s")
        hk(R.id.hkAltF4,"alt","f4"); hk(R.id.hkAltTab,"alt","tab")
        hk(R.id.hkWinD,"win","d"); hk(R.id.hkTaskMgr,"ctrl","shift","esc")

        v.findViewById<Button>(R.id.btnPushClip).setOnClickListener {
            rdp.post("/api/clipboard", JSONObject().put("text", etClip.text.toString()))
            tvSt.text="✓ دُفعت الحافظة"
        }
        v.findViewById<Button>(R.id.btnPullClip).setOnClickListener {
            rdp.get("/api/clipboard") { j -> etClip.setText(j?.optString("text","") ?: "") }
        }
    }
}
