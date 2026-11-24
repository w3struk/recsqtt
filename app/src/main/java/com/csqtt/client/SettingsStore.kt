package com.csqtt.client

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Глобальные настройки приложения.
 *
 * Профильные ключи, flow'ы, кэши и мутации живут в [ProfileSettingsStore] и
 * доступны через [profile]. Оба класса работают с **одним** `DataStore<Preferences>`:
 * второй экземпляр на файл `settings` создавать нельзя.
 */
class SettingsStore(context: Context) {
    private val appContext = context.applicationContext

    companion object {
        private val Context.dataStore by preferencesDataStore("settings")

        private val SHOW_SYSTEM_APPS = booleanPreferencesKey("show_system_apps")
        private val PARTICIPANT_SHOW_LOGS = booleanPreferencesKey("participant_show_logs")

        private val EXCLUDED_APPS = stringPreferencesKey("excluded_apps")
        private val WHITELIST_APPS = stringPreferencesKey("whitelist_apps")
        // Глобальный, а не профильный ключ: пресет приходит с серверного правила и к профилям
        // отношения не имеет, поэтому в ProfileSettingsStore он не живёт.
        private val ORANGE_TUNNEL_ENABLED = stringPreferencesKey("orange_tunnel_enabled")

        private val IS_WHITELIST = booleanPreferencesKey("is_whitelist")

        // Локальные списки приложений и видимость журнала больше не принадлежат профилю:
        // маршрутизация в большинстве случаев приходит с серверного правила, а журнал —
        // свойство приложения, а не подключения. Флаг закрывает одноразовый перенос
        // значений активного профиля в глобальные ключи (см. migrateGlobalAppSettings).
        private val APP_SETTINGS_GLOBAL_MIGRATED = booleanPreferencesKey("app_settings_global_migrated")
        private val AUTO_JS_RISK_ACKNOWLEDGED = booleanPreferencesKey("auto_js_risk_acknowledged")
        private val TCP_TRANSPORT_RISK_ACKNOWLEDGED = booleanPreferencesKey("tcp_transport_risk_acknowledged")
        private val BATTERY_OPTIMIZATION_PROMPT_HANDLED =
            booleanPreferencesKey("battery_optimization_prompt_handled")
        private val AUTO_PAUSE_ON_WIFI = booleanPreferencesKey("auto_pause_on_wifi")
        private val THEME_MODE = stringPreferencesKey("theme_mode") 
        private val IS_DYNAMIC_COLOR = booleanPreferencesKey("is_dynamic_color")
        private val THEME_PALETTE = stringPreferencesKey("theme_palette")

        private val UPDATE_LAST_CHECK_AT = longPreferencesKey("update_last_check_at")
        private val UPDATE_LATEST_VERSION = stringPreferencesKey("update_latest_version")
        private val UPDATE_LAST_ERROR = stringPreferencesKey("update_last_error")
        private val UPDATE_CHECK_INTERVAL_HOURS = intPreferencesKey("update_check_interval_hours")
        private val UPDATE_POSTPONE_UNTIL = longPreferencesKey("update_postpone_until")
        private val UPDATE_POSTPONE_VERSION = stringPreferencesKey("update_postpone_version")
        private val UPDATE_DIALOG_LAST_SHOWN_VERSION = stringPreferencesKey("update_dialog_last_shown_version")
        private val UPDATE_DIALOG_LAST_SHOWN_AT = longPreferencesKey("update_dialog_last_shown_at")
        private val UPDATE_DIALOG_LAST_ACTION_VERSION = stringPreferencesKey("update_dialog_last_action_version")
        private val UPDATE_DIALOG_LAST_ACTION = stringPreferencesKey("update_dialog_last_action")
        private val UPDATE_DIALOG_LAST_ACTION_AT = longPreferencesKey("update_dialog_last_action_at")

        private val CONNECTION_GENERATION = longPreferencesKey("connection_generation")
        private val TUNNEL_LAST_DIAGNOSTIC = stringPreferencesKey("tunnel_last_diagnostic")
        private val TUNNEL_LAST_DIAGNOSTIC_AT = longPreferencesKey("tunnel_last_diagnostic_at")
        private val TUNNEL_WAS_RUNNING = booleanPreferencesKey("tunnel_was_running")

        @Volatile
        internal var cachedExcludedApps: String? = null

        @Volatile
        internal var cachedWhitelistApps: String? = null

        @Volatile
        internal var cachedIsWhitelist: Boolean? = null

        @Volatile
        internal var cachedShowSystemApps: Boolean? = null

        @Volatile
        internal var cachedOrangeTunnelEnabled: String? = null

        private val uiCacheStarted = java.util.concurrent.atomic.AtomicBoolean(false)
        private val migrationsStarted = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Суффиксный ключ старого профильного хранения для настроек, которые уже
         * стали глобальными. Нужен только одноразовому переносу и запасному чтению
         * до его завершения: в обычном чтении и записи эти ключи идут без суффикса.
         */
        private fun <T> legacyProfileKey(baseKey: Preferences.Key<T>, profile: Int): Preferences.Key<T> {
            if (profile == ProfileRegistryPolicy.DEFAULT_PROFILE_ID) return baseKey
            val newName = "${baseKey.name}_$profile"
            @Suppress("UNCHECKED_CAST")
            return when (baseKey) {
                IS_WHITELIST, PARTICIPANT_SHOW_LOGS -> booleanPreferencesKey(newName) as Preferences.Key<T>
                else -> stringPreferencesKey(newName) as Preferences.Key<T>
            }
        }
    }

