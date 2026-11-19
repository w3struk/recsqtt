package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileSwitchPolicyTest {
    @Test
    fun sameProfileIsNotASwitchEvenWhileRunning() {
        assertEquals(
            ProfileSwitchAction.IGNORE,
            profileSwitchAction(
                sessionProfile = 2,
                nextProfile = 2,
                stopping = false,
                pausedForWifi = false,
                tunnelWanted = true,
            ),
        )
    }

    @Test
    fun liveTunnelReconnectsOnNewProfile() {
        assertEquals(
            ProfileSwitchAction.RECONNECT,
            profileSwitchAction(
                sessionProfile = 0,
                nextProfile = 3,
                stopping = false,
                pausedForWifi = false,
                tunnelWanted = true,
            ),
        )
    }

    /**
     * Первичный старт и ожидание перезапуска после сбоя: процесса ещё нет, но туннель
     * обязан подняться, поэтому переключение тоже идёт через перезапуск.
     */
    @Test
    fun pendingRunReconnectsBeforeAnyProcessExists() {
        assertEquals(
            ProfileSwitchAction.RECONNECT,
            profileSwitchAction(
                sessionProfile = 0,
                nextProfile = 1,
                stopping = false,
                pausedForWifi = false,
                tunnelWanted = true,
            ),
        )
    }

    @Test
    fun stoppedTunnelLeavesTheSwitchToTheNextStart() {
        assertEquals(
            ProfileSwitchAction.IGNORE,
            profileSwitchAction(
                sessionProfile = 0,
                nextProfile = 1,
                stopping = false,
                pausedForWifi = false,
                tunnelWanted = false,
            ),
        )
    }

    @Test
    fun stoppingTunnelIgnoresTheSwitch() {
        assertEquals(
            ProfileSwitchAction.IGNORE,
            profileSwitchAction(
                sessionProfile = 0,
                nextProfile = 1,
                stopping = true,
                pausedForWifi = false,
                tunnelWanted = true,
            ),
        )
    }

    /**
     * Автопауза сильнее намерения работать: процесс поднимает выход из паузы, и он же
     * читает настройки заново.
     */
    @Test
    fun wifiAutoPauseDefersToResume() {
        assertEquals(
            ProfileSwitchAction.DEFER_TO_RESUME,
            profileSwitchAction(
                sessionProfile = 0,
                nextProfile = 2,
                stopping = false,
                pausedForWifi = true,
                tunnelWanted = true,
            ),
        )
    }

    @Test
    fun wifiAutoPauseDefersEvenWithoutRunIntent() {
        assertEquals(
            ProfileSwitchAction.DEFER_TO_RESUME,
            profileSwitchAction(
                sessionProfile = 0,
                nextProfile = 2,
                stopping = false,
                pausedForWifi = true,
                tunnelWanted = false,
            ),
        )
    }
}
