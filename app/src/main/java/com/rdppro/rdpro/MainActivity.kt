package com.rdppro.rdpro

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class MainActivity : AppCompatActivity() {

    lateinit var rdp: RdpService
    private val ui = Handler(Looper.getMainLooper())
    private val statsTick = object : Runnable {
        override fun run() {
            findViewById<TextView>(R.id.tvFps)?.text = "${rdp.fps}fps ${rdp.latencyMs}ms"
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)

        val host = intent.getStringExtra("host") ?: "192.168.1.1"
        val port = intent.getStringExtra("port")?.toIntOrNull() ?: 8000
        rdp = RdpService(host, port)

        findViewById<TextView>(R.id.tvAddr).text = "$host:$port"
        val tvStatus = findViewById<TextView>(R.id.tvStatus)

        rdp.onConnected  = { tvStatus.text = "● متصل";   tvStatus.setTextColor(0xFF10B981.toInt()) }
        rdp.onDisconnect = { tvStatus.text = "● منقطع";  tvStatus.setTextColor(0xFFEF4444.toInt()) }

        rdp.connectScreen()
        rdp.connectControl()

        ui.postDelayed({
            rdp.get("/api/screen/size?monitor=1") { j ->
                if (j != null) {
                    rdp.screenW = j.optInt("width", 1920)
                    rdp.screenH = j.optInt("height", 1080)
                }
            }
        }, 1500)

        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.offscreenPageLimit = 6
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 7
            override fun createFragment(pos: Int): Fragment = when (pos) {
                0 -> ScreenFragment()
                1 -> MouseFragment()
                2 -> KeyboardFragment()
                3 -> SystemFragment()
                4 -> FilesFragment()
                5 -> TerminalFragment()
                else -> CameraFragment()
            }
        }

        val tabs  = listOf("شاشة","ماوس","كيبورد","نظام","ملفات","Terminal","كاميرا")
        TabLayoutMediator(findViewById(R.id.tabLayout), pager) { tab, i ->
            tab.text = tabs[i]
        }.attach()

        ui.post(statsTick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(statsTick)
        rdp.dispose()
    }
}
