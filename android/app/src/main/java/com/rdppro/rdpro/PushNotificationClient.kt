package com.rdppro.rdpro

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.*
import org.json.JSONObject

/**
 * PushNotificationClient v3.1
 * يتصل بـ /ws/notify ويعرض الإشعارات على الهاتف
 */
class PushNotificationClient(
    private val context: Context,
    private val rdp: RdpService
) {
    companion object {
        private const val CHANNEL_ID   = "rdp_pro_channel"
        private const val CHANNEL_NAME = "RDP Pro Alerts"
        private var notifId            = 1
    }

    private val ui      = Handler(Looper.getMainLooper())
    private var ws: WebSocket? = null
    private var active = false

    private val client = okhttp3.OkHttpClient.Builder()
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    init {
        createChannel()
    }

    fun start() {
        if (active) return
        active = true
        val req = Request.Builder()
            .url("ws://${rdp.host}:${rdp.port}/ws/notify?token=${rdp.token}")
            .build()

        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                try {
                    val j     = JSONObject(text)
                    val title = j.optString("title","RDP Pro")
                    val body  = j.optString("body","")
                    val icon  = j.optString("icon","info")
                    ui.post { showNotification(title, body, icon) }
                } catch (_: Exception) {}
            }
            override fun onFailure(ws: WebSocket, t: Throwable, r: Response?) {
                if (active) {
                    ui.postDelayed({ reconnect() }, 5000)
                }
            }
        })
    }

    private fun reconnect() {
        ws?.cancel()
        ws = null
        if (active) start()
    }

    fun stop() {
        active = false
        ws?.close(1000, "stop")
        ws = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "إشعارات RDP Pro من الحاسوب"
                enableVibration(true)
            }
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE)
                      as NotificationManager
            mgr.createNotificationChannel(channel)
        }
    }

    private fun showNotification(title: String, body: String, icon: String) {
        val intent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val iconRes = when (icon) {
            "file"    -> android.R.drawable.ic_menu_save
            "warning" -> android.R.drawable.ic_dialog_alert
            "error"   -> android.R.drawable.ic_dialog_alert
            else      -> android.R.drawable.ic_dialog_info
        }

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(notifId++, notif)
    }
}
