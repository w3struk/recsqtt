package com.csqtt.client.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Formatting rules for the operator lines added to the support report.
 *
 * [readOperatorSnapshot] touches Android telephony and is not exercised here: only the pure
 * [operatorReportLines] mapping is covered.
 */
class SupportReportOperatorTest {

    @Test
    fun `identical sim and network collapse into one line`() {
        val snapshot = OperatorSnapshot(
            simName = "МегаФон",
            simCode = "25002",
            simCountry = "ru",
            networkName = "МегаФон",
            networkCode = "25002",
            networkCountry = "ru",
        )
        assertEquals(
            listOf("Оператор: МегаФон / 25002 / RU"),
            operatorReportLines(snapshot),
        )
    }

    @Test
    fun `roaming network is shown next to the sim operator`() {
        val snapshot = OperatorSnapshot(
            simName = "МТС",
            simCode = "25001",
            simCountry = "ru",
            networkName = "Vodafone",
            networkCode = "20404",
            networkCountry = "nl",
        )
        assertEquals(
            listOf("Оператор (SIM): МТС / 25001 / RU", "Оператор (сеть): Vodafone / 20404 / NL"),
            operatorReportLines(snapshot),
        )
    }

    @Test
    fun `missing telephony yields a single n-a line`() {
        assertEquals(listOf("Оператор: n/a"), operatorReportLines(OperatorSnapshot()))
    }

    @Test
    fun `only the network operator is shown when the sim is absent`() {
        val snapshot = OperatorSnapshot(
            networkName = "Beeline",
            networkCode = "25099",
            networkCountry = "ru",
        )
        assertEquals(listOf("Оператор (сеть): Beeline / 25099 / RU"), operatorReportLines(snapshot))
    }

    @Test
    fun `blank fields keep their position as question marks`() {
        val snapshot = OperatorSnapshot(simCode = "25002")
        assertEquals(listOf("Оператор: ? / 25002 / ?"), operatorReportLines(snapshot))
    }

    @Test
    fun `fields are trimmed before formatting`() {
        val snapshot = OperatorSnapshot(
            simName = "  МегаФон ",
            simCode = " 25002 ",
            simCountry = " ru ",
        )
        assertEquals(listOf("Оператор: МегаФон / 25002 / RU"), operatorReportLines(snapshot))
    }
}
