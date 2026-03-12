package com.rdppro.rdpro

import android.content.Context
import android.os.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.snackbar.Snackbar

/**
 * MainActivity v4.1
 * ─────────────────────────────────────────────
 * • Smart Command Bar (pull‑down overlay)
 * • Permission level indicator
 * • Dark/Light theme toggle
 * • Language toggle AR/EN
 * • Live FPS / Latency in header
 * • Whiteboard accessible from Screen fragment toolbar
 */
class MainActivity : AppCompatActivity() {

    lateinit var rdp: RdpService
    private val ui   = Handler(Looper.getMainLooper())

    // Fragments
    private val dashFrag      by lazy { DashboardFragment() }
    private val screenFrag    by lazy { ScreenFragment() }
    private val filesFrag     by lazy { FilesFragment() }
    private val controlFrag   by lazy { ControlFragment() }
    private val moreFrag      by lazy { MoreFragment() }
    private var currentFrag: Fragment = dashFrag

    // Permission level: 0=full, 1=view-only, 2=files-only, 3=terminal-only
    var permLevel = 0

    // Smart Command Bar state
    private var cmdBarVisible = false

    // Stats tickers
    private val statsTick = object : Runnable {
        override fun run() {
            val tvFps = findViewById<TextView>(R.id.tvFps) ?: return
            val latMs = rdp.latencyMs
            val latColor = when { latMs < 50 -> 0xFF10B981.toInt(); latMs < 150 -> 0xFFF59E0B.toInt(); else -> 0xFFEF4444.toInt() }
            tvFps.text = "${rdp.fps}fps  ${latMs}ms"
            tvFps.setTextColor(latColor)
            ui.postDelayed(this, 1000)
        }
    }

    private val latencyTick = object : Runnable {
        override fun run() {
            rdp.measureLatency {}
            ui.postDelayed(this, 5000)
        }
    }

    override fun onCreate(s: Bundle?) {
        applyTheme()
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        val host  = intent.getStringExtra("host")  ?: "192.168.1.1"
        val port  = intent.getStringExtra("port")?.toIntOrNull() ?: 8000
        val token = intent.getStringExtra("token") ?: "rdppro-secret-2024"
        permLevel = intent.getIntExtra("perm", 0)

        rdp = RdpService(host, port, token)

        // Header
        val tvAddr    = findViewById<TextView>(R.id.tvAddr)
        val tvStatus  = findViewById<TextView>(R.id.tvStatus)
        val tvPerm    = findViewById<TextView>(R.id.tvPermLevel)

        tvAddr.text = "$host:$port"
        tvPerm.text = permLevelLabel()
        tvPerm.setTextColor(permLevelColor())

        rdp.onConnected   = { runOnUiThread { tvStatus.text = "● متصل";   tvStatus.setTextColor(0xFF10B981.toInt()) } }
        rdp.onDisconnect  = { runOnUiThread { tvStatus.text = "● منقطع";  tvStatus.setTextColor(0xFFEF4444.toInt()) } }

        rdp.connectScreen()
        rdp.connectControl()

        // Fetch screen size
        ui.postDelayed({
            rdp.get("/api/screen/size") { j ->
                if (j != null) { rdp.screenW = j.optInt("width",1920); rdp.screenH = j.optInt("height",1080) }
            }
        }, 1500)

        // Bottom nav
        val nav = findViewById<BottomNavigationView>(R.id.bottomNav)
        nav.setOnItemSelectedListener { item ->
            switchFrag(when (item.itemId) {
                R.id.nav_dash    -> dashFrag
                R.id.nav_screen  -> screenFrag
                R.id.nav_files   -> filesFrag
                R.id.nav_control -> controlFrag
                R.id.nav_more    -> moreFrag
                else             -> dashFrag
            })
            true
        }

        // Apply permission restrictions to nav
        applyPermissionToNav(nav)

        // Smart Command Bar — swipe gesture on header
        setupSmartCommandBar()

        // Theme / Lang buttons
        findViewById<View>(R.id.btnToggleTheme)?.setOnClickListener { toggleTheme() }
        findViewById<View>(R.id.btnToggleLang)?.setOnClickListener  { toggleLanguage() }

        switchFrag(dashFrag)
        ui.post(statsTick)
        ui.post(latencyTick)
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private fun permLevelLabel() = when (permLevel) {
        0 -> "⚡ كامل";  1 -> "👁 عرض فقط"
        2 -> "📁 ملفات"; 3 -> "💻 تيرمنال"
        else -> "كامل"
    }

    private fun permLevelColor() = when (permLevel) {
        0 -> 0xFF10B981.toInt(); 1 -> 0xFF3B82F6.toInt()
        2 -> 0xFFF59E0B.toInt(); 3 -> 0xFFA78BFA.toInt()
        else -> 0xFF10B981.toInt()
    }

    private fun applyPermissionToNav(nav: BottomNavigationView) {
        // View-only: disable control nav
        if (permLevel == 1) {
            nav.menu.findItem(R.id.nav_control)?.isEnabled = false
        }
        // Files-only: only show dash + files
        if (permLevel == 2) {
            nav.menu.findItem(R.id.nav_screen)?.isEnabled  = false
            nav.menu.findItem(R.id.nav_control)?.isEnabled = false
        }
    }

    fun isActionAllowed(action: String): Boolean {
        return when (permLevel) {
            1 -> action in listOf("view", "screenshot")            // view-only
            2 -> action in listOf("view", "files", "upload")       // files-only
            3 -> action in listOf("view", "terminal")              // terminal-only
            else -> true                                            // full access
        }
    }

    // ── Smart Command Bar ─────────────────────────────────────────────────────
    private fun setupSmartCommandBar() {
        val cmdBar  = findViewById<View>(R.id.smartCmdBar) ?: return
        val etQuery = cmdBar.findViewById<EditText>(R.id.etCmdQuery)

        // Swipe‑down on header triggers the bar
        val header = findViewById<View>(R.id.headerBar) ?: return
        var downY   = 0f
        header.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_UP   -> {
                    val dy = e.rawY - downY
                    if (dy > 60) toggleCmdBar(cmdBar)
                    else if (dy < -60 && cmdBarVisible) toggleCmdBar(cmdBar)
                }
            }
            false
        }

