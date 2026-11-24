package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRegistryPolicyTest {

    @Test
    fun `fresh registry has no profiles and starts numbering at zero`() {
        assertEquals(emptyList<Int>(), ProfileRegistryPolicy.discover(null, emptySet()))
        assertEquals(0, ProfileRegistryPolicy.nextId(emptyList(), null))
        assertEquals(ProfileRegistryPolicy.NO_PROFILE, ProfileRegistryPolicy.resolveActive(
            ids = emptyList(),
            requested = ProfileRegistryPolicy.NO_PROFILE,
            linked = emptySet(),
        ))
    }

    @Test
    fun `discovery ignores blank links and unrelated suffixed keys`() {
        val linked = ProfileRegistryPolicy.linkedProfileIds(
            mapOf(
                "csqtt_link" to "",
                "csqtt_link_1" to "",
                "csqtt_link_2" to "csqtt://connect?v=2&host=example",
                "excluded_apps_5" to "com.example",
                "profile_name_4" to "Профиль 4",
                "csqtt_link_0" to "ignored",
            ),
            linkKeyName = "csqtt_link",
        )

        assertEquals(setOf(2), linked)
    }

    @Test
    fun `discovery returns only linked ids and injects neither zero nor the active id`() {
        assertEquals(
            listOf(1),
            ProfileRegistryPolicy.discover(active = 2, linked = setOf(1)),
        )
        assertEquals(
            listOf(1, 3),
            ProfileRegistryPolicy.discover(active = 0, linked = setOf(3, 1)),
        )
        assertEquals(emptyList<Int>(), ProfileRegistryPolicy.discover(active = 0, linked = emptySet()))
    }

    @Test
    fun `requested id stays active even without a link`() {
        assertEquals(
            2,
            ProfileRegistryPolicy.resolveActive(
                ids = listOf(0, 1, 2),
                requested = 2,
                linked = setOf(1),
            ),
        )
        assertEquals(
            0,
            ProfileRegistryPolicy.resolveActive(
                ids = listOf(0, 1),
                requested = 0,
                linked = setOf(1),
            ),
        )
    }

    @Test
    fun `missing requested id falls back to the first linked then the first id`() {
        assertEquals(
            1,
            ProfileRegistryPolicy.resolveActive(
                ids = listOf(0, 1),
                requested = 9,
                linked = setOf(1),
            ),
        )
        assertEquals(
            0,
            ProfileRegistryPolicy.resolveActive(
                ids = listOf(0, 1),
                requested = 9,
                linked = emptySet(),
            ),
        )
    }

    @Test
    fun `stored order is preserved by parse and format while discovery is sorted`() {
        val parsed = requireNotNull(ProfileRegistryPolicy.parseIds("2,0,2,1"))
        assertEquals(listOf(2, 0, 1), parsed)
        assertEquals("2,0,1", ProfileRegistryPolicy.formatIds(parsed))
        assertEquals(
            listOf(0, 1, 2),
            ProfileRegistryPolicy.discover(active = null, linked = setOf(2, 0, 1)),
        )
    }

    @Test
    fun `parse distinguishes an absent registry from a migrated empty one`() {
        assertNull("null means not migrated", ProfileRegistryPolicy.parseIds(null))
        assertNull("invalid-only means not migrated", ProfileRegistryPolicy.parseIds("abc,-1"))
        assertNull("the no-profile sentinel can never be a registry id", ProfileRegistryPolicy.parseIds("-1"))
        assertEquals("blank means migrated empty", emptyList<Int>(), ProfileRegistryPolicy.parseIds(""))
        assertEquals(emptyList<Int>(), ProfileRegistryPolicy.parseIds("   "))
        assertEquals(listOf(1), ProfileRegistryPolicy.parseIds("1,abc,-2,1"))
    }

    @Test
    fun `next id repairs a stale counter and never reuses a deleted id`() {
        assertEquals(6, ProfileRegistryPolicy.nextId(listOf(0, 5), storedNext = 2))
        assertEquals(5, ProfileRegistryPolicy.nextId(listOf(0, 1), storedNext = 5))
        assertEquals(4, ProfileRegistryPolicy.nextId(listOf(0, 1, 2, 3), storedNext = 4))
    }

    @Test
    fun `next id keeps the monotonic counter after deleting every profile`() {
        assertEquals(0, ProfileRegistryPolicy.nextId(emptyList(), storedNext = null))
        assertEquals(0, ProfileRegistryPolicy.nextId(emptyList(), storedNext = 0))
        assertEquals(5, ProfileRegistryPolicy.nextId(emptyList(), storedNext = 5))
        assertEquals(3, ProfileRegistryPolicy.nextId(listOf(2), storedNext = -4))
    }

    @Test
    fun `any profile is deletable including the last one`() {
        assertTrue(ProfileRegistryPolicy.canDelete(listOf(0), 0))
        assertTrue(ProfileRegistryPolicy.canDelete(listOf(0, 1), 0))
        assertFalse(ProfileRegistryPolicy.canDelete(listOf(0, 1), 7))
        assertFalse(ProfileRegistryPolicy.canDelete(emptyList(), 0))
    }

    @Test
    fun `deleting the active profile picks the first remaining linked profile`() {
        assertEquals(
            1,
            ProfileRegistryPolicy.activeAfterDelete(
                ids = listOf(0, 1, 2),
                deleted = 0,
                active = 0,
                linked = setOf(1, 2),
            ),
        )
    }

    @Test
    fun `deleting the active profile without linked leftovers picks the first remaining`() {
        assertEquals(
            1,
            ProfileRegistryPolicy.activeAfterDelete(
                ids = listOf(0, 1, 2),
                deleted = 0,
                active = 0,
                linked = emptySet(),
            ),
        )
    }

    @Test
    fun `deleting an inactive profile leaves the active one untouched`() {
        assertEquals(
            2,
            ProfileRegistryPolicy.activeAfterDelete(
                ids = listOf(0, 1, 2),
                deleted = 1,
                active = 2,
                linked = setOf(2),
            ),
        )
    }

    @Test
    fun `deleting the last profile leaves no active profile`() {
        assertEquals(
            ProfileRegistryPolicy.NO_PROFILE,
            ProfileRegistryPolicy.activeAfterDelete(
                ids = listOf(0),
                deleted = 0,
                active = 0,
                linked = emptySet(),
            ),
        )
    }

    @Test
    fun `migration keeps linked profiles and drops an unlinked start profile`() {
        val migration = ProfileRegistryPolicy.migrateRegistry(
            storedIds = listOf(0, 1),
            linked = setOf(1),
            requestedActive = 0,
            storedNext = 2,
        )

        assertEquals(listOf(1), migration.ids)
        assertEquals(1, migration.active)
        assertEquals(2, migration.next)
        assertEquals(listOf(0), migration.dropped)
    }

    @Test
    fun `migration of an unlinked legacy profile produces an empty registry but keeps the counter`() {
        val migration = ProfileRegistryPolicy.migrateRegistry(
            storedIds = listOf(0),
            linked = emptySet(),
            requestedActive = 0,
            storedNext = 1,
        )

        assertEquals(emptyList<Int>(), migration.ids)
        assertEquals(ProfileRegistryPolicy.NO_PROFILE, migration.active)
        assertEquals(1, migration.next)
        assertEquals(listOf(0), migration.dropped)
    }

    @Test
    fun `migration of a fresh install writes an empty registry`() {
        val migration = ProfileRegistryPolicy.migrateRegistry(
            storedIds = null,
            linked = emptySet(),
            requestedActive = ProfileRegistryPolicy.NO_PROFILE,
            storedNext = null,
        )

        assertEquals(emptyList<Int>(), migration.ids)
        assertEquals(ProfileRegistryPolicy.NO_PROFILE, migration.active)
        assertEquals(0, migration.next)
        assertEquals(emptyList<Int>(), migration.dropped)
    }

    @Test
    fun `migration of a legacy base link keeps profile zero`() {
        val migration = ProfileRegistryPolicy.migrateRegistry(
            storedIds = null,
            linked = setOf(0),
            requestedActive = ProfileRegistryPolicy.NO_PROFILE,
            storedNext = null,
        )

        assertEquals(listOf(0), migration.ids)
        assertEquals(0, migration.active)
        assertEquals(1, migration.next)
        assertEquals(emptyList<Int>(), migration.dropped)
    }

    @Test
    fun `summary can use the server worker limit for the active profile`() {
        val profile = com.csqtt.client.ProfileSummary(
            id = 0,
            name = "Профиль 0",
            hasLink = true,
            summary = "TCP · Firefox · 100 потоков",
            transport = "tcp_tls",
            fingerprint = "firefox",
            workersPerHash = 100,
        )
        assertEquals("TCP · Firefox · 50 потоков", profile.summaryWithWorkers(50))
    }

    @Test
    fun `summary is either unconfigured or transport fingerprint workers`() {
        assertEquals(
            "Не настроен",
            ProfileRegistryPolicy.summaryText(
                hasLink = false,
                transport = "udp",
                fingerprint = "firefox",
                workers = 10,
            ),
        )
        assertEquals(
            "UDP · Firefox · 10 потоков",
            ProfileRegistryPolicy.summaryText(
                hasLink = true,
                transport = "udp",
                fingerprint = "firefox",
                workers = 10,
            ),
        )
        assertEquals(
            "TCP · Chrome · 25 потоков",
            ProfileRegistryPolicy.summaryText(
                hasLink = true,
                transport = "tcp_tls",
                fingerprint = "chrome",
                workers = 25,
            ),
        )
    }
}
