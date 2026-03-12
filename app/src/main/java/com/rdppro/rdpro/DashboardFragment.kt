package com.rdppro.rdpro

import android.graphics.Color
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
 * DashboardFragment v4.0
 * ─────────────────────────────────────────────
 * • Live Charts: CPU / RAM line charts (MPAndroidChart)
 * • Network sparkline
 * • Process list + Kill
 * • Quick commands 8
 * • Smart alerts: تنبيه عند CPU > 80%
 * • Disks usage
 */
class DashboardFragment : Fragment() {

    private lateinit var rdp: RdpService
    private val ui   = Handler(Looper.getMainLooper())
    private val tick = Runnable { refresh() }

    // Stats views
    private var tvCpu:    TextView?    = null
    private var tvRam:    TextView?    = null
    private var tvDisk:   TextView?    = null
    private var tvNet:    TextView?    = null
    private var tvUptime: TextView?    = null
    private var pbCpu:    ProgressBar? = null
    private var pbRam:    ProgressBar? = null
    private var pbDisk:   ProgressBar? = null
    private var rvProcs:  RecyclerView? = null
    private var procAdapter: ProcessAdapter? = null

    // Charts
    private var chartCpu: LineChart? = null
    private var chartRam: LineChart? = null
    private val cpuPoints  = mutableListOf<Entry>()
    private val ramPoints  = mutableListOf<Entry>()
    private var chartTick  = 0f
    private val MAX_POINTS = 30

    // Alert tracking
    private var lastAlertCpu = 0L

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_dashboard, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        tvCpu    = v.findViewById(R.id.tvCpu)
        tvRam    = v.findViewById(R.id.tvRam)
        tvDisk   = v.findViewById(R.id.tvDisk)
        tvNet    = v.findViewById(R.id.tvNet)
        tvUptime = v.findViewById(R.id.tvUptime)
        pbCpu    = v.findViewById(R.id.pbCpu)
        pbRam    = v.findViewById(R.id.pbRam)
        pbDisk   = v.findViewById(R.id.pbDisk)
        rvProcs  = v.findViewById(R.id.rvProcesses)
        chartCpu = v.findViewById(R.id.chartCpu)
        chartRam = v.findViewById(R.id.chartRam)

        setupCharts()

        procAdapter = ProcessAdapter { pid, name -> confirmKill(pid, name) }
        rvProcs?.layoutManager = LinearLayoutManager(requireContext())
        rvProcs?.adapter       = procAdapter
        rvProcs?.isNestedScrollingEnabled = false

