package com.csqtt.client

/**
 * Длительность текущей сессии в секундах без времени, проведённого в ожидании сети.
 *
 * `pausedAtMs` — момент входа в текущую паузу (`null`, если паузы нет),
 * `pausedTotalMs` — сумма уже завершённых пауз. Оба момента и `nowMs` обязаны быть
 * монотонными (`SystemClock.elapsedRealtime()`), иначе вычитание теряет смысл.
 */
internal fun elapsedSessionSeconds(
    startedAtMs: Long,
    nowMs: Long,
    pausedTotalMs: Long = 0L,
    pausedAtMs: Long? = null,
): Long {
    if (startedAtMs <= 0L) return 0L
    val effectiveNowMs = pausedAtMs ?: nowMs
    val elapsedMs = (effectiveNowMs - startedAtMs - pausedTotalMs).coerceAtLeast(0L)
    return elapsedMs / 1000L
}
