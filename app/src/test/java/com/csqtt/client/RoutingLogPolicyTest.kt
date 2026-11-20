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
        ipsCount: Int? = 6710,
        ipsSha: String? = sha,
    ) = RoutingLogPolicy.applyLine(rule, content, ips, ipsCount, ipsSha)

    @Test fun `both parts from the cache are one line`() {
        assertEquals(
            "Маршрутизация сервера из кеша: доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.CACHED),
        )
    }

    @Test fun `both parts updated are one line`() {
        assertEquals(
            "Маршрутизация сервера обновлена: доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.UPDATED, RoutingLogPolicy.IpsSource.UPDATED),
        )
    }

    @Test fun `updated content with cached ips names both sources`() {
        assertEquals(
            "Маршрутизация сервера (домены: обновлены, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.UPDATED, RoutingLogPolicy.IpsSource.CACHED),
        )
    }

    @Test fun `cached content with updated ips names both sources`() {
        assertEquals(
            "Маршрутизация сервера (домены: из кеша, адреса: обновлены): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.UPDATED),
        )
    }

    @Test fun `old server without content sha is named uncached`() {
        assertEquals(
            "Маршрутизация сервера (домены: без кеша, адреса: из кеша): " +
                "доменов 3, приложений 4, адресов 6710, sha 5cf0b8862ad4",
            line(RoutingLogPolicy.ContentSource.UNCACHED, RoutingLogPolicy.IpsSource.CACHED),
        )
    }

    @Test fun `rule without ips prints no count and no sha`() {
        assertEquals(
            "Маршрутизация сервера (домены: из кеша, адреса: нет): " +
                "доменов 3, приложений 4, адресов нет",
            line(RoutingLogPolicy.ContentSource.CACHED, RoutingLogPolicy.IpsSource.NONE, null, null),
        )
    }

    @Test fun `uncached rule without ips prints both sources`() {
        assertEquals(
            "Маршрутизация сервера (домены: без кеша, адреса: нет): " +
                "доменов 3, приложений 4, адресов нет",
            line(RoutingLogPolicy.ContentSource.UNCACHED, RoutingLogPolicy.IpsSource.NONE, null, null),
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
}
