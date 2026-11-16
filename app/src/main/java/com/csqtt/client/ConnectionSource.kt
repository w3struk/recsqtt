package com.csqtt.client

import com.csqtt.client.ui.utils.parseCsqttLink
import com.csqtt.client.ui.utils.peerAddress
import kotlinx.coroutines.flow.first

data class ConnectionSource(
    val peer: String,
    val password: String,
    val hashes: String,
    val hashesFromLink: Boolean,
    val vkAccessToken: String,
    val tokenFromLink: Boolean,
)

/**
 * Единственный источник подключения — ссылка `csqtt://`. Выходного профиля больше нет:
 * адрес, порт и пароль приходят только из ссылки, а `hashes` — сохранённые в профиле
 * VK-хеши, которые используются, если ссылка их не несёт.
 *
 * Функция чистая и не читает `SettingsStore`, поэтому её можно проверить на JVM.
 */
internal fun resolveConnectionSourceFrom(
    link: String?,
    hashes: String?,
    invalidHashes: Map<String, VkHashValidationStatus> = emptyMap(),
): ConnectionSource? {
    val parsed = link?.let(::parseCsqttLink) ?: return null
    val tokenFromLink = parsed.vkAccessToken.isNotEmpty()
    fun activeHashes(raw: String): String {
        val rawList = raw
            .split(Regex("[,\\s\\n]+"))
            .map(String::trim)
            .filter { it.length >= 16 }
            .distinct()
        // Фолбэка на исходный список нет осознанно: хеш, помеченный `Invalid`
        // после `CALL_UNAVAILABLE`, иначе снова уходил бы в подключение и снова
        // требовал капчу у VK, хотя звонок уже мёртв. Пустой результат доходит до
        // `ConnectionStartPolicy` и превращается в понятный блокер запуска.
        return VkHashValidationCodec.active(rawList, invalidHashes).joinToString(",")
    }
    val linkHashes = activeHashes(parsed.hashes.joinToString(","))
    return ConnectionSource(
        peer = parsed.peerAddress(),
        password = parsed.password,
        hashes = if (tokenFromLink) "" else linkHashes.ifEmpty { activeHashes(hashes.orEmpty()) },
        hashesFromLink = linkHashes.isNotEmpty(),
        vkAccessToken = parsed.vkAccessToken,
        tokenFromLink = tokenFromLink,
    )
}

suspend fun resolveConnectionSource(store: SettingsStore): ConnectionSource? =
    resolveConnectionSourceFrom(
        link = store.csqttLink.first(),
        hashes = store.vkHashes.first(),
        invalidHashes = VkHashValidationCodec.decode(store.vkHashCheckResults.first()),
    )
