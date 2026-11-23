package com.csqtt.client

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Локальные списки приложений и видимость журнала — глобальные настройки, а не
 * свойства профиля конфигурации: маршрутизация в большинстве случаев приходит
 * с серверного правила, а журнал относится к приложению, а не к подключению.
 * Ключ, прошедший через `getProfileKey`, получил бы суффикс профиля и менялся бы
 * вместе с ним.
 *
 * Проверка структурная: `SettingsStore` работает с `DataStore` и `Context`,
 * которые на JVM недоступны, поэтому объявления и форма обращений к ключам
 * проверяются по исходнику — так же, как в [SettingsStoreOrangeTunnelTest].
 */
class SettingsStoreGlobalAppSettingsTest {

    private val source: List<String> = listOf(
        File("app/src/main/java/com/csqtt/client/SettingsStore.kt"),
        File("src/main/java/com/csqtt/client/SettingsStore.kt"),
    ).first(File::isFile).readLines()

    private val sourceText: String = source.joinToString("\n")

    /** Тело объявления по его первой строке: до закрывающей `}` на том же отступе. */
    private fun bodyOf(declaration: String): String {
        val start = source.indexOfFirst { it.contains(declaration) }
        assertTrue("в SettingsStore нет «$declaration»", start >= 0)
        val indent = source[start].takeWhile { it == ' ' }
        val end = (start + 1 until source.size).first { source[it] == indent + "}" }
        return source.subList(start, end + 1).joinToString("\n")
    }

    @Test
    fun `routing lists and log visibility are read without the active profile`() {
        listOf(
            "val excludedApps: Flow<String>",
            "val whitelistApps: Flow<String>",
            "val isWhitelist: Flow<Boolean>",
            "val participantShowLogs: Flow<Boolean>",
        ).forEach { declaration ->
            val body = bodyOf(declaration)
            assertFalse(
                "$declaration всё ещё получает ключ через getProfileKey",
                body.contains("getProfileKey"),
            )
            assertFalse(
                "$declaration всё ещё выбирает значение по ACTIVE_PROFILE",
                body.contains("ACTIVE_PROFILE"),
            )
        }
    }

    @Test
    fun `the legacy profile value is only a fallback for the routing lists`() {
        listOf(
            "val excludedApps: Flow<String>" to "prefs[EXCLUDED_APPS]",
            "val whitelistApps: Flow<String>" to "prefs[WHITELIST_APPS]",
            "val isWhitelist: Flow<Boolean>" to "prefs[IS_WHITELIST]",
        ).forEach { (declaration, globalRead) ->
            val body = bodyOf(declaration)
            assertTrue("$declaration не читает глобальный ключ", body.contains(globalRead))
            assertTrue(
                "$declaration не имеет запасного чтения старого ключа на время миграции",
                body.contains("legacyProfileValue"),
            )
        }

        val logs = bodyOf("val participantShowLogs: Flow<Boolean>")
        assertFalse(
            "видимость журнала не нуждается в профильном запасном значении",
            logs.contains("legacyProfileValue"),
        )
    }

    @Test
    fun `saving routing lists and log visibility writes only global keys`() {
        val savers = listOf(
            "suspend fun saveExcludedApps" to "prefs[EXCLUDED_APPS] = packages",
            "suspend fun saveWhitelistApps" to "prefs[WHITELIST_APPS] = packages",
            "suspend fun saveAppRoutingLists" to "prefs[EXCLUDED_APPS] = blacklist",
            "suspend fun saveExceptionsMode" to "prefs[IS_WHITELIST] = isWhitelist",
            "suspend fun saveParticipantShowLogs" to "prefs[PARTICIPANT_SHOW_LOGS] = show",
        )
        savers.forEach { (declaration, globalWrite) ->
            val body = bodyOf(declaration)
            assertTrue("$declaration не пишет глобальный ключ", body.contains(globalWrite))
            assertFalse(
                "$declaration всё ещё пишет суффиксный ключ",
                body.contains("getProfileKey"),
            )
        }
    }

    @Test
    fun `the four keys no longer receive a profile suffix`() {
        val getProfileKeyBody = bodyOf("private fun <T> getProfileKey(")
        listOf(
            "EXCLUDED_APPS",
            "WHITELIST_APPS",
            "IS_WHITELIST",
            "PARTICIPANT_SHOW_LOGS",
        ).forEach { key ->
            assertFalse(
                "$key попал в профильную карту ключей",
                Regex("""\b$key\b""").containsMatchIn(getProfileKeyBody),
            )
        }
    }

    @Test
    fun `legacy migrations still read the old suffixed keys`() {
        listOf(
            "suspend fun migrateLegacyWhitelistMode(",
            "suspend fun repairInvertedLocalBlacklist(",
        ).forEach { declaration ->
            val body = bodyOf(declaration)
            assertTrue(
                "$declaration должен читать старые ключи через legacyProfileKey",
                body.contains("legacyProfileKey(EXCLUDED_APPS") &&
                    body.contains("legacyProfileKey(IS_WHITELIST"),
            )
            assertFalse(
                "$declaration не должен выдавать старые ключи за профильные",
                Regex("""getProfileKey\(\s*(EXCLUDED_APPS|WHITELIST_APPS|IS_WHITELIST)""")
                    .containsMatchIn(body),
            )
        }
    }

    @Test
    fun `the one-time migration copies the active profile and is gated`() {
        val migration = bodyOf("suspend fun migrateGlobalAppSettings(")

        assertTrue(
            "перенос не закрыт флагом до чтения DataStore",
            migration.contains("APP_SETTINGS_GLOBAL_MIGRATED] == true) return"),
        )
        assertTrue(
            "перенос не закрыт флагом внутри edit",
            migration.contains("APP_SETTINGS_GLOBAL_MIGRATED] == true) return@edit"),
        )
        assertTrue(
            "перенос не ограничен профилем, отличным от нулевого",
            migration.contains("if (profile != CsqttConstants.Profiles.MIN_INDEX)"),
        )
        listOf(
            "EXCLUDED_APPS",
            "WHITELIST_APPS",
            "IS_WHITELIST",
            "PARTICIPANT_SHOW_LOGS",
        ).forEach { key ->
            assertTrue(
                "$key не переносится в глобальный ключ",
                Regex("""legacyProfileKey\(\s*$key""").containsMatchIn(migration),
            )
        }
        assertTrue(
            "флаг переноса не выставляется",
            migration.contains("prefs[APP_SETTINGS_GLOBAL_MIGRATED] = true"),
        )
    }

    @Test
    fun `the migration runs after the legacy repairs`() {
        val init = bodyOf("init {")
        val repairAt = init.indexOf("repairInvertedLocalBlacklist()")
        val migrateAt = init.indexOf("migrateGlobalAppSettings()")

        assertTrue("init не вызывает repairInvertedLocalBlacklist", repairAt >= 0)
        assertTrue("init не вызывает migrateGlobalAppSettings", migrateAt >= 0)
        assertTrue(
            "перенос должен идти после ремонта легаси-списков",
            migrateAt > repairAt,
        )
    }

    @Test
    fun `no routing key is ever routed through the profile suffix helper`() {
        assertFalse(
            "списки приложений или режим снова проходят через getProfileKey",
            Regex(
                """getProfileKey\(\s*(EXCLUDED_APPS|WHITELIST_APPS|IS_WHITELIST|PARTICIPANT_SHOW_LOGS)"""
            ).containsMatchIn(sourceText),
        )
    }
}
