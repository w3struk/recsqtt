package com.csqtt.client

/**
 * Ручное закрытие звонков — инструмент тестировщика, а не пользователя: обычный
 * сценарий закрывает звонки сам (или «Забыть токен» в редакторе профиля).
 * Поэтому действие и его подтверждение рендерятся только в debug-сборке.
 */
internal object VkCallCloseUiPolicy {
    fun closeCallsVisible(debug: Boolean): Boolean = debug
}
