package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class UptimeTimerPolicyTest {
    @Test
    fun withoutPausesCountsWallTime() {
        assertEquals(
            42L,
            elapsedSessionSeconds(startedAtMs = 1_000L, nowMs = 43_000L),
        )
    }

    @Test
    fun completedPausesAreExcluded() {
        // 10 минут сессии, 2 минуты в ожидании сети → 8 минут.
        assertEquals(
            480L,
            elapsedSessionSeconds(
                startedAtMs = 1_000L,
                nowMs = 601_000L,
                pausedTotalMs = 120_000L,
            ),
        )
    }

    @Test
    fun currentPauseFreezesTheCounter() {
        // Текущее время ушло вперёд, но пауза началась на 300-й секунде.
        assertEquals(
            300L,
            elapsedSessionSeconds(
                startedAtMs = 1_000L,
                nowMs = 900_000L,
                pausedTotalMs = 0L,
                pausedAtMs = 301_000L,
            ),
        )
    }

    @Test
    fun completedAndCurrentPausesStack() {
        assertEquals(
            500L,
            elapsedSessionSeconds(
                startedAtMs = 1_000L,
                nowMs = 901_000L,
                pausedTotalMs = 200_000L,
                pausedAtMs = 701_000L,
            ),
        )
    }

    @Test
    fun zeroStartMeansNoSession() {
        assertEquals(0L, elapsedSessionSeconds(startedAtMs = 0L, nowMs = 50_000L))
    }

    @Test
    fun backwardsClockNeverGoesNegative() {
        assertEquals(
            0L,
            elapsedSessionSeconds(
                startedAtMs = 10_000L,
                nowMs = 5_000L,
                pausedTotalMs = 20_000L,
            ),
        )
    }

    @Test
    fun partialSecondsRoundDown() {
        assertEquals(1L, elapsedSessionSeconds(startedAtMs = 1_000L, nowMs = 2_999L))
    }
}