        // Quick command chips in bar
        setupCmdBarChips(cmdBar)

        // Type and run
        etQuery?.setOnEditorActionListener { tv, _, _ ->
            runSmartCmd(tv.text.toString().trim())
            tv.setText("")
            toggleCmdBar(cmdBar)
            true
        }
    }

    private fun toggleCmdBar(bar: View) {
        cmdBarVisible = !cmdBarVisible
        bar.visibility = if (cmdBarVisible) View.VISIBLE else View.GONE
        if (cmdBarVisible) bar.findViewById<View>(R.id.etCmdQuery)?.requestFocus()
    }

    private fun setupCmdBarChips(bar: View) {
        data class Chip(val id: Int, val label: String, val cmd: String)
        listOf(
            Chip(R.id.chip_lock,      "🔒 قفل",     "lock"),
            Chip(R.id.chip_sleep,     "💤 سكون",    "sleep"),
            Chip(R.id.chip_screenshot,"📷 صورة",    "screenshot"),
            Chip(R.id.chip_explorer,  "🗂 Explorer", "explorer"),
            Chip(R.id.chip_task,      "📊 مهام",    "task_manager"),
            Chip(R.id.chip_ps,        "💻 PS",      "powershell"),
        ).forEach { chip ->
            bar.findViewById<View>(chip.id)?.setOnClickListener {
                runSmartCmd(chip.cmd)
                toggleCmdBar(bar)
            }
        }
    }

    private fun runSmartCmd(cmd: String) {
        when {
            cmd == "screenshot" -> {
                rdp.get("/api/screen/snapshot") { j ->
                    runOnUiThread {
                        Toast.makeText(this, if (j != null) "✓ تم التقاط الشاشة" else "✗ فشل", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            cmd.startsWith("cd ") || cmd.startsWith("dir") || cmd.startsWith("ls") -> {
                rdp.post("/api/terminal/exec", org.json.JSONObject().put("cmd", cmd)) { j ->
                    val out = j?.optString("output","")?.take(200) ?: "فشل"
                    runOnUiThread { showSnackbar(out) }
                }
            }
            else -> {
                rdp.post("/api/system/$cmd") { j ->
                    runOnUiThread {
                        Toast.makeText(this, if (j?.optBoolean("ok")==true) "✓ $cmd" else "✗ فشل", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun showSnackbar(msg: String, color: Int = 0xFF1E293B.toInt()) {
        val root = findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(root, msg, Snackbar.LENGTH_SHORT).also {
            it.view.setBackgroundColor(color)
        }.show()
    }

    // ── Dark/Light Theme ──────────────────────────────────────────────────────
    private fun applyTheme() {
        val prefs = getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)
        val dark  = prefs.getBoolean("dark_theme", true)
        if (!dark) setTheme(R.style.Theme_RDPPro_Light)
    }

    private fun toggleTheme() {
        val prefs = getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)
        val dark  = prefs.getBoolean("dark_theme", true)
        prefs.edit().putBoolean("dark_theme", !dark).apply()
        recreate()  // restart activity with new theme
    }

    // ── Language ──────────────────────────────────────────────────────────────
    private fun toggleLanguage() {
        val prefs = getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)
        val ar    = prefs.getString("lang", "ar") == "ar"
        val newLang = if (ar) "en" else "ar"
        prefs.edit().putString("lang", newLang).apply()
        // Restart with new locale
        val locale = java.util.Locale(newLang)
        java.util.Locale.setDefault(locale)
        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
        recreate()
    }

    // ── Fragment switching ────────────────────────────────────────────────────
    private fun switchFrag(frag: Fragment) {
        if (frag == currentFrag && frag.isAdded) return
        val tx = supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
        if (!frag.isAdded) tx.add(R.id.fragmentContainer, frag)
        tx.hide(currentFrag).show(frag).commit()
        currentFrag = frag
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(statsTick)
        ui.removeCallbacks(latencyTick)
        rdp.dispose()
    }
}
