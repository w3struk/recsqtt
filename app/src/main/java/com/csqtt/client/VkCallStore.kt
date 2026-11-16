package com.csqtt.client

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray

/**
 * Хранилище реестра звонков: отдельный файл DataStore `vk_calls`, по ключу на профиль.
 *
 * Класс сознательно тонкий — вся логика отбора лежит в [VkCallReusePolicy].
 * Единственная обязанность сверх чтения/записи: атомарный `update`, чтобы
 * параллельные правки реестра не теряли записи и не смешивались.
 */
internal class VkCallStore(context: Context) {
    private val dataStore = context.applicationContext.vkCallsDataStore

    companion object {
        private const val TAG = "VkCallStore"

        private val Context.vkCallsDataStore by preferencesDataStore("vk_calls")

        /** Один замок на процесс: DataStore сериализует записи сам, но не чтение-изменение-запись. */
        private val UPDATE_MUTEX = Mutex()

        private fun key(profile: Int) = stringPreferencesKey("calls_v1_$profile")
    }

    suspend fun load(profile: Int): List<VkCallEntry> {
        val raw = runCatching { dataStore.data.first()[key(profile)] }
            .onFailure { Log.w(TAG, "Не удалось прочитать реестр звонков профиля $profile", it) }
            .getOrNull()
        return decode(raw, profile)
    }

    suspend fun save(profile: Int, entries: List<VkCallEntry>) {
        UPDATE_MUTEX.withLock {
            dataStore.edit { prefs -> prefs[key(profile)] = VkCallCodec.encode(entries) }
        }
    }

    suspend fun update(
        profile: Int,
        transform: (List<VkCallEntry>) -> List<VkCallEntry>,
    ): List<VkCallEntry> = UPDATE_MUTEX.withLock {
        var updated: List<VkCallEntry> = emptyList()
        dataStore.edit { prefs ->
            // Битое значение даёт пустой список: реестр восстанавливается с нуля,
            // а не продолжается поверх мусора.
            val current = decode(prefs[key(profile)], profile)
            updated = transform(current)
            prefs[key(profile)] = VkCallCodec.encode(updated)
        }
        updated
    }

    suspend fun clear(profile: Int) {
        UPDATE_MUTEX.withLock {
            dataStore.edit { prefs -> prefs.remove(key(profile)) }
        }
    }

    private fun decode(raw: String?, profile: Int): List<VkCallEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        if (runCatching { JSONArray(raw) }.isFailure) {
            Log.w(TAG, "Повреждённый реестр звонков профиля $profile — старт с пустого")
            if (TunnelManager.isLoggingEnabled) {
                TunnelManager.updateLog(
                    "vk_calls_store_corrupt",
                    "[ERR] Реестр звонков VK повреждён — начинаем с чистого",
                    60,
                    true,
                )
            }
            return emptyList()
        }
        return VkCallCodec.decode(raw)
    }
}
