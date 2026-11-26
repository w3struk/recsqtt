package com.csqtt.client

/**
 * Единый текст ожидания при потере физической сети. Используется панелью,
 * уведомлением и журналом: причина паузы отличает авиарежим от обычного оффлайна.
 */
internal fun networkPauseStatusText(reason: PhysicalNetworkPauseReason): String = when (reason) {
    PhysicalNetworkPauseReason.AIRPLANE_MODE -> "Ожидание. Режим полёта"
    PhysicalNetworkPauseReason.OFFLINE -> "Ожидание. Сеть отключена"
}
