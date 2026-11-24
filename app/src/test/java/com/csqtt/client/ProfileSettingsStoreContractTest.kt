package com.csqtt.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Граница между глобальным [SettingsStore] и профильным [ProfileSettingsStore].
 * DataStore и Context на JVM недоступны, поэтому контракт проверяется по
 * исходникам: ключи, сеттеры и опасные записи не должны переезжать обратно.
 */
class ProfileSettingsStoreContractTest {

    private val settingsSource: List<String> = listOf(
        File("app/src/main/java/com/csqtt/client/SettingsStore.kt"),
        File("src/main/java/com/csqtt/client/SettingsStore.kt"),
    ).first(File::isFile).readLines()

    private val profileSource: List<String> = listOf(
        File("app/src/main/java/com/csqtt/client/ProfileSettingsStore.kt"),
        File("src/main/java/com/csqtt/client/ProfileSettingsStore.kt"),
    ).first(File::isFile).readLines()

    private val settingsText = settingsSource.joinToString("\n")
    private val profileText = profileSource.joinToString("\n")

    private fun bodyOf(declaration: String, lines: List<String> = profileSource): String {
        val start = lines.indexOfFirst { it.contains(declaration) }
        assertTrue("нет объявления «$declaration»", start >= 0)
        val indent = lines[start].takeWhile { it == ' ' }
        val end = (start + 1 until lines.size).first { lines[it] == indent + "}" }
        return lines.subList(start, end + 1).joinToString("\n")
    }

    @Test
    fun `the profile store is constructed with the shared settings datastore`() {
        assertTrue(
            "SettingsStore не создаёт ProfileSettingsStore",
            Regex("""val profile = ProfileSettingsStore\(dataStore, appContext\)""").containsMatchIn(settingsText),
        )
        assertFalse(
            "ProfileSettingsStore создаёт второй DataStore",
            profileText.contains("preferencesDataStore"),
        )
    }

    @Test
    fun `registry and profile keys live only in the profile store`() {
        listOf("profile_ids", "next_profile_id", "active_profile").forEach { key ->
            assertTrue("$key отсутствует в ProfileSettingsStore", profileText.contains("\"$key\""))
            assertFalse("$key остался в SettingsStore", settingsText.contains("\"$key\""))
        }

        listOf(
            "csqtt_link", "vk_hashes", "vk_hash_mode", "vk_auth_mode", "captcha_mode",
            "captcha_solve_method", "captcha_wbv_solve_method", "vk_hash_check_results",
            "protocol", "sni", "obfs_mode", "turn_transport", "selected_turn_subnet",
            "custom_turn_ips", "turn_subnet_ip_limit", "selected_fingerprint",
            "active_client_ids", "vk_access_token", "vk_access_token_encrypted",
            "vk_access_token_user_id", "workers_per_hash", "listen_port", "profile_name",
        ).forEach { key ->
            assertTrue("$key отсутствует в ProfileSettingsStore", profileText.contains("\"$key\""))
            assertFalse("$key остался в SettingsStore", settingsText.contains("\"$key\""))
        }
    }

    @Test
    fun `global keys stay out of the profile store`() {
        listOf(
            "excluded_apps", "whitelist_apps", "is_whitelist", "participant_show_logs",
            "orange_tunnel_enabled", "theme_mode", "auto_pause_on_wifi", "connection_generation",
        ).forEach { key ->
            assertFalse("$key попал в ProfileSettingsStore", profileText.contains("\"$key\""))
            assertTrue("$key отсутствует в SettingsStore", settingsText.contains("\"$key\""))
        }
    }

    @Test
    fun `SettingsStore has no profile flows setters or suffix helper`() {
        listOf(
            "val csqttLink", "val vkHashes", "val selectedFingerprint",
            "val tunnelAuthSnapshot", "val profileNames",
            "suspend fun saveFingerprint", "suspend fun saveActiveProfile",
            "private fun <T> getProfileKey", "fun getProfileKey",
        ).forEach { token ->
            assertFalse("$token остался в SettingsStore", settingsText.contains(token))
        }
    }

