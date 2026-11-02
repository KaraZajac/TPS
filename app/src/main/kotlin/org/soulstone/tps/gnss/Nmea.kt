package org.soulstone.tps.gnss

enum class FixType { NO_FIX, TWO_D, THREE_D }

/** The two NMEA sentences TPS reads. Everything else the chip sends is ignored. */
sealed interface NmeaSentence {
    /** GSA: fix type, the satellites used, and DOP. NMEA 4.10+ adds a system ID. */
    data class Gsa(
        val fixType: FixType?,
        val pdop: Double?,
        val hdop: Double?,
        val vdop: Double?,
        /** 1 GPS, 2 GLONASS, 3 Galileo, 4 BeiDou, 5 QZSS, 6 NavIC (NMEA 4.10+). */
        val systemId: Int?,
        val svids: List<Int>,
    ) : NmeaSentence

    /** GGA: fix quality (2 = differential, e.g. SBAS corrections) and the geoid separation. */
    data class Gga(
        val quality: Int?,
        val satellites: Int?,
        val hdop: Double?,
        val mslAltitudeM: Double?,
        /** Geoid height above the WGS 84 ellipsoid. */
        val geoidSeparationM: Double?,
    ) : NmeaSentence
}

object Nmea {

    /** Parses a GSA or GGA sentence from any talker. Null for anything else, or a bad checksum. */
    fun parse(line: String): NmeaSentence? {
        val s = line.trim()
        if (s.length < 7 || s[0] != '$') return null
        val star = s.lastIndexOf('*')
        val body = if (star >= 0) {
            if (star + 3 > s.length) return null
            val given = s.substring(star + 1, star + 3).toIntOrNull(16) ?: return null
            s.substring(1, star).also { if (checksum(it) != given) return null }
        } else {
            s.substring(1)
        }
        val f = body.split(',')
        val id = f[0]
        if (id.length < 5 || id[0] == 'P') return null   // proprietary sentences
        return when (id.takeLast(3)) {
            "GSA" -> parseGsa(f)
            "GGA" -> parseGga(f)
            else -> null
        }
    }

    /** XOR of every character between `$` and `*`. */
    fun checksum(body: String): Int = body.fold(0) { acc, c -> acc xor c.code }

    private fun parseGsa(f: List<String>): NmeaSentence.Gsa? {
        if (f.size < 18) return null
        val fixType = when (f[2]) {
            "1" -> FixType.NO_FIX
            "2" -> FixType.TWO_D
            "3" -> FixType.THREE_D
            else -> null
        }
        return NmeaSentence.Gsa(
            fixType = fixType,
            pdop = dop(f[15]),
            hdop = dop(f[16]),
            vdop = dop(f[17]),
            systemId = f.getOrNull(18)?.trim()?.toIntOrNull(16),
            svids = (3..14).mapNotNull { f[it].toIntOrNull() },
        )
    }

    private fun parseGga(f: List<String>): NmeaSentence.Gga? {
        if (f.size < 12) return null
        return NmeaSentence.Gga(
            quality = f[6].toIntOrNull(),
            satellites = f[7].toIntOrNull(),
            hdop = dop(f[8]),
            mslAltitudeM = f[9].toDoubleOrNull(),
            geoidSeparationM = f[11].toDoubleOrNull(),
        )
    }

    /** Chips send 99.99 (or 0) when there's no solution. */
    private fun dop(s: String): Double? = s.toDoubleOrNull()?.takeIf { it > 0.0 && it < 99.0 }
}

/** Keeps the latest fix type, DOP and geoid separation from the NMEA stream. Not thread-safe. */
class NmeaTracker {

    data class State(
        val fixType: FixType? = null,
        val fixTypeAtMs: Long = 0,
        val dop: Dop? = null,
        val dopAtMs: Long = 0,
        val ggaQuality: Int? = null,
        val geoidSeparationM: Double? = null,
        val ggaAtMs: Long = 0,
    )

    var state = State()
        private set

    /** Feeds one line, timestamped on the elapsed-realtime clock. True if the state changed. */
    fun accept(line: String, nowMs: Long): Boolean {
        val before = state
        when (val s = Nmea.parse(line)) {
            is NmeaSentence.Gsa -> {
                // Some chips send one GSA per system; they carry the same fix type and DOP.
                var next = state
                if (s.fixType != null) next = next.copy(fixType = s.fixType, fixTypeAtMs = nowMs)
                if (s.pdop != null && s.hdop != null && s.vdop != null) {
                    next = next.copy(dop = Dop(s.pdop, s.hdop, s.vdop), dopAtMs = nowMs)
                }
                state = next
            }
            is NmeaSentence.Gga -> state = state.copy(
                ggaQuality = s.quality,
                // 0 from a chip that leaves the field blank in spirit; no geoid is exactly flat.
                geoidSeparationM = s.geoidSeparationM?.takeIf { it != 0.0 },
                ggaAtMs = nowMs,
            )
            null -> return false
        }
        return state != before
    }

    fun reset() {
        state = State()
    }
}
