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
import com.google.zxing.integration.android.IntentIntegrator

/**
 * ConnectActivity v4.0
 * + Wake-on-LAN (MAC field + Wake button)
 * + Temp Token generation
 * + Better UX history
 */
class ConnectActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("rdp_v4", Context.MODE_PRIVATE) }

    private lateinit var etIp:     TextInputEditText
    private lateinit var etPort:   TextInputEditText
    private lateinit var etToken:  TextInputEditText
    private lateinit var etMac:    TextInputEditText
    private lateinit var btnConn:  MaterialButton
    private lateinit var btnQr:    MaterialButton
    private lateinit var btnWol:   MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var rvHist:   RecyclerView
    private lateinit var histAdapter: HistoryAdapter

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_connect)

        etIp    = findViewById(R.id.etIp)
        etPort  = findViewById(R.id.etPort)
        etToken = findViewById(R.id.etToken)
        etMac   = findViewById(R.id.etMac)
        btnConn = findViewById(R.id.btnConnect)
        btnQr   = findViewById(R.id.btnQr)
        btnWol  = findViewById(R.id.btnWol)
        tvStatus= findViewById(R.id.tvStatus)
        rvHist  = findViewById(R.id.rvHistory)

        // Restore saved values
        etIp.setText(prefs.getString("ip", ""))
        etPort.setText(prefs.getString("port", "8000"))
        etToken.setText(prefs.getString("token", "rdppro-secret-2024"))
        etMac.setText(prefs.getString("mac", ""))

        // History RecyclerView
        histAdapter = HistoryAdapter(loadHistory()) { item ->
            val parts = item.addr.split(":")
            etIp.setText(parts.getOrElse(0) { "" })
            etPort.setText(parts.getOrElse(1) { "8000" })
        }
        rvHist.layoutManager = LinearLayoutManager(this)
        rvHist.adapter = histAdapter

        // Perm level spinner
        val permSpinner = findViewById<Spinner>(R.id.spinnerPerm)
        ArrayAdapter.createFromResource(
            this, R.array.perm_levels, android.R.layout.simple_spinner_item
        ).also { a ->
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            permSpinner.adapter = a
        }

        // Restore HTTPS switch
        val switchHttps = findViewById<android.widget.Switch>(R.id.switchHttps)
        switchHttps?.isChecked = prefs.getBoolean("https", false)

        btnConn.setOnClickListener { doConnect() }


        btnQr.setOnClickListener {
            IntentIntegrator(this)
                .setPrompt("امسح QR Code الخاص بالخادم")
                .setBeepEnabled(true)
                .setOrientationLocked(true)
                .initiateScan()
        }

        // Wake-on-LAN button
        btnWol.setOnClickListener { doWakeOnLan() }
    }

    private fun doConnect() {
        val ip    = etIp.text?.toString()?.trim() ?: ""
        val port  = etPort.text?.toString()?.trim()?.ifEmpty { "8000" } ?: "8000"
        val token = etToken.text?.toString()?.trim() ?: ""
        val useHttps = try { findViewById<android.widget.Switch>(R.id.switchHttps)?.isChecked == true } catch (e: Exception) { false }
        val permLevel = try { findViewById<Spinner>(R.id.spinnerPerm)?.selectedItemPosition ?: 0 } catch (e: Exception) { 0 }

        if (ip.isEmpty()) { showStatus("أدخل عنوان IP الحاسوب", true); return }

        btnConn.isEnabled = false
        showStatus("⟳ جاري الاتصال...", false)

        val rdp = RdpService(ip, port.toIntOrNull() ?: 8000, token, https = useHttps)

        // Check if 2FA is enabled on server first
        rdp.get2FAStatus { twoFAEnabled ->
            if (twoFAEnabled) {
                runOnUiThread {
                    btnConn.isEnabled = true
                    show2FADialog(rdp, ip, port, token, useHttps, permLevel)
                }
            } else {
                pingAndLaunch(rdp, ip, port, token, useHttps, permLevel)
            }
        }
    }

    private fun show2FADialog(rdp: RdpService, ip: String, port: String,
                              token: String, https: Boolean, perm: Int) {
        val et = android.widget.EditText(this).also {
            it.hint = "رمز 6 أرقام"
            it.inputType = android.text.InputType.TYPE_CLASS_NUMBER
            it.setPadding(40, 20, 40, 20)
        }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🔐 التحقق بخطوتين")
            .setMessage("أدخل رمز TOTP من تطبيق المصادقة")
            .setView(et)
            .setPositiveButton("تحقق") { _, _ ->
                val code = et.text.toString().trim()
                btnConn.isEnabled = false
                showStatus("⟳ جاري التحقق...", false)
                rdp.verify2FA(code) { ok, msg ->
                    if (ok) pingAndLaunch(rdp, ip, port, token, https, perm)
                    else runOnUiThread {
                        btnConn.isEnabled = true
                        showStatus("✗ $msg", true)
                        rdp.dispose()
                    }
                }
            }
            .setNegativeButton("إلغاء") { _, _ ->
                btnConn.isEnabled = true
                rdp.dispose()
            }
            .show()
    }

    private fun pingAndLaunch(rdp: RdpService, ip: String, port: String,
                              token: String, https: Boolean, perm: Int) {
        rdp.get("/api/ping") { j ->
            runOnUiThread {
                btnConn.isEnabled = true
                if (j?.optBoolean("pong") == true || j?.has("version") == true) {
                    savePrefs(ip, port, token)
                    saveHistory(ip, port)
                    prefs.edit().putBoolean("https", https).apply()
                    rdp.dispose()
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra("host",  ip)
                            .putExtra("port",  port)
                            .putExtra("token", token)
                            .putExtra("https", https)
                            .putExtra("perm",  perm)
                    )
                } else {
                    showStatus("✗ تعذّر الاتصال — تأكد من IP والـ Token", true)
                    rdp.dispose()
                }
            }
        }
    }

    private fun doWakeOnLan() {
        val ip   = etIp.text?.toString()?.trim() ?: ""
        val mac  = etMac.text?.toString()?.trim() ?: ""

        if (mac.isEmpty()) {
            showStatus("أدخل MAC Address للحاسوب", true)
            return
        }

        // Determine broadcast IP from device IP
        val broadcast = if (ip.isNotEmpty()) {
            val parts = ip.split(".")
            if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255"
            else "255.255.255.255"
        } else "255.255.255.255"

        btnWol.isEnabled = false
        showStatus("⟳ إرسال Magic Packet...", false)

        val token = etToken.text?.toString()?.trim() ?: ""
        val rdp = if (ip.isNotEmpty()) {
            val port = etPort.text?.toString()?.trim()?.toIntOrNull() ?: 8000
            RdpService(ip, port, token)
        } else null

        // Try local UDP first (works without server)
        val tempRdp = rdp ?: RdpService("localhost", 8000)
        tempRdp.sendWakeOnLan(mac, broadcast) { ok, msg ->
            runOnUiThread {
                btnWol.isEnabled = true
                showStatus(msg, !ok)
                // Save MAC
                prefs.edit().putString("mac", mac).apply()
            }
            tempRdp.dispose()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(req, res, data)
        if (result?.contents != null) {
            parseQrCode(result.contents)
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
            showStatus("✓ تم قراءة QR — اضغط اتصال", false)
        } catch (_: Exception) {
            showStatus("QR Code غير صالح", true)
        }
    }

    private fun showStatus(msg: String, isError: Boolean) {
        tvStatus.text = msg
        tvStatus.setTextColor(if (isError) 0xFFEF4444.toInt() else 0xFF10B981.toInt())
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
            val tvAddr: TextView = view.findViewById(R.id.tvHistAddr)
            val tvTime: TextView = view.findViewById(R.id.tvHistTime)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int): VH =
            VH(layoutInflater.inflate(R.layout.item_history, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val item = items[pos]
            h.tvAddr.text = item.addr
            h.tvTime.text = relTime(item.ts)
            h.itemView.setOnClickListener { onClick(item) }
        }

        private fun relTime(ts: Long): String {
            val diff = System.currentTimeMillis() - ts
            return when {
                diff < 60_000     -> "الآن"
                diff < 3_600_000  -> "${diff / 60_000} دقيقة"
                diff < 86_400_000 -> "${diff / 3_600_000} ساعة"
                else              -> "${diff / 86_400_000} يوم"
            }
        }
    }
}
