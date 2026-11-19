package com.csqtt.client

/**
 * Единственное условие, при котором авто-режимы не могут стартовать: в хранилище нет
 * VK-токена. После санитайзера ссылка токена не несёт, поэтому панель — единственный
 * источник кредов, и без импорта новой ссылки «Авто API»/«Авто ВК» работать нечему.
 *
 * Функция чистая и общая для сервиса и экрана настроек: текст причины обязан совпадать,
 * иначе пользователь видит одно сообщение при выборе режима и другое при запуске.
 */
internal object VkCredentialStartPolicy {

    const val TOKEN_FROM_PANEL_MESSAGE =
        "Этот режим доступен со свежей ссылкой из панели: импортируйте её"

    /**
     * `null` — запуск не блокируется этим правилом. Ручной режим проверяется отдельно
     * (`ConnectionStartPolicy`): отсутствие хешей — это другой блокер с другим текстом.
     * Ручные хеши не «разрешают» авто-режим: звонки печатаются только с токеном.
     */
    fun blockedReason(
        hashMode: String,
        authMode: String,
        hasToken: Boolean,
    ): String? {
        val autoRequested = hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_API ||
            hashMode == CsqttConstants.VkAutoHash.MODE_AUTO_JS ||
            authMode == CsqttConstants.VkAuth.MODE_AUTO_JS
        if (!autoRequested || hasToken) return null
        return TOKEN_FROM_PANEL_MESSAGE
    }

    /**
     * Маска `user_id` для карточки: показываем только хвост, потому что это
     * идентификатор аккаунта, а не секрет, но и целиком он пользователю не нужен.
     */
    fun maskedUserId(userId: String): String {
        val trimmed = userId.trim()
        if (trimmed.isEmpty()) return ""
        return "••••" + trimmed.takeLast(4)
    }
}
