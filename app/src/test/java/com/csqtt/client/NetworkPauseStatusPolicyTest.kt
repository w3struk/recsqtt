package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NetworkPauseStatusPolicyTest {
    @Test
    fun offlineAndAirplaneModeHaveDistinctTexts() {
        assertEquals(
            "Ожидание. Сеть отключена",
            networkPauseStatusText(PhysicalNetworkPauseReason.OFFLINE),
        )
        assertEquals(
            "Ожидание. Режим полёта",
            networkPauseStatusText(PhysicalNetworkPauseReason.AIRPLANE_MODE),
        )
        assertNotEquals(
            networkPauseStatusText(PhysicalNetworkPauseReason.OFFLINE),
            networkPauseStatusText(PhysicalNetworkPauseReason.AIRPLANE_MODE),
        )
    }
}
