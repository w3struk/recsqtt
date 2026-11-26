package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupTimingPolicyTest {
    @Test
    fun elapsedSecondsRoundsToNearestSecond() {
        assertEquals(12L, StartupTimingPolicy.elapsedSeconds(1_000L, 13_400L))
        assertEquals(13L, StartupTimingPolicy.elapsedSeconds(1_000L, 13_500L))
    }

    @Test
    fun elapsedSecondsNeverGoesNegative() {
        assertEquals(0L, StartupTimingPolicy.elapsedSeconds(2_000L, 1_000L))
    }

    @Test
    fun messagesNameBothProgressPoints() {
        assertEquals(
            "[СТАРТ] Первый поток готов через 12 с",
            StartupTimingPolicy.firstReadyMessage(0L, 12_000L),
        )
        assertEquals(
            "[СТАРТ] Все 100 потоков готовы через 63 с",
            StartupTimingPolicy.allReadyMessage(0L, 63_000L, 100),
        )
    }

    @Test
    fun effectiveTargetPrefersServerLimitButCannotExceedRequest() {
        assertEquals(100, StartupTimingPolicy.effectiveTarget(100, null))
        assertEquals(60, StartupTimingPolicy.effectiveTarget(100, 60))
        assertEquals(100, StartupTimingPolicy.effectiveTarget(100, 200))
        assertEquals(60, StartupTimingPolicy.effectiveTarget(0, 60))
    }
}
