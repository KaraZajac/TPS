package org.soulstone.tps.raw

import kotlin.math.abs

/**
 * The chip's own position, velocity and clock for one satellite, as Android's `SatellitePvt`
 * carries them (Android 12+). The chip computes them from the orbits it holds, for the moment
 * the measured signal left the satellite. Android gives every app with precise location the
 * data but a getter only to system apps, so TPS reads it from the measurement's public
 * serialized form ([ChipPvtParser]).
 */
data class ChipPvt(
    val flags: Int,
    /** Earth-centred, Earth-fixed, at the signal's transmit time, metres. */
    val position: Ecef,
    /** The range error the chip expects from its orbit, metres. */
    val positionUreMeters: Double,
    val velocity: Ecef,
    val hardwareCodeBiasMeters: Double,
    val timeCorrectionMeters: Double,
    val clockDriftMps: Double,
    /** The chip's delay estimates for this signal; null when it gave none. */
    val ionoMeters: Double?,
    val tropoMeters: Double?,
    /** Seconds since the GPS epoch (Android 13+); null when not given. */
    val timeOfClockSeconds: Long? = null,
    val timeOfEphemerisSeconds: Long? = null,
    val issueOfDataClock: Int? = null,
    val issueOfDataEphemeris: Int? = null,
    /** Where the chip's orbit came from, an [EphemerisSource] value (Android 13+); null when not given. */
    val ephemerisSource: Int? = null,
) {
    /** The satellite clock's offset for this signal, metres; positive when the satellite clock is ahead. */
    val clockMeters: Double get() = timeCorrectionMeters - hardwareCodeBiasMeters

    /** Possible for a navigation satellite: medium Earth to geostationary orbit, a sane clock and delays. */
    val plausible: Boolean
        get() {
            val numbers = listOf(position.x, position.y, position.z, velocity.x, velocity.y, velocity.z, timeCorrectionMeters, hardwareCodeBiasMeters)
            if (numbers.any { it.isNaN() || it.isInfinite() }) return false
            return position.norm() in 2.0e7..4.6e7 &&
                velocity.norm() < 6_000.0 &&
                abs(timeCorrectionMeters) < 3.0e6 &&
                abs(hardwareCodeBiasMeters) < 1_000.0 &&
                (ionoMeters == null || ionoMeters in -1.0..300.0) &&
                (tropoMeters == null || tropoMeters in -1.0..300.0)
        }
}

/** Android's `SatellitePvt.EPHEMERIS_SOURCE_*`. */
object EphemerisSource {
    /** Decoded from the satellites' own broadcasts. */
    const val DEMODULATED = 0
    /** From an assistance server, as good as a broadcast orbit (SUPL, say). */
    const val SERVER_NORMAL = 1
    /** A server's prediction lasting hours to days, 2–20 m. */
    const val SERVER_LONG_TERM = 2
    const val OTHER = 3
}

/** What reading one measurement's [ChipPvt] gave. */
sealed interface PvtRead {
    data class Found(val pvt: ChipPvt) : PvtRead
    /** The chip sent none with this signal. */
    data object Absent : PvtRead
    /** Something was there, in a form TPS doesn't know. */
    data class Unreadable(val why: String) : PvtRead
}

/**
 * Android Parcel bytes, as `Parcel.marshall()` gives them: little-endian, each item padded to
 * four bytes, strings as an int length then UTF-16 with a terminator.
 */
class ParcelBytes(private val b: ByteArray, var pos: Int = 0) {
    class Malformed(message: String) : Exception(message)

    val remaining: Int get() = b.size - pos

    private fun need(n: Int) {
        if (n < 0 || pos + n > b.size) throw Malformed("needs $n bytes at $pos of ${b.size}")
    }

    fun int(): Int {
        need(4)
        val v = (b[pos].toInt() and 0xff) or
            ((b[pos + 1].toInt() and 0xff) shl 8) or
            ((b[pos + 2].toInt() and 0xff) shl 16) or
            (b[pos + 3].toInt() shl 24)
        pos += 4
        return v
    }

    fun long(): Long {
        val low = int().toLong() and 0xffff_ffffL
        val high = int().toLong()
        return (high shl 32) or low
    }

    fun double(): Double = java.lang.Double.longBitsToDouble(long())

    /** `Parcel.writeString`: null as length -1. */
    fun string(): String? {
        val n = int()
        if (n == -1) return null
        if (n < 0 || n > MAX_STRING) throw Malformed("string length $n at ${pos - 4}")
        val size = pad4((n + 1) * 2)
        need(size)
        val chars = CharArray(n) { i -> ((b[pos + 2 * i].toInt() and 0xff) or ((b[pos + 2 * i + 1].toInt() and 0xff) shl 8)).toChar() }
        pos += size
        return String(chars)
    }

    companion object {
        const val MAX_STRING = 4096

        fun pad4(n: Int) = (n + 3) and 3.inv()

        /** The bytes `Parcel.writeString(s)` produces, for finding a string in a parcel. */
        fun encode(s: String): ByteArray {
            val out = ByteArray(4 + pad4((s.length + 1) * 2))
            for (k in 0 until 4) out[k] = (s.length ushr (8 * k)).toByte()
            s.forEachIndexed { i, c ->
                out[4 + 2 * i] = c.code.toByte()
                out[5 + 2 * i] = (c.code ushr 8).toByte()
            }
            return out
        }
    }
}

