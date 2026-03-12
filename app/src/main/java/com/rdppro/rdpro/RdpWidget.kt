package com.rdppro.rdpro

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * RdpWidget — Home Screen Widget
 * يعرض: حالة الاتصال + أزرار سريعة (اتصال / قفل) + إحصائيات
 */
class RdpWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> updateWidget(ctx, mgr, id) }
    }

    companion object {
        fun updateWidget(ctx: Context, mgr: AppWidgetManager, id: Int) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_rdp)

            // Connect button → opens ConnectActivity
            val connectIntent = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, ConnectActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetBtnConnect, connectIntent)

            // Load last known stats from prefs
            val prefs = ctx.getSharedPreferences("rdp_v4", Context.MODE_PRIVATE)
            val ip    = prefs.getString("ip","?") ?: "?"
            val fps   = prefs.getInt("last_fps", 0)
            val ms    = prefs.getLong("last_latency", 0)
            val cpu   = prefs.getInt("last_cpu", 0)

            views.setTextViewText(R.id.widgetStats, "${fps}fps  ${ms}ms  CPU ${cpu}%")
            views.setTextViewText(R.id.widgetStatus,
                if (prefs.getBoolean("connected", false)) "● متصل" else "● غير متصل"
            )
            views.setTextColor(R.id.widgetStatus,
                if (prefs.getBoolean("connected", false)) 0xFF10B981.toInt() else 0xFFEF4444.toInt()
            )

            mgr.updateAppWidget(id, views)
        }
    }
}
