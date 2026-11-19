package com.csqtt.client

/**
 * Внутренние детали доступа к VK не показываем обычному пользователю: он
 * импортирует ссылку панели и не управляет токенами. Статус кредов и ручное
 * забывание — инструменты отладки, поэтому видны только в debug-сборке.
 */
internal object VkCredentialUiPolicy {
    fun statusVisible(debug: Boolean): Boolean = debug

    fun forgetTokenVisible(debug: Boolean): Boolean = debug
}
