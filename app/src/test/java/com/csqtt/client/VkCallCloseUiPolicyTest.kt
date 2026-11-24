package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCallCloseUiPolicyTest {
    @Test
    fun `manual close button is debug only`() {
        assertTrue(VkCallCloseUiPolicy.closeCallsVisible(debug = true))
        assertFalse(VkCallCloseUiPolicy.closeCallsVisible(debug = false))
    }
}
