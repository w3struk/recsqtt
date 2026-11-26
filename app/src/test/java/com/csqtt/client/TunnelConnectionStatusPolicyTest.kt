package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelConnectionStatusPolicyTest {
    private fun status(
        emptyProfiles: Boolean = false,
        stopping: Boolean = false,
        wifiPaused: Boolean = false,
        networkPauseReason: PhysicalNetworkPauseReason? = null,
        connected: Boolean = false,
        stalled: Boolean = false,
        starting: Boolean = false,
        running: Boolean = false,
    ): TunnelConnectionStatus = tunnelConnectionStatus(
        emptyProfiles = emptyProfiles,
        stopping = stopping,
        wifiPaused = wifiPaused,
        networkPauseReason = networkPauseReason,
        connected = connected,
        stalled = stalled,
        starting = starting,
        running = running,
    )

    @Test
    fun emptyProfilesWinOverEverything() {
        assertEquals(
            TunnelConnectionStatus.OFF,
            status(
                emptyProfiles = true,
                stopping = true,
                wifiPaused = true,
                networkPauseReason = PhysicalNetworkPauseReason.OFFLINE,
                connected = true,
                stalled = true,
                starting = true,
                running = true,
            ),
        )
    }

    @Test
    fun stoppingWinsOverWaitingAndConnection() {
        assertEquals(
            TunnelConnectionStatus.STOPPING,
            status(
                stopping = true,
                connected = true,
                running = true,
            ),
        )
    }

    @Test
    fun wifiPauseWinsOverNetworkPauseAndConnection() {
        assertEquals(
            TunnelConnectionStatus.WAITING_WIFI,
            status(
                wifiPaused = true,
                networkPauseReason = PhysicalNetworkPauseReason.OFFLINE,
                connected = true,
                running = true,
            ),
        )
    }

    @Test
    fun networkPauseWinsOverConnectionAndStall() {
        assertEquals(
            TunnelConnectionStatus.WAITING_NETWORK,
            status(
                networkPauseReason = PhysicalNetworkPauseReason.AIRPLANE_MODE,
                connected = true,
                stalled = true,
                running = true,
            ),
        )
    }

    @Test
    fun runningWithoutReadyIsConnectingNotConnected() {
        assertEquals(
            TunnelConnectionStatus.CONNECTING,
            status(
                starting = false,
                running = true,
                connected = false,
                stalled = false,
            ),
        )
    }

    @Test
    fun startingBeforeProcessIsConnecting() {
        assertEquals(
            TunnelConnectionStatus.CONNECTING,
            status(starting = true, running = false),
        )
    }

    @Test
    fun stallTurnsConnectingIntoNoConnection() {
        assertEquals(
            TunnelConnectionStatus.NO_CONNECTION,
            status(running = true, stalled = true),
        )
    }

    @Test
    fun readyBeatsStaleStall() {
        assertEquals(
            TunnelConnectionStatus.CONNECTED,
            status(running = true, connected = true, stalled = true),
        )
    }

    @Test
    fun idleManagerIsOff() {
        assertEquals(TunnelConnectionStatus.OFF, status())
    }

    @Test
    fun textsMatchStates() {
        assertEquals("Отключено", tunnelConnectionStatusText(TunnelConnectionStatus.OFF, null))
        assertEquals("Остановка", tunnelConnectionStatusText(TunnelConnectionStatus.STOPPING, null))
        assertEquals(
            "Ожидание. Автопауза при Wi-Fi",
            tunnelConnectionStatusText(TunnelConnectionStatus.WAITING_WIFI, null),
        )
        assertEquals(
            "Ожидание. Режим полёта",
            tunnelConnectionStatusText(
                TunnelConnectionStatus.WAITING_NETWORK,
                PhysicalNetworkPauseReason.AIRPLANE_MODE,
            ),
        )
        assertEquals(
            "Ожидание. Сеть отключена",
            tunnelConnectionStatusText(TunnelConnectionStatus.WAITING_NETWORK, null),
        )
        assertEquals("Подключено", tunnelConnectionStatusText(TunnelConnectionStatus.CONNECTED, null))
        assertEquals(
            "Нет соединения",
            tunnelConnectionStatusText(TunnelConnectionStatus.NO_CONNECTION, null),
        )
        assertEquals("Подключение", tunnelConnectionStatusText(TunnelConnectionStatus.CONNECTING, null))
    }

    @Test
    fun shortTextsForTileAndWidget() {
        assertEquals("Отключено", tunnelConnectionShortStatusText(TunnelConnectionStatus.OFF))
        assertEquals("Остановка", tunnelConnectionShortStatusText(TunnelConnectionStatus.STOPPING))
        assertEquals("Ожидание Wi-Fi", tunnelConnectionShortStatusText(TunnelConnectionStatus.WAITING_WIFI))
        assertEquals("Ожидание сети", tunnelConnectionShortStatusText(TunnelConnectionStatus.WAITING_NETWORK))
        assertEquals("Подключено", tunnelConnectionShortStatusText(TunnelConnectionStatus.CONNECTED))
        assertEquals("Нет соединения", tunnelConnectionShortStatusText(TunnelConnectionStatus.NO_CONNECTION))
        assertEquals("Подключение", tunnelConnectionShortStatusText(TunnelConnectionStatus.CONNECTING))
    }

    @Test
    fun timerHiddenBeforeFirstReady() {
        assertFalse(showsSessionTimer(TunnelConnectionStatus.CONNECTING, 0L))
        assertFalse(showsSessionTimer(TunnelConnectionStatus.NO_CONNECTION, 0L))
        assertFalse(showsSessionTimer(TunnelConnectionStatus.CONNECTED, null))
        assertTrue(showsSessionTimer(TunnelConnectionStatus.CONNECTED, 0L))
    }

    @Test
    fun frozenTimerStaysVisibleDuringWaitingAndStop() {
        assertTrue(showsSessionTimer(TunnelConnectionStatus.WAITING_WIFI, 42L))
        assertTrue(showsSessionTimer(TunnelConnectionStatus.WAITING_NETWORK, 42L))
        assertTrue(showsSessionTimer(TunnelConnectionStatus.STOPPING, 42L))
    }
}
