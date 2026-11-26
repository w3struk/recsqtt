package com.csqtt.client

/**
 * Тайминги прогрева воркеров, которые попадают в журнал отчёта.
 *
 * Раньше единственным следом старта была строка «Активных: N», поэтому
 * «набирается медленнее обычного» нельзя было измерить постфактум. Здесь
 * считаются две точки: первый готовый поток и момент, когда готовы все.
 */
internal object StartupTimingPolicy {
    const val FIRST_KEY: String = "startup_timing_first"
    const val ALL_KEY: String = "startup_timing_all"

    fun elapsedSeconds(startedAtMs: Long, readyAtMs: Long): Long =
        ((readyAtMs.coerceAtLeast(startedAtMs) - startedAtMs) + 500L) / 1000L

    fun firstReadyMessage(startedAtMs: Long, readyAtMs: Long): String =
        "[СТАРТ] Первый поток готов через ${elapsedSeconds(startedAtMs, readyAtMs)} с"

    fun allReadyMessage(startedAtMs: Long, readyAtMs: Long, workers: Int): String =
        "[СТАРТ] Все $workers потоков готовы через ${elapsedSeconds(startedAtMs, readyAtMs)} с"

    /**
     * Сколько потоков реально ждём: сервер может урезать цель через событие
     * `Workers`, но не может поднять её выше запрошенной приложением.
     */
    fun effectiveTarget(configuredWorkers: Int, serverLimit: Int?): Int {
        val configured = configuredWorkers.coerceAtLeast(0)
        val limit = serverLimit?.takeIf { it > 0 } ?: return configured
        return if (configured > 0) minOf(configured, limit) else limit
    }
}
