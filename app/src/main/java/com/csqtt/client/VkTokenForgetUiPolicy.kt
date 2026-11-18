package com.csqtt.client

/**
 * «Забыть токен» — тестовый/сервисный рычаг, а не пользовательская функция:
 * стандартный сценарий закрывает доступ импортом новой ссылки, очисткой данных
 * или uninstall. В release карточка показывает только статус доступа.
 */
internal object VkTokenForgetUiPolicy {
    fun isVisible(debug: Boolean): Boolean = debug
}
