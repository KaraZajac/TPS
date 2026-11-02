package org.soulstone.tps.gnss

import kotlin.math.abs
import kotlin.math.roundToInt

/** Which part of the spectrum a carrier is in. Two different tiers make a signal dual-frequency. */
enum class Tier { UPPER_L, LOWER_L, S_BAND }

/**
 * The carrier groups a phone can report, identified by frequency.
 *
 * Several systems share a carrier (1575.42 MHz is GPS L1, Galileo E1, BeiDou B1C,
 * QZSS L1, NavIC L1 and SBAS L1), so a [Carrier] is named for the frequency and
 * [Bands.label] gives each system's own name for it.
 */
enum class Carrier(val tier: Tier?) {
    L1(Tier.UPPER_L),   // 1575.42
    B1I(Tier.UPPER_L),  // 1561.098
    G1(Tier.UPPER_L),   // GLONASS FDMA 1602 + k·0.5625 (L1OC at 1600.995 falls inside)
    L2(Tier.LOWER_L),   // 1227.60
    G2(Tier.LOWER_L),   // GLONASS FDMA 1246 + k·0.4375 (L2OC at 1248.06 falls inside)
    G3(Tier.LOWER_L),   // GLONASS L3OC 1202.025
    L5(Tier.LOWER_L),   // 1176.45
    E5B(Tier.LOWER_L),  // 1207.14
    E6(Tier.LOWER_L),   // 1278.75
    B3I(Tier.LOWER_L),  // 1268.52
    S(Tier.S_BAND),     // 2492.028
    UNKNOWN(null);

    /**
     * Whether [c] broadcasts on this carrier. Chips can report nonsense here (the
     * emulator puts its GPS satellites on a GLONASS G1 frequency), and a band name a
     * system doesn't have would mislead, so such a pairing counts as unknown.
     */
    fun broadcastBy(c: Constellation): Boolean = c == Constellation.UNKNOWN || when (this) {
        L1, L5 -> c in L1_L5_SYSTEMS
        B1I, B3I -> c == Constellation.BEIDOU
        G1, G2, G3 -> c == Constellation.GLONASS
        L2 -> c == Constellation.GPS || c == Constellation.QZSS
        E5B -> c == Constellation.GALILEO || c == Constellation.BEIDOU
        E6 -> c == Constellation.GALILEO || c == Constellation.QZSS
        S -> c == Constellation.NAVIC
        UNKNOWN -> true
    }

    companion object {
        private val L1_L5_SYSTEMS = setOf(
            Constellation.GPS, Constellation.GALILEO, Constellation.BEIDOU,
            Constellation.QZSS, Constellation.NAVIC, Constellation.SBAS,
        )

        /** Tolerance around a nominal carrier. Distinct carriers are at least 5 MHz apart. */
        private const val TOLERANCE_MHZ = 1.0

        fun of(carrierHz: Double?): Carrier {
            if (carrierHz == null || carrierHz.isNaN() || carrierHz <= 0.0) return UNKNOWN
            val mhz = carrierHz / 1e6
            fun near(nominal: Double) = abs(mhz - nominal) <= TOLERANCE_MHZ
            return when {
                near(1575.42) -> L1
                near(1561.098) -> B1I
                mhz in 1597.5..1606.0 -> G1   // channels k = -7..+6: 1598.0625..1605.375
                near(1227.60) -> L2
                mhz in 1242.4..1249.2 -> G2   // 1242.9375..1248.625
                near(1202.025) -> G3
                near(1176.45) -> L5
                near(1207.14) -> E5B
                near(1278.75) -> E6
                near(1268.52) -> B3I
                near(2492.028) -> S
                else -> UNKNOWN
            }
        }
    }
}

object Bands {

    private val ORDER = listOf(
        "L1", "E1", "B1C", "L1S", "B1I", "G1", "L2", "G2", "G3",
        "L5", "E5a", "B2a", "E5b", "B2I", "B2b", "E6", "L6", "B3I", "S",
    )

    /** Sort key for band names: upper L-band first, as the carriers are ordered. */
    fun rank(label: String): Int = ORDER.indexOf(label).let { if (it < 0) ORDER.size else it }

    /** A system's own name for a carrier: L5 is E5a on Galileo and B2a on BeiDou. */
    fun label(constellation: Constellation, svid: Int, carrier: Carrier): String = when (carrier) {
        Carrier.L1 -> when (constellation) {
            Constellation.GALILEO -> "E1"
            Constellation.BEIDOU -> "B1C"
            Constellation.QZSS -> if (svid in 183..192) "L1S" else "L1"
            else -> "L1"
        }
        Carrier.B1I -> "B1I"
        Carrier.G1 -> "G1"
        Carrier.L2 -> "L2"
        Carrier.G2 -> "G2"
        Carrier.G3 -> "G3"
        Carrier.L5 -> when (constellation) {
            Constellation.GALILEO -> "E5a"
            Constellation.BEIDOU -> "B2a"
            else -> "L5"
        }
        // BeiDou-2 (C01–C18) broadcasts B2I here; BeiDou-3 broadcasts B2b.
        Carrier.E5B -> when (constellation) {
            Constellation.BEIDOU -> if (svid < 19) "B2I" else "B2b"
            else -> "E5b"
        }
        Carrier.E6 -> if (constellation == Constellation.QZSS) "L6" else "E6"
        Carrier.B3I -> "B3I"
        Carrier.S -> "S"
        Carrier.UNKNOWN -> "?"
    }

    /** GLONASS FDMA channel number k for a G1 or G2 carrier, or null for anything else. */
    fun glonassChannel(carrierHz: Double): Int? {
        val mhz = carrierHz / 1e6
        val k = when (Carrier.of(carrierHz)) {
            Carrier.G1 -> (mhz - 1602.0) / 0.5625
            Carrier.G2 -> (mhz - 1246.0) / 0.4375
            else -> return null
        }
        val rounded = k.roundToInt()
        return rounded.takeIf { it in -7..6 && abs(k - rounded) < 0.1 }
    }
}
