package com.csqtt.client

import com.csqtt.client.ui.utils.formatCsqttLink
import com.csqtt.client.ui.utils.parseCsqttLink

/**
 * Решение о судьбе ссылки `csqtt://` при импорте.
 *
 * Ссылка из панели — транспорт кредов, а не место их хранения: всё, что пришло
 * в `token=`/`user_id=`, уходит в хранилище, а сама ссылка переписывается без
 * этих параметров. Тогда «Забыть токен» окончателен, а второго источника истины
 * не остаётся. Ссылка без токена — осознанная просьба очистить сохранённый
 * доступ, поэтому она сохраняется как есть.
 */
internal object VkLinkImportPolicy {

    data class Decision(
        val token: String,
        val userId: String,
        val sanitizedLink: String,
        val wipesStoredCredentials: Boolean,
    )

    /** Невалидная ссылка — `null`; отсутствие токена — решение об очистке кредов. */
    fun decide(raw: String): Decision? {
        val link = parseCsqttLink(raw) ?: return null
        if (link.vkAccessToken.isEmpty()) {
            return Decision(
                token = "",
                userId = "",
                sanitizedLink = raw.trim(),
                wipesStoredCredentials = true,
            )
        }
        return Decision(
            token = link.vkAccessToken,
            userId = link.userId,
            sanitizedLink = formatCsqttLink(
                host = link.host,
                peer = link.port,
                password = link.password,
                hashes = link.hashes,
                token = "",
                userId = "",
            ),
            wipesStoredCredentials = false,
        )
    }

    /**
     * Миграция уже сохранённой ссылки: срабатывает только пока в ней есть `token=`,
     * поэтому повторный прогон — no-op. Битая ссылка не трогается.
     */
    fun migration(storedLink: String): Decision? =
        decide(storedLink)?.takeIf { it.token.isNotEmpty() }
}
