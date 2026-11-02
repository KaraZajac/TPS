package org.soulstone.tps.gnss

import kotlin.math.cos
import kotlin.math.sin

/** One entry of Android's `GnssStatus`: one satellite on one carrier. */
data class Signal(
    val constellation: Constellation,
    val svid: Int,
    val cn0DbHz: Float,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean = false,
    val hasAlmanac: Boolean = false,
    /** Null when the chip doesn't report it (it's optional, API 26+). */
    val carrierHz: Double? = null,
)

enum class SatState {
    /** At least one of its signals is used in the fix. */
    USED,

    /** Received (C/N0 > 0) but not used. */
    TRACKED,

    /** Listed with C/N0 = 0: the chip expects it from the almanac but isn't receiving it. */
    PREDICTED,
}

/** A satellite with its signals merged: a dual-frequency satellite arrives as two [Signal]s. */
data class Satellite(
    val constellation: Constellation,
    val svid: Int,
    val label: String,
    val elevationDeg: Float,
    val azimuthDeg: Float,
    /** The strongest received band; 0 when predicted. */
    val cn0DbHz: Float,
    /** Band names, in carrier order: the received ones, or every listed one if none is received. */
    val bands: List<String>,
    val state: SatState,
    /** Received on two tiers (e.g. L1 and L5). */
    val dualFrequency: Boolean,
    /** Used in the fix on two tiers. */
    val dualFrequencyInUse: Boolean,
    /** For SBAS: WAAS, EGNOS, … from the PRN table. */
    val sbasSystem: String? = null,
) {
    /**
     * Chips report 0°/0° for a satellite whose position they don't know. A real one at
     * exactly 0.0/0.0 is rare enough not to need the ephemeris and almanac flags.
     */
    val positionKnown: Boolean get() = !(elevationDeg == 0f && azimuthDeg == 0f)

    /** Has a known position above the horizon, so it can go on the sky circle. */
    val plottable: Boolean get() = positionKnown && elevationDeg >= 0f

    val inView: Boolean get() = state != SatState.PREDICTED
}

data class ConstellationSummary(
    val constellation: Constellation,
    /** Used first, then tracked, then predicted; strongest first within each. */
    val satellites: List<Satellite>,
) {
    /** Received satellites (used or tracked). Predicted ones don't count as in view. */
    val inView: Int = satellites.count { it.inView }
    val used: Int = satellites.count { it.state == SatState.USED }
    val predicted: Int = satellites.count { it.state == SatState.PREDICTED }

    /** Every band received from this system, in carrier order. */
    val bands: List<String> = satellites.filter { it.inView }.flatMap { it.bands }
        .filter { it != "?" }.distinct().sortedBy { Bands.rank(it) }
}

enum class FrequencyMode {
    /** Some satellite is used in the fix on two tiers. */
    DUAL_IN_USE,

    /** Two tiers are received, but no satellite is used on both. */
    DUAL_TRACKED,

    SINGLE,

    /** The chip reports no carrier frequencies, so it can't be told. */
    UNKNOWN,
}

data class SkySnapshot(
    val satellites: List<Satellite>,
    /** All seven known systems, plus UNKNOWN if anything arrived as that; systems in view first. */
    val constellations: List<ConstellationSummary>,
) {
    val inView: Int = satellites.count { it.inView }
    val used: Int = satellites.count { it.state == SatState.USED }
    val isEmpty: Boolean get() = satellites.isEmpty()

    val frequencyMode: FrequencyMode = when {
        satellites.any { it.dualFrequencyInUse } -> FrequencyMode.DUAL_IN_USE
        satellites.any { it.dualFrequency } -> FrequencyMode.DUAL_TRACKED
        satellites.any { s -> s.bands.any { it != "?" } } -> FrequencyMode.SINGLE
        else -> FrequencyMode.UNKNOWN
    }

    fun summary(c: Constellation): ConstellationSummary? = constellations.firstOrNull { it.constellation == c }

    companion object {
        // Declared before EMPTY: companion properties initialise in order, and EMPTY calls of().
        private val SATELLITE_ORDER = compareBy<Satellite>({ it.state.ordinal }, { -it.cn0DbHz }, { it.svid })

        val EMPTY = of(emptyList())

        fun of(signals: List<Signal>): SkySnapshot {
            val satellites = signals
                .groupBy { it.constellation to it.svid }
                .map { (key, group) -> merge(key.first, key.second, group) }
                .sortedWith(SATELLITE_ORDER)

            val byConstellation = satellites.groupBy { it.constellation }
            val systems = if (Constellation.UNKNOWN in byConstellation) Constellation.entries else Constellation.KNOWN
            val summaries = systems
                .map { ConstellationSummary(it, byConstellation[it].orEmpty()) }
                // Stable sort: systems in view keep card order ahead of the rest.
                .sortedBy { if (it.inView > 0) 0 else 1 }
            return SkySnapshot(satellites, summaries)
        }

        private fun merge(c: Constellation, svid: Int, signals: List<Signal>): Satellite {
            val received = signals.filter { it.cn0DbHz > 0f }
            val used = signals.filter { it.usedInFix }
            val state = when {
                used.isNotEmpty() -> SatState.USED
                received.isNotEmpty() -> SatState.TRACKED
                else -> SatState.PREDICTED
            }
            // Position from the strongest signal that has one.
            val located = signals.filter { !(it.elevationDeg == 0f && it.azimuthDeg == 0f) }
            val position = located.ifEmpty { signals }.maxBy { it.cn0DbHz }

            fun carrier(s: Signal) = Carrier.of(s.carrierHz).let { if (it.broadcastBy(c)) it else Carrier.UNKNOWN }
            fun carriers(list: List<Signal>) = list.map(::carrier).distinct().sortedBy { it.ordinal }
            val bandCarriers = carriers(received.ifEmpty { signals })
            fun tiers(list: List<Signal>) = carriers(list).mapNotNull { it.tier }.toSet()

            return Satellite(
                constellation = c,
                svid = svid,
                label = SatelliteLabels.label(c, svid),
                elevationDeg = position.elevationDeg,
                azimuthDeg = position.azimuthDeg,
                cn0DbHz = received.maxOfOrNull { it.cn0DbHz } ?: 0f,
                bands = bandCarriers.map { Bands.label(c, svid, it) },
                state = state,
                dualFrequency = tiers(received).size >= 2,
                dualFrequencyInUse = tiers(used).size >= 2,
                sbasSystem = if (c == Constellation.SBAS) Sbas.system(svid) else null,
            )
        }
    }
}

/** A point on the unit sky disc: centre = overhead, radius 1 = horizon. */
data class SkyPoint(val x: Float, val y: Float)

object SkyProjection {
    /**
     * Where a satellite sits on the sky disc: north up and east right, as on a map, with
     * the radius linear in zenith angle. [headingDeg] rotates the disc for heading-up.
     */
    fun project(azimuthDeg: Float, elevationDeg: Float, headingDeg: Float = 0f): SkyPoint {
        val r = (90f - elevationDeg.coerceIn(0f, 90f)) / 90f
        val a = Math.toRadians((azimuthDeg - headingDeg).toDouble())
        return SkyPoint((r * sin(a)).toFloat(), (-r * cos(a)).toFloat())
    }
}
