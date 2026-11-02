package org.soulstone.tps.gnss

import java.util.Locale
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A synthetic sky for the emulator and for screenshots. The emulator's own GNSS HAL
 * invents a few GPS-only satellites, all at 0°/0°, so it can't show the sky circle.
 *
 * Satellites move on circular orbits with each system's real radius, inclination,
 * period and plane layout, seen from Singapore, one of the places where all seven
 * systems are overhead. Time runs [speedup] times fast so the sky visibly drifts.
 * The PRNs, GLONASS channels and bands follow the real systems, but the positions
 * are not the real constellations': this is a plausible sky, not an almanac.
 *
 * The first frames replay a start: nothing for 1.5 s, then satellites predicted from
 * the almanac and acquired strongest-first, and the first fix at 6.8 s.
 */
class DemoSky(private val speedup: Double = 30.0) {

    data class Frame(
        val signals: List<Signal>,
        val fix: Fix?,
        /** The time to first fix the demo "chip" reports, once it has one. */
        val ttffMs: Long?,
        /** The NMEA a chip would send with this frame: one GSA and one GGA. */
        val nmea: List<String>,
    )

    private enum class Kind { MEO, IGSO, GEO }

    private class Orbit(
        val constellation: Constellation,
        val svid: Int,
        val kind: Kind,
        val radiusKm: Double,
        val inclinationDeg: Double,
        /** Inertial RAAN for MEO; longitude of the ascending node for IGSO; longitude for GEO. */
        val nodeDeg: Double,
        val phaseDeg: Double,
        val periodS: Double,
        val carriersHz: List<Double>,
        val cn0OffsetDb: Float,
    )

    private val orbits: List<Orbit> = buildOrbits()
    private val observer: DoubleArray = ecefOf(LATITUDE, LONGITUDE, ELLIPSOID_HEIGHT_M / 1000.0)

    fun frame(elapsedMs: Long, nowWallMs: Long, nowElapsedMs: Long): Frame {
        if (elapsedMs < FIRST_SIGNALS_MS) return Frame(emptyList(), null, null, emptyList())
        val simS = START_S + elapsedMs / 1000.0 * speedup
        val wobbleS = elapsedMs / 1000.0
        val fixed = elapsedMs >= FIRST_FIX_MS

        val signals = ArrayList<Signal>()
        for (o in orbits) {
            val (el, az) = lookAngles(o, simS)
            if (el < 0.0) continue
            val upper = cn0(o, el, wobbleS)
            val blocked = (o.svid * 7 + o.constellation.ordinal * 3) % 17 == 0
            val acquired = !blocked && upper >= TRACK_DB && elapsedMs >= acquireAtMs(upper)
            val used = fixed && acquired && el >= 10.0 && upper >= USE_DB &&
                o.constellation != Constellation.SBAS &&
                !(o.constellation == Constellation.GLONASS && o.svid % 5 == 0)
            o.carriersHz.forEachIndexed { i, hz ->
                val db = if (i == 0) upper else upper - LOWER_BAND_LOSS_DB
                when {
                    // Before acquisition the chip lists what its almanac predicts, at C/N0 0.
                    !acquired -> if (i == 0) signals += Signal(
                        o.constellation, o.svid, 0f, el.toFloat(), az.toFloat(),
                        usedInFix = false, hasEphemeris = false, hasAlmanac = true, carrierHz = hz,
                    )
                    db >= TRACK_DB -> signals += Signal(
                        o.constellation, o.svid, db, el.toFloat(), az.toFloat(),
                        usedInFix = used && (i == 0 || db >= USE_DB),
                        hasEphemeris = true, hasAlmanac = true, carrierHz = hz,
                    )
                }
            }
        }
        if (!fixed) return Frame(signals, null, null, emptyList())

        val fix = demoFix(wobbleS, nowWallMs, nowElapsedMs)
        return Frame(signals, fix, FIRST_FIX_MS, nmea(signals, fix))
    }

