package dev.eggmasonvalue.tracky.core.ble

import java.util.Locale

/** Android-free advertisement snapshot, useful for deterministic JVM tests. */
data class BleAdvertisement(
    val name: String?,
    val serviceUuids: List<String>,
)

object ScaleCandidateMatcher {
    private const val NAME_PREFIX = "QN-Scale"
    private val service16 = setOf("ffe0", "fff0")
    private val bluetoothBase = Regex("^0000([0-9a-f]{4})-0000-1000-8000-00805f9b34fb$")

    /** Name and services are deliberately OR-matched; many scales advertise only one of them. */
    fun matches(advertisement: BleAdvertisement): Boolean {
        val nameMatch = advertisement.name
            ?.trim()
            ?.startsWith(NAME_PREFIX, ignoreCase = true) == true
        return nameMatch || advertisement.serviceUuids.any { uuid -> shortUuid(uuid) in service16 }
    }

    private fun shortUuid(value: String): String? {
        val normalized = value.trim().lowercase(Locale.ROOT).removePrefix("0x")
        return when {
            normalized.length == 4 && normalized.all { it in "0123456789abcdef" } -> normalized
            else -> bluetoothBase.matchEntire(normalized)?.groupValues?.get(1)
        }
    }

    /** Removes controls and bounds untrusted advertisement text before exposing it to UI state. */
    fun safeName(value: String?): String? = value
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.take(64)
        ?.ifEmpty { null }
}
