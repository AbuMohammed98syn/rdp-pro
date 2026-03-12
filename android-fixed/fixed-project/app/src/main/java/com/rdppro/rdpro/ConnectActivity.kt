package com.rdppro.rdpro

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.zxing.integration.android.IntentIntegrator

/**
 * شاشة الاتصال v3
 * - حقول IP + Port + Token
 * - تاريخ الاتصالات مع آخر حالة
 * - مسح QR Code
 * - فحص الاتصال قبل الدخول
 */
class ConnectActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("rdp_v3", Context.MODE_PRIVATE) }

    // Views
    private lateinit var etIp:     TextInputEditText
    private lateinit var etPort:   TextInputEditText
    private lateinit var etToken:  TextInputEditText
    private lateinit var btnConn:  MaterialButton
    private lateinit var btnQr:    MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var rvHist:   RecyclerView
    private lateinit var histAdapter: HistoryAdapter

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_connect)

        etIp    = findViewById(R.id.etIp)
        etPort  = findViewById(R.id.etPort)
        etToken = findViewById(R.id.etToken)
        btnConn = findViewById(R.id.btnConnect)
        btnQr   = findViewById(R.id.btnQr)
        tvStatus= findViewById(R.id.tvStatus)
        rvHist  = findViewById(R.id.rvHistory)

        // استعادة آخر قيم
        etIp.setText(prefs.getString("ip", ""))
        etPort.setText(prefs.getString("port", "8000"))
        etToken.setText(prefs.getString("token", "rdppro-secret-2024"))

        // History RecyclerView
        histAdapter = HistoryAdapter(loadHistory()) { item ->
            val parts = item.addr.split(":")
            etIp.setText(parts.getOrElse(0) { "" })
            etPort.setText(parts.getOrElse(1) { "8000" })
        }
        rvHist.layoutManager = LinearLayoutManager(this)
        rvHist.adapter       = histAdapter

        btnConn.setOnClickListener { doConnect() }

        btnQr.setOnClickListener {
            IntentIntegrator(this)
                .setPrompt("امسح QR Code الخاص بالخادم")
                .setBeepEnabled(true)
                .setOrientationLocked(true)
                .initiateScan()
        }
    }

    private fun doConnect() {
        val ip    = etIp.text?.toString()?.trim() ?: ""
        val port  = etPort.text?.toString()?.trim()?.ifEmpty { "8000" } ?: "8000"
        val token = etToken.text?.toString()?.trim() ?: ""

        if (ip.isEmpty()) {
            showStatus("أدخل عنوان IP الحاسوب", isError = true)
            return
        }

        btnConn.isEnabled = false
        showStatus("⟳ جاري الاتصال...", isError = false)

        // فحص سريع قبل الانتقال
        val rdp = RdpService(ip, port.toIntOrNull() ?: 8000, token)
        rdp.get("/api/ping") { j ->
            runOnUiThread {
                btnConn.isEnabled = true
                if (j?.optBoolean("pong") == true) {
                    savePrefs(ip, port, token)
                    saveHistory(ip, port)
                    rdp.dispose()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra("host",  ip)
                            .putExtra("port",  port)
                            .putExtra("token", token)
                    )
                } else {
                    showStatus("✗ تعذّر الاتصال — تأكد من IP والـ Token", isError = true)
                    rdp.dispose()
                }
            }
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(req, res, data)
        if (result?.contents != null) {
            // صيغة QR المتوقعة: rdppro://192.168.1.x:8000/token
            val raw = result.contents
            parseQrCode(raw)
        } else {
            super.onActivityResult(req, res, data)
        }
    }

    private fun parseQrCode(raw: String) {
        try {
            val noScheme = raw.removePrefix("rdppro://")
            val parts    = noScheme.split("/")
            val hostPort = parts[0].split(":")
            etIp.setText(hostPort[0])
            if (hostPort.size > 1) etPort.setText(hostPort[1])
            if (parts.size > 1)    etToken.setText(parts[1])
            showStatus("✓ تم قراءة QR — اضغط اتصال", isError = false)
        } catch (_: Exception) {
            showStatus("QR Code غير صالح", isError = true)
        }
    }

    private fun showStatus(msg: String, isError: Boolean) {
        tvStatus.text      = msg
        tvStatus.setTextColor(
            if (isError) 0xFFEF4444.toInt() else 0xFF10B981.toInt()
        )
        tvStatus.visibility = View.VISIBLE
    }

    private fun savePrefs(ip: String, port: String, token: String) {
        prefs.edit()
            .putString("ip",    ip)
            .putString("port",  port)
            .putString("token", token)
            .apply()
    }

    // ── History ───────────────────────────────────────────────────────────────
    data class HistoryItem(val addr: String, val ts: Long)

    private fun loadHistory(): MutableList<HistoryItem> {
        val n = prefs.getInt("hist_n", 0)
        return (0 until n).mapNotNull {
            val addr = prefs.getString("hist_addr_$it", null) ?: return@mapNotNull null
            val ts   = prefs.getLong("hist_ts_$it", 0)
            HistoryItem(addr, ts)
        }.toMutableList()
    }

    private fun saveHistory(ip: String, port: String) {
        val key  = "$ip:$port"
        val list = loadHistory().toMutableList()
        list.removeAll { it.addr == key }
        list.add(0, HistoryItem(key, System.currentTimeMillis()))
        if (list.size > 8) list.removeAt(list.lastIndex)
        prefs.edit().apply {
            putInt("hist_n", list.size)
            list.forEachIndexed { i, item ->
                putString("hist_addr_$i", item.addr)
                putLong("hist_ts_$i", item.ts)
            }
            apply()
        }
    }

    // ── History Adapter ───────────────────────────────────────────────────────
    inner class HistoryAdapter(
        private val items: MutableList<HistoryItem>,
        private val onClick: (HistoryItem) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.VH>() {

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvAddr: TextView  = view.findViewById(R.id.tvHistAddr)
            val tvTime: TextView  = view.findViewById(R.id.tvHistTime)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_history, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvAddr.text = item.addr
            h.tvTime.text = _relativeTime(item.ts)
            h.itemView.setOnClickListener { onClick(item) }
        }

        private fun _relativeTime(ts: Long): String {
            val diff = System.currentTimeMillis() - ts
            return when {
                diff < 60_000         -> "الآن"
                diff < 3_600_000      -> "${diff/60_000} دقيقة"
                diff < 86_400_000     -> "${diff/3_600_000} ساعة"
                else                  -> "${diff/86_400_000} يوم"
            }
        }
    }
}
