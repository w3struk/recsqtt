package com.csqtt.client

import org.json.JSONArray
import org.json.JSONObject

/**
 * Сериализация реестра звонков в DataStore.
 *
 * Формат — JSON-массив объектов; ключи snake_case повторяют поля [VkCallEntry].
 * Декодирование намеренно «мягкое»: одна битая запись не должна уносить весь
 * реестр, поэтому она пропускается, а не роняет чтение.
 */
internal object VkCallCodec {
    private const val KEY_CALL_ID = "call_id"
    private const val KEY_HASH = "hash"
    private const val KEY_JOIN_LINK = "join_link"
    private const val KEY_CREATED_AT_MS = "created_at_ms"
    private const val KEY_IDLE_SINCE_MS = "idle_since_ms"
    private const val KEY_TOKEN_FP = "token_fp"
    private const val KEY_ACCOUNT_USER_ID = "account_user_id"
    private const val KEY_MODE = "mode"
    private const val KEY_FAIL_COUNT = "fail_count"
    private const val KEY_LAST_FAIL_MS = "last_fail_ms"

    fun encode(entries: List<VkCallEntry>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put(KEY_CALL_ID, entry.callId)
                    put(KEY_HASH, entry.hash)
                    put(KEY_JOIN_LINK, entry.joinLink)
                    put(KEY_CREATED_AT_MS, entry.createdAtMs)
                    put(KEY_IDLE_SINCE_MS, entry.idleSinceMs ?: JSONObject.NULL)
                    put(KEY_TOKEN_FP, entry.tokenFp)
                    put(KEY_ACCOUNT_USER_ID, entry.accountUserId)
                    put(KEY_MODE, entry.mode)
                    put(KEY_FAIL_COUNT, entry.failCount)
                    put(KEY_LAST_FAIL_MS, entry.lastFailMs ?: JSONObject.NULL)
                },
            )
        }
        return array.toString()
    }

    fun decode(raw: String?): List<VkCallEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val entries = ArrayList<VkCallEntry>(array.length())
        for (index in 0 until array.length()) {
            val json = array.optJSONObject(index) ?: continue
            val entry = decodeEntry(json) ?: continue
            entries += entry
        }
        return entries
    }

    private fun decodeEntry(json: JSONObject): VkCallEntry? {
        val callId = json.optString(KEY_CALL_ID)
        val hash = json.optString(KEY_HASH)
        // Без идентификатора и хеша запись бесполезна: ссылку не к чему привязать.
        if (callId.isBlank() || hash.isBlank()) return null
        if (!json.has(KEY_JOIN_LINK) || !json.has(KEY_TOKEN_FP)) return null
        val createdAtMs = (json.opt(KEY_CREATED_AT_MS) as? Number)?.toLong() ?: return null
        return VkCallEntry(
            callId = callId,
            hash = hash,
            joinLink = json.optString(KEY_JOIN_LINK),
            createdAtMs = createdAtMs,
            idleSinceMs = (json.opt(KEY_IDLE_SINCE_MS) as? Number)?.toLong(),
            tokenFp = json.optString(KEY_TOKEN_FP),
            accountUserId = json.optString(KEY_ACCOUNT_USER_ID),
            mode = json.optString(KEY_MODE).ifBlank { CsqttConstants.VkAutoHash.MODE_AUTO_API },
            failCount = (json.opt(KEY_FAIL_COUNT) as? Number)?.toInt() ?: 0,
            lastFailMs = (json.opt(KEY_LAST_FAIL_MS) as? Number)?.toLong(),
        )
    }
}