    private fun demoFix(t: Double, nowWallMs: Long, nowElapsedMs: Long): Fix {
        val north = 0.9 * sin(t / 11.0) + 0.4 * sin(t / 3.7)
        val east = 0.8 * cos(t / 13.0) + 0.3 * sin(t / 4.1)
        val height = ELLIPSOID_HEIGHT_M + 0.8 * sin(t / 9.0)
        return Fix(
            latitude = LATITUDE + north / 111_320.0,
            longitude = LONGITUDE + east / (111_320.0 * cos(Math.toRadians(LATITUDE))),
            accuracyM = (2.8 + 0.5 * sin(t / 17.0)).toFloat(),
            altitudeM = height,
            mslAltitudeM = height - GEOID_SEPARATION_M,
            mslSource = MslSource.DEMO,
            verticalAccuracyM = (4.4 + 0.6 * sin(t / 13.0)).toFloat(),
            speedMps = abs(0.04 * sin(t / 5.0)).toFloat(),
            speedAccuracyMps = 0.12f,
            gnssTimeMs = nowWallMs - DEMO_CLOCK_OFFSET_MS,
            elapsedRealtimeMs = nowElapsedMs,
            clockOffsetMs = DEMO_CLOCK_OFFSET_MS,
        )
    }

    /** One GSA (with DOP from the geometry) and one GGA (with the geoid separation). */
    private fun nmea(signals: List<Signal>, fix: Fix): List<String> {
        val sky = SkySnapshot.of(signals)
        val dop = DopCalculator.compute(sky.satellites) ?: return emptyList()
        val ids = sky.satellites.filter { it.state == SatState.USED && it.constellation == Constellation.GPS }
            .take(12).map { it.svid.toString().padStart(2, '0') }
        val gsa = buildString {
            append("GNGSA,A,3,")
            repeat(12) { append(ids.getOrElse(it) { "" }).append(',') }
            append(f2(dop.pdop)).append(',').append(f2(dop.hdop)).append(',').append(f2(dop.vdop)).append(",1")
        }
        val gga = "GNGGA,000000.00," + nmeaAngle(fix.latitude, 2, 'N', 'S') + "," +
            nmeaAngle(fix.longitude, 3, 'E', 'W') + ",1," + sky.used.coerceAtMost(99) + "," +
            f2(dop.hdop) + "," + f2(fix.mslAltitudeM ?: 0.0) + ",M," + f2(GEOID_SEPARATION_M) + ",M,,"
        return listOf(gsa, gga).map { "$" + it + "*" + Nmea.checksum(it).toString(16).uppercase().padStart(2, '0') }
    }

    private fun cn0(o: Orbit, elDeg: Double, t: Double): Float {
        val base = 18.0 + 27.0 * sin(Math.toRadians(elDeg)).coerceAtLeast(0.0).pow(0.6)
        val wobble = 1.2 * sin(t / 40.0 + o.svid * 1.7 + o.constellation.ordinal)
        return (base + o.cn0OffsetDb + wobble).toFloat()
    }

    /** Strong satellites are acquired first: 45 dB-Hz at 1.5 s, 25 dB-Hz by about 5 s. */
    private fun acquireAtMs(cn0: Float): Long =
        FIRST_SIGNALS_MS + ((45f - cn0).coerceIn(0f, 25f) / 25f * 3_600f).toLong()

    private fun lookAngles(o: Orbit, t: Double): Pair<Double, Double> {
        val sat = satelliteEcef(o, t)
        val dx = sat[0] - observer[0]
        val dy = sat[1] - observer[1]
        val dz = sat[2] - observer[2]
        val lat = Math.toRadians(LATITUDE)
        val lon = Math.toRadians(LONGITUDE)
        val e = -sin(lon) * dx + cos(lon) * dy
        val n = -sin(lat) * cos(lon) * dx - sin(lat) * sin(lon) * dy + cos(lat) * dz
        val u = cos(lat) * cos(lon) * dx + cos(lat) * sin(lon) * dy + sin(lat) * dz
        val el = Math.toDegrees(atan2(u, hypot(e, n)))
        val az = (Math.toDegrees(atan2(e, n)) + 360.0) % 360.0
        return el to az
    }

    private fun satelliteEcef(o: Orbit, t: Double): DoubleArray {
        if (o.kind == Kind.GEO) {
            val l = Math.toRadians(o.nodeDeg)
            return doubleArrayOf(o.radiusKm * cos(l), o.radiusKm * sin(l), 0.0)
        }
        val theta = THETA0 + EARTH_RATE * t
        // IGSO nodes are given as longitudes; with a sidereal-day period they stay there.
        val raan = Math.toRadians(o.nodeDeg) + if (o.kind == Kind.IGSO) THETA0 else 0.0
        val u = Math.toRadians(o.phaseDeg) + 2 * PI * t / o.periodS
        val i = Math.toRadians(o.inclinationDeg)
        val x = o.radiusKm * (cos(raan) * cos(u) - sin(raan) * sin(u) * cos(i))
        val y = o.radiusKm * (sin(raan) * cos(u) + cos(raan) * sin(u) * cos(i))
        val z = o.radiusKm * sin(u) * sin(i)
        return doubleArrayOf(x * cos(theta) + y * sin(theta), -x * sin(theta) + y * cos(theta), z)
    }

