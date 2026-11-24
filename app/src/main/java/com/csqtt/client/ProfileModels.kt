package com.csqtt.client

/**
 * Снимок участия профиля в VK-авторизации.
 *
 * [NOT_LOADED_PROFILE] — «ещё не прочитано». Любое прочитанное значение, включая
 * [ProfileRegistryPolicy.NO_PROFILE] (`-1`), означает загруженный снимок; `-1`
 * говорит, что профиля нет, а не что данные не пришли.
 */
internal data class TunnelAuthSnapshot(
    val profile: Int = NOT_LOADED_PROFILE,
    val csqttLink: String = "",
) {
    val isLoaded: Boolean
        get() = profile != NOT_LOADED_PROFILE

    companion object {
        const val NOT_LOADED_PROFILE = Int.MIN_VALUE
    }
}

/**
 * Редактируемые поля профиля в полноэкранном редакторе.
 *
 * Дефолты — состояние нового профиля (Firefox, 10 потоков, UDP, капча `auto`,
 * режимы `vkcalls`/`manual`). Кэш проверки хешей (`vk_hash_check_results`) сюда
 * не входит: сервис пишет его во время работы, и снимок формы не должен его
 * перетирать.
 *
 * `hasVkToken` только для чтения: сам токен в форму не попадает.
 */
data class ProfileDraft(
    val id: Int = -1,
    val name: String = "",
    val link: String = "",
    val vkHashes: String = "",
    val vkAuthMode: String = CsqttConstants.VkAuth.MODE_CALLS,
    val vkHashMode: String = CsqttConstants.VkAutoHash.MODE_MANUAL,
    val captchaMode: String = "auto",
    val captchaSolveMethod: String = "auto",
    val captchaWbvSolveMethod: String = "auto",
    val turnTransport: String = CsqttConstants.Tunnel.DEFAULT_TURN_TRANSPORT,
    val fingerprint: String = CsqttConstants.Tunnel.DEFAULT_FINGERPRINT,
    val selectedTurnSubnet: String = "",
    val turnSubnetIpLimit: String = "ALL",
    val workersPerHash: Int = 10,
    val hasVkToken: Boolean = false,
)

/**
 * Креды, декодированные из внешнего импорта, пока редактор нового профиля
 * не сохранён. Токен не отображается и не переживает отмену.
 */
internal data class ImportedLinkCredentials(
    val token: String,
    val userId: String,
)

/** Строка списка профилей: имя, наличие ссылки и краткая сводка подключения. */
data class ProfileSummary(
    val id: Int,
    val name: String,
    val hasLink: Boolean,
    val summary: String,
    val transport: String = "udp",
    val fingerprint: String = "firefox",
    val workersPerHash: Int = 0,
) {
    /** Сводка с текущим лимитом сервера вместо сохранённого значения. */
    fun summaryWithWorkers(workers: Int): String =
        ProfileRegistryPolicy.summaryText(hasLink, transport, fingerprint, workers)
}

/**
 * Параметры запуска и переподключения туннеля для явного профиля сессии.
 *
 * Токен сюда не входит: сервис читает его отдельно через
 * `ProfileSettingsStore.vkAccessToken(profile)`.
 */
data class ProfilePrepareSnapshot(
    val profile: Int,
    val sni: String,
    val obfsMode: String,
    val turnTransport: String,
    val customTurnIps: String,
    val captchaMode: String,
    val captchaSolveMethod: String,
    val captchaWbvSolveMethod: String,
    val fingerprint: String,
    val clientIds: String,
    val workersPerHash: Int,
    val vkHashMode: String,
    val vkAuthMode: String,
)

/**
 * Ссылка, хеши и кэш проверки хешей **явного** профиля сессии — вход
 * [resolveConnectionSourceFrom]. Отдельно от [ProfilePrepareSnapshot], потому что
 * `vk_hash_check_results` пишется сервисом во время работы и в черновик формы не входит.
 */
internal data class ProfileConnectionSnapshot(
    val link: String,
    val vkHashes: String,
    val vkHashCheckResults: String,
)
