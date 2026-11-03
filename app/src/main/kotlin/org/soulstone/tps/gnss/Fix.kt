package org.soulstone.tps.gnss

/** Where the height above sea level came from. */
enum class MslSource {
    /** `Location.getMslAltitudeMeters()`, filled in by the platform (API 34+). */
    PLATFORM,

    /** `AltitudeConverter`, from the platform's bundled geoid model (API 34+). */
    CONVERTER,

    /** Ellipsoid height minus the geoid separation in the chip's NMEA `GGA` sentence. */
    NMEA,

    DEMO,
}

/** A position fix, independent of `android.location.Location` so demo sky can make one too. */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    /** Horizontal accuracy, 68% confidence radius. */
    val accuracyM: Float?,
    /** Height above the WGS 84 ellipsoid. */
    val altitudeM: Double?,
    val mslAltitudeM: Double? = null,
    val mslSource: MslSource? = null,
    val verticalAccuracyM: Float? = null,
    val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null,
    val bearingDeg: Float? = null,
    val bearingAccuracyDeg: Float? = null,
    /** UTC time of the fix from GNSS, in epoch milliseconds. */
    val gnssTimeMs: Long,
    /** When the fix was computed, on the elapsed-realtime clock. */
    val elapsedRealtimeMs: Long,
    /** Phone clock minus GNSS time at the moment of the fix. */
    val clockOffsetMs: Long? = null,
    val mock: Boolean = false,
)

/** Who computed the position on screen: TPS itself (the super position), or the phone's GNSS chip. */
enum class FixSource { TPS, CHIP }

/** The fix ring's colour tiers. Green means good here (unlike OVERWATCH, where it means clear). */
enum class FixTier { IDLE, SEARCHING, POOR, FAIR, GOOD }

object FixQuality {
    /** Green at this accuracy or better. */
    const val GOOD_M = 5f

    /** Yellow at this accuracy or better; orange beyond it. */
    const val FAIR_M = 15f

    /** A fix older than this counts as lost (GNSS fixes arrive once a second). */
    const val STALE_MS = 5_000L

    fun tier(running: Boolean, hasFix: Boolean, fixType: FixType?, accuracyM: Float?): FixTier = when {
        !running -> FixTier.IDLE
        !hasFix || fixType == FixType.NO_FIX -> FixTier.SEARCHING
        fixType == FixType.TWO_D -> FixTier.POOR
        accuracyM == null -> FixTier.POOR
        accuracyM <= GOOD_M -> FixTier.GOOD
        accuracyM <= FAIR_M -> FixTier.FAIR
        else -> FixTier.POOR
    }

    /**
     * 2D or 3D when the chip doesn't say (no NMEA `GSA`). A fix with a height counts
     * as 3D unless the chip flagged three or fewer satellites as used. Chips that flag
     * none still produce fixes, so zero doesn't count against it.
     */
    fun inferFixType(hasFix: Boolean, hasAltitude: Boolean, usedSatellites: Int): FixType = when {
        !hasFix -> FixType.NO_FIX
        usedSatellites in 1..3 -> FixType.TWO_D
        !hasAltitude -> FixType.TWO_D
        else -> FixType.THREE_D
    }
}
