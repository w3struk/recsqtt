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
}
