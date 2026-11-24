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
        listOf("profile_ids", "next_profile_id", "active_profile", "profile_registry_schema").forEach { key ->
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
    fun `addProfile purges a reused id and clears inherited credentials`() {
        val body = bodyOf("internal suspend fun addProfile(")
        val remove = body.indexOf("removeProfileKeys(prefs, id)")
        val writeFields = body.indexOf("writeProfileFields(")
        assertTrue("addProfile не чистит ключи переиспользованного id", remove >= 0)
        assertTrue(
            "addProfile пишет поля до очистки старых ключей",
            writeFields < 0 || remove < writeFields,
        )
        assertTrue(
            "addProfile не просит стереть креды без токена",
            body.contains("clearCredentialsWhenAbsent = true"),
        )
        assertTrue("addProfile не чистит реестр звонков нового id", body.contains("vkCalls.clear(id)"))
    }

    @Test
    fun `saveProfileDraft keeps stored credentials when the link has no token`() {
        val body = bodyOf("internal suspend fun saveProfileDraft(")
        assertTrue("saveProfileDraft не пишет креды", body.contains("writeImportedCredentials("))
        assertFalse(
            "saveProfileDraft стирает сохранённый токен при правке ссылки",
            body.contains("clearCredentialsWhenAbsent = true"),
        )
        val helper = bodyOf("private fun writeImportedCredentials(")
        assertTrue(
            "у writeImportedCredentials нет безопасного значения по умолчанию",
            helper.contains("clearCredentialsWhenAbsent: Boolean = false"),
        )
    }

    @Test
    fun `import into the start profile rechecks the empty link and activates it`() {
        val body = bodyOf("suspend fun importExternalLinkIntoStartProfile(")
        assertTrue("импорт не проверяет пустую ссылку", body.contains("currentLink.isNotBlank()"))
        assertTrue(
            "импорт не активирует профиль",
            body.contains("prefs[ACTIVE_PROFILE] = id") ||
                body.contains("prefs[ACTIVE_PROFILE] = startId"),
        )
        assertTrue("импорт не пишет креды", body.contains("applyLinkDecision"))
        assertTrue("импорт не включает авто-режим для ссылки с токеном", body.contains("applyImportedLinkModes"))
    }

    @Test
    fun `import into an empty registry allocates the next id and adds it to the list`() {
        val body = bodyOf("suspend fun importExternalLinkIntoStartProfile(")
        assertTrue("импорт не проверяет пустой реестр", body.contains("ids.isEmpty()"))
        assertTrue("импорт не выделяет id из счётчика", body.contains("nextProfileIdOf(prefs)"))
        assertTrue("импорт не дописывает id в реестр", body.contains("prefs[PROFILE_IDS] ="))
        assertTrue("импорт не двигает монотонный счётчик", body.contains("prefs[NEXT_PROFILE_ID] = id + 1"))
        val remove = body.indexOf("removeProfileKeys(prefs, id)")
        val applyLink = body.indexOf("applyLinkDecision(prefs, decision, id)")
        assertTrue("импорт не чистит ключи переиспользованного id", remove >= 0)
        assertTrue("импорт пишет ссылку до очистки старых ключей", applyLink < 0 || remove < applyLink)
        assertTrue("импорт не чистит реестр звонков нового id", body.contains("vkCalls.clear(id)"))
    }

    @Test
    fun `registry migration purges orphaned keys inside the registry transaction`() {
        val body = bodyOf("suspend fun migrateRegistryIfNeeded(")
        assertTrue("миграция не сканирует осиротевшие ключи", body.contains("profileIdsInStorage(prefs)"))
        val edit = body.indexOf("dataStore.edit {")
        val remove = body.indexOf("removeProfileKeys(prefs, profile)")
        val reread = body.indexOf("profileIdsOf(dataStore.data.first())")
        assertTrue("миграция не чистит ключи выпавших профилей", remove >= 0)
        assertTrue("миграция чистит ключи вне транзакции реестра", edit < 0 || edit < remove)
        assertTrue(
            "миграция чистит ключи после перечитывания реестра",
            reread < 0 || remove < reread,
        )
        assertTrue(
            "миграция не пропускает пересозданные id при очистке звонков",
            body.contains("if (profile in current) continue"),
        )
        assertTrue("миграция не ловит IOException", body.contains("catch (error: IOException)"))
    }

    @Test
    fun `the orphan scan and profile key purge cover both key lists`() {
        val scan = bodyOf("private fun profileIdsInStorage(")
        assertTrue("скан не смотрит строковые ключи", scan.contains("PROFILE_STRING_KEYS"))
        assertTrue("скан не смотрит целочисленные ключи", scan.contains("PROFILE_INT_KEYS"))

        val remove = bodyOf("private fun removeProfileKeys(")
        assertTrue("очистка не удаляет строковые ключи", remove.contains("for (key in PROFILE_STRING_KEYS)"))
        assertTrue("очистка не удаляет целочисленные ключи", remove.contains("for (key in PROFILE_INT_KEYS)"))

        val clear = bodyOf("private suspend fun clearProfileKeys(")
        assertTrue("clearProfileKeys не переиспользует removeProfileKeys", clear.contains("removeProfileKeys(prefs, profile)"))
    }

    @Test
    fun `snapshots reject ids outside the registry before reading their keys`() {
        listOf(
            "internal suspend fun prepareSnapshot(",
            "internal suspend fun connectionSnapshot(",
        ).forEach { declaration ->
            val body = bodyOf(declaration)
            val guard = body.indexOf("if (profile !in profileIdsOf(prefs)) return null")
            assertTrue("$declaration не проверяет реестр", guard >= 0)
            val firstKey = body.indexOf("getProfileKey(")
            assertTrue(
                "$declaration читает ключи профиля до проверки реестра",
                firstKey < 0 || guard < firstKey,
            )
        }
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
