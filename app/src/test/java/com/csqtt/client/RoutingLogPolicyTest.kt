package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingLogPolicyTest {
    private val sha = "5cf0b8862ad4" + "0".repeat(52)

    // Приложения считаются вместе с пресетами: 2 + 1 + 1 = 4.
    private val rule = ServerRoutingRule(
        isWhite = false,
        packages = listOf("com.example.app", "com.example.two"),
        domains = listOf("example.ru", "example.com", "example.net"),
        presetRed = listOf("ru.example.red"),
        presetOrange = listOf("ru.example.orange"),
    )

    private fun line(
        content: RoutingLogPolicy.ContentSource,
        ips: RoutingLogPolicy.IpsSource,
        change: RoutingLogPolicy.ContentChange = RoutingLogPolicy.ContentChange.FIRST,
        ipsCount: Int? = 6710,
        ipsSha: String? = sha,
    ) = RoutingLogPolicy.applyLine(rule, content, change, ips, ipsCount, ipsSha)

    @Test fun `both parts from the cache are one line`() {
        assertEquals(
            "Маршрутизация сервера из кеша: доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.CACHED),
        )
    }

    @Test fun `both parts updated are one line`() {
        assertEquals(
            "Маршрутизация сервера обновлена: доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(
                RoutingLogPolicy.ContentSource.UPDATED,
                RoutingLogPolicy.IpsSource.UPDATED,
                RoutingLogPolicy.ContentChange.BOTH,
            ),
        )
    }

    /**
     * Правка списка приложений меняет `content_sha` всего блока, поэтому сервер
     * пересылает домены заново — хотя они те же. Строка обязана называть именно
     * изменение, иначе «домены: обновлены» читается как «список доменов изменён».
     */
    @Test fun `application only change leaves the domains unnamed as changed`() {
        assertEquals(
            "Маршрутизация сервера (приложения изменены, домены без изменений, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(
                RoutingLogPolicy.ContentSource.UPDATED,
                RoutingLogPolicy.IpsSource.CACHED,
                RoutingLogPolicy.ContentChange.APPS,
            ),
        )
    }

    @Test fun `domain only change names the domains and keeps the applications quiet`() {
        assertEquals(
            "Маршрутизация сервера (домены изменены, приложения без изменений, адреса: обновлены): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(
                RoutingLogPolicy.ContentSource.UPDATED,
                RoutingLogPolicy.IpsSource.UPDATED,
                RoutingLogPolicy.ContentChange.DOMAINS,
            ),
        )
    }

    @Test fun `domain and application change together name both`() {
        assertEquals(
            "Маршрутизация сервера (домены и приложения изменены, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(
                RoutingLogPolicy.ContentSource.UPDATED,
                RoutingLogPolicy.IpsSource.CACHED,
                RoutingLogPolicy.ContentChange.BOTH,
            ),
        )
    }

    @Test fun `resent identical content is not called a change`() {
        assertEquals(
            "Маршрутизация сервера (контент переслан без изменений, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(
                RoutingLogPolicy.ContentSource.UPDATED,
                RoutingLogPolicy.IpsSource.CACHED,
                RoutingLogPolicy.ContentChange.NONE,
            ),
        )
    }

    @Test fun `first delivery names the content as updated`() {
        assertEquals(
            "Маршрутизация сервера (контент обновлён, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.UPDATED, RoutingLogPolicy.IpsSource.CACHED),
        )
    }

    @Test fun `cached content with updated ips names both sources`() {
        assertEquals(
            "Маршрутизация сервера (контент из кеша, адреса: обновлены): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.UPDATED),
        )
    }

    @Test fun `old server without content sha is named uncached`() {
        assertEquals(
            "Маршрутизация сервера (контент без кеша, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.UNCACHED, RoutingLogPolicy.IpsSource.CACHED),
        )
    }

    @Test fun `rule without ips prints no count and no sha`() {
        assertEquals(
            "Маршрутизация сервера (контент из кеша, адреса: нет): " +
                "доменов 3, приложений 4, адресов нет",
            line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.NONE, ipsCount = null, ipsSha = null),
        )
    }

    @Test fun `uncached rule without ips prints both sources`() {
        assertEquals(
            "Маршрутизация сервера (контент без кеша, адреса: нет): " +
                "доменов 3, приложений 4, адресов нет",
            line(RoutingLogPolicy.ContentSource.UNCACHED, RoutingLogPolicy.IpsSource.NONE, ipsCount = null, ipsSha = null),
        )
    }

    @Test fun `sha is truncated to twelve characters`() {
        val line = line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.CACHED)
        assertTrue(line.contains("sha 5cf0b8862ad4"))
        assertFalse(line.contains(sha))
    }

    @Test fun `applications are counted together with presets`() {
        val line = line(RoutingLogPolicy.ContentSource.UPDATED, RoutingLogPolicy.IpsSource.UPDATED)
        assertTrue(line.contains("приложений 4"))
    }

    private fun content(
        domains: List<String> = listOf("example.ru"),
        packages: List<String> = listOf("com.example.app"),
        presetRed: List<String> = emptyList(),
        presetOrange: List<String> = emptyList(),
    ) = CachedRoutingContent(
        sha = "a".repeat(64),
        domains = domains,
        packages = packages,
        presetRed = presetRed,
        presetOrange = presetOrange,
    )

    @Test fun `a delivery without a previous cache is a first one`() {
        assertEquals(
            RoutingLogPolicy.ContentChange.FIRST,
            RoutingLogPolicy.change(null, content()),
        )
    }

    @Test fun `only domains changed are named as a domain change`() {
        assertEquals(
            RoutingLogPolicy.ContentChange.DOMAINS,
            RoutingLogPolicy.change(content(), content(domains = listOf("other.ru"))),
        )
    }

    @Test fun `only packages changed are named as an application change`() {
        assertEquals(
            RoutingLogPolicy.ContentChange.APPS,
            RoutingLogPolicy.change(content(), content(packages = listOf("com.example.two"))),
        )
    }

    @Test fun `a preset change counts as an application change`() {
        assertEquals(
            RoutingLogPolicy.ContentChange.APPS,
            RoutingLogPolicy.change(content(), content(presetOrange = listOf("ru.example.orange"))),
        )
    }

    @Test fun `domains and applications changed together are a full change`() {
        assertEquals(
            RoutingLogPolicy.ContentChange.BOTH,
            RoutingLogPolicy.change(
                content(),
                content(domains = listOf("other.ru"), packages = listOf("com.example.two")),
            ),
        )
    }

    @Test fun `identical content under a new sha changed nothing`() {
        assertEquals(
            RoutingLogPolicy.ContentChange.NONE,
            RoutingLogPolicy.change(content(), content().copy(sha = "b".repeat(64))),
        )
    }
}
