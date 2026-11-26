package com.csqtt.client

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.Toast
import kotlinx.coroutines.flow.first

internal object QuickTunnelAction {
    const val START = "START"
    const val STOP = "STOP"
}

internal suspend fun buildQuickStartIntent(context: Context): Intent? {
    val store = SettingsStore(context.applicationContext)
    val source = resolveConnectionSource(store) ?: return null
    return Intent(context, TunnelService::class.java).apply {
        action = QuickTunnelAction.START
        putExtra("peer", source.peer)
        putExtra("vk_hashes", source.hashes)
        putExtra("vk_hashes_from_link", source.hashesFromLink)
        putExtra("workers_per_hash", store.profile.workersPerHash.first())
        putExtra("port", 0)
        putExtra("sni", store.profile.sni.first())
        putExtra("connection_password", source.password)
        putExtra("protocol", store.profile.protocol.first())
        putExtra("vk_auth_mode", store.profile.vkAuthMode.first())
        putExtra("captcha_mode", store.profile.captchaMode.first())
        putExtra("captcha_solve_method", store.profile.captchaSolveMethod.first())
        putExtra("fingerprint", store.profile.selectedFingerprint.first())
        putExtra("obfs_mode", store.profile.obfsMode.first())
        putExtra("turn_transport", store.profile.turnTransport.first())
        putExtra("custom_turn_ips", store.profile.customTurnIps.first())
    }
}

internal suspend fun toggleTunnelFromQuickAction(context: Context, openMain: () -> Unit) {
    if (TunnelManager.stopping.value) return
    if (tunnelPowerIsOn(TunnelManager.running.value, TunnelManager.starting.value, TunnelManager.autoPausedForWifi.value)) {
        context.startService(Intent(context, TunnelService::class.java).apply { action = QuickTunnelAction.STOP })
        return
    }
    // Та же защёлка, что у логотипа на «Подключении»: тап по плитке сразу после
    // остановки не поднимает сессию заново. Стоит после ветки STOP — выключение
    // в этом окне обязано срабатывать.
    if (TunnelManager.startCooldownActive()) return
    if (VpnService.prepare(context) != null) {
        context.showRaisedToast("Откройте reCSQTT и выдайте VPN-разрешение", Toast.LENGTH_LONG)
        openMain()
        return
    }
    val startIntent = buildQuickStartIntent(context)
    if (startIntent == null) {
        context.showRaisedToast("Заполните настройки подключения в reCSQTT", Toast.LENGTH_LONG)
        openMain()
        return
    }
    context.startForegroundService(startIntent)
}
