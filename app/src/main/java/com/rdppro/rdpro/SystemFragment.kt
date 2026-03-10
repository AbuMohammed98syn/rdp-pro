package com.rdppro.rdpro

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

class SystemFragment : Fragment() {
    private lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())
    private val tick = Runnable { load() }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, b: Bundle?) =
        i.inflate(R.layout.fragment_system, c, false)

    override fun onViewCreated(v: View, b: Bundle?) {
        super.onViewCreated(v, b)
        rdp = (activity as MainActivity).rdp
        load()

        fun act(id: Int, ep: String, label: String) =
            v.findViewById<Button>(id).setOnClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("تأكيد").setMessage("$label؟")
                    .setPositiveButton("تأكيد") { _,_ ->
                        rdp.post("/api/system/$ep") { j ->
                            Toast.makeText(context, if(j!=null)"تم: $label" else "فشل", Toast.LENGTH_SHORT).show()
                        }
                    }.setNegativeButton("إلغاء", null).show()
            }

        act(R.id.btnLock,    "lock",         "قفل الشاشة")
        act(R.id.btnTaskMgr, "task_manager", "مدير المهام")
        act(R.id.btnCmd,     "cmd",          "موجه الأوامر")
        act(R.id.btnSleep,   "sleep",        "السكون")
        act(R.id.btnRestart, "restart",      "إعادة التشغيل")
        act(R.id.btnShutdown,"shutdown",     "إيقاف التشغيل")
    }

    private fun load() {
        rdp.get("/api/system/stats") { j ->
            val v = view ?: return@get
            fun pb(id: Int, `val`: Int) = v.findViewById<ProgressBar>(id)?.apply { progress = `val` }
            fun tv(id: Int, t: String)  = v.findViewById<TextView>(id)?.apply { text = t }
            val cpu  = j?.optDouble("cpu_percent",0.0)?.toInt() ?: 0
            val ram  = j?.optDouble("memory_percent",0.0)?.toInt() ?: 0
            val disk = j?.optDouble("disk_percent",0.0)?.toInt() ?: 0
            pb(R.id.pbCpu, cpu);   tv(R.id.tvCpu,  "CPU $cpu%")
            pb(R.id.pbRam, ram);   tv(R.id.tvRam,  "RAM $ram%")
            pb(R.id.pbDisk, disk); tv(R.id.tvDisk, "Disk $disk%")
            tv(R.id.tvNet, "↑ ${"%.1f".format(j?.optDouble("net_sent_mbps",0.0))} ↓ ${"%.1f".format(j?.optDouble("net_recv_mbps",0.0))} Mbps")
            tv(R.id.tvUptime, "Uptime ${"%.1f".format(j?.optDouble("uptime_h",0.0))}h")
        }
        ui.postDelayed(tick, 3000)
    }

    override fun onDestroyView() { super.onDestroyView(); ui.removeCallbacks(tick) }
}
