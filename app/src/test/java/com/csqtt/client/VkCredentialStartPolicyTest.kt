package com.csqtt.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Без сохранённого токена авто-режимы не стартуют: ссылка после импорта кредов
 * токена не несёт, поэтому взять его неоткуда. Текст причины общий с сервисом —
 * иначе экран и лог запуска говорили бы разное.
 */
class VkCredentialStartPolicyTest {

    private val autoApi = CsqttConstants.VkAutoHash.MODE_AUTO_API
    private val autoJs = CsqttConstants.VkAutoHash.MODE_AUTO_JS
    private val manual = CsqttConstants.VkAutoHash.MODE_MANUAL
    private val calls = CsqttConstants.VkAuth.MODE_CALLS

    @Test
    fun `the message names the panel link`() {
        assertEquals(
            "Этот режим доступен со свежей ссылкой из панели: импортируйте её",
            VkCredentialStartPolicy.TOKEN_FROM_PANEL_MESSAGE,
        )
    }

    @Test
    fun `auto api is blocked without a token`() {
        assertEquals(
            VkCredentialStartPolicy.TOKEN_FROM_PANEL_MESSAGE,
            VkCredentialStartPolicy.blockedReason(autoApi, calls, hasToken = false),
        )
    }

    @Test
    fun `auto api starts with a stored token`() {
        assertNull(VkCredentialStartPolicy.blockedReason(autoApi, calls, hasToken = true))
    }

    @Test
    fun `auto js is blocked without a token in either selector`() {
        assertEquals(
            VkCredentialStartPolicy.TOKEN_FROM_PANEL_MESSAGE,
            VkCredentialStartPolicy.blockedReason(autoJs, calls, hasToken = false),
        )
        assertEquals(
            VkCredentialStartPolicy.TOKEN_FROM_PANEL_MESSAGE,
            VkCredentialStartPolicy.blockedReason(
                manual,
                CsqttConstants.VkAuth.MODE_AUTO_JS,
                hasToken = false,
            ),
        )
        assertNull(VkCredentialStartPolicy.blockedReason(autoJs, calls, hasToken = true))
    }

    @Test
    fun `manual mode with hashes stays available without a token`() {
        assertNull(VkCredentialStartPolicy.blockedReason(manual, calls, hasToken = false))
    }

    @Test
    fun `manual hashes do not authorize an auto mode`() {
        assertEquals(
            VkCredentialStartPolicy.TOKEN_FROM_PANEL_MESSAGE,
            VkCredentialStartPolicy.blockedReason(autoApi, calls, hasToken = false),
        )
    }

    @Test
    fun `manual mode without hashes is left to the hashes blocker`() {
        // Текст про токен здесь был бы ложью: в ручном режиме проблема — хеши.
        assertNull(VkCredentialStartPolicy.blockedReason(manual, calls, hasToken = false))
    }

    @Test
    fun `user id is masked to its last four digits`() {
        assertEquals("••••9876", VkCredentialStartPolicy.maskedUserId("123459876"))
        assertEquals("••••12", VkCredentialStartPolicy.maskedUserId("12"))
        assertEquals("", VkCredentialStartPolicy.maskedUserId(""))
        assertEquals("", VkCredentialStartPolicy.maskedUserId("   "))
    }
}
