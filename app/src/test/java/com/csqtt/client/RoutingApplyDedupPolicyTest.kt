package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingApplyDedupPolicyTest {
    private val rule = ServerRoutingRule(
        isWhite = false,
        packages = listOf("com.example.app"),
        domains = listOf("example.ru"),
    )

    private val contentSha = "a".repeat(64)
    private val ipsSha = "b".repeat(64)

    /**
     * Кадр, который сервер присылает при совпадении кешей: только sha, без
     * доменов и приложений (`content_cached`).
     */
    private fun cachedDelivery(
        contentSha: String = this.contentSha,
        ipsSha: String = this.ipsSha,
    ) = ServerRoutingRule(
        isWhite = false,
        packages = emptyList(),
        domains = emptyList(),
        ipsSha = ipsSha,
        contentSha = contentSha,
        contentCached = true,
    )

    /** Правило, которое приложение реально применяет: кеш уже подставлен в него. */
    private fun appliedFromCache() = cachedDelivery().copy(
        domains = listOf("example.ru"),
        packages = listOf("com.example.app"),
        presetRed = listOf("com.example.red"),
    )

    @Test fun `the same rule in one generation is a repeat`() {
        assertTrue(RoutingApplyDedupPolicy.isRepeat(rule, rule))
    }

    @Test fun `another rule is not a repeat`() {
        val other = rule.copy(domains = listOf("other.ru"))
        assertFalse(RoutingApplyDedupPolicy.isRepeat(rule, other))
    }

    @Test fun `nothing pushed yet is not a repeat`() {
        assertFalse(RoutingApplyDedupPolicy.isRepeat(null, rule))
    }

    @Test fun `a clear is never a repeat`() {
        assertFalse(RoutingApplyDedupPolicy.isRepeat(rule, null))
    }

    @Test fun `the same rule after a clear is applied again`() {
        val pushed: ServerRoutingRule? = null
        assertFalse(RoutingApplyDedupPolicy.isRepeat(pushed, rule))
    }

    /**
     * Периодическая сверка правила (GETCONF раз в 5 минут) приносит тот же кадр,
     * что и первичное применение, но без «тяжёлой» части: её даёт кеш. Сравнение
     * по значению «как есть» такие повторы не узнавало и каждый раз заново
     * применяло правило, писало строку в журнал и повторно отправляло клиенту
     * UPDATE_IPS/UPDATE_DOMAINS.
     */
    @Test fun `a cached delivery of the applied rule is a repeat`() {
        assertTrue(RoutingApplyDedupPolicy.isRepeat(appliedFromCache(), cachedDelivery()))
    }

    @Test fun `a cached delivery with another content sha is not a repeat`() {
        assertFalse(
            RoutingApplyDedupPolicy.isRepeat(appliedFromCache(), cachedDelivery(contentSha = "c".repeat(64))),
        )
    }

    @Test fun `a cached delivery with another ips sha is not a repeat`() {
        assertFalse(
            RoutingApplyDedupPolicy.isRepeat(appliedFromCache(), cachedDelivery(ipsSha = "d".repeat(64))),
        )
    }

    @Test fun `a cached delivery cannot borrow the content of a legacy rule without sha`() {
        val legacy = rule.copy(ipsSha = ipsSha, contentCached = false)
        assertFalse(RoutingApplyDedupPolicy.isRepeat(legacy, cachedDelivery()))
    }
}
