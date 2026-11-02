package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.soulstone.tps.gnss.Constellation

/** Earth-centred, Earth-fixed coordinates, metres. */
data class Ecef(val x: Double, val y: Double, val z: Double) {
    operator fun minus(o: Ecef) = Ecef(x - o.x, y - o.y, z - o.z)
    fun norm() = sqrt(x * x + y * y + z * z)
}

/**
 * The broadcast orbit and clock of a GPS, Galileo, BeiDou or QZSS satellite: Keplerian
 * elements with harmonic corrections (IS-GPS-200 Table 20-IV). Times are seconds of the
 * satellite system's own week; angles are radians.
 */
data class KeplerEphemeris(
    val constellation: Constellation,
    val svid: Int,
    /** Issue of data: GPS IODE, Galileo IODnav. */
    val iod: Int,
    val toe: Double,
    val toc: Double,
    val sqrtA: Double,
    val e: Double,
    val i0: Double,
    val omega0: Double,
    val omega: Double,
    val m0: Double,
    val deltaN: Double,
    val idot: Double,
    val omegaDot: Double,
    val cuc: Double,
    val cus: Double,
    val crc: Double,
    val crs: Double,
    val cic: Double,
    val cis: Double,
    val af0: Double,
    val af1: Double,
    val af2: Double,
    /** Group delay for a single-frequency user: GPS TGD, Galileo BGD(E1,E5b). */
    val groupDelay: Double,
    val healthy: Boolean,
)

/** A satellite's position when it sent a signal, and its clock error then (seconds, to subtract). */
data class SatelliteState(val position: Ecef, val clockError: Double)

object Orbits {
    private const val GPS_MU = 3.986005e14
    private const val GAL_MU = 3.986004418e14
    private const val BDS_MU = 3.986004418e14
    private const val GPS_OMEGA_E = 7.2921151467e-5
    private const val BDS_OMEGA_E = 7.2921150e-5
    private const val HALF_WEEK = 302_400.0

    fun mu(c: Constellation) = when (c) {
        Constellation.GALILEO -> GAL_MU
        Constellation.BEIDOU -> BDS_MU
        else -> GPS_MU
    }

    fun omegaE(c: Constellation) = if (c == Constellation.BEIDOU) BDS_OMEGA_E else GPS_OMEGA_E

    /** Time from [reference] to [t], wrapped across the week boundary. */
    fun sinceEpoch(t: Double, reference: Double): Double {
        var dt = t - reference
        if (dt > HALF_WEEK) dt -= 2 * HALF_WEEK
        if (dt < -HALF_WEEK) dt += 2 * HALF_WEEK
        return dt
    }

    /** Eccentric anomaly at time [t] (system time of week). */
    private fun eccentricAnomaly(eph: KeplerEphemeris, tk: Double): Double {
        val a = eph.sqrtA * eph.sqrtA
        val n = sqrt(mu(eph.constellation) / (a * a * a)) + eph.deltaN
        val m = eph.m0 + n * tk
        var ek = m
        repeat(30) {
            val next = m + eph.e * sin(ek)
            if (abs(next - ek) < 1e-14) return next
            ek = next
        }
        return ek
    }

    /** The satellite's clock error at system time [t]: polynomial, relativity, group delay. */
    fun clockError(eph: KeplerEphemeris, t: Double): Double {
        val dt = sinceEpoch(t, eph.toc)
        val ek = eccentricAnomaly(eph, sinceEpoch(t, eph.toe))
        val relativity = -2.0 * sqrt(mu(eph.constellation)) / (Pseudoranges.C * Pseudoranges.C) * eph.e * eph.sqrtA * sin(ek)
        return eph.af0 + eph.af1 * dt + eph.af2 * dt * dt + relativity - eph.groupDelay
    }

    /**
     * Position at system time [t] (in the Earth-fixed frame of that instant) and clock error.
     * BeiDou's geostationary satellites need a different frame rotation and aren't handled here.
     */
    fun state(eph: KeplerEphemeris, t: Double): SatelliteState {
        val c = eph.constellation
        val tk = sinceEpoch(t, eph.toe)
        val a = eph.sqrtA * eph.sqrtA
        val ek = eccentricAnomaly(eph, tk)
        val nu = atan2(sqrt(1 - eph.e * eph.e) * sin(ek), cos(ek) - eph.e)
        val phi = nu + eph.omega
        val sin2 = sin(2 * phi)
        val cos2 = cos(2 * phi)
        val u = phi + eph.cus * sin2 + eph.cuc * cos2
        val r = a * (1 - eph.e * cos(ek)) + eph.crs * sin2 + eph.crc * cos2
        val i = eph.i0 + eph.cis * sin2 + eph.cic * cos2 + eph.idot * tk
        val xp = r * cos(u)
        val yp = r * sin(u)
        val we = omegaE(c)
        val om = eph.omega0 + (eph.omegaDot - we) * tk - we * eph.toe
        val x = xp * cos(om) - yp * cos(i) * sin(om)
        val y = xp * sin(om) + yp * cos(i) * cos(om)
        val z = yp * sin(i)
        return SatelliteState(Ecef(x, y, z), clockError(eph, t))
    }

    /** True for BeiDou's geostationary satellites (C01–C05, C59–C63), whose orbits need the GEO formula. */
    fun isBeidouGeo(c: Constellation, svid: Int) = c == Constellation.BEIDOU && (svid <= 5 || svid >= 59)
}
