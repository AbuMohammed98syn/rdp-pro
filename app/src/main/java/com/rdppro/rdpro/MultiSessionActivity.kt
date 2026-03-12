package com.rdppro.rdpro

import android.content.Context
import android.content.Intent
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton

/**
 * MultiSessionActivity v5.0
 * ─────────────────────────────────────────────
 * • إدارة اتصالات متعددة بحواسيب مختلفة
 * • بطاقة لكل جهاز: IP + حالة + FPS + CPU
 * • تبديل بين الجلسات بضغطة
 * • إضافة / حذف / تعديل اتصالات
 * • مؤشر حالة حي لكل جهاز
 */
class MultiSessionActivity : AppCompatActivity() {

    private val sessions = mutableListOf<Session>()
    private lateinit var adapter: SessionAdapter
    private val ui   = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshStats(); ui.postDelayed(this, 3000) }
    }

    data class Session(
        val id:    String,
        var name:  String,
        val host:  String,
        val port:  Int,
        val token: String,
        val https: Boolean = false,
        // Live stats
        var connected: Boolean = false,
        var cpu:       Int     = 0,
        var ram:       Int     = 0,
        var fps:       Int     = 0,
        var latencyMs: Long    = 0,
        var rdp:       RdpService? = null
    )

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_multi_session)

        val rv  = findViewById<RecyclerView>(R.id.rvSessions)
        val fab = findViewById<FloatingActionButton>(R.id.fabAddSession)

        adapter = SessionAdapter(sessions,
            onOpen   = { sess -> openSession(sess) },
            onDelete = { pos  -> confirmDelete(pos) },
            onEdit   = { sess -> showEditDialog(sess) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        fab.setOnClickListener { showAddDialog() }

        // Load saved sessions
        loadSessions()
        // Start pinging all
        ui.post(tick)
    }

    // ── Ping all sessions ─────────────────────────────────────────────────────
    private fun refreshStats() {
        sessions.forEach { sess ->
            val rdp = sess.rdp ?: RdpService(sess.host, sess.port, sess.token, sess.https)
                .also { sess.rdp = it }

            rdp.get("/api/system/stats") { j ->
                if (j != null) {
                    sess.connected = true
                    sess.cpu = j.optDouble("cpu_percent",0.0).toInt()
                    sess.ram = j.optDouble("memory_percent",0.0).toInt()
                } else {
                    sess.connected = false
                }
                ui.post { adapter.notifyDataSetChanged() }
            }
            rdp.measureLatency { ms ->
                sess.latencyMs = ms
                ui.post { adapter.notifyDataSetChanged() }
            }
        }
    }

    // ── Open session ──────────────────────────────────────────────────────────
    private fun openSession(sess: Session) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("host",  sess.host)
                .putExtra("port",  sess.port.toString())
                .putExtra("token", sess.token)
                .putExtra("https", sess.https)
        )
    }

    // ── Add / Edit dialogs ────────────────────────────────────────────────────
    private fun showAddDialog(existing: Session? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_add_session, null)
        val etName  = view.findViewById<EditText>(R.id.etSessName)
        val etIp    = view.findViewById<EditText>(R.id.etSessIp)
        val etPort  = view.findViewById<EditText>(R.id.etSessPort)
        val etToken = view.findViewById<EditText>(R.id.etSessToken)
        val swHttps = view.findViewById<Switch>(R.id.swSessHttps)

        existing?.let {
            etName.setText(it.name); etIp.setText(it.host)
            etPort.setText(it.port.toString()); etToken.setText(it.token)
            swHttps.isChecked = it.https
        }

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "➕ إضافة جهاز" else "✏️ تعديل")
            .setView(view)
            .setPositiveButton("حفظ") { _, _ ->
                val ip    = etIp.text.toString().trim()
                val port  = etPort.text.toString().toIntOrNull() ?: 8000
                val token = etToken.text.toString().trim()
                val name  = etName.text.toString().trim().ifEmpty { ip }
                val https = swHttps.isChecked

                if (ip.isEmpty()) return@setPositiveButton

                if (existing != null) {
                    existing.name = name
                } else {
                    sessions.add(Session(
                        id    = System.currentTimeMillis().toString(),
                        name  = name,
                        host  = ip,
                        port  = port,
                        token = token,
                        https = https
                    ))
                }
                adapter.notifyDataSetChanged()
                saveSessions()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun showEditDialog(sess: Session) = showAddDialog(sess)

    private fun confirmDelete(pos: Int) {
        val name = sessions.getOrNull(pos)?.name ?: return
        AlertDialog.Builder(this)
            .setTitle("حذف الجهاز").setMessage("حذف \"$name\"؟")
            .setPositiveButton("حذف") { _, _ ->
                sessions[pos].rdp?.dispose()
                sessions.removeAt(pos)
                adapter.notifyDataSetChanged()
                saveSessions()
            }.setNegativeButton("إلغاء", null).show()
    }

    // ── Persistence ───────────────────────────────────────────────────────────
    private fun saveSessions() {
        val prefs = getSharedPreferences("rdp_sessions", Context.MODE_PRIVATE)
        val arr   = org.json.JSONArray()
        sessions.forEach { s ->
            arr.put(org.json.JSONObject().apply {
                put("id",s.id); put("name",s.name); put("host",s.host)
                put("port",s.port); put("token",s.token); put("https",s.https)
            })
        }
        prefs.edit().putString("sessions", arr.toString()).apply()
    }

    private fun loadSessions() {
        val prefs = getSharedPreferences("rdp_sessions", Context.MODE_PRIVATE)
        val raw   = prefs.getString("sessions","[]") ?: "[]"
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                sessions.add(Session(
                    id    = j.optString("id","$i"),
                    name  = j.optString("name","جهاز $i"),
                    host  = j.optString("host",""),
                    port  = j.optInt("port",8000),
                    token = j.optString("token",""),
                    https = j.optBoolean("https",false)
                ))
            }
            adapter.notifyDataSetChanged()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        sessions.forEach { it.rdp?.dispose() }
    }

    // ── Adapter ───────────────────────────────────────────────────────────────
    inner class SessionAdapter(
        private val items: List<Session>,
        private val onOpen:   (Session) -> Unit,
        private val onDelete: (Int)     -> Unit,
        private val onEdit:   (Session) -> Unit
    ) : RecyclerView.Adapter<SessionAdapter.VH>() {

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val tvName:    TextView = v.findViewById(R.id.tvSessName)
            val tvAddr:    TextView = v.findViewById(R.id.tvSessAddr)
            val tvStats:   TextView = v.findViewById(R.id.tvSessStats)
            val tvStatus:  TextView = v.findViewById(R.id.tvSessStatus)
            val btnOpen:   View     = v.findViewById(R.id.btnSessOpen)
            val btnEdit:   View     = v.findViewById(R.id.btnSessEdit)
            val btnDelete: View     = v.findViewById(R.id.btnSessDelete)
            val progCpu:   ProgressBar = v.findViewById(R.id.progSessCpu)
            val progRam:   ProgressBar = v.findViewById(R.id.progSessRam)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(layoutInflater.inflate(R.layout.item_session, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val s = items[pos]
            h.tvName.text   = s.name
            h.tvAddr.text   = "${if(s.https) "🔒" else "🌐"} ${s.host}:${s.port}"
            h.tvStats.text  = "CPU ${s.cpu}%  RAM ${s.ram}%  ${s.latencyMs}ms"
            h.tvStatus.text = if (s.connected) "● متصل"   else "● منقطع"
            h.tvStatus.setTextColor(
                if (s.connected) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
            )
            h.progCpu.progress = s.cpu
            h.progRam.progress = s.ram

            h.btnOpen.setOnClickListener   { onOpen(s) }
            h.btnEdit.setOnClickListener   { onEdit(s) }
            h.btnDelete.setOnClickListener { onDelete(pos) }

            // Pulse animation on connected
            if (s.connected) {
                h.tvStatus.animate().alpha(0.5f).setDuration(600)
                    .withEndAction { h.tvStatus.animate().alpha(1f).setDuration(600).start() }
                    .start()
            }
        }
    }
}
