package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileImportPolicyTest {

    private fun summary(id: Int, hasLink: Boolean) = ProfileSummary(
        id = id,
        name = "Профиль $id",
        hasLink = hasLink,
        summary = if (hasLink) "UDP · Firefox · 10 потоков" else "Не настроен",
    )

    @Test
    fun `empty registry auto-saves the import`() {
        assertEquals(
            ExternalImportRoute.AutoSaveStartProfile,
            ProfileImportPolicy.route(emptyList()),
        )
    }

    @Test
    fun `empty profile zero auto-saves the import`() {
        assertEquals(
            ExternalImportRoute.AutoSaveStartProfile,
            ProfileImportPolicy.route(listOf(summary(0, hasLink = false))),
        )
    }

    @Test
    fun `filled profile zero opens the new profile editor`() {
        assertEquals(
            ExternalImportRoute.OpenNewProfileEditor,
            ProfileImportPolicy.route(listOf(summary(0, hasLink = true), summary(1, hasLink = true))),
        )
    }

    @Test
    fun `absent profile zero opens the new profile editor`() {
        assertEquals(
            ExternalImportRoute.OpenNewProfileEditor,
            ProfileImportPolicy.route(listOf(summary(3, hasLink = true))),
        )
    }

    @Test
    fun `other configured profiles do not change the two rules`() {
        assertEquals(
            ExternalImportRoute.AutoSaveStartProfile,
            ProfileImportPolicy.route(
                listOf(summary(0, hasLink = false), summary(1, hasLink = true)),
            ),
        )
        assertEquals(
            ExternalImportRoute.OpenNewProfileEditor,
            ProfileImportPolicy.route(
                listOf(summary(0, hasLink = true), summary(1, hasLink = false)),
            ),
        )
    }

    @Test
    fun `prefill keeps the sanitized link and only non-empty credentials`() {
        val withToken = ProfileImportPolicy.prefill(
            VkLinkImportPolicy.Decision(
                token = "token",
                userId = "42",
                sanitizedLink = "csqtt://clean",
                wipesStoredCredentials = false,
            ),
        )
        assertEquals("csqtt://clean", withToken.link)
        assertNotNull(withToken.credentials)
        assertEquals("token", withToken.credentials?.token)
        assertEquals("42", withToken.credentials?.userId)

        val withoutToken = ProfileImportPolicy.prefill(
            VkLinkImportPolicy.Decision(
                token = "",
                userId = "",
                sanitizedLink = "csqtt://clean",
                wipesStoredCredentials = true,
            ),
        )
        assertNull(withoutToken.credentials)
    }
}
