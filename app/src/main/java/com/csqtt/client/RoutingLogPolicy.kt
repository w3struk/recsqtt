package com.csqtt.client

/**
 * Одна строка журнала на фактическое применение серверного правила.
 *
 * Раньше домены и адреса журналировались двумя независимыми строками, и каждая
 * висела с `(x2)` на повторном `CONFIG`. Здесь обе части сводятся в одну строку,
 * а sha списка адресов сокращается до 12 знаков: полный хеш нужен только для
 * сверки с отчётом сервера и занимал больше половины строки.
 */
internal object RoutingLogPolicy {
    /** Откуда взялись домены и приложения. */
    enum class ContentSource { UPDATED, CACHED, UNCACHED }

    /** Откуда взялся список адресов; [NONE] — сервер его не присылал. */
    enum class IpsSource { UPDATED, CACHED, NONE }

    fun applyLine(
        rule: ServerRoutingRule,
        content: ContentSource,
        ips: IpsSource,
        ipsCount: Int?,
        ipsSha: String?,
    ): String {
        val headline = when {
            content == ContentSource.CACHED && ips == IpsSource.CACHED ->
                "Маршрутизация сервера из кеша"
            content == ContentSource.UPDATED && ips == IpsSource.UPDATED ->
                "Маршрутизация сервера обновлена"
            else ->
                "Маршрутизация сервера (домены: ${contentWord(content)}, " +
                    "адреса: ${ipsWord(ips)})"
        }
        val apps = rule.packages.size + rule.presetRed.size + rule.presetOrange.size
        val tail = if (ips == IpsSource.NONE) {
            "адресов нет"
        } else {
            "адресов ${ipsCount ?: 0}, sha ${ipsSha.orEmpty().take(12)}"
        }
        return "$headline: доменов ${rule.domains.size}, приложений $apps, $tail"
    }

    private fun contentWord(source: ContentSource): String = when (source) {
        ContentSource.UPDATED -> "обновлены"
        ContentSource.CACHED -> "из кеша"
        ContentSource.UNCACHED -> "без кеша"
    }

    private fun ipsWord(source: IpsSource): String = when (source) {
        IpsSource.UPDATED -> "обновлены"
        IpsSource.CACHED -> "из кеша"
        IpsSource.NONE -> "нет"
    }
}
