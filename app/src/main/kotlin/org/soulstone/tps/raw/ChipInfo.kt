package org.soulstone.tps.raw

import android.location.GnssCapabilities
import android.location.LocationManager
import android.os.Build
import androidx.annotation.RequiresApi
import org.soulstone.tps.gnss.Bands
import org.soulstone.tps.gnss.Carrier
import org.soulstone.tps.gnss.Constellation

/** What the phone's GNSS chip says about itself. */
data class ChipInfo(
    val hardware: String?,
    val year: Int?,
    val device: String,
    val android: String,
    /** Capability name to yes/no; null where Android can't say on this version. */
    val capabilities: List<Pair<String, Boolean?>>,
    /** Every signal the chip supports (API 34+); null below that, empty if the chip doesn't say. */
    val signals: List<String>?,
    /** Systems the chip can receive, from [signals]; null when unknown. */
    val supported: Set<Constellation>?,
) {
    fun capability(name: String): Boolean? = capabilities.firstOrNull { it.first == name }?.second

    companion object {
        fun read(lm: LocationManager?): ChipInfo {
            val hardware = if (Build.VERSION.SDK_INT >= 28) lm?.gnssHardwareModelName else null
            val year = if (Build.VERSION.SDK_INT >= 28) lm?.gnssYearOfHardware?.takeIf { it > 0 } else null
            val caps = if (Build.VERSION.SDK_INT >= 30) lm?.gnssCapabilities else null
            val capabilities = when {
                caps == null -> emptyList()
                Build.VERSION.SDK_INT >= 31 -> Api31.capabilities(caps)
                else -> listOf("Antenna info" to @Suppress("DEPRECATION") caps.hasGnssAntennaInfo())
            }
            val signals = if (Build.VERSION.SDK_INT >= 34 && caps != null) Api34.signals(caps) else null
            return ChipInfo(
                hardware = hardware?.takeIf { it.isNotBlank() },
                year = year,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
                capabilities = capabilities,
                signals = signals?.map { it.second },
                supported = signals?.takeIf { it.isNotEmpty() }?.map { it.first }?.toSet(),
            )
        }
    }

    @RequiresApi(31)
    private object Api31 {
        fun capabilities(c: GnssCapabilities): List<Pair<String, Boolean?>> {
            val list = mutableListOf<Pair<String, Boolean?>>(
                "Raw measurements" to c.hasMeasurements(),
                "Navigation messages" to c.hasNavigationMessages(),
                "Antenna info" to c.hasAntennaInfo(),
            )
            if (Build.VERSION.SDK_INT >= 34) list += Api34.capabilities(c)
            return list
        }
    }

    @RequiresApi(34)
    private object Api34 {
        fun capabilities(c: GnssCapabilities): List<Pair<String, Boolean?>> = listOf(
            "Carrier phase (ADR)" to when (c.hasAccumulatedDeltaRange()) {
                GnssCapabilities.CAPABILITY_SUPPORTED -> true
                GnssCapabilities.CAPABILITY_UNSUPPORTED -> false
                else -> null
            },
            "Satellite PVT (system apps only)" to c.hasSatellitePvt(),
            "Correlation vectors" to c.hasMeasurementCorrelationVectors(),
            "Measurement corrections" to c.hasMeasurementCorrections(),
            "Satellite blocklist" to c.hasSatelliteBlocklist(),
            "Low-power mode" to c.hasLowPowerMode(),
        )

        fun signals(c: GnssCapabilities): List<Pair<Constellation, String>> = c.gnssSignalTypes.map { s ->
            val system = Constellation.fromAndroid(s.constellationType)
            val band = Bands.label(system, 0, Carrier.of(s.carrierFrequencyHz))
                .let { if (it == "?") String.format(java.util.Locale.ROOT, "%.2f MHz", s.carrierFrequencyHz / 1e6) else it }
            system to "${system.displayName} $band" + (s.codeType.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
        }.distinctBy { it.second }
    }
}
