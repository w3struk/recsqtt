package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCredentialUiPolicyTest {

    @Test
    fun `credential status and forget action are debug-only`() {
        assertTrue(VkCredentialUiPolicy.statusVisible(debug = true))
        assertFalse(VkCredentialUiPolicy.statusVisible(debug = false))
        assertTrue(VkCredentialUiPolicy.forgetTokenVisible(debug = true))
        assertFalse(VkCredentialUiPolicy.forgetTokenVisible(debug = false))
    }
}
