package com.rdppro.rdpro

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject

/**
 * Dashboard Fragment v3
 * - إحصائيات النظام الحية: CPU / RAM / Disk / Network
 * - قائمة العمليات مع Kill
 * - 8 أوامر سريعة
 * - وقت التشغيل
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

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_dashboard, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp

        // bind views
        tvCpu    = v.findViewById(R.id.tvCpu)
        tvRam    = v.findViewById(R.id.tvRam)
        tvDisk   = v.findViewById(R.id.tvDisk)
        tvNet    = v.findViewById(R.id.tvNet)
        tvUptime = v.findViewById(R.id.tvUptime)
        pbCpu    = v.findViewById(R.id.pbCpu)
        pbRam    = v.findViewById(R.id.pbRam)
        pbDisk   = v.findViewById(R.id.pbDisk)
        rvProcs  = v.findViewById(R.id.rvProcesses)

        // Processes RecyclerView
        procAdapter = ProcessAdapter { pid, name -> confirmKill(pid, name) }
        rvProcs?.layoutManager = LinearLayoutManager(requireContext())
        rvProcs?.adapter       = procAdapter
        rvProcs?.isNestedScrollingEnabled = false

        // Quick commands
        setupQuickCommands(v)

        refresh()
    }

    private fun setupQuickCommands(v: View) {
        data class Cmd(val id: Int, val label: String, val action: String, val confirm: Boolean = false)

        val cmds = listOf(
            Cmd(R.id.qcmdLock,       "قفل الشاشة",   "lock"),
            Cmd(R.id.qcmdTaskMgr,    "مدير المهام",  "task_manager"),
            Cmd(R.id.qcmdCmd,        "CMD",           "cmd"),
            Cmd(R.id.qcmdPowerShell, "PowerShell",   "powershell"),
            Cmd(R.id.qcmdExplorer,   "Explorer",     "explorer"),
            Cmd(R.id.qcmdSleep,      "سكون",         "sleep",    true),
            Cmd(R.id.qcmdRestart,    "إعادة تشغيل", "restart",  true),
            Cmd(R.id.qcmdShutdown,   "إيقاف",        "shutdown", true),
        )

        cmds.forEach { cmd ->
            v.findViewById<View>(cmd.id)?.setOnClickListener {
                if (cmd.confirm) {
                    AlertDialog.Builder(requireContext())
                        .setTitle("تأكيد")
                        .setMessage("${cmd.label}؟")
                        .setPositiveButton("تأكيد") { _, _ -> sysAction(cmd.action) }
                        .setNegativeButton("إلغاء", null)
                        .show()
                } else {
                    sysAction(cmd.action)
                }
            }
        }
    }

    private fun sysAction(action: String) {
        rdp.post("/api/system/$action") { j ->
            val msg = if (j?.optBoolean("ok") == true) "✓ تم" else "✗ فشل"
            ui.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun confirmKill(pid: Int, name: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("إيقاف العملية")
            .setMessage("إيقاف $name (PID $pid)؟")
            .setPositiveButton("إيقاف") { _, _ ->
                rdp.post("/api/system/kill",
                    JSONObject().put("pid", pid)
                ) { j ->
                    val msg = if (j?.optBoolean("ok") == true) "✓ تم إيقاف $name"
                              else "✗ ${j?.optString("error","فشل")}"
                    ui.post { Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                    if (j?.optBoolean("ok") == true) refresh()
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun refresh() {
        // Stats
        rdp.get("/api/system/stats") { j ->
            if (j == null) return@get
            ui.post {
                val cpu  = j.optDouble("cpu_percent", 0.0).toInt()
                val ram  = j.optDouble("memory_percent", 0.0).toInt()
                val disk = j.optDouble("disk_percent", 0.0).toInt()
                val sent = j.optDouble("net_sent_mbps", 0.0)
                val recv = j.optDouble("net_recv_mbps", 0.0)
                val up   = j.optDouble("uptime_h", 0.0)
                val host = j.optString("hostname", "")
                val ramUsed  = j.optDouble("memory_used_gb", 0.0)
                val ramTotal = j.optDouble("memory_total_gb", 0.0)

                tvCpu?.text  = "CPU  $cpu%"
                tvRam?.text  = "RAM  $ram%  (${"%.1f".format(ramUsed)}/${"%.1f".format(ramTotal)} GB)"
                tvDisk?.text = "Disk $disk%"
                tvNet?.text  = "↑ ${"%.1f".format(sent)}  ↓ ${"%.1f".format(recv)} Mbps"
                tvUptime?.text = "Uptime: ${"%.1f".format(up)}h  •  $host"

                pbCpu?.progress  = cpu
                pbRam?.progress  = ram
                pbDisk?.progress = disk

                // لون CPU بناءً على الحمل
                val cpuColor = when {
                    cpu > 80 -> 0xFFEF4444.toInt()
                    cpu > 50 -> 0xFFF59E0B.toInt()
                    else     -> 0xFF3B82F6.toInt()
                }
                tvCpu?.setTextColor(cpuColor)
            }
        }

        // Processes
        rdp.get("/api/system/processes?sort=cpu&limit=10") { j ->
            val arr = j?.optJSONArray("processes") ?: return@get
            val list = mutableListOf<ProcessItem>()
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(ProcessItem(
                    pid    = p.optInt("pid"),
                    name   = p.optString("name"),
                    cpu    = p.optDouble("cpu", 0.0),
                    memMb  = p.optDouble("mem_mb", 0.0),
                    status = p.optString("status")
                ))
            }
            ui.post { procAdapter?.update(list) }
        }

        ui.postDelayed(tick, 3000)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ui.removeCallbacks(tick)
        tvCpu = null; tvRam = null; tvDisk = null
        tvNet = null; tvUptime = null; pbCpu = null
        pbRam = null; pbDisk  = null; rvProcs = null
    }

    // ── Process list data ─────────────────────────────────────────────────────
    data class ProcessItem(
        val pid: Int, val name: String, val cpu: Double,
        val memMb: Double, val status: String
    )

    inner class ProcessAdapter(
        private val onKill: (Int, String) -> Unit
    ) : RecyclerView.Adapter<ProcessAdapter.VH>() {

        private val items = mutableListOf<ProcessItem>()

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val tvName:  TextView = view.findViewById(R.id.tvProcName)
            val tvMeta:  TextView = view.findViewById(R.id.tvProcMeta)
            val tvCpu:   TextView = view.findViewById(R.id.tvProcCpu)
            val btnKill: View     = view.findViewById(R.id.btnKill)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, t: Int): VH {
            val v = layoutInflater.inflate(R.layout.item_process, parent, false)
            return VH(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val p = items[pos]
            h.tvName.text = p.name
            h.tvMeta.text = "PID ${p.pid}  •  ${"%.0f".format(p.memMb)} MB"
            h.tvCpu.text  = "${"%.1f".format(p.cpu)}%"
            h.tvCpu.setTextColor(
                when {
                    p.cpu > 30 -> 0xFFEF4444.toInt()
                    p.cpu > 10 -> 0xFFF59E0B.toInt()
                    else       -> 0xFF10B981.toInt()
                }
            )
            h.btnKill.setOnClickListener { onKill(p.pid, p.name) }
        }

        fun update(newList: List<ProcessItem>) {
            items.clear()
            items.addAll(newList)
            notifyDataSetChanged()
        }
    }
}
