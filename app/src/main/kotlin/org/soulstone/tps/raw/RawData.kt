package org.soulstone.tps.raw

import kotlin.math.floor
import org.soulstone.tps.gnss.Constellation

/** One `GnssClock` reading, without Android types. */
data class RawClock(
    val timeNanos: Long,
    /** Null when the chip doesn't know GPS time yet; then no pseudorange can be formed. */
    val fullBiasNanos: Long?,
    val biasNanos: Double = 0.0,
    val biasUncertaintyNanos: Double? = null,
    val driftNanosPerSecond: Double? = null,
    val driftUncertaintyNanosPerSecond: Double? = null,
    val timeUncertaintyNanos: Double? = null,
    val discontinuityCount: Int = 0,
    val leapSecond: Int? = null,
    val elapsedRealtimeNanos: Long? = null,
)

/** One `GnssMeasurement`: one satellite on one signal at one epoch. */
data class RawMeasurement(
    val constellation: Constellation,
    val svid: Int,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncertaintyNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncertaintyMps: Double = 0.0,
    val adrState: Int = 0,
    val adrMeters: Double = 0.0,
    val adrUncertaintyMeters: Double = 0.0,
    val carrierHz: Double? = null,
    val multipath: Int = 0,
    val snrDb: Double? = null,
    val basebandCn0DbHz: Double? = null,
    val codeType: String? = null,
    val fullInterSignalBiasNanos: Double? = null,
    val satelliteInterSignalBiasNanos: Double? = null,
    /** The chip's own position and clock for this satellite, when it sent them (Android 12+). */
    val pvt: ChipPvt? = null,
)

/** Automatic gain control for one band (API 33): a sudden drop can mean jamming. */
data class Agc(val constellation: Constellation, val carrierHz: Long, val levelDb: Double)

/** One `GnssMeasurementsEvent`. */
data class RawEpoch(
    val clock: RawClock,
    val measurements: List<RawMeasurement>,
    val agc: List<Agc> = emptyList(),
    val fullTracking: Boolean? = null,
    /** Signals whose chip position was sent but couldn't be read, and why (the first). */
    val pvtUnreadable: Int = 0,
    val pvtWhy: String? = null,
)

/** One `GnssNavigationMessage`: a subframe, page or string of broadcast data. */
class NavMessage(
    val type: Int,
    val svid: Int,
    val messageId: Int,
    val submessageId: Int,
    val status: Int,
    val data: ByteArray,
)

/** `GnssMeasurement.STATE_*` bits (the values are part of the public API). */
object MeasurementState {
    const val CODE_LOCK = 1 shl 0
    const val BIT_SYNC = 1 shl 1
    const val SUBFRAME_SYNC = 1 shl 2
    const val TOW_DECODED = 1 shl 3
    const val MSEC_AMBIGUOUS = 1 shl 4
    const val SYMBOL_SYNC = 1 shl 5
    const val GLO_STRING_SYNC = 1 shl 6
    const val GLO_TOD_DECODED = 1 shl 7
    const val BDS_D2_BIT_SYNC = 1 shl 8
    const val BDS_D2_SUBFRAME_SYNC = 1 shl 9
    const val GAL_E1BC_CODE_LOCK = 1 shl 10
    const val GAL_E1C_2ND_CODE_LOCK = 1 shl 11
    const val GAL_E1B_PAGE_SYNC = 1 shl 12
    const val SBAS_SYNC = 1 shl 13
    const val TOW_KNOWN = 1 shl 14
    const val GLO_TOD_KNOWN = 1 shl 15
    const val SECOND_CODE_LOCK = 1 shl 16
}

/** `GnssNavigationMessage.TYPE_*` and `STATUS_*` values. */
object NavType {
    const val GPS_L1CA = 0x0101
    const val GPS_L2CNAV = 0x0102
    const val GPS_L5CNAV = 0x0103
    const val GPS_CNAV2 = 0x0104
    const val SBS = 0x0201
    const val GLO_L1CA = 0x0301
    const val QZS_L1CA = 0x0401
    const val BDS_D1 = 0x0501
    const val BDS_D2 = 0x0502
    const val BDS_CNAV1 = 0x0503
    const val BDS_CNAV2 = 0x0504
    const val GAL_I = 0x0601
    const val GAL_F = 0x0602
    const val IRN_L5CA = 0x0701

    const val STATUS_PARITY_PASSED = 1
    const val STATUS_PARITY_REBUILT = 2

