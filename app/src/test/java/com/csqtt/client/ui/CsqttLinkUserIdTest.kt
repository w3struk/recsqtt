package com.csqtt.client.ui

import com.csqtt.client.ui.utils.decryptCsqttPayload
import com.csqtt.client.ui.utils.encryptCsqttPayload
import com.csqtt.client.ui.utils.formatCsqttLink
import com.csqtt.client.ui.utils.parseCsqttLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsqttLinkUserIdTest {
    private fun enc(plain: String): String = "csqtt://" + encryptCsqttPayload(plain)

    @Test
    fun parsesTokenWithUserId() {
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&token=vk1.aBc&user_id=123456"),
        )
        requireNotNull(link)
        assertEquals("vk1.aBc", link.vkAccessToken)
        assertEquals("123456", link.userId)
    }

    @Test
    fun parsesTokenWithoutUserId() {
        val link = parseCsqttLink(enc("connect?v=2&host=vps.example&peer=46000&password=secret&token=vk1.aBc"))
        requireNotNull(link)
        assertEquals("vk1.aBc", link.vkAccessToken)
        assertEquals("", link.userId)
    }

    @Test
    fun ignoresUserIdWithoutToken() {
        val link = parseCsqttLink(enc("connect?v=2&host=vps.example&peer=46000&password=secret&user_id=123456"))
        requireNotNull(link)
        assertEquals("", link.vkAccessToken)
        assertEquals("", link.userId)
    }

    @Test
    fun ignoresNonDigitUserId() {
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&token=vk1.aBc&user_id=12ab56"),
        )
        requireNotNull(link)
        assertEquals("vk1.aBc", link.vkAccessToken)
        assertEquals("", link.userId)
    }

    @Test
    fun ignoresOverlongUserId() {
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&token=vk1.aBc&user_id=" + "1".repeat(21)),
        )
        requireNotNull(link)
        assertEquals("vk1.aBc", link.vkAccessToken)
        assertEquals("", link.userId)
    }

    @Test
    fun hashesAndTokenStayMutuallyExclusive() {
        val link = parseCsqttLink(
            enc("connect?v=2&host=vps.example&peer=46000&password=secret&hashes=abcdefghijklmnop&token=vk1.aBc&user_id=123456"),
        )
        assertNull(link)
    }

    @Test
    fun formatterEmitsUserIdOnlyWithToken() {
        val withCreds = formatCsqttLink(
            "203.0.113.7",
            46000,
            "SecretPass123456",
            token = "vk1.aBc",
            userId = "123456",
        )
        val plainWithCreds = decryptCsqttPayload(withCreds.removePrefix("csqtt://"))
        requireNotNull(plainWithCreds)
        assertTrue(plainWithCreds.contains("token=vk1.aBc"))
        assertTrue(plainWithCreds.contains("user_id=123456"))
        assertEquals("123456", parseCsqttLink(withCreds)?.userId)

        val withoutToken = formatCsqttLink("203.0.113.7", 46000, "SecretPass123456", userId = "123456")
        val plainWithoutToken = decryptCsqttPayload(withoutToken.removePrefix("csqtt://"))
        requireNotNull(plainWithoutToken)
        assertFalse(plainWithoutToken.contains("token="))
        assertFalse(plainWithoutToken.contains("user_id="))
    }

    @Test
    fun sanitizerKeepsRouteAndDropsCredentials() {
        val sanitized = formatCsqttLink(
            host = "203.0.113.7",
            peer = 46000,
            password = "SecretPass123456",
            hashes = listOf("abcdefghijklmnop1"),
            token = "",
            userId = "",
        )
        val plain = decryptCsqttPayload(sanitized.removePrefix("csqtt://"))
        requireNotNull(plain)
        assertFalse(plain.contains("token="))
        assertFalse(plain.contains("user_id="))

        val link = parseCsqttLink(sanitized)
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("SecretPass123456", link.password)
        assertEquals(listOf("abcdefghijklmnop1"), link.hashes)
        assertEquals("", link.vkAccessToken)
        assertEquals("", link.userId)
    }

    @Test
    fun parsesSeparatorLessPayloadWithUserId() {
        val link = parseCsqttLink(
            enc("connect?v=2host=203.0.113.7peer=46000token=vk1.aBcuser_id=123456"),
        )
        requireNotNull(link)
        assertEquals("203.0.113.7", link.host)
        assertEquals(46000, link.port)
        assertEquals("vk1.aBc", link.vkAccessToken)
        assertEquals("123456", link.userId)
    }
}
