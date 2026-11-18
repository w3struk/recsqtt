package com.csqtt.client

import java.security.MessageDigest

/*
 * Долговечность заминченных звонков: что переживает перезапуск и когда ссылку
 * пора забыть. Файл назван отдельно от `VkCallPolicies.kt` намеренно — там уже
 * живут помощники ретраев (`VkRequestPacer`, `runVkCallAttempts`).
 */

internal enum class VkCallLifecycle { KEEP, FINISH, WIPE, DROP_ONE }

/**
 * Что делать с заминченными звонками при каждом событии жизненного цикла.
 *
 * Правило по умолчанию — [VkCallLifecycle.KEEP]: ссылка на звонок переживает сбой,
 * перезапуск и остановку, иначе каждый старт стоит нового `calls.start`.
 * [VkCallLifecycle.WIPE]/[VkCallLifecycle.DROP_ONE] сюда пока не отдаются —
 * они зарезервированы под точечные сценарии вызывающей стороны.
 */
internal object VkCallLifecyclePolicy {
    fun onUnexpectedExit(): VkCallLifecycle = VkCallLifecycle.KEEP

    fun onPlannedRestart(): VkCallLifecycle = VkCallLifecycle.KEEP

    fun onUserStop(): VkCallLifecycle = VkCallLifecycle.KEEP

    fun onServiceDestroy(): VkCallLifecycle = VkCallLifecycle.KEEP

    fun onStartFailure(): VkCallLifecycle = VkCallLifecycle.KEEP

    fun onTokenInvalid(): VkCallLifecycle = VkCallLifecycle.FINISH

    /**
     * Logout не закрывает звонки сам по себе: ссылка принадлежит профилю и ждёт
     * возврата. Закрываем только когда токен выбрасывается и им ещё можно
     * распорядиться — иначе получим неубиваемые записи в аккаунте.
     */
    fun onLogout(tokenStillValid: Boolean): VkCallLifecycle =
        if (tokenStillValid) VkCallLifecycle.FINISH else VkCallLifecycle.KEEP

    /** Профиль хранит свой токен и свой реестр; переключение ничего не ломает. */
    fun onProfileSwitch(): VkCallLifecycle = VkCallLifecycle.KEEP

    /** `manual`/`auto_js` реестр не читают, возврат в `auto_api` переиспользует ссылки. */
    fun onModeChanged(): VkCallLifecycle = VkCallLifecycle.KEEP

    fun onExplicitClose(): VkCallLifecycle = VkCallLifecycle.FINISH
}

internal data class VkCallSelection(
    val keep: List<VkCallEntry>,
    /** Лишние живые звонки: их завершаем и выбрасываем из реестра. */
    val overflow: List<VkCallEntry>,
    /** Сколько звонков ещё нужно заминчить. */
    val missing: Int,
)

/**
 * Кому принадлежит запись реестра.
 *
 * Ссылка на звонок анонимна: присоединиться может любой токен, поэтому привязка к
 * литералу токена рвала бы переиспользование после ротации токена того же аккаунта.
 */
internal object VkCallOwnerPolicy {
    /**
     * Владелец записи: userId, если известен обеим сторонам; иначе — отпечаток токена.
     * Если одна сторона неизвестна (токен из ссылки не даёт userId), границей владения
     * считаем профиль: запись могла быть создана тем же профилем другим токеном.
     */
    fun matches(entry: VkCallEntry, tokenFp: String, userId: String): Boolean = when {
        userId.isNotBlank() && entry.accountUserId.isNotBlank() -> entry.accountUserId == userId
        userId.isBlank() && entry.accountUserId.isBlank() -> entry.tokenFp == tokenFp
        else -> true
    }

    /**
     * Присваивает запись аккаунту, если владелец записи ещё неизвестен, а текущий
     * `userId` известен. Запись уже используется этим аккаунтом (правило выше), так что
     * фиксируем факт: дальше другой аккаунт её не переиспользует.
     */
    fun claim(entry: VkCallEntry, userId: String): VkCallEntry =
        if (userId.isNotBlank() && entry.accountUserId.isBlank()) {
            entry.copy(accountUserId = userId)
        } else {
            entry
        }
}

