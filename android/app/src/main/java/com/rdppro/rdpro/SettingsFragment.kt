package com.rdppro.rdpro

import android.content.Context
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import org.json.JSONObject

/**
 * SettingsFragment v3.1
 * إعدادات التطبيق: جودة الصورة، FPS، الشاشة، التوكن
 */
class SettingsFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val prefs by lazy {
        requireActivity().getSharedPreferences("rdp_v3", Context.MODE_PRIVATE)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        // ── جودة الصورة ────────────────────────────────────────────────────────
        val sliderQ  = v.findViewById<Slider>(R.id.sliderQuality)
        val tvQ      = v.findViewById<TextView>(R.id.tvQualityVal)
        val savedQ   = prefs.getInt("quality", 65)
        sliderQ.value = savedQ.toFloat()
        tvQ.text      = "$savedQ%"

        sliderQ.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val q = value.toInt()
            tvQ.text = "$q%"
            prefs.edit().putInt("quality", q).apply()
            applyScreenConfig(quality = q)
        }

        // ── FPS ────────────────────────────────────────────────────────────────
        val sliderFps = v.findViewById<Slider>(R.id.sliderFps)
        val tvFps     = v.findViewById<TextView>(R.id.tvFpsVal)
        val savedFps  = prefs.getInt("fps", 30)
        sliderFps.value = savedFps.toFloat()
        tvFps.text      = "$savedFps fps"

        sliderFps.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val fps = value.toInt()
            tvFps.text = "$fps fps"
            prefs.edit().putInt("fps", fps).apply()
            applyScreenConfig(fps = fps)
        }

        // ── Scale ──────────────────────────────────────────────────────────────
        val sliderScale = v.findViewById<Slider>(R.id.sliderScale)
        val tvScale     = v.findViewById<TextView>(R.id.tvScaleVal)
        val savedScale  = prefs.getFloat("scale", 1.0f)
        sliderScale.value = savedScale
        tvScale.text      = "×${"%.1f".format(savedScale)}"

        sliderScale.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            tvScale.text = "×${"%.1f".format(value)}"
            prefs.edit().putFloat("scale", value).apply()
            applyScreenConfig(scale = value)
        }

        // ── اختيار الشاشة ──────────────────────────────────────────────────────
        val spinnerMon = v.findViewById<Spinner>(R.id.spinnerMonitor)
        loadMonitors(spinnerMon)

        // ── معلومات الاتصال ────────────────────────────────────────────────────
        val tvConn = v.findViewById<TextView>(R.id.tvConnInfo)
        rdp.get("/api/info") { j ->
            activity?.runOnUiThread {
                if (j == null) { tvConn?.text = "تعذّر الاتصال"; return@runOnUiThread }
                tvConn?.text = buildString {
                    appendLine("🖥️ ${j.optString("hostname","?")}")
                    appendLine("🌐 ${j.optString("ip","?")}:${j.optInt("port",8000)}")
                    appendLine("📦 v${j.optString("version","?")}")
                    appendLine("🔊 صوت: ${if(j.optBoolean("audio")) "✓" else "✗"}")
                    append("📺 شاشات: ${j.optJSONArray("monitors")?.length() ?: 1}")
                }
            }
        }

        // ── اختبار الاتصال ─────────────────────────────────────────────────────
        val btnTest   = v.findViewById<Button>(R.id.btnTestConn)
        val tvLatency = v.findViewById<TextView>(R.id.tvLatency)
        btnTest?.setOnClickListener {
            tvLatency.text = "⟳ جاري القياس..."
            rdp.measureLatency { ms ->
                activity?.runOnUiThread {
                    val color = when {
                        ms < 50  -> 0xFF10B981.toInt()
                        ms < 150 -> 0xFFF59E0B.toInt()
                        else     -> 0xFFEF4444.toInt()
                    }
                    tvLatency.text = "${ms}ms"
                    tvLatency.setTextColor(color)
                }
            }
        }

        // ── قطع الاتصال ────────────────────────────────────────────────────────
        v.findViewById<Button>(R.id.btnDisconnect)?.setOnClickListener {
            rdp.dispose()
            activity?.finish()
        }
    }

    private fun applyScreenConfig(
        quality: Int?   = null,
        fps:     Int?   = null,
        scale:   Float? = null,
        monitor: Int?   = null
    ) {
        val body = JSONObject().apply {
            quality?.let { put("quality", it) }
            fps?.let     { put("fps", it) }
            scale?.let   { put("scale", it.toDouble()) }
            monitor?.let { put("monitor", it) }
        }
        rdp.post("/api/screen/config", body) { j ->
            val ok = j?.optBoolean("ok") == true
            activity?.runOnUiThread {
                if (!ok) Toast.makeText(context,"✗ فشل تطبيق الإعدادات",Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadMonitors(spinner: Spinner) {
        rdp.get("/api/screen/monitors") { j ->
            val arr = j?.optJSONArray("monitors") ?: return@get
            val names = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                names.add("${m.optString("name","Monitor")}  ${m.optInt("width")}×${m.optInt("height")}")
            }
            activity?.runOnUiThread {
                val adapter = ArrayAdapter(requireContext(),
                    android.R.layout.simple_spinner_item, names)
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                spinner.adapter = adapter
                spinner.setSelection(prefs.getInt("monitor", 0))
                spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>, v: View?, pos: Int, id: Long) {
                        prefs.edit().putInt("monitor", pos).apply()
                        applyScreenConfig(monitor = pos)
                    }
                    override fun onNothingSelected(p: AdapterView<*>) {}
                }
            }
        }
    }
}
