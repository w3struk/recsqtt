package com.csqtt.client

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тот же порядок объявлений, что и в [SettingsStoreInitOrderTest], но для
 * профильных кэшей. `init` обязан идти после всех свойств, которые он собирает:
 * на старом порядке приложение падало на старте и при рестарте сервиса.
 */
class ProfileSettingsStoreInitOrderTest {

    private val source: List<String> = listOf(
        File("app/src/main/java/com/csqtt/client/ProfileSettingsStore.kt"),
        File("src/main/java/com/csqtt/client/ProfileSettingsStore.kt"),
    ).first(File::isFile).readLines()

    @Test
    fun `the init block that starts the profile cache collectors comes after every property it reads`() {
        val property = Regex("""^    (private |internal )?(val|var) (\w+)""")
        val properties = source.withIndex()
            .mapNotNull { (i, line) -> property.find(line)?.let { it.groupValues[3] to i } }
            .toMap()

        val init = source.indexOfFirst { it == "    init {" }
        assertTrue("в ProfileSettingsStore нет блока init", init >= 0)
        val end = (init until source.size).first { source[it] == "    }" }

        val collected = Regex("""launch \{ (\w+)\.collect""")
            .findAll(source.subList(init, end).joinToString("\n"))
            .map { it.groupValues[1] }
            .toList()
        assertTrue("блок init не поднимает профильные сборщики кэша", collected.size >= 6)

        for (name in collected) {
            val at = properties[name]
            assertTrue("$name не является свойством ProfileSettingsStore", at != null)
            assertTrue(
                "$name объявлен после блока init, который его собирает (null при сборе)",
                at!! < init,
            )
        }
    }

    @Test
    fun `the registry migration is launched from init`() {
        val init = source.indexOfFirst { it == "    init {" }
        assertTrue("в ProfileSettingsStore нет блока init", init >= 0)
        val end = (init until source.size).first { source[it] == "    }" }
        val initBody = source.subList(init, end).joinToString("\n")

        assertTrue(
            "init не запускает migrateRegistryIfNeeded",
            initBody.contains("migrateRegistryIfNeeded()"),
        )
    }

    @Test
    fun `no property is declared after the init block`() {
        val property = Regex("""^    (private |internal )?(val|var) (\w+)""")
        val init = source.indexOfFirst { it == "    init {" }
        assertTrue("в ProfileSettingsStore нет блока init", init >= 0)

        val after = source.withIndex()
            .filter { (i, line) -> i > init && property.containsMatchIn(line) }
            .map { (i, line) -> property.find(line)!!.groupValues[3] }
        assertTrue("свойства объявлены после блока init: $after", after.isEmpty())
    }
}
