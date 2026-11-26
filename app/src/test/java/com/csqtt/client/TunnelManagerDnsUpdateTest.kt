package com.csqtt.client

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * Контракт `TunnelManager` с Rust-клиентом по bootstrap-DNS:
 * argv-снимок при старте, `UPDATE_DNS|...` только живому процессу и только
 * при изменении списка, пустой список — явная очистка link-тира.
 */
class TunnelManagerDnsUpdateTest {
    private val originalFields = linkedMapOf<String, Any?>()

    private fun field(name: String) = TunnelManager::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun invoke(name: String, vararg args: Any?): Any? = TunnelManager::class.java
        .declaredMethods.single { it.name == name && it.parameterCount == args.size }
        .apply { isAccessible = true }
        .invoke(TunnelManager, *args)

    private fun pending() = field("pendingRoutingReload").get(null) as RoutingDeferredReload

    private fun text(output: ByteArrayOutputStream) = output.toString(Charsets.UTF_8.name())

    @Before fun setUp() {
        for (name in listOf(
            "process",
            "processIdentity",
            "processGeneration",
            "nativeProcessPid",
            "strongAppContext",
            "lastContext",
            "routingTerminal",
            "isLoggingEnabled",
            "physicalDnsServers",
        )) {
            originalFields[name] = field(name).get(null)
        }
        field("isLoggingEnabled").setBoolean(null, false)
        field("strongAppContext").set(null, null)
        field("lastContext").set(null, null)
        field("physicalDnsServers").set(null, emptyList<String>())
        pending().clear()
    }

    @After fun tearDown() {
        pending().clear()
        (field("lifecycleState").get(null) as TunnelLifecycleState).requestStop()
        originalFields.forEach { (name, value) -> field(name).set(null, value) }
    }

    private fun attachProcess(output: OutputStream): Any {
        val lifecycle = field("lifecycleState").get(null) as TunnelLifecycleState
        val process = Mockito.mock(Process::class.java)
        Mockito.`when`(process.isAlive).thenReturn(true)
        Mockito.`when`(process.outputStream).thenReturn(output)
        return synchronized(pending()) {
            val epoch = lifecycle.requestStart()
            val ticket = requireNotNull(lifecycle.reserveProcess(epoch, processIsNull = true))
            // Тот же путь публикации, что у startProcessLocked, а не его копия.
            requireNotNull(invoke("attachProcessIdentity", process, ticket))
        }
    }

    @Test fun `without a process the list is stored for the next spawn`() {
        TunnelManager.onPhysicalDnsChanged(listOf(" 1.1.1.1 ", "1.1.1.1", "::1", "8.8.8.8"))

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), field("physicalDnsServers").get(null))
        assertNull("нет процесса — писать некуда", field("processIdentity").get(null))
    }

    @Test fun `a live process receives UPDATE_DNS on change`() {
        val output = ByteArrayOutputStream()
        attachProcess(output)
        output.reset()

        TunnelManager.onPhysicalDnsChanged(listOf("1.1.1.1"))

        assertEquals("UPDATE_DNS|1.1.1.1\n", text(output))
    }

    @Test fun `repeating the same list does not write a second command`() {
        val output = ByteArrayOutputStream()
        attachProcess(output)
        TunnelManager.onPhysicalDnsChanged(listOf("1.1.1.1"))
        val afterFirst = text(output)

        TunnelManager.onPhysicalDnsChanged(listOf("1.1.1.1"))
        TunnelManager.onPhysicalDnsChanged(listOf(" 1.1.1.1 "))

        assertEquals(afterFirst, text(output))
    }

    @Test fun `an empty list clears the link tier with an empty payload`() {
        val output = ByteArrayOutputStream()
        attachProcess(output)
        TunnelManager.onPhysicalDnsChanged(listOf("1.1.1.1"))
        output.reset()

        TunnelManager.onPhysicalDnsChanged(emptyList())

        assertEquals("UPDATE_DNS|\n", text(output))
    }

    @Test fun `publishing a process re-sends the stored list`() {
        TunnelManager.onPhysicalDnsChanged(listOf("95.167.167.95", "95.167.167.96"))
        val output = ByteArrayOutputStream()

        attachProcess(output)

        assertEquals("UPDATE_DNS|95.167.167.95,95.167.167.96\n", text(output))
    }

    @Test fun `the dead process is not written to`() {
        val output = ByteArrayOutputStream()
        attachProcess(output)
        val process = field("process").get(null) as Process
        Mockito.`when`(process.isAlive).thenReturn(false)
        output.reset()

        TunnelManager.onPhysicalDnsChanged(listOf("1.1.1.1"))

        assertEquals("", text(output))
        assertEquals(listOf("1.1.1.1"), field("physicalDnsServers").get(null))
    }
}
