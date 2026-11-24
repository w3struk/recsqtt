package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileRegistryPolicyTest {

    @Test
    fun `fresh install starts with profile zero and next id one`() {
        assertEquals(listOf(0), ProfileRegistryPolicy.discover(null, emptySet()))
        assertEquals(1, ProfileRegistryPolicy.nextId(listOf(0), null))
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
    fun `discovery keeps the active unlinked profile but resolve picks a linked one`() {
        val linked = setOf(1)
        val ids = ProfileRegistryPolicy.discover(active = 2, linked = linked)

        assertEquals(listOf(0, 1, 2), ids)
        assertEquals(1, ProfileRegistryPolicy.resolveActive(ids, requested = 2, linked = linked))
    }

    @Test
    fun `missing requested id falls back to the first id even when unlinked`() {
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
    fun `profile zero stays active without a link`() {
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
    fun `stored order is preserved by parse and format while discovery is sorted`() {
        val parsed = requireNotNull(ProfileRegistryPolicy.parseIds("2,0,2,1"))
        assertEquals(listOf(2, 0, 1), parsed)
        assertEquals("2,0,1", ProfileRegistryPolicy.formatIds(parsed))
        assertEquals(
            listOf(0, 1, 2),
            ProfileRegistryPolicy.discover(active = 2, linked = setOf(1)),
        )
    }

    @Test
    fun `parse drops junk and negatives and returns null when nothing is valid`() {
        assertEquals(listOf(1), ProfileRegistryPolicy.parseIds("1,abc,-2,1"))
        assertNull(ProfileRegistryPolicy.parseIds(null))
        assertNull(ProfileRegistryPolicy.parseIds("   "))
        assertNull(ProfileRegistryPolicy.parseIds("abc,-1"))
    }

    @Test
    fun `next id repairs a stale counter and never reuses a deleted id`() {
        assertEquals(6, ProfileRegistryPolicy.nextId(listOf(0, 5), storedNext = 2))
        assertEquals(5, ProfileRegistryPolicy.nextId(listOf(0, 1), storedNext = 5))
        assertEquals(4, ProfileRegistryPolicy.nextId(listOf(0, 1, 2, 3), storedNext = 4))
    }

    @Test
    fun `the last profile cannot be deleted`() {
        assertFalse(ProfileRegistryPolicy.canDelete(listOf(0), 0))
        assertTrue(ProfileRegistryPolicy.canDelete(listOf(0, 1), 0))
        assertFalse(ProfileRegistryPolicy.canDelete(listOf(0, 1), 7))
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
    fun `deleting profile zero is allowed when another profile remains`() {
        assertTrue(ProfileRegistryPolicy.canDelete(listOf(0, 1), 0))
        assertEquals(
            1,
            ProfileRegistryPolicy.activeAfterDelete(
                ids = listOf(0, 1),
                deleted = 0,
                active = 0,
                linked = setOf(1),
            ),
        )
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