/**
 * Finds and reads the `SatellitePvt` in a serialized `GnssMeasurement` (`writeToParcel`, then
 * `Parcel.marshall()`). Android 12–16 write it, when present, as its class name and then:
 * an int of flags; position, velocity and clock objects (each a class name then 4, 4 and 3
 * doubles); the ionospheric and tropospheric delays; and from Android 13 the times of clock and
 * ephemeris (longs), the two issue-of-data numbers and the ephemeris source (ints).
 *
 * TPS searches for the class name rather than counting fields from the start, so a field added
 * to `GnssMeasurement` itself doesn't break it.
 */
object ChipPvtParser {
    /** `GnssMeasurement.HAS_SATELLITE_PVT`, a bit of the first int of the parcel (its flags). */
    const val MEASUREMENT_HAS_PVT = 1 shl 20

    private const val CLASS = "android.location.SatellitePvt"
    private const val POSITION = "$CLASS\$PositionEcef"
    private const val VELOCITY = "$CLASS\$VelocityEcef"
    private const val CLOCK = "$CLASS\$ClockInfo"

    const val HAS_POSITION_VELOCITY_CLOCK = 1
    const val HAS_IONO = 1 shl 1
    const val HAS_TROPO = 1 shl 2
    const val HAS_ISSUE_OF_DATA_CLOCK = 1 shl 3
    const val HAS_ISSUE_OF_DATA_EPHEMERIS = 1 shl 4
    const val HAS_TIME_OF_CLOCK = 1 shl 5
    const val HAS_TIME_OF_EPHEMERIS = 1 shl 6

    private val marker = ParcelBytes.encode(CLASS)

    /**
     * [headerMatches] gets the parcel's first three ints (flags, svid, constellation) and says
     * whether they agree with the measurement's public getters; only then is the flags' PVT
     * bit trusted to mean "none sent". [extras]: the Android 13 fields are there (API 33+).
     */
    fun parse(bytes: ByteArray, extras: Boolean, headerMatches: (flags: Int, svid: Int, constellation: Int) -> Boolean): PvtRead {
        if (bytes.size < 12) return PvtRead.Unreadable("short")
        val head = ParcelBytes(bytes)
        val flags = head.int()
        val headerKnown = headerMatches(flags, head.int(), head.int())
        if (headerKnown && flags and MEASUREMENT_HAS_PVT == 0) return PvtRead.Absent
        val at = indexOf(bytes, marker)
            ?: return PvtRead.Unreadable(if (headerKnown) "flagged but not found" else "unknown layout")
        return try {
            body(ParcelBytes(bytes, at + marker.size), extras)
        } catch (e: ParcelBytes.Malformed) {
            PvtRead.Unreadable(e.message ?: "malformed")
        }
    }

    private fun body(p: ParcelBytes, extras: Boolean): PvtRead {
        val flags = p.int()
        val position = nested(p, POSITION, 4)
        val velocity = nested(p, VELOCITY, 4)
        val clock = nested(p, CLOCK, 3)
        val iono = p.double()
        val tropo = p.double()
        if (flags and HAS_POSITION_VELOCITY_CLOCK == 0 || position == null || velocity == null || clock == null) {
            return PvtRead.Absent
        }
        var toc: Long? = null
        var toe: Long? = null
        var iodc: Int? = null
        var iode: Int? = null
        var source: Int? = null
        if (extras && p.remaining >= 28) {
            toc = p.long().takeIf { flags and HAS_TIME_OF_CLOCK != 0 }
            toe = p.long().takeIf { flags and HAS_TIME_OF_EPHEMERIS != 0 }
            iodc = p.int().takeIf { flags and HAS_ISSUE_OF_DATA_CLOCK != 0 }
            iode = p.int().takeIf { flags and HAS_ISSUE_OF_DATA_EPHEMERIS != 0 }
            source = p.int().takeIf { it in EphemerisSource.DEMODULATED..EphemerisSource.OTHER }
        }
        return PvtRead.Found(
            ChipPvt(
                flags = flags,
                position = Ecef(position[0], position[1], position[2]),
                positionUreMeters = position[3],
                velocity = Ecef(velocity[0], velocity[1], velocity[2]),
                hardwareCodeBiasMeters = clock[0],
                timeCorrectionMeters = clock[1],
                clockDriftMps = clock[2],
                ionoMeters = iono.takeIf { flags and HAS_IONO != 0 },
                tropoMeters = tropo.takeIf { flags and HAS_TROPO != 0 },
                timeOfClockSeconds = toc,
                timeOfEphemerisSeconds = toe,
                issueOfDataClock = iodc,
                issueOfDataEphemeris = iode,
                ephemerisSource = source,
            )
        )
    }

    /** A `writeParcelable` object: its class name (null for a null object), then [count] doubles. */
    private fun nested(p: ParcelBytes, name: String, count: Int): DoubleArray? {
        val written = p.string() ?: return null
        if (written != name) throw ParcelBytes.Malformed("expected $name, found $written")
        return DoubleArray(count) { p.double() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int? {
        // Parcel items start on four-byte boundaries.
        var i = 0
        while (i + needle.size <= haystack.size) {
            var k = 0
            while (k < needle.size && haystack[i + k] == needle[k]) k++
            if (k == needle.size) return i
            i += 4
        }
        return null
    }
}
