package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialWaitLogPolicyTest {
    @Test
    fun parsesCredentialRequestAndInvalidationAsRequested() {
        assertEquals(
            CredentialSignal.Requested(3),
            CredentialWaitLogPolicy.parse("[КРЕД #3] Запрос (хеш: abcd1234...)"),
        )
        assertEquals(
            CredentialSignal.Requested(4),
            CredentialWaitLogPolicy.parse("[КРЕД #4] Невалиден, запрашиваем новый"),
        )
    }

    @Test
    fun parsesAcquiredCredential() {
        assertEquals(
            CredentialSignal.Acquired(2),
            CredentialWaitLogPolicy.parse(
                "[КРЕД #2] OK, TURN: [\"1.2.3.4:3478\"], до 10 воркеров, identity deadbeef",
            ),
        )
    }

    @Test
    fun parsesRetryReasonAndDelay() {
        val signal = CredentialWaitLogPolicy.parse(
            "[КРЕД #1] Ошибка: step=auth: DNS lookup failed. Повторяем через 30s...",
        )
        assertEquals(
            CredentialSignal.Retry(1, "step=auth: DNS lookup failed", "30 с"),
            signal,
        )
    }

    @Test
    fun ignoresUnrelatedClientLines() {
        assertNull(CredentialWaitLogPolicy.parse("[TURN] ChannelBind активен: канал 0x4000"))
        assertNull(CredentialWaitLogPolicy.parse("[ВОРКЕР #1] Задача отменена"))
    }

    @Test
    fun sanitizeReasonCollapsesWhitespaceAndLongTokens() {
        val reason = CredentialWaitLogPolicy.sanitizeReason(
            "step=auth\n  failed with   token=abcdefghijklmnopqrstuvwxyz0123456789ABCD.",
        )

        assertEquals("step=auth failed with token=…", reason)
    }

    @Test
    fun sanitizeReasonBoundsLength() {
        val reason = CredentialWaitLogPolicy.sanitizeReason("слово ".repeat(80))

        assertEquals(160, reason.length)
        assertTrue(reason.endsWith("…"))
    }

    @Test
    fun formatDelayConvertsRustDurations() {
        assertEquals("30 с", CredentialWaitLogPolicy.formatDelay("30s"))
        assertEquals("2 с", CredentialWaitLogPolicy.formatDelay("2s"))
        assertEquals("1.5 с", CredentialWaitLogPolicy.formatDelay("1.5s"))
        assertEquals("0.25 с", CredentialWaitLogPolicy.formatDelay("250ms"))
        assertEquals("120 с", CredentialWaitLogPolicy.formatDelay("2m"))
        assertEquals("soon", CredentialWaitLogPolicy.formatDelay("soon"))
    }

    @Test
    fun waitingMessageUsesSingularVerbForOneGroup() {
        assertEquals(
            "[КРЕД] 1 из 10 групп ждёт TURN-креды",
            CredentialWaitLogPolicy.waitingMessage(1, 10, null),
        )
    }

    @Test
    fun waitingMessageCarriesLastFailureAndBackoff() {
        val retry = CredentialSignal.Retry(1, "DNS lookup failed", "30 с")

        assertEquals(
            "[КРЕД] 2 из 10 групп ждут TURN-креды · последний отказ: DNS lookup failed · пауза 30 с",
            CredentialWaitLogPolicy.waitingMessage(2, 10, retry),
        )
    }

    @Test
    fun groupsForTargetRoundsUpByCohortSize() {
        assertEquals(10, CredentialWaitLogPolicy.groupsForTarget(100))
        assertEquals(1, CredentialWaitLogPolicy.groupsForTarget(5))
        assertEquals(2, CredentialWaitLogPolicy.groupsForTarget(11))
        assertEquals(1, CredentialWaitLogPolicy.groupsForTarget(0))
    }

    @Test
    fun trackerReportsWaitProgressWithoutRepeatingIdenticalState() {
        val tracker = CredentialWaitTracker()
        val first = CredentialSignal.Retry(1, "DNS lookup failed", "2 с")
        val second = CredentialSignal.Retry(2, "486 from relay", "30 с")

        assertEquals(
            "[КРЕД] 1 из 10 групп ждёт TURN-креды · последний отказ: DNS lookup failed · пауза 2 с",
            tracker.apply(first, 10),
        )
        assertNull(tracker.apply(first, 10))
        assertEquals(
            "[КРЕД] 2 из 10 групп ждут TURN-креды · последний отказ: 486 from relay · пауза 30 с",
            tracker.apply(second, 10),
        )
        assertEquals(
            "[КРЕД] 1 из 10 групп ждёт TURN-креды · последний отказ: 486 from relay · пауза 30 с",
            tracker.apply(CredentialSignal.Acquired(1), 10),
        )
        assertEquals(
            "[КРЕД] Все группы получили TURN-креды (10) ✓",
            tracker.apply(CredentialSignal.Acquired(2), 10),
        )
        assertNull(tracker.apply(CredentialSignal.Acquired(3), 10))
        assertNull(tracker.apply(CredentialSignal.Requested(4), 10))
    }
}
