package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCallDurabilityTest {
    private val ttl = 1_000L

    private fun entry(
        callId: String = "call-1",
        tokenFp: String = "fp-1",
        accountUserId: String = "",
        mode: String = CsqttConstants.VkAutoHash.MODE_AUTO_API,
        createdAtMs: Long = 0L,
        failCount: Int = 0,
        lastFailMs: Long? = null,
    ) = VkCallEntry(
        callId = callId,
        hash = "abcdef0123456789",
        joinLink = "https://vk.ru/call/join/$callId",
        createdAtMs = createdAtMs,
        tokenFp = tokenFp,
        accountUserId = accountUserId,
        mode = mode,
        failCount = failCount,
        lastFailMs = lastFailMs,
    )

    @Test
    fun eligibleRequiresSameToken() {
        val own = entry(callId = "own", tokenFp = "fp-1")
        val foreign = entry(callId = "foreign", tokenFp = "fp-2")

        val result = VkCallReusePolicy.eligible(listOf(own, foreign), "fp-1", "", nowMs = 10, ttlMs = ttl)

        assertEquals(listOf(own), result)
    }

    @Test
    fun eligibleAcceptsRotatedTokenOfTheSameAccount() {
        val own = entry(callId = "own", tokenFp = "old-fp", accountUserId = "user-1")

        val result = VkCallReusePolicy.eligible(listOf(own), "new-fp", "user-1", nowMs = 10, ttlMs = ttl)

        assertEquals(listOf(own), result)
    }

    @Test
    fun eligibleRejectsAnotherAccount() {
        val foreign = entry(callId = "foreign", tokenFp = "fp-1", accountUserId = "user-2")

        val result = VkCallReusePolicy.eligible(listOf(foreign), "fp-1", "user-1", nowMs = 10, ttlMs = ttl)

        assertTrue(result.isEmpty())
    }

    @Test
    fun ownerMatchesOnUserIdWhenBothSidesKnowIt() {
        val entry = entry(tokenFp = "old-fp", accountUserId = "user-1")

        assertTrue(VkCallOwnerPolicy.matches(entry, "new-fp", "user-1"))
        assertFalse(VkCallOwnerPolicy.matches(entry, "old-fp", "user-2"))
    }

    @Test
    fun ownerFallsBackToTokenFingerprintWhenBothSidesLackUserId() {
        val entry = entry(tokenFp = "fp-1", accountUserId = "")

        assertTrue(VkCallOwnerPolicy.matches(entry, "fp-1", ""))
        assertFalse(VkCallOwnerPolicy.matches(entry, "fp-2", ""))
    }

    @Test
    fun ownerTreatsUnknownSideAsSameAccount() {
        val known = entry(callId = "known", tokenFp = "fp-1", accountUserId = "user-1")
        val unknown = entry(callId = "unknown", tokenFp = "fp-1", accountUserId = "")

        assertTrue(VkCallOwnerPolicy.matches(known, "fp-2", ""))
        assertTrue(VkCallOwnerPolicy.matches(unknown, "fp-2", "user-1"))
    }

    @Test
    fun claimStampsUnknownOwnerWithCurrentAccount() {
        val unknown = entry(tokenFp = "fp-1", accountUserId = "")

        assertEquals("user-1", VkCallOwnerPolicy.claim(unknown, "user-1").accountUserId)
    }

    @Test
    fun claimKeepsKnownOwnerAndIgnoresBlankAccount() {
        val known = entry(tokenFp = "fp-1", accountUserId = "user-1")

        assertEquals("user-1", VkCallOwnerPolicy.claim(known, "user-2").accountUserId)
        assertEquals("", VkCallOwnerPolicy.claim(known.copy(accountUserId = ""), "  ").accountUserId)
    }

    @Test
    fun eligibleRequiresAutoApiMode() {
        val autoApi = entry(callId = "auto-api", mode = CsqttConstants.VkAutoHash.MODE_AUTO_API)
        val autoJs = entry(callId = "auto-js", mode = CsqttConstants.VkAutoHash.MODE_AUTO_JS)
        val manual = entry(callId = "manual", mode = CsqttConstants.VkAutoHash.MODE_MANUAL)

        val result = VkCallReusePolicy.eligible(
            listOf(autoApi, autoJs, manual),
            "fp-1",
            "",
            nowMs = 10,
            ttlMs = ttl,
        )

        assertEquals(listOf(autoApi), result)
    }

    @Test
    fun eligibleHonoursTtlBoundary() {
        val fresh = entry(callId = "fresh", createdAtMs = 0)

        val atBoundary = VkCallReusePolicy.eligible(listOf(fresh), "fp-1", "", nowMs = ttl, ttlMs = ttl)
        val pastBoundary = VkCallReusePolicy.eligible(listOf(fresh), "fp-1", "", nowMs = ttl + 1, ttlMs = ttl)

        assertEquals(listOf(fresh), atBoundary)
        assertTrue(pastBoundary.isEmpty())
    }

    @Test
    fun eligibleDropsEntryWithExhaustedFailures() {
        val almost = entry(callId = "almost", failCount = VkCallReusePolicy.MAX_FAILURES - 1)
        val exhausted = entry(callId = "exhausted", failCount = VkCallReusePolicy.MAX_FAILURES)

        val result = VkCallReusePolicy.eligible(listOf(almost, exhausted), "fp-1", "", nowMs = 10, ttlMs = ttl)

        assertEquals(listOf(almost), result)
    }

    @Test
    fun eligiblePreservesInputOrder() {
        val first = entry(callId = "first", createdAtMs = 3)
        val second = entry(callId = "second", createdAtMs = 1)
        val third = entry(callId = "third", createdAtMs = 2)

        val result = VkCallReusePolicy.eligible(
            listOf(first, second, third),
            "fp-1",
            "",
            nowMs = 10,
            ttlMs = ttl,
        )

        assertEquals(listOf(first, second, third), result)
    }

    @Test
    fun selectSplitsKeepOverflowAndMissing() {
        val eligible = listOf(
            entry(callId = "c1"),
            entry(callId = "c2"),
            entry(callId = "c3"),
            entry(callId = "c4"),
            entry(callId = "c5"),
        )

        val selection = VkCallReusePolicy.select(eligible, requested = 2)

        assertEquals(listOf("c1", "c2"), selection.keep.map(VkCallEntry::callId))
        assertEquals(listOf("c3", "c4", "c5"), selection.overflow.map(VkCallEntry::callId))
        assertEquals(0, selection.missing)
    }

    @Test
    fun selectReportsMissingWhenNotEnoughCalls() {
        val eligible = listOf(entry(callId = "c1"), entry(callId = "c2"))

        val selection = VkCallReusePolicy.select(eligible, requested = 5)

        assertEquals(2, selection.keep.size)
        assertTrue(selection.overflow.isEmpty())
        assertEquals(3, selection.missing)
    }

    @Test
    fun selectWithNothingEligibleAsksForEveryCall() {
        val selection = VkCallReusePolicy.select(emptyList(), requested = 4)

        assertTrue(selection.keep.isEmpty())
        assertTrue(selection.overflow.isEmpty())
        assertEquals(4, selection.missing)
    }

    @Test
    fun firstFailureStartsCounter() {
        val failed = VkCallReusePolicy.registerFailure(entry(failCount = 0), nowMs = 1_000)

        assertEquals(1, failed.failCount)
        assertEquals(1_000L, failed.lastFailMs)
        assertFalse(VkCallReusePolicy.shouldDrop(failed))
    }

    @Test
    fun failuresInsideTheWindowAccumulate() {
        val first = VkCallReusePolicy.registerFailure(entry(failCount = 0), nowMs = 1_000)
        val second = VkCallReusePolicy.registerFailure(first, nowMs = 1_000 + VkCallReusePolicy.FAILURE_WINDOW_MS)

        assertEquals(2, second.failCount)
        assertEquals(1_000L + VkCallReusePolicy.FAILURE_WINDOW_MS, second.lastFailMs)
    }

    @Test
    fun failureAfterTheWindowRestartsTheCounter() {
        val first = VkCallReusePolicy.registerFailure(entry(failCount = 0), nowMs = 1_000)
        val restarted = VkCallReusePolicy.registerFailure(
            first,
            nowMs = 1_000 + VkCallReusePolicy.FAILURE_WINDOW_MS + 1,
        )

        assertEquals(1, restarted.failCount)
        assertEquals(1_000L + VkCallReusePolicy.FAILURE_WINDOW_MS + 1, restarted.lastFailMs)
        assertFalse(VkCallReusePolicy.shouldDrop(restarted))
    }

    @Test
    fun threeFailuresInsideTheWindowDropTheEntry() {
        var current = entry(failCount = 0)
        repeat(VkCallReusePolicy.MAX_FAILURES) { index ->
            current = VkCallReusePolicy.registerFailure(current, nowMs = 1_000L * (index + 1))
        }

        assertEquals(VkCallReusePolicy.MAX_FAILURES, current.failCount)
        assertTrue(VkCallReusePolicy.shouldDrop(current))
    }

    @Test
    fun tokenFingerprintIsStableAndLowercaseHex() {
        val first = VkCallReusePolicy.tokenFingerprint("vk-token")
        val second = VkCallReusePolicy.tokenFingerprint("vk-token")

        assertEquals(first, second)
        assertEquals(64, first.length)
        assertEquals(first.lowercase(), first)
        assertTrue(first.all { it in "0123456789abcdef" })
    }

    @Test
    fun tokenFingerprintDistinguishesTokens() {
        assertNotEquals(
            VkCallReusePolicy.tokenFingerprint("token-a"),
            VkCallReusePolicy.tokenFingerprint("token-b"),
        )
    }

    @Test
    fun tokenFingerprintOfBlankTokenIsEmpty() {
        assertEquals("", VkCallReusePolicy.tokenFingerprint(""))
        assertEquals("", VkCallReusePolicy.tokenFingerprint("   "))
    }

    @Test
    fun lifecycleKeepsCallsOnTransportEvents() {
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onUnexpectedExit())
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onPlannedRestart())
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onUserStop())
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onServiceDestroy())
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onStartFailure())
    }

    @Test
    fun lifecycleFinishesOnlyWhenTheTokenIsLostForGood() {
        assertEquals(VkCallLifecycle.FINISH, VkCallLifecyclePolicy.onTokenInvalid())
        assertEquals(VkCallLifecycle.FINISH, VkCallLifecyclePolicy.onLogout(tokenStillValid = true))
        assertEquals(VkCallLifecycle.FINISH, VkCallLifecyclePolicy.onExplicitClose())
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onLogout(tokenStillValid = false))
    }

    @Test
    fun lifecycleKeepsCallsAcrossProfileAndModeChanges() {
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onProfileSwitch())
        assertEquals(VkCallLifecycle.KEEP, VkCallLifecyclePolicy.onModeChanged())
    }

    @Test
    fun replacementBudgetAllowsExactlyMaxReplacements() {
        val budget = VkCallReplacementBudget(max = 2)

        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
        assertFalse(budget.tryConsume())
    }

    @Test
    fun replacementBudgetDefaultsToTwo() {
        val budget = VkCallReplacementBudget()

        assertTrue(budget.tryConsume())
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
    }

    @Test
    fun replacementBudgetResetRestoresTheAllowance() {
        val budget = VkCallReplacementBudget(max = 1)
        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())

        budget.reset()

        assertTrue(budget.tryConsume())
        assertFalse(budget.tryConsume())
    }

    @Test
    fun callDomainCodesMarkTheLinkDead() {
        listOf(951, 954, 9000, 9008, 9999).forEach { code ->
            assertTrue("code $code must be a call-domain code", VkCallEventPolicy.isCallDomainCode(code))
        }
    }

    @Test
    fun networkAndProtocolCodesKeepTheLinkAlive() {
        listOf(0, 14, 486, 401).forEach { code ->
            assertFalse("code $code must not be a call-domain code", VkCallEventPolicy.isCallDomainCode(code))
        }
    }
}
