package com.csqtt.client

/**
 * Ручное закрытие звонков — инструмент тестировщика, а не пользователя: обычный
 * сценарий закрывает звонки сам (или «Забыть токен» в настройках). Поэтому кнопка
 * и её подтверждение в `ConnectionTab` рендерятся только в debug-сборке.
 */
internal object VkCallCloseUiPolicy {
    fun isVisible(debug: Boolean): Boolean = debug
}
