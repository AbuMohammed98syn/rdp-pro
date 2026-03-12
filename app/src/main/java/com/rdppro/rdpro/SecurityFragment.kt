package com.rdppro.rdpro

import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.*
import com.github.mikephil.charting.formatter.ValueFormatter
import org.json.JSONObject

/**
 * SecurityFragment v4.1
 * Tabs: Audit Log | IP Whitelist | Network Chart | Perm Tokens
 */
class SecurityFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val ui  = Handler(Looper.getMainLooper())
    private val tick = Runnable { refreshNet() }

    // Network chart data
    private val sentPoints = mutableListOf<Entry>()
    private val recvPoints = mutableListOf<Entry>()
    private var chartNetSent: LineChart? = null
    private var chartNetRecv: LineChart? = null
    private var chartTick = 0f

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_security, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        val pager = v.findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.secPager)
        val tabs  = v.findViewById<com.google.android.material.tabs.TabLayout>(R.id.secTabs)

        val frags  = listOf(AuditLogTab(), IpWhitelistTab(), NetworkChartTab(), PermTokensTab())
        val labels = listOf("Audit", "IP", "شبكة", "Tokens")

        pager.adapter = object : androidx.viewpager2.adapter.FragmentStateAdapter(this) {
            override fun getItemCount() = frags.size
            override fun createFragment(pos: Int): Fragment = frags[pos]
        }
        com.google.android.material.tabs.TabLayoutMediator(tabs, pager) { tab, i ->
            tab.text = labels[i]
        }.attach()
    }

    // ── Audit Log Tab ──────────────────────────────────────────────────────────
    inner class AuditLogTab : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.tab_audit_log, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val rv       = v.findViewById<RecyclerView>(R.id.rvAudit)
            val btnRefresh = v.findViewById<View>(R.id.btnAuditRefresh)
            val btnClear   = v.findViewById<View>(R.id.btnAuditClear)

            val adapter = AuditAdapter()
            rv.layoutManager = LinearLayoutManager(requireContext())
            rv.adapter = adapter

            fun load() {
                rdp.getAuditLog(100) { list ->
                    ui.post { adapter.update(list) }
                }
            }
            load()

            btnRefresh.setOnClickListener { load() }
            btnClear.setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("مسح السجل").setMessage("حذف جميع أحداث Audit Log؟")
                    .setPositiveButton("حذف") { _, _ ->
                        rdp.post("/api/audit/log") { j ->
                            ui.post { if (j?.optBoolean("ok")==true) load() }
                        }
                    }.setNegativeButton("إلغاء", null).show()
            }
        }

        inner class AuditAdapter : RecyclerView.Adapter<AuditAdapter.VH>() {
            private val items = mutableListOf<JSONObject>()
            inner class VH(v: View) : RecyclerView.ViewHolder(v) {
                val tvAction: TextView = v.findViewById(R.id.tvAuditAction)
                val tvDetail: TextView = v.findViewById(R.id.tvAuditDetail)
                val tvTs:     TextView = v.findViewById(R.id.tvAuditTs)
            }
            override fun onCreateViewHolder(p: ViewGroup, t: Int) =
                VH(layoutInflater.inflate(R.layout.item_audit, p, false))
            override fun getItemCount() = items.size
            override fun onBindViewHolder(h: VH, i: Int) {
                val j = items[i]
                h.tvAction.text = j.optString("action","")
                h.tvDetail.text = j.optString("detail","") + if (j.optString("ip","").isNotEmpty()) "  [${j.optString("ip")}]" else ""
                h.tvTs.text     = j.optString("ts","").takeLast(19)
            }
            fun update(list: List<JSONObject>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
        }
    }

    // ── IP Whitelist Tab ───────────────────────────────────────────────────────
    inner class IpWhitelistTab : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.tab_ip_whitelist, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val etIp   = v.findViewById<EditText>(R.id.etWlIp)
            val btnAdd = v.findViewById<View>(R.id.btnWlAdd)
            val btnBlock = v.findViewById<View>(R.id.btnWlBlock)
            val tvList = v.findViewById<TextView>(R.id.tvWhitelist)

            fun load() {
                rdp.get("/api/security/whitelist") { j ->
                    val wl = j?.optJSONArray("whitelist")
                    val bl = j?.optJSONArray("blacklist")
                    val sb = StringBuilder()
                    sb.appendLine("✅ Whitelist:")
                    if (wl == null || wl.length() == 0) sb.appendLine("  (فارغ — الكل مسموح)")
                    else for (k in 0 until wl.length()) sb.appendLine("  ${wl.getString(k)}")
                    sb.appendLine("🚫 Blacklist:")
                    if (bl == null || bl.length() == 0) sb.appendLine("  (فارغ)")
                    else for (k in 0 until bl.length()) sb.appendLine("  ${bl.getString(k)}")
                    ui.post { tvList.text = sb }
                }
            }
            load()

            btnAdd.setOnClickListener {
                val ip = etIp.text.toString().trim()
                if (ip.isEmpty()) return@setOnClickListener
                rdp.post("/api/security/whitelist", JSONObject().put("ip", ip)) { j ->
                    ui.post { etIp.text.clear(); load() }
                }
            }
            btnBlock.setOnClickListener {
                val ip = etIp.text.toString().trim()
                if (ip.isEmpty()) return@setOnClickListener
                rdp.post("/api/security/blacklist", JSONObject().put("ip", ip)) { _ ->
                    ui.post { etIp.text.clear(); load() }
                }
            }
        }
    }

    // ── Network Chart Tab ──────────────────────────────────────────────────────
    inner class NetworkChartTab : Fragment() {
        private var chartSent: LineChart? = null
        private var chartRecv: LineChart? = null
        private val sentPts = mutableListOf<Entry>()
        private val recvPts = mutableListOf<Entry>()
        private val h = Handler(Looper.getMainLooper())
        private val t = Runnable { refresh() }

        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.tab_network_chart, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            chartSent = v.findViewById(R.id.chartNetSent)
            chartRecv = v.findViewById(R.id.chartNetRecv)
            configChart(chartSent, 0xFF22D3EE.toInt())
            configChart(chartRecv, 0xFF10B981.toInt())
            refresh()
        }

        private fun configChart(chart: LineChart?, color: Int) {
            chart?.apply {
                description.isEnabled = false; legend.isEnabled = false
                setTouchEnabled(false); setDrawGridBackground(false)
                setBackgroundColor(0); setNoDataText("جاري التحميل...")
                xAxis.apply { position = XAxis.XAxisPosition.BOTTOM; setDrawLabels(false); gridColor=0xFF131E36.toInt() }
                axisLeft.apply { textColor=0xFF4A5568.toInt(); textSize=8f; gridColor=0xFF131E36.toInt(); axisMinimum=0f;
                    valueFormatter = object : ValueFormatter() { override fun getFormattedValue(v: Float) = "${"%.1f".format(v)}" } }
                axisRight.isEnabled = false
            }
        }

        private fun pushPoint(chart: LineChart?, pts: MutableList<Entry>, v: Float, color: Int, tick: Float) {
            pts.add(Entry(tick, v))
            if (pts.size > 30) pts.removeAt(0)
            val ds = LineDataSet(pts.toList(),"").apply {
                this.color=color; lineWidth=1.5f; setDrawCircles(false); setDrawValues(false)
                setFillAlpha(40); setDrawFilled(true); fillColor=color; mode=LineDataSet.Mode.CUBIC_BEZIER }
            chart?.data = LineData(ds); chart?.invalidate()
        }

        private var netTick = 0f
        private fun refresh() {
            rdp.get("/api/system/stats") { j ->
                if (j == null) { h.postDelayed(t, 3000); return@get }
                val sent = j.optDouble("net_sent_mbps",0.0).toFloat()
                val recv = j.optDouble("net_recv_mbps",0.0).toFloat()
                netTick++
                h.post {
                    pushPoint(chartSent, sentPts, sent, 0xFF22D3EE.toInt(), netTick)
                    pushPoint(chartRecv, recvPts, recv, 0xFF10B981.toInt(), netTick)
                    view?.findViewById<TextView>(R.id.tvNetSent)?.text = "↑ ${"%.2f".format(sent)} Mbps"
                    view?.findViewById<TextView>(R.id.tvNetRecv)?.text = "↓ ${"%.2f".format(recv)} Mbps"
                }
            }
            h.postDelayed(t, 3000)
        }

        override fun onDestroyView() {
            super.onDestroyView(); h.removeCallbacks(t)
            chartSent=null; chartRecv=null
        }
    }

    // ── Perm Tokens Tab ────────────────────────────────────────────────────────
    inner class PermTokensTab : Fragment() {
        override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
            i.inflate(R.layout.tab_perm_tokens, c, false)

        override fun onViewCreated(v: View, b: Bundle?) {
            super.onViewCreated(v, b)
            val spinnerPerm   = v.findViewById<Spinner>(R.id.spinnerPermType)
            val spinnerExpiry = v.findViewById<Spinner>(R.id.spinnerPermExpiry)
            val btnGen        = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnGenPermToken)
            val tvToken       = v.findViewById<TextView>(R.id.tvPermTokenResult)
            val tvCopy        = v.findViewById<View>(R.id.btnCopyPermToken)

            val permOpts  = listOf("⚡ كامل" to 0, "👁 عرض فقط" to 1, "📁 ملفات" to 2, "💻 تيرمنال" to 3)
            val expiryOpts= listOf("30 دقيقة" to 30, "ساعة" to 60, "6 ساعات" to 360, "يوم" to 1440)

            spinnerPerm.adapter   = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, permOpts.map{it.first}).also{ it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            spinnerExpiry.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, expiryOpts.map{it.first}).also{ it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            btnGen.setOnClickListener {
                val perm   = permOpts[spinnerPerm.selectedItemPosition].second
                val expiry = expiryOpts[spinnerExpiry.selectedItemPosition].second
                btnGen.isEnabled = false
                rdp.createPermToken(expiry, perm, "من الهاتف") { j ->
                    ui.post {
                        btnGen.isEnabled = true
                        if (j == null) { tvToken.text = "✗ فشل"; return@post }
                        val tok  = j.optString("token","")
                        val name = j.optString("perm_name","")
                        tvToken.text = "$tok\n($name)"
                        tvToken.visibility = View.VISIBLE
                        tvCopy.visibility  = View.VISIBLE
                    }
                }
            }

            tvCopy.setOnClickListener {
                val token = tvToken.text.toString().lines().firstOrNull() ?: return@setOnClickListener
                val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("token", token))
                Toast.makeText(context,"✓ تم نسخ Token", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacks(tick)
    }

    private fun refreshNet() { /* handled inside NetworkChartTab */ }
}
