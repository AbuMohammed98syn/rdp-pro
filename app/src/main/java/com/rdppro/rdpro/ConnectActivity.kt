package com.rdppro.rdpro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ConnectActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("rdp", Context.MODE_PRIVATE) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_connect)

        val etIp    = findViewById<EditText>(R.id.etIp)
        val etPort  = findViewById<EditText>(R.id.etPort)
        val btnConn = findViewById<Button>(R.id.btnConnect)
        val tvErr   = findViewById<TextView>(R.id.tvError)
        val lvHist  = findViewById<ListView>(R.id.lvHistory)

        etIp.setText(prefs.getString("ip", ""))
        etPort.setText(prefs.getString("port", "8000"))

        val hist = loadHistory()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, hist.toMutableList())
        lvHist.adapter = adapter
        lvHist.setOnItemClickListener { _, _, i, _ ->
            val p = adapter.getItem(i)?.split(":") ?: return@setOnItemClickListener
            etIp.setText(p[0])
            if (p.size > 1) etPort.setText(p[1])
        }

        btnConn.setOnClickListener {
            val ip   = etIp.text.toString().trim()
            val port = etPort.text.toString().trim().ifEmpty { "8000" }
            if (ip.isEmpty()) { tvErr.visibility = View.VISIBLE; tvErr.text = "ادخل IP الحاسوب"; return@setOnClickListener }
            tvErr.visibility = View.GONE
            prefs.edit().putString("ip", ip).putString("port", port).apply()
            saveHistory(ip, port)
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra("host", ip).putExtra("port", port))
        }
    }

    private fun loadHistory(): List<String> {
        val n = prefs.getInt("hist_n", 0)
        return (0 until n).mapNotNull { prefs.getString("hist_$it", null) }
    }

    private fun saveHistory(ip: String, port: String) {
        val key = "$ip:$port"
        val h = loadHistory().toMutableList()
        h.remove(key); h.add(0, key)
        if (h.size > 6) h.removeLast()
        prefs.edit().apply {
            putInt("hist_n", h.size)
            h.forEachIndexed { i, v -> putString("hist_$i", v) }
            apply()
        }
    }
}
