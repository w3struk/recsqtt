package com.csqtt.client

import com.csqtt.client.ui.utils.decryptCsqttPayload
import com.csqtt.client.ui.utils.encryptCsqttPayload
import com.csqtt.client.ui.utils.parseCsqttLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VkLinkImportPolicyTest {
    private fun enc(plain: String): String = "csqtt://" + encryptCsqttPayload(plain)

    @Test
    fun decideWithTokenSplitsCredentialsFromRoute() {
        val raw = enc("connect?v=2&host=203.0.113.7&peer=46000&password=secret&token=vk1.aBc&user_id=123456")
        val decision = requireNotNull(VkLinkImportPolicy.decide(raw))

        assertEquals("vk1.aBc", decision.token)
        assertEquals("123456", decision.userId)
        assertFalse(decision.wipesStoredCredentials)

        val plain = decryptCsqttPayload(decision.sanitizedLink.removePrefix("csqtt://"))
        requireNotNull(plain)
        assertFalse(plain.contains("token="))
        assertFalse(plain.contains("user_id="))

        val sanitized = requireNotNull(parseCsqttLink(decision.sanitizedLink))
        assertEquals("203.0.113.7", sanitized.host)
        assertEquals(46000, sanitized.port)
        assertEquals("secret", sanitized.password)
        assertEquals("", sanitized.vkAccessToken)
        assertEquals("", sanitized.userId)
    }

    @Test
    fun decideWithHashesKeepsHashesInSanitizedLink() {
        val raw = enc("connect?v=2&host=203.0.113.7&peer=46000&hashes=abcdefghijklmnop1")
        val decision = requireNotNull(VkLinkImportPolicy.decide(raw))

        assertTrue(decision.wipesStoredCredentials)
        assertEquals("", decision.token)
        assertEquals("", decision.userId)
        val sanitized = requireNotNull(parseCsqttLink(decision.sanitizedLink))
        assertEquals(listOf("abcdefghijklmnop1"), sanitized.hashes)
    }

    @Test
    fun decideWithoutTokenWipesStoredCredentials() {
        val raw = enc("connect?v=2&host=203.0.113.7&peer=46000&password=secret")
        val decision = requireNotNull(VkLinkImportPolicy.decide(raw))

        assertTrue(decision.wipesStoredCredentials)
        assertEquals("", decision.token)
        assertEquals("", decision.userId)
        assertEquals(raw.trim(), decision.sanitizedLink)
    }

    @Test
    fun decideRejectsInvalidLink() {
        assertNull(VkLinkImportPolicy.decide("https://example.com/connect?v=2"))
        assertNull(VkLinkImportPolicy.decide(""))
    }

    @Test
    fun migrationIsIdempotent() {
        val stored = enc("connect?v=2&host=203.0.113.7&peer=46000&password=secret&token=vk1.aBc&user_id=123456")
        val first = requireNotNull(VkLinkImportPolicy.migration(stored))
        assertEquals("vk1.aBc", first.token)
        assertEquals("123456", first.userId)
        assertFalse(first.wipesStoredCredentials)

        // После перезаписи в ссылке нет токена — повторная миграция ничего не делает.
        assertNull(VkLinkImportPolicy.migration(first.sanitizedLink))
        assertNull(VkLinkImportPolicy.migration(first.sanitizedLink))
    }

    @Test
    fun migrationIgnoresLinkWithoutToken() {
        val stored = enc("connect?v=2&host=203.0.113.7&peer=46000&hashes=abcdefghijklmnop1")
        assertNull(VkLinkImportPolicy.migration(stored))
    }

    @Test
    fun migrationIgnoresBrokenLink() {
        assertNull(VkLinkImportPolicy.migration("csqtt://not-a-payload"))
    }

    @Test
    fun sanitizedLinkCannotImportCredentialsOnTextEdit() {
        val stored = enc("connect?v=2&host=203.0.113.7&peer=46000&password=secret&token=vk1.aBc&user_id=123456")
        val sanitized = requireNotNull(VkLinkImportPolicy.migration(stored)).sanitizedLink

        // Санитизированная ссылка хранится без кредов; её правка не должна стирать
        // сохранённый доступ, поэтому импорт кредов делается только при непустом токене.
        val decision = requireNotNull(VkLinkImportPolicy.decide(sanitized))
        assertTrue(decision.token.isEmpty())
        assertNull(VkLinkImportPolicy.migration(sanitized))
    }

    @Test
    fun decisionIsNotNullForMinimalLink() {
        val decision = VkLinkImportPolicy.decide(enc("connect?v=2&host=203.0.113.7&peer=46000"))
        assertNotNull(decision)
    }
}
