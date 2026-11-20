package com.csqtt.client

/**
 * Одна строка журнала на фактическое применение серверного правила.
 *
 * Раньше домены и адреса журналировались двумя независимыми строками, и каждая
 * висела с `(x2)` на повторном `CONFIG`. Здесь обе части сводятся в одну строку,
 * а sha списка адресов сокращается до 12 знаков: полный хеш нужен только для
 * сверки с отчётом сервера и занимал больше половины строки.
 *
 * Заголовок называет **что изменилось**, а не откуда пришли данные: сервер
 * считает один `content_sha` на домены и приложения вместе, поэтому правка
 * списка приложений заставляет его переслать и домены. Формулировка «домены:
 * обновлены» в этом случае читалась как «список доменов изменён» и уводила
 * разбор в ложную сторону.
 */
internal object RoutingLogPolicy {
    /** Откуда взялись домены и приложения. */
    enum class ContentSource { UPDATED, CACHED, UNCACHED }

    /** Откуда взялся список адресов; [NONE] — сервер его не присылал. */
    enum class IpsSource { UPDATED, CACHED, NONE }

    /** Что именно изменилось в «тяжёлой» части относительно уже применённой. */
    enum class ContentChange { FIRST, BOTH, DOMAINS, APPS, NONE }

    /**
     * Сравнение нового блока с тем, что применено сейчас. Приложения считаются
     * вместе с пресетами: в `content_sha` они входят одним набором, и для
     * маршрутизации это тоже одна и та же часть правила.
     */
    fun change(previous: CachedRoutingContent?, next: CachedRoutingContent): ContentChange {
        if (previous == null) return ContentChange.FIRST
        val domains = previous.domains != next.domains
        val apps = previous.packages != next.packages ||
            previous.presetRed != next.presetRed ||
            previous.presetOrange != next.presetOrange
        return when {
            domains && apps -> ContentChange.BOTH
            domains -> ContentChange.DOMAINS
            apps -> ContentChange.APPS
            else -> ContentChange.NONE
        }
    }

    fun applyLine(
        rule: ServerRoutingRule,
        content: ContentSource,
        change: ContentChange,
        ips: IpsSource,
        ipsCount: Int?,
        ipsSha: String?,
    ): String {
        val headline = headline(content, change, ips)
        val apps = rule.packages.size + rule.presetRed.size + rule.presetOrange.size
        val tail = if (ips == IpsSource.NONE) {
            "адресов нет"
        } else {
            "адресов ${ipsCount ?: 0}, sha ${ipsSha.orEmpty().take(12)}"
        }
        return "$headline: доменов ${rule.domains.size}, приложений $apps, $tail"
    }

    /**
     * Короткая форма остаётся только там, где она ничего не искажает: всё из
     * кеша или всё пришло обновлённым. Смешанные и частичные случаи называют
     * каждую часть отдельно.
     */
    private fun headline(content: ContentSource, change: ContentChange, ips: IpsSource): String = when {
        content == ContentSource.CACHED && ips == IpsSource.CACHED ->
            "Маршрутизация сервера из кеша"

        content == ContentSource.UPDATED && change.isFullContent() && ips == IpsSource.UPDATED ->
            "Маршрутизация сервера обновлена"

        else -> "Маршрутизация сервера (${contentClause(content, change)}, ${ipsClause(ips)})"
    }

    private fun ContentChange.isFullContent(): Boolean =
        this == ContentChange.FIRST || this == ContentChange.BOTH

    private fun contentClause(content: ContentSource, change: ContentChange): String = when (content) {
        ContentSource.CACHED -> "контент из кеша"
        ContentSource.UNCACHED -> "контент без кеша"
        ContentSource.UPDATED -> when (change) {
            ContentChange.FIRST -> "контент обновлён"
            ContentChange.BOTH -> "домены и приложения изменены"
            ContentChange.DOMAINS -> "домены изменены, приложения без изменений"
            ContentChange.APPS -> "приложения изменены, домены без изменений"
            ContentChange.NONE -> "контент переслан без изменений"
        }
    }

    private fun ipsClause(ips: IpsSource): String = when (ips) {
        IpsSource.UPDATED -> "адреса: обновлены"
        IpsSource.CACHED -> "адреса: из кеша"
        IpsSource.NONE -> "адреса: нет"
    }
}
