package com.csqtt.client

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCallCloseUiPolicyTest {
    @Test
    fun `manual close button is debug only`() {
        assertTrue(VkCallCloseUiPolicy.isVisible(debug = true))
        assertFalse(VkCallCloseUiPolicy.isVisible(debug = false))
    }
}
