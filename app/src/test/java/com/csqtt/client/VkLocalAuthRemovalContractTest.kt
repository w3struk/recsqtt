package com.csqtt.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source contract for dropping local VK auth: the login island is gone, the START
 * intent no longer carries a VK token, and the service start path reads credentials
 * from `SettingsStore` only.
 *
 * Retired symbol names are assembled from fragments so this contract does not trip
 * the repository-wide grep for them (same convention as `LoggingToggleContractTest`).
 */
class VkLocalAuthRemovalContractTest {
    private fun moduleFile(relativePath: String): File =
        listOf(
            File("app/$relativePath"),
            File(relativePath),
        ).first(File::isFile)

    private fun readModuleSource(relativePath: String): String = moduleFile(relativePath).readText()

    private fun assertAbsent(fileName: String, source: String, vararg forbidden: String) {
        forbidden.forEach { token ->
            assertFalse("$fileName must not contain `$token`", source.contains(token))
        }
    }

    @Test
    fun `login island is deleted`() {
        val files = listOf(
            "src/main/java/com/csqtt/client/ui/dialogs/" + "VkAuth" + "Dialog.kt",
            "src/main/java/com/csqtt/client/" + "VkAuth" + "WebViewManager.kt",
            "src/main/java/com/csqtt/client/" + "VkToken" + "Scraper.kt",
        )
        files.forEach { path ->
            val present = listOf(File("app/$path"), File(path)).any(File::isFile)
            assertFalse("$path must not exist", present)
        }
    }

    @Test
    fun `main activity has no login dialog state or callback`() {
        assertAbsent(
            "MainActivity.kt",
            readModuleSource("src/main/java/com/csqtt/client/MainActivity.kt"),
            "VkAuth" + "Dialog",
            "showVkAuth" + "Dialog",
            "onVkAuth" + "Requested",
        )
    }

    @Test
    fun `settings tab has no login or revoke plumbing`() {
        assertAbsent(
            "SettingsTab.kt",
            readModuleSource("src/main/java/com/csqtt/client/ui/SettingsTab.kt"),
            "onVkAuth" + "Requested",
            "VkToken" + "RevokeDialog",
            "onLogin",
        )
    }

    @Test
    fun `start intent producers carry no vk token extras`() {
        val tokenExtra = "vk_" + "access_token"
        val fromLinkExtra = "vk_" + "token_from_link"
        assertAbsent(
            "TunnelQuickActions.kt",
            readModuleSource("src/main/java/com/csqtt/client/TunnelQuickActions.kt"),
            tokenExtra,
            fromLinkExtra,
        )
        assertAbsent(
            "ConnectionTab.kt",
            readModuleSource("src/main/java/com/csqtt/client/ui/ConnectionTab.kt"),
            tokenExtra,
            fromLinkExtra,
        )
    }

    @Test
    fun `service and connection source read credentials from the store only`() {
        assertAbsent(
            "TunnelService.kt",
            readModuleSource("src/main/java/com/csqtt/client/TunnelService.kt"),
            "tokenFromLink",
            "accessTokenOverride",
            "vk_" + "access_token",
            "vk_" + "token_from_link",
        )
        assertAbsent(
            "ConnectionSource.kt",
            readModuleSource("src/main/java/com/csqtt/client/ConnectionSource.kt"),
            "tokenFromLink",
            "vkAccessToken",
        )
    }

    @Test
    fun `manual close calls button is gated by the debug policy`() {
        val connectionTab = readModuleSource("src/main/java/com/csqtt/client/ui/ConnectionTab.kt")
        assertTrue(
            "ConnectionTab.kt must gate the close-calls action on VkCallCloseUiPolicy",
            connectionTab.contains("VkCallCloseUiPolicy.isVisible(BuildConfig.DEBUG)"),
        )
    }
}
