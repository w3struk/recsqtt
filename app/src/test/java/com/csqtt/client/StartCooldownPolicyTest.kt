package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartCooldownPolicyTest {
    private val window = CsqttConstants.Timeouts.POST_STOP_START_COOLDOWN_MS

    @Test
    fun noStopEverMeansNoCooldown() {
        assertFalse(StartCooldownPolicy.active(lastStopElapsedMs = 0L, nowElapsedMs = 0L))
        assertFalse(StartCooldownPolicy.active(lastStopElapsedMs = 0L, nowElapsedMs = 100L))
    }

    @Test
    fun startRightAfterStopIsBlocked() {
        assertTrue(StartCooldownPolicy.active(lastStopElapsedMs = 5_000L, nowElapsedMs = 5_000L))
        assertTrue(StartCooldownPolicy.active(lastStopElapsedMs = 5_000L, nowElapsedMs = 5_299L))
    }

    @Test
    fun windowEndsExactlyAtItsLength() {
        assertFalse(StartCooldownPolicy.active(lastStopElapsedMs = 5_000L, nowElapsedMs = 5_000L + window))
        assertFalse(StartCooldownPolicy.active(lastStopElapsedMs = 5_000L, nowElapsedMs = 5_000L + window + 1L))
    }

    @Test
    fun clockGoingBackwardsDoesNotBlockStart() {
        assertFalse(StartCooldownPolicy.active(lastStopElapsedMs = 5_000L, nowElapsedMs = 4_999L))
    }

    @Test
    fun explicitWindowIsHonoured() {
        assertTrue(StartCooldownPolicy.active(lastStopElapsedMs = 10L, nowElapsedMs = 15L, windowMs = 10L))
        assertFalse(StartCooldownPolicy.active(lastStopElapsedMs = 10L, nowElapsedMs = 20L, windowMs = 10L))
    }
}
