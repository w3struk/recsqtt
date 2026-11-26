package com.csqtt.client

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

class VpnWidgetProvider : AppWidgetProvider() {

    companion object {
        fun updateAllWidgets(context: Context) {
            runCatching {
                val appWidgetManager = AppWidgetManager.getInstance(context)
                val thisWidget = ComponentName(context, VpnWidgetProvider::class.java)
                val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)
                if (appWidgetIds.isNotEmpty()) {
                    val intent = Intent(context, VpnWidgetProvider::class.java).apply {
                        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, appWidgetIds)
                    }
                    context.sendBroadcast(intent)
                }
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = TunnelManager.connectionUiState.value
        for (appWidgetId in appWidgetIds) {
            updateWidgetState(context, appWidgetManager, appWidgetId, state)
        }
    }

    private fun statusColor(status: TunnelConnectionStatus): Int = when (status) {
        TunnelConnectionStatus.CONNECTED -> 0xFF00E5FF.toInt()
        TunnelConnectionStatus.CONNECTING,
        TunnelConnectionStatus.WAITING_WIFI,
        TunnelConnectionStatus.WAITING_NETWORK,
        -> 0xFFFFB300.toInt()
        TunnelConnectionStatus.NO_CONNECTION -> 0xFFF87171.toInt()
        TunnelConnectionStatus.STOPPING,
        TunnelConnectionStatus.OFF,
        -> 0xFF888888.toInt()
    }

    private fun updateWidgetState(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        state: TunnelConnectionUiState
    ) {
        val views = RemoteViews(context.packageName, R.layout.vpn_widget)

        views.setTextViewText(R.id.widget_status, tunnelConnectionShortStatusText(state.status))
        views.setTextColor(R.id.widget_status, statusColor(state.status))
        views.setInt(
            R.id.widget_toggle_btn,
            "setBackgroundResource",
            if (state.powerOn) R.drawable.bg_widget_button_active else R.drawable.bg_widget_button_inactive
        )

        val openIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val openPendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_container, openPendingIntent)

        val toggleIntent = Intent(context, VpnWidgetToggleReceiver::class.java).apply {
            action = VpnWidgetToggleReceiver.ACTION
        }
        val togglePendingIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId + 1000,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_toggle_btn, togglePendingIntent)

        appWidgetManager.updateAppWidget(appWidgetId, views)
    }
}