        setupQuickCommands(v)
        refresh()
    }

    // ── Charts Setup ──────────────────────────────────────────────────────────
    private fun setupCharts() {
        fun configChart(chart: LineChart, label: String, color: Int) {
            chart.apply {
                description.isEnabled = false
                legend.isEnabled      = false
                setTouchEnabled(false)
                setDrawGridBackground(false)
                setBackgroundColor(Color.TRANSPARENT)
                setNoDataText("جاري التحميل...")
                setNoDataTextColor(0xFF64748B.toInt())

                xAxis.apply {
                    position     = XAxis.XAxisPosition.BOTTOM
                    textColor    = 0xFF4A5568.toInt()
                    textSize     = 8f
                    gridColor    = 0xFF131E36.toInt()
                    axisLineColor = 0xFF131E36.toInt()
                    setDrawLabels(false)
                }

                axisLeft.apply {
                    textColor    = 0xFF4A5568.toInt()
                    textSize     = 8f
                    gridColor    = 0xFF131E36.toInt()
                    axisLineColor = 0xFF131E36.toInt()
                    axisMinimum  = 0f
                    axisMaximum  = 100f
                    valueFormatter = object : ValueFormatter() {
                        override fun getFormattedValue(v: Float) = "${v.toInt()}%"
                    }
                }

                axisRight.isEnabled = false
            }
        }

        chartCpu?.let { configChart(it, "CPU", 0xFF3B82F6.toInt()) }
        chartRam?.let { configChart(it, "RAM", 0xFF10B981.toInt()) }
    }

    private fun pushChartPoint(chart: LineChart, points: MutableList<Entry>, value: Float, color: Int) {
        chartTick += 1f
        points.add(Entry(chartTick, value))
        if (points.size > MAX_POINTS) points.removeAt(0)

        val dataset = LineDataSet(points.toList(), "").apply {
            this.color           = color
            lineWidth            = 1.5f
            setDrawCircles(false)
            setDrawValues(false)
            setFillAlpha(40)
            setDrawFilled(true)
            fillColor            = color
            mode                 = LineDataSet.Mode.CUBIC_BEZIER
        }

        chart.data = LineData(dataset)
        chart.invalidate()
    }

    // ── Quick Commands ────────────────────────────────────────────────────────
    private fun setupQuickCommands(v: View) {
        data class Cmd(val id: Int, val action: String, val confirm: Boolean = false)
        listOf(
            Cmd(R.id.qcmdLock,       "lock"),
            Cmd(R.id.qcmdTaskMgr,    "task_manager"),
            Cmd(R.id.qcmdCmd,        "cmd"),
            Cmd(R.id.qcmdPowerShell, "powershell"),
            Cmd(R.id.qcmdExplorer,   "explorer"),
            Cmd(R.id.qcmdSleep,      "sleep",    true),
            Cmd(R.id.qcmdRestart,    "restart",  true),
            Cmd(R.id.qcmdShutdown,   "shutdown", true),
        ).forEach { cmd ->
            v.findViewById<View>(cmd.id)?.setOnClickListener {
                if (cmd.confirm) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("تأكيد").setMessage("تنفيذ ${cmd.action}؟")
                        .setPositiveButton("تأكيد") { _, _ -> sysAction(cmd.action) }
                        .setNegativeButton("إلغاء", null).show()
                } else {
                    sysAction(cmd.action)
                }
            }
        }
    }

    private fun sysAction(action: String) {
        rdp.post("/api/system/$action") { j ->
            val ok  = j?.optBoolean("ok") == true
            ui.post { Toast.makeText(context, if (ok) "✓ تم" else "✗ فشل", Toast.LENGTH_SHORT).show() }
        }
    }

    // ── Kill Process ──────────────────────────────────────────────────────────
    private fun confirmKill(pid: Int, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("إيقاف العملية").setMessage("إيقاف $name (PID $pid)؟")
            .setPositiveButton("إيقاف") { _, _ ->
                rdp.post("/api/system/kill", JSONObject().put("pid", pid)) { j ->
                    ui.post {
                        Toast.makeText(
                            context,
                            if (j?.optBoolean("ok") == true) "✓ تم إيقاف $name" else "✗ فشل",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null).show()
    }

    // ── Refresh ───────────────────────────────────────────────────────────────
    private fun refresh() {
        rdp.get("/api/system/stats") { j ->
            if (j == null) { ui.postDelayed(tick, 3000); return@get }
            ui.post {
                val cpu      = j.optDouble("cpu_percent", 0.0).toFloat()
                val ram      = j.optDouble("memory_percent", 0.0).toFloat()
                val disk     = j.optDouble("disk_percent", 0.0).toInt()
                val sent     = j.optDouble("net_sent_mbps", 0.0)
                val recv     = j.optDouble("net_recv_mbps", 0.0)
                val up       = j.optDouble("uptime_h", 0.0)
                val host     = j.optString("hostname", "")
                val ramUsed  = j.optDouble("memory_used_gb", 0.0)
                val ramTotal = j.optDouble("memory_total_gb", 0.0)

                // Text labels
                tvCpu?.text  = "CPU  ${cpu.toInt()}%"
                tvRam?.text  = "RAM  ${ram.toInt()}%  (${"%.1f".format(ramUsed)}/${"%.1f".format(ramTotal)} GB)"
                tvDisk?.text = "Disk $disk%"
                tvNet?.text  = "↑ ${"%.1f".format(sent)}  ↓ ${"%.1f".format(recv)} Mbps"
                tvUptime?.text = "Uptime: ${"%.1f".format(up)}h  •  $host"

                // Progress bars
                pbCpu?.progress  = cpu.toInt()
                pbRam?.progress  = ram.toInt()
                pbDisk?.progress = disk

                // CPU color
                tvCpu?.setTextColor(when {
                    cpu > 80 -> 0xFFEF4444.toInt()
                    cpu > 50 -> 0xFFF59E0B.toInt()
                    else     -> 0xFF3B82F6.toInt()
                })

                // Push to charts
                chartCpu?.let { pushChartPoint(it, cpuPoints, cpu, 0xFF3B82F6.toInt()) }
                chartRam?.let { pushChartPoint(it, ramPoints, ram, 0xFF10B981.toInt()) }

                // Smart alert: CPU > 80%
                checkSmartAlerts(cpu.toInt(), ram.toInt(), disk)
            }
        }

        rdp.get("/api/system/processes?sort=cpu&limit=10") { j ->
            val arr = j?.optJSONArray("processes") ?: return@get
            val list = (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                ProcessItem(p.optInt("pid"), p.optString("name"), p.optDouble("cpu"), p.optDouble("mem_mb"), p.optString("status"))
            }
            ui.post { procAdapter?.update(list) }
        }

        ui.postDelayed(tick, 3000)
    }

    // ── Smart Alerts ──────────────────────────────────────────────────────────
    private fun checkSmartAlerts(cpu: Int, ram: Int, disk: Int) {
        val now = System.currentTimeMillis()
        if (cpu > 80 && now - lastAlertCpu > 60_000) {
            lastAlertCpu = now
            // Show snackbar / push notification
            view?.let {
                com.google.android.material.snackbar.Snackbar.make(
                    it,
                    "⚠️ CPU مرتفع: $cpu% — تحقق من العمليات",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).setTextColor(0xFFF87171.toInt()).show()
            }
        }
        if (disk > 90 && now - lastAlertCpu > 120_000) {
            view?.let {
                com.google.android.material.snackbar.Snackbar.make(
                    it, "⚠️ مساحة القرص ممتلئة: $disk%",
                    com.google.android.material.snackbar.Snackbar.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacks(tick)
        tvCpu=null; tvRam=null; tvDisk=null; tvNet=null; tvUptime=null
        pbCpu=null; pbRam=null; pbDisk=null; rvProcs=null
        chartCpu=null; chartRam=null
    }

    // ── Data classes ──────────────────────────────────────────────────────────
    data class ProcessItem(val pid: Int, val name: String, val cpu: Double, val memMb: Double, val status: String)

    inner class ProcessAdapter(private val onKill: (Int, String) -> Unit) :
        RecyclerView.Adapter<ProcessAdapter.VH>() {
        private val items = mutableListOf<ProcessItem>()
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName: TextView = v.findViewById(R.id.tvProcName)
            val tvMeta: TextView = v.findViewById(R.id.tvProcMeta)
            val tvCpu:  TextView = v.findViewById(R.id.tvProcCpu)
            val btnKill: View   = v.findViewById(R.id.btnKill)
        }
        override fun onCreateViewHolder(p: android.view.ViewGroup, t: Int) =
            VH(layoutInflater.inflate(R.layout.item_process, p, false))
        override fun getItemCount() = items.size
        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = items[pos]
            h.tvName.text = p.name
            h.tvMeta.text = "PID ${p.pid}  •  ${"%.0f".format(p.memMb)} MB"
            h.tvCpu.text  = "${"%.1f".format(p.cpu)}%"
            h.tvCpu.setTextColor(when { p.cpu > 30 -> 0xFFEF4444.toInt(); p.cpu > 10 -> 0xFFF59E0B.toInt(); else -> 0xFF10B981.toInt() })
            h.btnKill.setOnClickListener { onKill(p.pid, p.name) }
        }
        fun update(list: List<ProcessItem>) { items.clear(); items.addAll(list); notifyDataSetChanged() }
    }
}
