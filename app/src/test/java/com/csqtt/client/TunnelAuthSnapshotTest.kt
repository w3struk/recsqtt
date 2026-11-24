package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelAuthSnapshotTest {
    @Test
    fun `default snapshot is a loading sentinel`() {
        assertFalse(TunnelAuthSnapshot().isLoaded)
        assertEquals(Int.MIN_VALUE, TunnelAuthSnapshot().profile)
    }

    @Test
    fun `profile snapshot is loaded`() {
        assertTrue(TunnelAuthSnapshot(profile = 0).isLoaded)
    }

    @Test
    fun `no profile is a loaded snapshot`() {
        val snapshot = TunnelAuthSnapshot(profile = ProfileRegistryPolicy.NO_PROFILE)

        assertTrue(snapshot.isLoaded)
        assertFalse(snapshot.profile >= 0)
        assertEquals(ProfileRegistryPolicy.NO_PROFILE, snapshot.profile)
    }

    @Test
    fun `snapshot keeps the participant link`() {
        val snapshot = TunnelAuthSnapshot(
            profile = 0,
            csqttLink = "csqtt://connect?peer=example.test",
        )

        assertTrue(snapshot.isLoaded)
        assertEquals("csqtt://connect?peer=example.test", snapshot.csqttLink)
    }
}
