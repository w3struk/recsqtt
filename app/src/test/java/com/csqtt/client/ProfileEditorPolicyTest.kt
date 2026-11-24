package com.csqtt.client

import com.csqtt.client.ui.utils.encryptCsqttPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileEditorPolicyTest {

    private fun enc(plain: String): String = "csqtt://" + encryptCsqttPayload(plain)

    private val link = enc("connect?v=2&host=203.0.113.7&peer=46000&password=client-pass")

    @Test
    fun `blank and broken links cannot be saved`() {
        assertFalse(ProfileEditorPolicy.canSave(""))
        assertFalse(ProfileEditorPolicy.canSave("   "))
        assertFalse(ProfileEditorPolicy.canSave("csqtt://broken"))
        assertTrue(ProfileEditorPolicy.canSave(link))
    }

    @Test
    fun `default name follows the allocated id`() {
        org.junit.Assert.assertEquals("Профиль 5", ProfileEditorPolicy.defaultName(5))
    }

    @Test
    fun `rename alone does not request reconnect`() {
        val before = ProfileDraft(link = link, name = "Профиль 0")
        val after = before.copy(name = "Рабочий")

        assertFalse(ProfileEditorPolicy.reconnectRequired(before, after))
        assertFalse(
            ProfileEditorPolicy.reconnectRequired(
                before,
                before.copy(hasVkToken = true),
            ),
        )
    }

    @Test
    fun `link whitespace alone does not request reconnect`() {
        val before = ProfileDraft(link = link)
        val after = before.copy(link = "  $link  ")

        assertFalse(ProfileEditorPolicy.reconnectRequired(before, after))
    }

    @Test
    fun `token-only imported link enables auto api`() {
        val rawTokenLink = enc(
            "connect?v=2&host=203.0.113.7&peer=46000&password=secret&token=vk1.aBc&user_id=123456",
        )
        val fromRawLink = ProfileEditorPolicy.importedLinkModes(
            ProfileDraft(link = rawTokenLink, vkHashMode = CsqttConstants.VkAutoHash.MODE_MANUAL),
            importedToken = false,
        )
        org.junit.Assert.assertEquals(CsqttConstants.VkAutoHash.MODE_AUTO_API, fromRawLink.vkHashMode)
        org.junit.Assert.assertEquals(CsqttConstants.VkAuth.MODE_CALLS, fromRawLink.vkAuthMode)

        val sanitizedLink = enc("connect?v=2&host=203.0.113.7&peer=46000&password=secret")
        val fromPrefill = ProfileEditorPolicy.importedLinkModes(
            ProfileDraft(link = sanitizedLink, vkHashMode = CsqttConstants.VkAutoHash.MODE_MANUAL),
            importedToken = true,
        )
        org.junit.Assert.assertEquals(CsqttConstants.VkAutoHash.MODE_AUTO_API, fromPrefill.vkHashMode)
        org.junit.Assert.assertEquals(CsqttConstants.VkAuth.MODE_CALLS, fromPrefill.vkAuthMode)
    }

    @Test
    fun `link hashes win over token when choosing modes`() {
        val linkWithHashesAndToken = enc(
            "connect?v=2&host=203.0.113.7&peer=46000&hashes=abcdefghijklmnop1&token=vk1.aBc&user_id=1",
        )
        val result = ProfileEditorPolicy.importedLinkModes(
            ProfileDraft(link = linkWithHashesAndToken, vkHashMode = CsqttConstants.VkAutoHash.MODE_MANUAL),
            importedToken = false,
        )
        org.junit.Assert.assertEquals(CsqttConstants.VkAutoHash.MODE_MANUAL, result.vkHashMode)
    }

    @Test
    fun `token rotation requests reconnect even when the link text is unchanged`() {
        val before = ProfileDraft(link = link)
        assertTrue(
            ProfileEditorPolicy.reconnectRequired(
                before,
                before,
                credentialsChanged = true,
            ),
        )
        assertFalse(ProfileEditorPolicy.reconnectRequired(before, before))
    }

    @Test
    fun `each connection field requests reconnect`() {
        val base = ProfileDraft(link = link)
        val changes = listOf(
            base.copy(link = enc("connect?v=2&host=203.0.113.8&peer=46000&password=pass")),
            base.copy(vkHashes = "hash-one"),
            base.copy(vkAuthMode = CsqttConstants.VkAuth.MODE_AUTO_JS),
            base.copy(vkHashMode = CsqttConstants.VkAutoHash.MODE_AUTO_API),
            base.copy(captchaMode = "wv"),
            base.copy(captchaSolveMethod = "manual"),
            base.copy(captchaWbvSolveMethod = "manual"),
            base.copy(turnTransport = CsqttConstants.Tunnel.TURN_TRANSPORT_TCP_TLS),
            base.copy(fingerprint = "chrome"),
            base.copy(selectedTurnSubnet = "subnet-1"),
            base.copy(turnSubnetIpLimit = "10"),
            base.copy(workersPerHash = 20),
        )

        changes.forEach { changed ->
            assertTrue(
                "изменение ${changed} должно требовать переподключения",
                ProfileEditorPolicy.reconnectRequired(base, changed),
            )
        }
    }

    @Test
    fun `id change alone does not request reconnect`() {
        val before = ProfileDraft(id = 0, link = link)
        assertFalse(ProfileEditorPolicy.reconnectRequired(before, before.copy(id = 7)))
    }
}