    @Test
    fun `profile setters take an explicit id and use the membership guard`() {
        listOf(
            "suspend fun saveProfileName",
            "suspend fun saveFingerprint",
            "suspend fun saveCsqttParticipantLink",
            "suspend fun saveWorkersPerHash",
            "suspend fun saveVkHashMode",
            "suspend fun saveTurnTransport",
            "suspend fun saveCaptchaMode",
        ).forEach { declaration ->
            val body = bodyOf(declaration)
            assertTrue("$declaration не принимает profile", body.contains("(profile: Int"))
            assertTrue("$declaration не использует editForProfile", body.contains("editForProfile(profile)"))
        }
    }

    @Test
    fun `addProfile sanitizes the link and activates the new profile`() {
        val body = bodyOf("internal suspend fun addProfile(")
        assertTrue("addProfile не пишет очищенную ссылку", body.contains("decision.sanitizedLink") || body.contains("writeProfileFields"))
        assertTrue("addProfile не пишет PROFILE_IDS", body.contains("prefs[PROFILE_IDS] ="))
        assertTrue("addProfile не активирует профиль", body.contains("prefs[ACTIVE_PROFILE] = id"))
    }

    @Test
    fun `saveProfileDraft never changes the active profile`() {
        val body = bodyOf("internal suspend fun saveProfileDraft(")
        assertFalse("saveProfileDraft меняет ACTIVE_PROFILE", body.contains("ACTIVE_PROFILE"))
        assertTrue("saveProfileDraft не пишет поля черновика", body.contains("writeProfileFields"))
    }

    @Test
    fun `import into start profile rechecks the empty link and activates profile zero`() {
        val body = bodyOf("suspend fun importExternalLinkIntoStartProfile(")
        assertTrue("импорт не проверяет пустую ссылку", body.contains("currentLink.isNotBlank()"))
        assertTrue("импорт не активирует профиль 0", body.contains("prefs[ACTIVE_PROFILE] = startId"))
        assertTrue("импорт не пишет креды", body.contains("applyLinkDecision"))
        assertTrue("импорт не включает авто-режим для ссылки с токеном", body.contains("applyImportedLinkModes"))
    }

    @Test
    fun `profile display name has a single source`() {
        assertTrue(
            "newDraft не использует общий дефолт имени",
            profileText.contains("ProfileEditorPolicy.defaultName(id)"),
        )
        assertTrue(
            "имя по умолчанию задаётся вторым литералом",
            profileText.contains("ProfileEditorPolicy.defaultName(profile)"),
        )
        assertFalse("в store остался ручной литерал имени", profileText.contains("\"Профиль \$"))
    }

    @Test
    fun `deleteProfile keeps the counter and clears the call registry`() {
        val body = bodyOf("suspend fun deleteProfile(")
        assertFalse("deleteProfile уменьшает next_profile_id", body.contains("NEXT_PROFILE_ID"))
        assertTrue("deleteProfile не чистит ключи", body.contains("clearProfileKeys(profile)"))
        assertTrue("deleteProfile не чистит реестр звонков", body.contains("vkCalls.clear(profile)"))
    }

    @Test
    fun `link credential migration walks the whole registry`() {
        val body = bodyOf("suspend fun migrateLinkCredentialsIfNeeded(")
        assertTrue("миграция не перебирает профили", body.contains("profileIdsOf(prefs)"))
    }

    @Test
    fun `active-resolved flows use the snapshot helper`() {
        listOf(
            "val csqttLink: Flow<String>",
            "val vkHashes: Flow<String>",
            "val workersPerHash: Flow<Int>",
            "val selectedFingerprint: Flow<String>",
            "val tunnelAuthSnapshot",
        ).forEach { declaration ->
            val body = bodyOf(declaration)
            assertTrue("$declaration не читает активный профиль из снимка", body.contains("activeProfileOf(prefs)"))
        }
    }
}