    private val dataStore = appContext.dataStore

    /**
     * Профильные настройки на том же `DataStore`. Объявлен до flow'ов, которые
     * читают [legacyProfileValue]: он разрешает активный профиль через
     * [ProfileSettingsStore.activeProfileOf].
     */
    val profile = ProfileSettingsStore(dataStore, appContext)

    val tunnelLastDiagnostic: Flow<String> = dataStore.data.map {
        it[TUNNEL_LAST_DIAGNOSTIC] ?: ""
    }
    val showSystemApps: Flow<Boolean> = dataStore.data.map { it[SHOW_SYSTEM_APPS] ?: false }
    val orangeTunnelEnabled: Flow<String> = dataStore.data.map { it[ORANGE_TUNNEL_ENABLED] ?: "" }
    val participantShowLogs: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PARTICIPANT_SHOW_LOGS] ?: false
    }

    val excludedApps: Flow<String> = dataStore.data.map { prefs ->
        prefs[EXCLUDED_APPS] ?: legacyProfileValue(prefs, EXCLUDED_APPS) ?: ""
    }
    val whitelistApps: Flow<String> = dataStore.data.map { prefs ->
        prefs[WHITELIST_APPS] ?: legacyProfileValue(prefs, WHITELIST_APPS) ?: ""
    }

    val isWhitelist: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[IS_WHITELIST] ?: legacyProfileValue(prefs, IS_WHITELIST) ?: false
    }

    /**
     * Значение активного профиля из старого суффиксного ключа — только как запасной
     * вариант, пока [migrateGlobalAppSettings] не перенёс его в глобальный. Нужен из-за
     * гонки с автостартом сервиса: `TunnelService` может подняться раньше, чем завершится
     * асинхронная миграция, и иначе прочитал бы пустой список (а белый режим — как чёрный).
     * Записанная пустая строка или `false` в глобальном ключе — осознанное значение,
     * поэтому запасной путь включается только при его отсутствии.
     */
    private fun <T> legacyProfileValue(prefs: Preferences, baseKey: Preferences.Key<T>): T? {
        val profileId = profile.activeProfileOf(prefs)
        if (profileId == ProfileRegistryPolicy.DEFAULT_PROFILE_ID) return null
        return prefs[legacyProfileKey(baseKey, profileId)]
    }

    val autoJsRiskAcknowledged: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_JS_RISK_ACKNOWLEDGED] ?: false
    }
    val tcpTransportRiskAcknowledged: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[TCP_TRANSPORT_RISK_ACKNOWLEDGED] ?: false
    }
    val autoPauseOnWifi: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[AUTO_PAUSE_ON_WIFI] ?: false
    }
    suspend fun isBatteryOptimizationPromptHandled(): Boolean =
        dataStore.data.first()[BATTERY_OPTIMIZATION_PROMPT_HANDLED] ?: false

    val themeMode: Flow<String> = dataStore.data.map { it[THEME_MODE] ?: "system" }
    val isDynamicColor: Flow<Boolean> = dataStore.data.map { it[IS_DYNAMIC_COLOR] ?: false }
    val themePalette: Flow<String> = dataStore.data.map { it[THEME_PALETTE] ?: "indigo" }

    val connectionGeneration: Flow<Long> = dataStore.data.map { prefs ->
        prefs[CONNECTION_GENERATION] ?: 0L
    }

    val tunnelWasRunning: Flow<Boolean> = dataStore.data.map { it[TUNNEL_WAS_RUNNING] ?: false }
    val updateLastCheckAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECK_AT] ?: 0L }
    val updateLatestVersion: Flow<String> = dataStore.data.map { it[UPDATE_LATEST_VERSION] ?: "" }
    val updateLastError: Flow<String> = dataStore.data.map { it[UPDATE_LAST_ERROR] ?: "" }
    val updateCheckIntervalHours: Flow<Int> = dataStore.data.map { it[UPDATE_CHECK_INTERVAL_HOURS] ?: 24 }
    val updatePostponeUntil: Flow<Long> = dataStore.data.map { it[UPDATE_POSTPONE_UNTIL] ?: 0L }
    val updatePostponeVersion: Flow<String> = dataStore.data.map { it[UPDATE_POSTPONE_VERSION] ?: "" }

    // Последним в классе, после всех свойств: Kotlin присваивает их сверху вниз, а сборщики
    // ниже стартуют на других потоках сразу. Выше flow'ов, которые они читают, сборщик успевал
    // прочитать null и вызвать collect на null — приложение падало при запуске и при рестарте
    // сервиса. Порядок объявления здесь единственное, что удерживает кэш от гонки.
    init {
        if (migrationsStarted.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                migrateGlobalAppSettings()
            }
        }
        if (uiCacheStarted.compareAndSet(false, true)) {
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                launch { excludedApps.collect { cachedExcludedApps = it } }
                launch { whitelistApps.collect { cachedWhitelistApps = it } }
                launch { isWhitelist.collect { cachedIsWhitelist = it } }
                launch { showSystemApps.collect { cachedShowSystemApps = it } }
                launch { orangeTunnelEnabled.collect { cachedOrangeTunnelEnabled = it } }
            }
        }
    }


    suspend fun saveTunnelWasRunning(running: Boolean) {
        dataStore.edit { prefs ->
            prefs[TUNNEL_WAS_RUNNING] = running
        }
    }

    suspend fun saveThemeMode(mode: String) {
        dataStore.edit { prefs ->
            prefs[THEME_MODE] = mode
        }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun saveThemePalette(palette: String) {
        dataStore.edit { prefs ->
            prefs[THEME_PALETTE] = palette
        }
    }

    suspend fun reserveConnectionGeneration(
        proposed: Long = 0L,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): Long {
        var reserved = 0L
        dataStore.edit { prefs ->
            reserved = reserveTunnelGenerationId(
                nowSeconds.coerceAtLeast(0L),
                proposed.coerceAtLeast(0L),
                prefs[CONNECTION_GENERATION] ?: 0L,
            )
            prefs[CONNECTION_GENERATION] = reserved
        }
        return reserved
    }

    suspend fun recordConnectionGeneration(generation: Long): Long {
        var persisted = 0L
        dataStore.edit { prefs ->
            persisted = maxOf(prefs[CONNECTION_GENERATION] ?: 0L, generation.coerceAtLeast(0L))
            prefs[CONNECTION_GENERATION] = persisted
        }
        return persisted
    }

    suspend fun saveTunnelDiagnostic(code: String, message: String) {
        val normalizedCode = code.trim().take(80)
        val normalizedMessage = message.trim().replace(Regex("\\s+"), " ").take(400)
        dataStore.edit { prefs ->
            prefs[TUNNEL_LAST_DIAGNOSTIC] = "$normalizedCode|$normalizedMessage"
            prefs[TUNNEL_LAST_DIAGNOSTIC_AT] = System.currentTimeMillis()
        }
    }

    suspend fun clearTunnelDiagnostic() {
        dataStore.edit { prefs ->
            prefs.remove(TUNNEL_LAST_DIAGNOSTIC)
            prefs.remove(TUNNEL_LAST_DIAGNOSTIC_AT)
        }
    }

    suspend fun saveUpdateState(lastCheckAt: Long, latestVersion: String, error: String) {
        dataStore.edit { prefs ->
            prefs[UPDATE_LAST_CHECK_AT] = lastCheckAt
            prefs[UPDATE_LATEST_VERSION] = latestVersion
            prefs[UPDATE_LAST_ERROR] = error
        }
    }

    suspend fun saveUpdateCheckIntervalHours(hours: Int) {
        dataStore.edit { prefs ->
            prefs[UPDATE_CHECK_INTERVAL_HOURS] = hours
        }
    }

    suspend fun saveUpdatePostpone(version: String, until: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_POSTPONE_VERSION] = version
            prefs[UPDATE_POSTPONE_UNTIL] = until
        }
    }

    suspend fun saveUpdateDialogShown(version: String, shownAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_SHOWN_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_SHOWN_AT] = shownAt
        }
    }

    suspend fun saveUpdateDialogAction(version: String, action: String, actedAt: Long) {
        dataStore.edit { prefs ->
            prefs[UPDATE_DIALOG_LAST_ACTION_VERSION] = version
            prefs[UPDATE_DIALOG_LAST_ACTION] = action
            prefs[UPDATE_DIALOG_LAST_ACTION_AT] = actedAt
        }
    }

    suspend fun saveShowSystemApps(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[SHOW_SYSTEM_APPS] = enabled
        }
    }

    suspend fun saveOrangeTunnelEnabled(packages: String) {
        dataStore.edit { prefs ->
            prefs[ORANGE_TUNNEL_ENABLED] = packages
        }
    }

    suspend fun saveParticipantShowLogs(show: Boolean) {
        dataStore.edit { prefs ->
            prefs[PARTICIPANT_SHOW_LOGS] = show
        }
    }

    suspend fun saveExcludedApps(packages: String) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = packages
        }
    }

    suspend fun saveWhitelistApps(packages: String) {
        dataStore.edit { prefs ->
            prefs[WHITELIST_APPS] = packages
        }
    }

    suspend fun saveAppRoutingLists(blacklist: String, whitelist: String, isWhitelist: Boolean? = null) {
        dataStore.edit { prefs ->
            prefs[EXCLUDED_APPS] = blacklist
            prefs[WHITELIST_APPS] = whitelist
            if (isWhitelist != null) prefs[IS_WHITELIST] = isWhitelist
        }
    }

    suspend fun saveAutoJsRiskAcknowledged(acknowledged: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_JS_RISK_ACKNOWLEDGED] = acknowledged
        }
    }

    suspend fun saveTcpTransportRiskAcknowledged(acknowledged: Boolean) {
        dataStore.edit { prefs ->
            prefs[TCP_TRANSPORT_RISK_ACKNOWLEDGED] = acknowledged
        }
    }

    suspend fun saveAutoPauseOnWifi(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[AUTO_PAUSE_ON_WIFI] = enabled
        }
    }

    suspend fun markBatteryOptimizationPromptHandled() {
        dataStore.edit { prefs ->
            prefs[BATTERY_OPTIMIZATION_PROMPT_HANDLED] = true
        }
    }

    suspend fun saveIsWhitelist(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[IS_WHITELIST] = enabled
        }
    }

    /**
     * Одноразовый перенос настроек, которые перестали быть профильными.
     *
     * Локальные списки приложений и видимость журнала больше не зависят от профиля
     * конфигурации: маршрутизация в большинстве случаев приходит с серверного правила,
     * а журнал — свойство приложения, а не подключения. Переносим значения **активного**
     * профиля: именно их пользователь видит сейчас, и именно они должны остаться после
     * обновления. У профиля 0 базовые ключи уже глобальные — переносить нечего.
     *
     * Значения остальных профилей не удаляем: они просто перестают читаться, поэтому
     * откат на прежнюю версию APK вернёт прежнее поведение. Гонку с ранним чтением
     * закрывает [legacyProfileValue] — он читает суффиксный ключ, пока глобальный пуст.
     */
    suspend fun migrateGlobalAppSettings() {
        if (dataStore.data.first()[APP_SETTINGS_GLOBAL_MIGRATED] == true) return

        dataStore.edit { prefs ->
            if (prefs[APP_SETTINGS_GLOBAL_MIGRATED] == true) return@edit

            val profileId = profile.activeProfileOf(prefs)
            if (profileId != ProfileRegistryPolicy.DEFAULT_PROFILE_ID) {
                prefs[legacyProfileKey(EXCLUDED_APPS, profileId)]?.let { prefs[EXCLUDED_APPS] = it }
                prefs[legacyProfileKey(WHITELIST_APPS, profileId)]?.let { prefs[WHITELIST_APPS] = it }
                prefs[legacyProfileKey(IS_WHITELIST, profileId)]?.let { prefs[IS_WHITELIST] = it }
                prefs[legacyProfileKey(PARTICIPANT_SHOW_LOGS, profileId)]?.let {
                    prefs[PARTICIPANT_SHOW_LOGS] = it
                }
            }

            prefs[APP_SETTINGS_GLOBAL_MIGRATED] = true
        }
    }
}
