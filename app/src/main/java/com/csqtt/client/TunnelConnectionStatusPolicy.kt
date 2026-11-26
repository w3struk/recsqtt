package com.csqtt.client

internal enum class TunnelConnectionStatus {
    OFF,
    STOPPING,
    WAITING_WIFI,
    WAITING_NETWORK,
    CONNECTED,
    NO_CONNECTION,
    CONNECTING,
}

/**
 * Снимок для плитки и виджета: питание процесса отдельно от статуса соединения,
 * чтобы «Подключено» не появлялось раньше первого READY.
 */
internal data class TunnelConnectionUiState(
    val powerOn: Boolean,
    val status: TunnelConnectionStatus,
)

/**
 * Статус панели и уведомления. `running` — процесс запущен, `connected` — в текущем
 * процессе пришёл `READY`. Ожидания сети перекрывают всё, кроме пустых профилей и
 * остановки: в паузе процесс не работает, хотя панель должна объяснить ожидание.
 */
internal fun tunnelConnectionStatus(
    emptyProfiles: Boolean,
    stopping: Boolean,
    wifiPaused: Boolean,
    networkPauseReason: PhysicalNetworkPauseReason?,
    connected: Boolean,
    stalled: Boolean,
    starting: Boolean,
    running: Boolean,
): TunnelConnectionStatus = when {
    emptyProfiles -> TunnelConnectionStatus.OFF
    stopping -> TunnelConnectionStatus.STOPPING
    wifiPaused -> TunnelConnectionStatus.WAITING_WIFI
    networkPauseReason != null -> TunnelConnectionStatus.WAITING_NETWORK
    connected -> TunnelConnectionStatus.CONNECTED
    stalled -> TunnelConnectionStatus.NO_CONNECTION
    starting || running -> TunnelConnectionStatus.CONNECTING
    else -> TunnelConnectionStatus.OFF
}

internal fun tunnelConnectionStatusText(
    status: TunnelConnectionStatus,
    networkPauseReason: PhysicalNetworkPauseReason?,
): String = when (status) {
    TunnelConnectionStatus.OFF -> "Отключено"
    TunnelConnectionStatus.STOPPING -> "Остановка"
    TunnelConnectionStatus.WAITING_WIFI -> "Ожидание. Автопауза при Wi-Fi"
    TunnelConnectionStatus.WAITING_NETWORK ->
        networkPauseReason?.let(::networkPauseStatusText) ?: "Ожидание. Сеть отключена"
    TunnelConnectionStatus.CONNECTED -> "Подключено"
    TunnelConnectionStatus.NO_CONNECTION -> "Нет соединения"
    TunnelConnectionStatus.CONNECTING -> "Подключение"
}

/**
 * Короткий текст для плитки в шторке и виджета: полные формулировки панели там
 * не помещаются, но состояние обязано совпадать.
 */
internal fun tunnelConnectionShortStatusText(status: TunnelConnectionStatus): String = when (status) {
    TunnelConnectionStatus.OFF -> "Отключено"
    TunnelConnectionStatus.STOPPING -> "Остановка"
    TunnelConnectionStatus.WAITING_WIFI -> "Ожидание Wi-Fi"
    TunnelConnectionStatus.WAITING_NETWORK -> "Ожидание сети"
    TunnelConnectionStatus.CONNECTED -> "Подключено"
    TunnelConnectionStatus.NO_CONNECTION -> "Нет соединения"
    TunnelConnectionStatus.CONNECTING -> "Подключение"
}

/**
 * Таймер — длительность соединения, поэтому до первого `READY` его нет. Замороженное
 * значение показывается в ожидании и на остановке, если сессия успела начаться.
 */
internal fun showsSessionTimer(status: TunnelConnectionStatus, uptimeSeconds: Long?): Boolean =
    uptimeSeconds != null && when (status) {
        TunnelConnectionStatus.CONNECTED,
        TunnelConnectionStatus.WAITING_WIFI,
        TunnelConnectionStatus.WAITING_NETWORK,
        TunnelConnectionStatus.STOPPING,
        -> true
        else -> false
    }
