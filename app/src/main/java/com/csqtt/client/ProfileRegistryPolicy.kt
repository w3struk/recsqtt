package com.csqtt.client

/**
 * Чистые правила динамического реестра профилей.
 *
 * Здесь нет DataStore, Android или Compose: только список ID, активный профиль и
 * множество профилей с непустой ссылкой. Хранилище и UI обязаны принимать те же
 * решения, поэтому все пограничные случаи закреплены JVM-тестами.
 *
 * Реестр может быть пустым: профиль не создаётся «на всякий случай», и пустой
 * список — законное состояние после удаления последнего профиля.
 */
internal object ProfileRegistryPolicy {
    const val DEFAULT_PROFILE_ID = 0

    /** Профиля нет: реестр пуст или активный профиль не выбран. */
    const val NO_PROFILE = -1

    /**
     * Разбирает сохранённый `profile_ids`.
     *
     * * `null` — ключа нет: реестр ещё не мигрирован, список нужно обнаружить.
     * * пустая/пробельная строка — реестр мигрирован, но пуст.
     * * только мусор (`abc`, отрицательные) — некорректно, нужно обнаружение.
     * * смесь мусора и ID — валидные ID в порядке хранения.
     */
    fun parseIds(raw: String?): List<Int>? {
        if (raw == null) return null
        val ids = LinkedHashSet<Int>()
        for (part in raw.split(',')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val id = token.toIntOrNull() ?: continue
            if (id < 0) continue
            ids += id
        }
        if (ids.isNotEmpty()) return ids.toList()
        return if (raw.isBlank()) emptyList() else null
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
     * Профили для ещё не мигрированного реестра: ровно те, у которых есть
     * непустая ссылка. Стартовый профиль 0 и активный ID не выдумываются —
     * пустой список здесь нормален. Порядок — по возрастанию ID.
     */
    @Suppress("UNUSED_PARAMETER")
    fun discover(active: Int?, linked: Set<Int>): List<Int> =
        linked.filter { it >= 0 }.distinct().sorted()

    /**
     * Активный профиль не может быть несуществующим: пустой реестр даёт
     * [NO_PROFILE]. Сохранённый ID остаётся активным, даже если у профиля нет
     * ссылки; иначе выбирается первый профиль со ссылкой, затем просто первый.
     */
    fun resolveActive(ids: List<Int>, requested: Int, linked: Set<Int>): Int {
        if (ids.isEmpty()) return NO_PROFILE
        if (requested in ids) return requested
        return ids.firstOrNull { it in linked } ?: ids.first()
    }

    /**
     * Следующий ID не переиспользует удалённые и не отстаёт от списка.
     * Пустой реестр начинает с 0; монотонный счётчик переживает удаление всех
     * профилей.
     */
    fun nextId(ids: List<Int>, storedNext: Int?): Int = maxOf(
        storedNext?.takeIf { it >= 0 } ?: 0,
        (ids.maxOrNull() ?: -1) + 1,
        0,
    )

    /** Удалить можно любой профиль, включая последний. */
    fun canDelete(ids: List<Int>, id: Int): Boolean = id in ids

    /**
     * Кого сделать активным после удаления. Если удалён неактивный профиль,
     * активный не меняется. Иначе берётся первый оставшийся со ссылкой, затем
     * просто первый оставшийся; пустой реестр даёт [NO_PROFILE].
     */
    fun activeAfterDelete(
        ids: List<Int>,
        deleted: Int,
        active: Int,
        linked: Set<Int>,
    ): Int {
        val remaining = ids.filterNot { it == deleted }
        if (remaining.isEmpty()) return NO_PROFILE
        if (active in remaining) return active
        return remaining.firstOrNull { it in linked } ?: remaining.first()
    }

    /** Итог миграции реестра из обнаруженного состояния в явный `profile_ids`. */
    data class RegistryMigration(
        val ids: List<Int>,
        val active: Int,
        val next: Int,
        val dropped: List<Int>,
    )

    /**
     * Чистая миграция в схему реестра v2. Новый список — ровно профили со
     * ссылкой: неlinked-профили, включая стартовый 0, отбрасываются, и их ID
     * попадают в [RegistryMigration.dropped]. Активный профиль разрешается теми
     * же правилами, что и в [resolveActive]; `next` — правилом [nextId].
     */
    fun migrateRegistry(
        storedIds: List<Int>?,
        linked: Set<Int>,
        requestedActive: Int,
        storedNext: Int?,
    ): RegistryMigration {
        val ids = linked.filter { it >= 0 }.distinct().sorted()
        val idSet = ids.toSet()
        return RegistryMigration(
            ids = ids,
            active = resolveActive(ids = ids, requested = requestedActive, linked = linked),
            next = nextId(ids = ids, storedNext = storedNext),
            dropped = (storedIds ?: emptyList()).distinct().filterNot { it in idSet },
        )
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
