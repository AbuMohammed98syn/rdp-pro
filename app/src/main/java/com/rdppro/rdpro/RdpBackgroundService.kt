package com.rdppro.rdpro

import android.app.*
import android.content.*
import android.graphics.Color
import android.os.*
import androidx.core.app.NotificationCompat
import org.json.JSONObject

/**
 * RdpBackgroundService v5.0
 * ─────────────────────────────────────────────
 * • Foreground Service يبقي الاتصال نشطاً خلفياً
 * • Notification مستمرة مع حالة + CPU + FPS
 * • Pending Intent للعودة للتطبيق
 * • إجراءات من الإشعار: قطع / قفل شاشة
 * • Smart Alert: إشعار منفصل عند CPU > 80%
 */
class RdpBackgroundService : Service() {

    companion object {
        const val CHANNEL_ID      = "rdp_bg_channel"
        const val NOTIF_ID        = 1001
        const val ALERT_NOTIF_ID  = 1002
        const val ACTION_DISCONNECT = "com.rdppro.rdpro.DISCONNECT"
        const val ACTION_LOCK       = "com.rdppro.rdpro.LOCK"
        const val ACTION_SCREENSHOT = "com.rdppro.rdpro.SCREENSHOT"

        // Start / stop helpers
        fun start(ctx: Context, host: String, port: Int, token: String, https: Boolean = false) {
            val intent = Intent(ctx, RdpBackgroundService::class.java).apply {
                putExtra("host",  host)
                putExtra("port",  port)
                putExtra("token", token)
                putExtra("https", https)
            }
            ctx.startForegroundService(intent)
        }
        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RdpBackgroundService::class.java))
        }
    }

    private var rdp:  RdpService? = null
    private val ui    = Handler(Looper.getMainLooper())
    private val stats = object : Runnable {
        override fun run() { pollStats(); ui.postDelayed(this, 5000) }
    }

    private var lastCpu = 0; private var lastFps = 0; private var lastMs = 0L
    private var host = ""; private var port = 8000; private var token = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        host  = intent?.getStringExtra("host")  ?: ""
        port  = intent?.getIntExtra("port", 8000)  ?: 8000
        token = intent?.getStringExtra("token") ?: ""
        val https = intent?.getBooleanExtra("https", false) ?: false

        // Start foreground immediately
        startForeground(NOTIF_ID, buildNotification("جاري الاتصال..."))

        // Create rdp service
        rdp = RdpService(host, port, token, https).also { r ->
            r.onConnected  = { ui.post { updateNotif("● متصل") } }
            r.onDisconnect = { ui.post { updateNotif("● منقطع — إعادة الاتصال...") } }
            r.onNotify     = { j -> handleServerNotify(j) }
            r.connectScreen()
            r.connectControl()
            r.connectNotifications()
        }

        ui.post(stats)
        return START_STICKY  // restart if killed by system
    }

    // ── Stats polling ─────────────────────────────────────────────────────────
    private fun pollStats() {
        rdp?.get("/api/system/stats") { j ->
            if (j != null) {
                lastCpu = j.optDouble("cpu_percent",0.0).toInt()
                val ram = j.optDouble("memory_percent",0.0).toInt()
                val msg = "CPU $lastCpu%  RAM $ram%  ${lastFps}fps  ${lastMs}ms"
                ui.post { updateNotif(msg) }

                // Smart alert: CPU > 80%
                if (lastCpu > 80) sendCpuAlert(lastCpu)
            }
        }
        rdp?.measureLatency { ms -> lastMs = ms }
    }

    private fun sendCpuAlert(cpu: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, "rdp_alert_channel")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("⚠️ RDP Pro — تحذير")
            .setContentText("CPU مرتفع على ${host}: $cpu%")
            .setColor(Color.RED)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(ALERT_NOTIF_ID, notif)
    }

    private fun handleServerNotify(j: JSONObject) {
        val type = j.optString("type","")
        val msg  = j.optString("message","")
        if (type == "alert" && msg.isNotEmpty()) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(this, "rdp_alert_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🔔 RDP Pro")
                .setContentText(msg)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notif)
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────
    private fun buildNotification(status: String): Notification {
        // Open app intent
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, ConnectActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Disconnect action
        val disconnectIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Lock action
        val lockIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent(ACTION_LOCK),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("🖥️ RDP Pro — $host:$port")
            .setContentText(status)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setSilent(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "قطع", disconnectIntent)
            .addAction(android.R.drawable.ic_lock_lock, "🔒 قفل", lockIntent)
            .setColor(0xFF3B82F6.toInt())
            .build()
    }

    private fun updateNotif(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(status))
    }

    // ── Broadcast receiver for notification actions ────────────────────────────
    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DISCONNECT -> { rdp?.dispose(); stopSelf() }
                ACTION_LOCK       -> rdp?.post("/api/system/lock")
                ACTION_SCREENSHOT -> rdp?.get("/api/screen/snapshot") { _ -> }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        ui.removeCallbacks(stats)
        rdp?.dispose()
        runCatching { unregisterReceiver(actionReceiver) }
        super.onDestroy()
    }

    // ── Notification channels ─────────────────────────────────────────────────
    private fun createNotificationChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        // Main channel
        NotificationChannel(CHANNEL_ID, "RDP Pro — اتصال نشط",
            NotificationManager.IMPORTANCE_LOW).also {
            it.description = "إشعار دائم أثناء الاتصال بالحاسوب"
            it.setShowBadge(false)
            nm.createNotificationChannel(it)
        }

        // Alert channel
        NotificationChannel("rdp_alert_channel", "RDP Pro — تنبيهات",
            NotificationManager.IMPORTANCE_HIGH).also {
            it.description = "تنبيهات CPU والأحداث المهمة"
            it.enableLights(true); it.lightColor = Color.RED
            nm.createNotificationChannel(it)
        }

        // Register broadcast receiver
        registerReceiver(actionReceiver, android.content.IntentFilter().apply {
            addAction(ACTION_DISCONNECT)
            addAction(ACTION_LOCK)
            addAction(ACTION_SCREENSHOT)
        }, RECEIVER_NOT_EXPORTED)
    }
}
