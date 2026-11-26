package com.csqtt.client

/**
 * Защёлка после остановки: короткое окно, в котором старт не поднимает сессию заново.
 * Защищает от двойного нажатия (логотип, плитка в шторке, ярлык), а не показывает
 * пользователю состояние — поэтому окно проверяется по монотонным часам, а не по
 * `StateFlow`.
 */
internal object StartCooldownPolicy {
    fun active(
        lastStopElapsedMs: Long,
        nowElapsedMs: Long,
        windowMs: Long = CsqttConstants.Timeouts.POST_STOP_START_COOLDOWN_MS,
    ): Boolean {
        if (lastStopElapsedMs == 0L) return false
        val elapsed = nowElapsedMs - lastStopElapsedMs
        // Отрицательный зазор — часы уехали назад; такое окно не блокирует старт.
        return elapsed in 0 until windowMs
    }
}
