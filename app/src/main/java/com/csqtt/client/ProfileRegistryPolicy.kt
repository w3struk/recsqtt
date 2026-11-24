package com.csqtt.client

/**
 * Чистые правила динамического реестра профилей.
 *
 * Здесь нет DataStore, Android или Compose: только список ID, активный профиль и
 * множество профилей с непустой ссылкой. Хранилище и UI обязаны принимать те же
 * решения, поэтому все пограничные случаи закреплены JVM-тестами.
 */
internal object ProfileRegistryPolicy {
    const val DEFAULT_PROFILE_ID = 0
    const val DEFAULT_NEXT_ID = 1

    /**
     * Разбирает сохранённый `profile_ids`. Возвращает `null`, если строки нет
     * или в ней не осталось ни одного корректного ID: вызывающий тогда
     * переходит к обнаружению по данным.
     */
    fun parseIds(raw: String?): List<Int>? {
        if (raw.isNullOrBlank()) return null
        val ids = LinkedHashSet<Int>()
        for (part in raw.split(',')) {
            val id = part.trim().toIntOrNull() ?: continue
            if (id < 0) continue
            ids += id
        }
        return ids.takeIf { it.isNotEmpty() }?.toList()
    }

    fun formatIds(ids: List<Int>): String = ids.joinToString(",")

    /**
     * Профили, у которых есть непустая ссылка. Профиль 0 хранится в базовом
     * ключе, остальные — в ключах с суффиксом `_N`. Ключ `_0` игнорируется:
     * профиль 0 никогда не имеет суффикса.
     */
    fun linkedProfileIds(
        stringPrefs: Map<String, String?>,
        linkKeyName: String,
    ): Set<Int> {
        val linked = LinkedHashSet<Int>()
        if (!stringPrefs[linkKeyName].isNullOrBlank()) linked += DEFAULT_PROFILE_ID

        val prefix = "${linkKeyName}_"
        for ((key, value) in stringPrefs) {
            if (value.isNullOrBlank() || !key.startsWith(prefix)) continue
            val suffix = key.substring(prefix.length)
            val id = suffix.toIntOrNull() ?: continue
            if (id <= DEFAULT_PROFILE_ID) continue
            linked += id
        }
        return linked
    }

    /**
     * Список профилей при первом запуске новой версии: стартовый 0, активный
     * профиль (даже без ссылки, чтобы его можно было удалить) и все профили со
     * ссылкой. Порядок — по возрастанию ID.
     */
    fun discover(active: Int?, linked: Set<Int>): List<Int> {
        val ids = sortedSetOf(DEFAULT_PROFILE_ID)
        if (active != null && active >= 0) ids += active
        ids += linked.filter { it >= 0 }
        return ids.toList()
    }

    /**
     * Активный профиль не может быть несуществующим. Если сохранённый ID есть в
     * списке, он остаётся активным; профиль 0 разрешён без ссылки как стартовое
     * состояние. Для остальных без ссылки выбирается первый профиль со ссылкой.
     */
    fun resolveActive(ids: List<Int>, requested: Int, linked: Set<Int>): Int {
        if (requested !in ids) return ids.firstOrNull() ?: DEFAULT_PROFILE_ID
        if (requested == DEFAULT_PROFILE_ID || requested in linked) return requested
        return ids.firstOrNull { it in linked } ?: requested
    }

    /** Следующий ID не переиспользует удалённые и не отстаёт от списка. */
    fun nextId(ids: List<Int>, storedNext: Int?): Int {
        val fromStorage = storedNext?.takeIf { it > 0 } ?: DEFAULT_NEXT_ID
        val fromList = (ids.maxOrNull() ?: -1) + 1
        return maxOf(fromStorage, fromList, DEFAULT_NEXT_ID)
    }

    fun canDelete(ids: List<Int>, id: Int): Boolean = ids.size > 1 && id in ids

    /**
     * Кого сделать активным после удаления. Если удалён неактивный профиль,
     * активный не меняется. Иначе берётся первый оставшийся со ссылкой, затем
     * просто первый оставшийся.
     */
    fun activeAfterDelete(
        ids: List<Int>,
        deleted: Int,
        active: Int,
        linked: Set<Int>,
    ): Int {
        val remaining = ids.filterNot { it == deleted }
        if (active in remaining) return active
        return remaining.firstOrNull { it in linked }
            ?: remaining.firstOrNull()
            ?: DEFAULT_PROFILE_ID
    }

    fun summaryText(
        hasLink: Boolean,
        transport: String,
        fingerprint: String,
        workers: Int,
    ): String {
        if (!hasLink) return "Не настроен"
        val transportLabel = when (transport) {
            "tcp_tls" -> "TCP"
            else -> "UDP"
        }
        val fingerprintLabel = fingerprint.replaceFirstChar { it.uppercase() }
        return "$transportLabel · $fingerprintLabel · $workers потоков"
    }
}
