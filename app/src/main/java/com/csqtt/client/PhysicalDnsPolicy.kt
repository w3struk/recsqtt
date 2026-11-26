package com.csqtt.client

/**
 * Снимок одной сети для выбора bootstrap-DNS.
 *
 * Поля `isVpn` и `hasInternet` уже отфильтрованы читателем `LinkProperties`,
 * но политика проверяет их сама: чистая функция не должна зависеть от того,
 * что вызывающий не забыл фильтр.
 */
internal data class PhysicalDnsCandidate(
    val active: Boolean,
    /** «VK достижим», а не «DNS жив»: под captive portal DNS может работать. */
    val usable: Boolean,
    val isVpn: Boolean,
    val hasInternet: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val ipv4Servers: List<String>,
)

/**
 * Выбор DNS физической сети для bootstrap-резолва клиента.
 *
 * Клиент запускается до туннеля, поэтому его DNS не могут быть адресами,
 * которые приложение само поставило на TUN: при активном VPN `activeNetwork`
 * — это VPN, и его DNS клиенту напрямую недоступен. Отсюда фильтр `isVpn`
 * и чтение всех сетей, а не только активной.
 */
internal object PhysicalDnsPolicy {
    const val MAX_SERVERS = 2

    /**
     * `usable` — это «VK достижим», а не «DNS жив», поэтому он не фильтрует,
     * а только сортирует: активная сеть важнее, затем уже проверенная.
     * Сортировка стабильная, поэтому исходный порядок системных сетей
     * сохраняется для равных кандидатов.
     */
    fun select(candidates: List<PhysicalDnsCandidate>): List<String> {
        val ordered = candidates
            .filter { !it.isVpn && it.hasInternet }
            .sortedWith(
                compareByDescending<PhysicalDnsCandidate> { it.active }
                    .thenByDescending { it.usable }
                    .thenByDescending { it.isWifi }
            )
        val chosen = ordered.firstOrNull { it.ipv4Servers.isNotEmpty() } ?: return emptyList()
        return normalize(chosen.ipv4Servers)
    }

    /**
     * Trim, только IPv4-литералы, без ведущих нулей, дедупликация, максимум два.
     * `InetAddress.getByName` не используется: он резолвит имена.
     */
    fun normalize(raw: List<String>): List<String> =
        raw.asSequence()
            .map { it.trim() }
            .filter(::isIpv4Literal)
            .distinct()
            .take(MAX_SERVERS)
            .toList()

    /** Пустой список даёт `UPDATE_DNS|` — это команда очистки link-тира. */
    fun command(servers: List<String>): String = "UPDATE_DNS|" + servers.joinToString(",")

    private fun isIpv4Literal(value: String): Boolean {
        val octets = value.split('.')
        if (octets.size != 4) return false
        return octets.all { octet ->
            octet.isNotEmpty() &&
                octet.length <= 3 &&
                octet.all { it in '0'..'9' } &&
                // Ведущий ноль — не литерал, а неоднозначная запись ( octal ).
                (octet.length == 1 || octet[0] != '0') &&
                (octet.toIntOrNull() ?: return false) in 0..255
        }
    }
}

/**
 * Начальный список DNS для argv. Пустой список не добавляет флаг вовсе:
 * отсутствие `-dns-servers` в Rust эквивалентно пустому значению.
 */
internal object PhysicalDnsArgvPolicy {
    fun flag(servers: List<String>): List<String> =
        if (servers.isEmpty()) {
            emptyList()
        } else {
            listOf("-dns-servers", servers.joinToString(","))
        }
}
