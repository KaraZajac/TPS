package org.soulstone.tps.raw

/** What Android says about a raw-data stream: from the callbacks' status, or from data arriving. */
enum class RawStatus { UNKNOWN, NOT_SUPPORTED, READY, LOCATION_DISABLED, NOT_ALLOWED }

/** The recorder: the file being written, if any, and the last one finished (to share). */
data class RecorderState(val active: String? = null, val startedAtMs: Long = 0, val lines: Long = 0, val last: String? = null)

/** Raw measurements, navigation messages and TPS's own fixes this session, for the CHIP and OWN FIXES cards. */
data class RawState(
    val measurements: RawStatus = RawStatus.UNKNOWN,
    val navigation: RawStatus = RawStatus.UNKNOWN,
    /** Measurement events received this session. */
    val epochs: Long = 0,
    /** Navigation messages received this session. */
    val navMessages: Long = 0,
    /** Signals in the latest measurement event. */
    val signals: Int = 0,
    /** Whether the chip reports full tracking (API 34); null when it doesn't say. */
    val fullTracking: Boolean? = null,
    val agc: List<Agc> = emptyList(),
    /** [agc] came from the per-measurement field. */
    val agcFromSignals: Boolean = false,
    /** Whether the phone has a barometer (null until TPS has looked), and its latest second's mean, hPa. */
    val barometer: Boolean? = null,
    val pressureHpa: Double? = null,
    val report: OwnFixReport? = null,
    /** Signals in the latest event that came with the chip's satellite position. */
    val pvtSignals: Int = 0,
    /** Of the latest event's signals, those whose chip position was sent but couldn't be read, and why. */
    val pvtUnreadable: Int = 0,
    val pvtWhy: String? = null,
    /** Whether any chip position has been read this session. */
    val pvtSeen: Boolean = false,
    /** Where the latest event's chip positions came from ([EphemerisSource] to signals; -1 when not said). */
    val pvtSources: Map<Int, Int> = emptyMap(),
)
