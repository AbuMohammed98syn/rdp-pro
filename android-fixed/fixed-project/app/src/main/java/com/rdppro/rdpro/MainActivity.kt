package com.rdppro.rdpro

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

/**
 * MainActivity v3.1
 * Bottom Navigation 5 أقسام + header بار + auto-reconnect
 */
class MainActivity : AppCompatActivity() {

    lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())

    private val dashFrag    by lazy { DashboardFragment() }
    private val screenFrag  by lazy { ScreenFragment() }
    private val filesFrag   by lazy { FilesFragment() }
    private val controlFrag by lazy { ControlFragment() }
    private val moreFrag    by lazy { MoreFragment() }

    private var currentFrag: Fragment = dashFrag

    private val statsTick = object : Runnable {
        override fun run() {
            val tv = findViewById<TextView>(R.id.tvFps) ?: return
            tv.text = "${rdp.fps}fps  ${rdp.latencyMs}ms"
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
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        val host  = intent.getStringExtra("host")  ?: "192.168.1.1"
        val port  = intent.getStringExtra("port")?.toIntOrNull() ?: 8000
        val token = intent.getStringExtra("token") ?: "rdppro-secret-2024"

        rdp = RdpService(host, port, token)

        val tvAddr   = findViewById<TextView>(R.id.tvAddr)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        tvAddr.text  = "$host:$port"

        rdp.onConnected = {
            runOnUiThread {
                tvStatus.text = "● متصل"
                tvStatus.setTextColor(0xFF10B981.toInt())
            }
        }
        rdp.onDisconnect = {
            runOnUiThread {
                tvStatus.text = "● منقطع"
                tvStatus.setTextColor(0xFFEF4444.toInt())
            }
        }

        rdp.connectScreen()
        rdp.connectControl()

        // جلب معلومات الشاشة بعد ثانية
        ui.postDelayed({
            rdp.get("/api/screen/size") { j ->
                if (j != null) {
                    rdp.screenW = j.optInt("width",  1920)
                    rdp.screenH = j.optInt("height", 1080)
                }
            }
        }, 1500)

        // Bottom Navigation
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

        switchFrag(dashFrag)
        ui.post(statsTick)
        ui.post(latencyTick)
    }

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