    fun name(type: Int): String = when (type) {
        GPS_L1CA -> "GPS L1 C/A"
        GPS_L2CNAV -> "GPS L2 CNAV"
        GPS_L5CNAV -> "GPS L5 CNAV"
        GPS_CNAV2 -> "GPS CNAV-2"
        SBS -> "SBAS"
        GLO_L1CA -> "GLONASS L1"
        QZS_L1CA -> "QZSS L1 C/A"
        BDS_D1 -> "BeiDou D1"
        BDS_D2 -> "BeiDou D2"
        BDS_CNAV1 -> "BeiDou B-CNAV1"
        BDS_CNAV2 -> "BeiDou B-CNAV2"
        GAL_I -> "Galileo I/NAV"
        GAL_F -> "Galileo F/NAV"
        IRN_L5CA, 0x0702 -> "NavIC L5"
        0x0703 -> "NavIC L1"
        else -> "type 0x" + type.toString(16)
    }
}

/** A pseudorange: the distance to a satellite plus the receiver's clock error. */
data class Pseudorange(
    val measurement: RawMeasurement,
    val meters: Double,
    /** One-sigma, from the chip's own uncertainty on the received satellite time. */
    val sigmaMeters: Double,
    /** When the signal left the satellite, in seconds of the satellite system's own week (or, for GLONASS, day). */
    val transmitTimeSeconds: Double,
)

object Pseudoranges {
    const val C = 299_792_458.0
    const val WEEK_NS = 604_800_000_000_000L
    const val DAY_NS = 86_400_000_000_000L

    /** BeiDou time is 14 s behind GPS time. */
    const val BDS_OFFSET_NS = 14_000_000_000L

    /** GLONASS keeps UTC(SU) + 3 h (Moscow time), with leap seconds. */
    const val GLO_OFFSET_NS = 3 * 3_600_000_000_000L

    /** GPS minus UTC since 2017, used when the chip doesn't report leap seconds. */
    const val DEFAULT_LEAP_SECONDS = 18

    /** Signals usable for a fix: at least 0.06 s (MEO, overhead) and at most 0.15 s (GEO, low). */
    private const val MIN_TRAVEL_S = 0.06
    private const val MAX_TRAVEL_S = 0.15

    /** Above this the chip's own time uncertainty is worse than 150 m. */
    private const val MAX_UNCERTAINTY_NS = 500L

    /**
     * Forms the pseudorange for [m], or null if the chip hasn't decoded enough to time it.
     *
     * Receive time in GPS time is `TimeNanos − (FullBiasNanos + BiasNanos) + TimeOffsetNanos`.
     * It's kept as whole nanoseconds (a Long) as long as possible: nanoseconds since 1980
     * don't fit a Double's precision.
     */
    fun compute(clock: RawClock, m: RawMeasurement): Pseudorange? {
        val fullBias = clock.fullBiasNanos ?: return null
        if (m.receivedSvTimeUncertaintyNanos > MAX_UNCERTAINTY_NS) return null
        val gpsNanos = clock.timeNanos - fullBias
        val fraction = m.timeOffsetNanos - clock.biasNanos
        val leap = (clock.leapSecond ?: DEFAULT_LEAP_SECONDS) * 1_000_000_000L

        val period: Long
        val receiveNs: Double
        when (m.constellation) {
            Constellation.GPS, Constellation.QZSS, Constellation.GALILEO, Constellation.NAVIC -> {
                if (!m.hasAny(MeasurementState.TOW_DECODED or MeasurementState.TOW_KNOWN)) return null
                period = WEEK_NS
                receiveNs = Math.floorMod(gpsNanos, WEEK_NS) + fraction
            }
            Constellation.BEIDOU -> {
                if (!m.hasAny(MeasurementState.TOW_DECODED or MeasurementState.TOW_KNOWN)) return null
                period = WEEK_NS
                receiveNs = Math.floorMod(gpsNanos - BDS_OFFSET_NS, WEEK_NS) + fraction
            }
            Constellation.GLONASS -> {
                if (!m.hasAny(MeasurementState.GLO_TOD_DECODED or MeasurementState.GLO_TOD_KNOWN)) return null
                period = DAY_NS
                receiveNs = Math.floorMod(gpsNanos + GLO_OFFSET_NS - leap, DAY_NS) + fraction
            }
            Constellation.SBAS, Constellation.UNKNOWN -> return null
        }
        var travelNs = receiveNs - m.receivedSvTimeNanos
        // The receive time can be just past a week (or day) boundary the transmit time is before.
        if (travelNs < -period / 2.0) travelNs += period
        if (travelNs > period / 2.0) travelNs -= period
        val travelS = travelNs * 1e-9
        if (travelS !in MIN_TRAVEL_S..MAX_TRAVEL_S) return null
        val transmit = m.receivedSvTimeNanos * 1e-9
        return Pseudorange(
            measurement = m,
            meters = travelS * C,
            sigmaMeters = m.receivedSvTimeUncertaintyNanos * 1e-9 * C,
            transmitTimeSeconds = transmit - floor(transmit / (period * 1e-9)) * (period * 1e-9),
        )
    }

    private fun RawMeasurement.hasAny(bits: Int) = state and bits != 0
}
