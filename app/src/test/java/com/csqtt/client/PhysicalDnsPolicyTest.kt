package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalDnsPolicyTest {

    private fun candidate(
        active: Boolean = false,
        usable: Boolean = false,
        isVpn: Boolean = false,
        hasInternet: Boolean = true,
        isWifi: Boolean = false,
        isCellular: Boolean = false,
        servers: List<String> = emptyList(),
    ) = PhysicalDnsCandidate(
        active = active,
        usable = usable,
        isVpn = isVpn,
        hasInternet = hasInternet,
        isWifi = isWifi,
        isCellular = isCellular,
        ipv4Servers = servers,
    )

    @Test fun `vpn network dns is never selected even when active`() {
        val selected = PhysicalDnsPolicy.select(
            listOf(
                candidate(active = true, usable = true, isVpn = true, servers = listOf("77.88.8.8")),
                candidate(active = false, isWifi = true, servers = listOf("95.167.167.95")),
            ),
        )
        assertEquals(listOf("95.167.167.95"), selected)
    }

    @Test fun `network without internet capability is skipped`() {
        val selected = PhysicalDnsPolicy.select(
            listOf(
                candidate(active = true, hasInternet = false, servers = listOf("10.0.0.1")),
                candidate(hasInternet = true, servers = listOf("1.1.1.1")),
            ),
        )
        assertEquals(listOf("1.1.1.1"), selected)
    }

    @Test fun `active physical network wins over inactive wifi`() {
        val selected = PhysicalDnsPolicy.select(
            listOf(
                candidate(isWifi = true, usable = true, servers = listOf("192.168.1.1")),
                candidate(active = true, isCellular = true, servers = listOf("95.167.167.96")),
            ),
        )
        assertEquals(listOf("95.167.167.96"), selected)
    }

    @Test fun `usable network wins among equally active candidates`() {
        val selected = PhysicalDnsPolicy.select(
            listOf(
                candidate(usable = false, isCellular = true, servers = listOf("10.0.0.1")),
                candidate(usable = true, isCellular = true, servers = listOf("10.0.0.2")),
            ),
        )
        assertEquals(listOf("10.0.0.2"), selected)
    }

    @Test fun `empty list on the active network falls through to the next candidate`() {
        val selected = PhysicalDnsPolicy.select(
            listOf(
                candidate(active = true, usable = true, servers = emptyList()),
                candidate(isWifi = true, servers = listOf("8.8.8.8")),
            ),
        )
        assertEquals(listOf("8.8.8.8"), selected)
    }

    @Test fun `no candidates gives an empty list`() {
        assertTrue(PhysicalDnsPolicy.select(emptyList()).isEmpty())
    }

    @Test fun `ipv6 names and broken octets are dropped`() {
        val selected = PhysicalDnsPolicy.select(
            listOf(
                candidate(
                    active = true,
                    servers = listOf(
                        "2001:db8::1",
                        "dns.example",
                        "256.1.1.1",
                        "1.1.1",
                        "01.2.3.4",
                        "1.2.3.4.5",
                        "1..1.1",
                        " 95.167.167.95 ",
                    ),
                ),
            ),
        )
        assertEquals(listOf("95.167.167.95"), selected)
    }

    @Test fun `deduplicates and keeps only the first two addresses in order`() {
        assertEquals(
            listOf("95.167.167.95", "95.167.167.96"),
            PhysicalDnsPolicy.normalize(listOf("95.167.167.95", "95.167.167.96", "95.167.167.95", "8.8.8.8")),
        )
    }

    @Test fun `normalize of an empty or blank input is empty`() {
        assertTrue(PhysicalDnsPolicy.normalize(emptyList()).isEmpty())
        assertTrue(PhysicalDnsPolicy.normalize(listOf("  ", "")).isEmpty())
    }

    @Test fun `empty server list is an explicit clear command`() {
        assertEquals("UPDATE_DNS|", PhysicalDnsPolicy.command(emptyList()))
    }

    @Test fun `command joins the list with commas`() {
        assertEquals("UPDATE_DNS|95.167.167.95,95.167.167.96", PhysicalDnsPolicy.command(listOf("95.167.167.95", "95.167.167.96")))
    }

    @Test fun `argv omits the flag for an empty list`() {
        assertTrue(PhysicalDnsArgvPolicy.flag(emptyList()).isEmpty())
    }

    @Test fun `argv carries the comma separated list`() {
        assertEquals(
            listOf("-dns-servers", "95.167.167.95,95.167.167.96"),
            PhysicalDnsArgvPolicy.flag(listOf("95.167.167.95", "95.167.167.96")),
        )
    }
}