internal object VkCallReusePolicy {
    const val TTL_MS: Long = 14L * 24 * 60 * 60 * 1000
    const val MAX_FAILURES: Int = 3
    const val FAILURE_WINDOW_MS: Long = 10L * 60 * 1000

    /**
     * Ссылки, которые можно отдать новой сессии: тот же режим и владелец,
     * не истёк TTL и не исчерпан бюджет падений. Порядок входа сохраняется —
     * вызывающая сторона рассчитывает на «сначала самые старые».
     */
    fun eligible(
        entries: List<VkCallEntry>,
        tokenFp: String,
        userId: String,
        nowMs: Long,
        ttlMs: Long = TTL_MS,
    ): List<VkCallEntry> = entries.filter { entry ->
        entry.mode == CsqttConstants.VkAutoHash.MODE_AUTO_API &&
            VkCallOwnerPolicy.matches(entry, tokenFp, userId) &&
            entry.failCount < MAX_FAILURES &&
            nowMs - entry.createdAtMs <= ttlMs
    }

    fun select(eligible: List<VkCallEntry>, requested: Int): VkCallSelection {
        val wanted = requested.coerceAtLeast(0)
        val keep = eligible.take(wanted)
        return VkCallSelection(
            keep = keep,
            overflow = eligible.drop(wanted),
            missing = (wanted - keep.size).coerceAtLeast(0),
        )
    }

    /**
     * Учитывает неудачную попытку вернуться в звонок. Серия падений живёт в окне
     * [FAILURE_WINDOW_MS]: после паузы счётчик начинается заново, иначе редкие
     * сбои за недели сложились бы в ложный вердикт «ссылка мертва».
     */
    fun registerFailure(entry: VkCallEntry, nowMs: Long): VkCallEntry {
        val windowExpired = entry.lastFailMs == null || nowMs - entry.lastFailMs > FAILURE_WINDOW_MS
        return entry.copy(
            failCount = if (windowExpired) 1 else entry.failCount + 1,
            lastFailMs = nowMs,
        )
    }

    fun shouldDrop(entry: VkCallEntry): Boolean = entry.failCount >= MAX_FAILURES

    fun tokenFingerprint(token: String): String {
        if (token.isBlank()) return ""
        val digest = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        val hex = CharArray(digest.size * 2)
        digest.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            hex[index * 2] = HEX_DIGITS[value ushr 4]
            hex[index * 2 + 1] = HEX_DIGITS[value and 0x0f]
        }
        return String(hex)
    }

    private const val HEX_DIGITS = "0123456789abcdef"
}

/**
 * Классификация кодов недоступности звонка по тому, что они значат для ссылки.
 *
 * Коды домена звонков (`951`, `954`, `9000..9999`) означают, что сама ссылка мертва:
 * переиспользовать её бессмысленно, нужен новый `calls.start`. Всё остальное —
 * сетевой сбой, застой без credential'а или чужая ошибка: ссылка, скорее всего, жива.
 */
internal object VkCallEventPolicy {
    fun isCallDomainCode(code: Int): Boolean =
        code == 951 || code == 954 || code in 9000..9999
}

/**
 * Бюджет точечных замен мёртвых звонков в пределах одной жизни сервиса.
 *
 * Без него серия мгновенных падений превращается в бесконечный цикл `calls.start`:
 * каждый новый хеш умирает так же быстро, как предыдущий. После исчерпания бюджета
 * вызывающая сторона обязана остановить туннель, а не заминчивать ещё один звонок.
 */
internal class VkCallReplacementBudget(private val max: Int = 2) {
    private var used = 0

    fun tryConsume(): Boolean {
        if (used >= max) return false
        used++
        return true
    }

    fun reset() {
        used = 0
    }
}

