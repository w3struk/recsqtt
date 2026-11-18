package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkTokenForgetUiPolicyTest {

    @Test
    fun `forget token is a debug-only action`() {
        assertTrue(VkTokenForgetUiPolicy.isVisible(debug = true))
        assertFalse(VkTokenForgetUiPolicy.isVisible(debug = false))
    }
}
