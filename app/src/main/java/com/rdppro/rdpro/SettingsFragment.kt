package com.rdppro.rdpro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * SettingsFragment v4.0
 * ─────────────────────────────────────────────
 * • جودة الشاشة + FPS + Scale
 * • اختيار الشاشة (Multi-monitor)
 * • Temp Token إنشاء + نسخ
 * • معلومات الاتصال
 * • قطع الاتصال
 */
class SettingsFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val prefs by lazy {
        requireActivity().getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        setupQuality(v)
        setupFps(v)
        setupScale(v)
        setupMonitorSelector(v)
        setupTempToken(v)
        setupConnectionInfo(v)
        setupActions(v)
    }

    // ── Quality slider ────────────────────────────────────────────────────────
    private fun setupQuality(v: View) {
        val slider = v.findViewById<Slider>(R.id.sliderQuality)
        val tvVal  = v.findViewById<TextView>(R.id.tvQualityVal)
        slider.value = prefs.getInt("quality", 65).toFloat()
        tvVal.text   = "${slider.value.toInt()}%"
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            tvVal.text = "${value.toInt()}%"
            prefs.edit().putInt("quality", value.toInt()).apply()
            rdp.post("/api/screen/quality", JSONObject().put("quality", value.toInt()))
        }
    }

    // ── FPS slider ────────────────────────────────────────────────────────────
    private fun setupFps(v: View) {
        val slider = v.findViewById<Slider>(R.id.sliderFps)
        val tvVal  = v.findViewById<TextView>(R.id.tvFpsVal)
        slider.value = prefs.getInt("fps", 30).toFloat()
        tvVal.text   = "${slider.value.toInt()} fps"
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            tvVal.text = "${value.toInt()} fps"
            prefs.edit().putInt("fps", value.toInt()).apply()
            rdp.post("/api/screen/quality", JSONObject().put("fps", value.toInt()))
        }
    }

    // ── Scale slider ──────────────────────────────────────────────────────────
    private fun setupScale(v: View) {
        val slider = v.findViewById<Slider>(R.id.sliderScale)
        val tvVal  = v.findViewById<TextView>(R.id.tvScaleVal)
        slider.value = prefs.getFloat("scale", 1.0f)
        tvVal.text   = "×${"%.1f".format(slider.value)}"
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            tvVal.text = "×${"%.1f".format(value)}"
            prefs.edit().putFloat("scale", value).apply()
        }
    }

    // ── Monitor selector ──────────────────────────────────────────────────────
    private fun setupMonitorSelector(v: View) {
        val spinner = v.findViewById<Spinner>(R.id.spinnerMonitor)
        rdp.get("/api/screen/displays") { j ->
            val arr = j?.optJSONArray("monitors") ?: return@get
            val names = (0 until arr.length()).map { i ->
                val m = arr.getJSONObject(i)
                "شاشة ${m.optInt("index", i) + 1}  ${m.optInt("width")}×${m.optInt("height")}" +
                        if (m.optBoolean("primary")) "  (رئيسية)" else ""
            }
            activity?.runOnUiThread {
                val adapter = ArrayAdapter(requireContext(),
                    android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.setSelection(j?.optInt("selected", 0) ?: 0)
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>, vv: View?, pos: Int, id: Long) {
                        rdp.post("/api/screen/select", JSONObject().put("index", pos))
                        prefs.edit().putInt("monitor", pos).apply()
                    }
                    override fun onNothingSelected(p: AdapterView<*>) {}
                }
            }
        }
    }

    // ── Temp Token ────────────────────────────────────────────────────────────
    private fun setupTempToken(v: View) {
        val btnGen    = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGenToken)
        val tvToken   = v.findViewById<TextView>(R.id.tvTokenResult)
        val tvExpiry  = v.findViewById<TextView>(R.id.tvTokenExpiry)
        val spinner   = v.findViewById<Spinner>(R.id.spinnerTokenExpiry)
        val btnCopy   = v.findViewById<View>(R.id.btnCopyToken)

        // Expiry options
        val expiryOptions = listOf("15 دقيقة" to 15, "ساعة" to 60, "6 ساعات" to 360, "24 ساعة" to 1440)
        spinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            expiryOptions.map { it.first }
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        btnGen.setOnClickListener {
            val (_, minutes) = expiryOptions[spinner.selectedItemPosition]
            btnGen.isEnabled = false
            rdp.createTempToken(minutes, "جلسة مؤقتة") { j ->
                activity?.runOnUiThread {
                    btnGen.isEnabled = true
                    if (j == null) {
                        tvToken.text = "✗ فشل إنشاء Token"
                        return@runOnUiThread
                    }
                    val token  = j.optString("token", "")
                    val expiry = j.optString("expires_at", "")
                    tvToken.text  = token
                    tvToken.visibility = View.VISIBLE
                    tvExpiry.text = "ينتهي: $expiry"
                    tvExpiry.visibility = View.VISIBLE
                    btnCopy.visibility = View.VISIBLE
                }
            }
        }

        btnCopy.setOnClickListener {
            val token = tvToken.text.toString()
            if (token.isNotEmpty()) {
                val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("rdp_token", token))
                Toast.makeText(context, "✓ تم نسخ Token", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Connection info ───────────────────────────────────────────────────────
    private fun setupConnectionInfo(v: View) {
        val tvInfo    = v.findViewById<TextView>(R.id.tvConnInfo)
        val tvLatency = v.findViewById<TextView>(R.id.tvLatency)
        val btnTest   = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnTestConn)

        rdp.get("/api/info") { j ->
            activity?.runOnUiThread {
                if (j == null) { tvInfo?.text = "تعذّر الاتصال"; return@runOnUiThread }
                tvInfo?.text = buildString {
                    appendLine("🖥️  ${j.optString("hostname", "?")}")
                    appendLine("🌐  ${j.optString("ip", "?")}")
                    appendLine("🔌  Port ${j.optInt("port", 8000)}")
                    appendLine("📦  v${j.optString("version", "?")}")
                    appendLine("🔊  صوت: ${if (j.optBoolean("audio_available")) "✓" else "✗"}")
                }
            }
        }

        btnTest.setOnClickListener {
            tvLatency.text = "⟳..."
            rdp.measureLatency { ms ->
                activity?.runOnUiThread {
                    val color = when { ms < 50 -> 0xFF10B981.toInt(); ms < 150 -> 0xFFF59E0B.toInt(); else -> 0xFFEF4444.toInt() }
                    tvLatency.text = "${ms}ms"
                    tvLatency.setTextColor(color)
                }
            }
        }
    }

    // ── Disconnect ────────────────────────────────────────────────────────────
    private fun setupActions(v: View) {
        v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnDisconnect)
            ?.setOnClickListener {
                rdp.dispose()
                activity?.finish()
            }
    }
}