    private fun buildOrbits(): List<Orbit> = buildList {
        val rnd = Random(7)
        fun offset() = (rnd.nextGaussian() * 1.8).toFloat()
        fun meo(c: Constellation, svid: Int, radius: Double, incl: Double, raan: Double, phase: Double, period: Double, carriers: List<Double>, extra: Float = 0f) =
            add(Orbit(c, svid, Kind.MEO, radius, incl, raan, phase, period, carriers, offset() + extra))
        fun igso(c: Constellation, svid: Int, incl: Double, node: Double, phase: Double, carriers: List<Double>) =
            add(Orbit(c, svid, Kind.IGSO, GEO_RADIUS_KM, incl, node, phase, SIDEREAL_DAY_S, carriers, offset()))
        fun geo(c: Constellation, svid: Int, lon: Double, carriers: List<Double>, extra: Float = 0f) =
            add(Orbit(c, svid, Kind.GEO, GEO_RADIUS_KM, 0.0, lon, 0.0, SIDEREAL_DAY_S, carriers, offset() + extra))

        // GPS: six planes at 55°, 31 satellites; Block IIF and III broadcast L5.
        val gpsPlanes = listOf(
            listOf(1, 2, 3, 4, 5), listOf(6, 7, 8, 9, 10), listOf(11, 12, 13, 14, 15),
            listOf(16, 17, 18, 19, 20, 21), listOf(22, 23, 24, 25, 26), listOf(27, 28, 29, 30, 32),
        )
        val gpsL5 = setOf(1, 3, 4, 6, 8, 9, 10, 11, 14, 18, 23, 24, 25, 26, 27, 28, 30, 32)
        gpsPlanes.forEachIndexed { p, prns ->
            prns.forEachIndexed { k, prn ->
                meo(Constellation.GPS, prn, 26_560.0, 55.0, 25.0 + 60.0 * p, 360.0 * k / prns.size + 17.0 * p,
                    43_082.0, if (prn in gpsL5) listOf(L1_HZ, L5_HZ) else listOf(L1_HZ))
            }
        }
        // GLONASS: three planes of eight at 64.8°, slots 1–24, FDMA on G1 (phones don't track G2).
        val channel = intArrayOf(1, -4, 5, 6, 1, -4, 5, 6, -2, -7, 0, -1, -2, -7, 0, -1, 4, -3, 3, 2, 4, -3, 3, 2)
        for (slot in 1..24) {
            val p = (slot - 1) / 8
            meo(Constellation.GLONASS, slot, 25_508.0, 64.8, 70.0 + 120.0 * p, 45.0 * ((slot - 1) % 8) + 15.0 * p,
                40_544.0, listOf(1602e6 + channel[slot - 1] * 0.5625e6), extra = -1f)
        }
        // Galileo: three planes of eight at 56°, E1 + E5a.
        listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 15, 19, 21, 24, 25, 26, 27, 29, 30, 31, 33, 34)
            .forEachIndexed { i, prn ->
                meo(Constellation.GALILEO, prn, 29_600.0, 56.0, 110.0 + 120.0 * (i / 8), 45.0 * (i % 8) + 15.0 * (i / 8),
                    50_680.0, listOf(L1_HZ, L5_HZ), extra = 0.5f)
            }
        // BeiDou-3 MEO: three planes of eight at 55°, B1I + B2a.
        ((19..30) + (32..37) + (41..46)).forEachIndexed { i, prn ->
            meo(Constellation.BEIDOU, prn, 27_906.0, 55.0, 160.0 + 120.0 * (i / 8), 45.0 * (i % 8) + 15.0 * (i / 8),
                46_380.0, listOf(B1I_HZ, L5_HZ))
        }
        // BeiDou IGSO over 118°E and 95°E (BeiDou-2 ones broadcast B1I only), and BeiDou-2 GEO.
        igso(Constellation.BEIDOU, 38, 55.0, 118.0, 0.0, listOf(B1I_HZ, L5_HZ))
        igso(Constellation.BEIDOU, 39, 55.0, 118.0, 120.0, listOf(B1I_HZ, L5_HZ))
        igso(Constellation.BEIDOU, 40, 55.0, 118.0, 240.0, listOf(B1I_HZ, L5_HZ))
        igso(Constellation.BEIDOU, 6, 55.0, 118.0, 60.0, listOf(B1I_HZ))
        igso(Constellation.BEIDOU, 9, 55.0, 95.0, 200.0, listOf(B1I_HZ))
        igso(Constellation.BEIDOU, 16, 55.0, 118.0, 300.0, listOf(B1I_HZ))
        geo(Constellation.BEIDOU, 1, 140.0, listOf(B1I_HZ))
        geo(Constellation.BEIDOU, 2, 80.0, listOf(B1I_HZ))
        geo(Constellation.BEIDOU, 3, 110.5, listOf(B1I_HZ))
        // QZSS: three on the figure-eight over 139°E (J02–J04) and QZS-3 (J07) at 127°E.
        igso(Constellation.QZSS, 194, 41.0, 139.0, 0.0, listOf(L1_HZ, L5_HZ))
        igso(Constellation.QZSS, 195, 41.0, 139.0, 120.0, listOf(L1_HZ, L5_HZ))
        igso(Constellation.QZSS, 196, 41.0, 139.0, 240.0, listOf(L1_HZ, L5_HZ))
        geo(Constellation.QZSS, 199, 127.0, listOf(L1_HZ, L5_HZ))
        // NavIC: GEO at 83°E and 32.5°E, NVS-01 at 129.5°E (adds L1), IGSO over 55°E and 111.75°E.
        geo(Constellation.NAVIC, 3, 83.0, listOf(L5_HZ))
        geo(Constellation.NAVIC, 6, 32.5, listOf(L5_HZ))
        geo(Constellation.NAVIC, 10, 129.5, listOf(L1_HZ, L5_HZ))
        igso(Constellation.NAVIC, 2, 29.0, 55.0, 30.0, listOf(L5_HZ))
        igso(Constellation.NAVIC, 5, 29.0, 111.75, 200.0, listOf(L5_HZ))
        igso(Constellation.NAVIC, 9, 29.0, 55.0, 210.0, listOf(L5_HZ))
        // SBAS in view from Singapore: GAGAN at 55°E and 93.5°E, SouthPAN at 143.5°E.
        geo(Constellation.SBAS, 127, 55.0, listOf(L1_HZ), extra = -2f)
        geo(Constellation.SBAS, 132, 93.5, listOf(L1_HZ), extra = -2f)
        geo(Constellation.SBAS, 122, 143.5, listOf(L1_HZ), extra = -2f)
    }

    companion object {
        /** Merlion Park, Singapore. */
        const val LATITUDE = 1.28680
        const val LONGITUDE = 103.85453
        private const val ELLIPSOID_HEIGHT_M = 11.0
        private const val GEOID_SEPARATION_M = 7.0
        private const val DEMO_CLOCK_OFFSET_MS = 40L

        const val FIRST_SIGNALS_MS = 1_500L
        const val FIRST_FIX_MS = 6_800L
        private const val TRACK_DB = 23f
        private const val USE_DB = 27f
        private const val LOWER_BAND_LOSS_DB = 2.5f

        /** Simulated start time, picked for a well-spread opening sky. */
        private const val START_S = 4.0 * 3600.0
        private const val THETA0 = 1.0
        private const val EARTH_RATE = 7.2921159e-5
        private const val SIDEREAL_DAY_S = 86_164.0905
        private const val GEO_RADIUS_KM = 42_164.0

        private const val L1_HZ = 1575.42e6
        private const val L5_HZ = 1176.45e6
        private const val B1I_HZ = 1561.098e6

        private fun f2(v: Double) = String.format(Locale.ROOT, "%.2f", v)

        /** NMEA's ddmm.mmmmm (latitude) or dddmm.mmmmm (longitude), then the hemisphere. */
        private fun nmeaAngle(deg: Double, degreeDigits: Int, positive: Char, negative: Char): String {
            val a = abs(deg)
            val d = floor(a).toInt()
            val m = (a - d) * 60.0
            return d.toString().padStart(degreeDigits, '0') +
                String.format(Locale.ROOT, "%08.5f", m) + "," + if (deg >= 0) positive else negative
        }

        /** WGS 84 geodetic to Earth-centred, Earth-fixed, in km. */
        private fun ecefOf(latDeg: Double, lonDeg: Double, heightKm: Double): DoubleArray {
            val a = 6378.137
            val e2 = 6.69437999014e-3
            val lat = Math.toRadians(latDeg)
            val lon = Math.toRadians(lonDeg)
            val n = a / sqrt(1 - e2 * sin(lat) * sin(lat))
            return doubleArrayOf(
                (n + heightKm) * cos(lat) * cos(lon),
                (n + heightKm) * cos(lat) * sin(lon),
                (n * (1 - e2) + heightKm) * sin(lat),
            )
        }
    }
}
