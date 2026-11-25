package com.csqtt.client.ui

import android.content.Context
import android.content.pm.PackageManager
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Operator identity captured for the support report.
 *
 * SIM values describe the home subscription, network values describe the operator the device is
 * actually registered to; they differ while roaming. All fields are empty when telephony is
 * unavailable or the platform returned nothing.
 */
internal data class OperatorSnapshot(
    val simName: String = "",
    val simCode: String = "",
    val simCountry: String = "",
    val networkName: String = "",
    val networkCode: String = "",
    val networkCountry: String = "",
)

/**
 * Reads the operator without any phone permission: the SIM and network getters used here need
 * none. Devices without [PackageManager.FEATURE_TELEPHONY] (Wi-Fi tablets) yield an empty
 * snapshot, and a throwing getter degrades to an empty field instead of failing the report.
 */
internal fun readOperatorSnapshot(context: Context): OperatorSnapshot {
    val hasTelephony = runCatching {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
    }.getOrDefault(false)
    if (!hasTelephony) return OperatorSnapshot()

    val telephony = runCatching {
        context.getSystemService(TelephonyManager::class.java)
    }.getOrNull() ?: return OperatorSnapshot()

    fun read(getter: (TelephonyManager) -> String?): String =
        runCatching { getter(telephony) }.getOrNull().orEmpty()

    return OperatorSnapshot(
        simName = read { it.simOperatorName },
        simCode = read { it.simOperator },
        simCountry = read { it.simCountryIso },
        networkName = read { it.networkOperatorName },
        networkCode = read { it.networkOperator },
        networkCountry = read { it.networkCountryIso },
    )
}

/**
 * Formats the report lines: `Оператор: <name> / <MCC+MNC> / <ISO-2>`.
 *
 * A SIM and a network operator that render identically collapse into one line; a differing
 * network operator (roaming) is named separately, and an absent SIM leaves only the network.
 * Unknown fields become `?` so the positions stay readable. No telephony yields `n/a`.
 */
internal fun operatorReportLines(snapshot: OperatorSnapshot): List<String> {
    val sim = formatOperator(snapshot.simName, snapshot.simCode, snapshot.simCountry)
    val network = formatOperator(snapshot.networkName, snapshot.networkCode, snapshot.networkCountry)
    return when {
        sim == null && network == null -> listOf("Оператор: n/a")
        sim == null -> listOf("Оператор (сеть): $network")
        network == null || network == sim -> listOf("Оператор: $sim")
        else -> listOf("Оператор (SIM): $sim", "Оператор (сеть): $network")
    }
}

private fun formatOperator(name: String, code: String, country: String): String? {
    val parts = listOf(
        name.trim().ifBlank { "?" },
        code.trim().ifBlank { "?" },
        country.trim().uppercase(Locale.ROOT).ifBlank { "?" },
    )
    return if (parts.all { it == "?" }) null else parts.joinToString(" / ")
}
