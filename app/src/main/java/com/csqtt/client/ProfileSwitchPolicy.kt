package com.csqtt.client

/** Что делать с живым туннелем, когда пользователь сменил профиль конфигурации. */
internal enum class ProfileSwitchAction {
    /** Переключать нечего: профиль тот же, туннель остановлен или уже останавливается. */
    IGNORE,

    /** Туннель жив или должен быть жив: пересобрать параметры и заменить процесс на месте. */
    RECONNECT,

    /**
     * Автопауза Wi-Fi: процесса уже нет, поэтому перезапускать нечего —
     * возобновление (`restoreTunnel`) само пересоберёт параметры из настроек.
     */
    DEFER_TO_RESUME,
}

/**
 * Решение о переподключении при смене профиля.
 *
 * [sessionProfile] — профиль, с которым поднят текущий процесс (снимок `TunnelParams.profile`),
 * [nextProfile] — профиль, выбранный в настройках сейчас.
 *
 * Перезапуск решает [tunnelWanted], а не `running`: у туннеля бывают состояния, когда
 * процесса ещё нет, но он обязан быть — первичный старт и ожидание перезапуска после
 * сбоя. В обоих случаях переключение обязано дойти до нового процесса, иначе он поднимется
 * со старыми параметрами.
 *
 * [pausedForWifi] проверяется раньше [tunnelWanted]: на автопаузе намерение «работать»
 * сохранено, но поднимать процесс нельзя — это сделает выход из паузы.
 */
internal fun profileSwitchAction(
    sessionProfile: Int,
    nextProfile: Int,
    stopping: Boolean,
    pausedForWifi: Boolean,
    tunnelWanted: Boolean,
): ProfileSwitchAction = when {
    nextProfile == sessionProfile -> ProfileSwitchAction.IGNORE
    stopping -> ProfileSwitchAction.IGNORE
    pausedForWifi -> ProfileSwitchAction.DEFER_TO_RESUME
    tunnelWanted -> ProfileSwitchAction.RECONNECT
    else -> ProfileSwitchAction.IGNORE
}
