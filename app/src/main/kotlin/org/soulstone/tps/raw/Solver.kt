package org.soulstone.tps.raw

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.DopCalculator

/** WGS 84 geodetic position: degrees and metres above the ellipsoid. */
data class Geodetic(val latitude: Double, val longitude: Double, val height: Double)

object Geo {
    private const val A = 6_378_137.0
    private const val F = 1 / 298.257223563
    private const val E2 = F * (2 - F)

    fun toEcef(g: Geodetic): Ecef {
        val lat = Math.toRadians(g.latitude)
        val lon = Math.toRadians(g.longitude)
        val n = A / sqrt(1 - E2 * sin(lat) * sin(lat))
        return Ecef((n + g.height) * cos(lat) * cos(lon), (n + g.height) * cos(lat) * sin(lon), (n * (1 - E2) + g.height) * sin(lat))
    }

    fun toGeodetic(p: Ecef): Geodetic {
        val r2 = p.x * p.x + p.y * p.y
        var z = p.z
        var zk: Double
        var v = A
        do {
            zk = z
            val sinp = z / sqrt(r2 + z * z)
            v = A / sqrt(1 - E2 * sinp * sinp)
            z = p.z + v * E2 * sinp
        } while (abs(z - zk) >= 1e-4)
        val lat = if (r2 > 1e-12) atan(z / sqrt(r2)) else if (p.z > 0) PI / 2 else -PI / 2
        val lon = if (r2 > 1e-12) atan2(p.y, p.x) else 0.0
        return Geodetic(Math.toDegrees(lat), Math.toDegrees(lon), sqrt(r2 + z * z) - v)
    }

    /** East, north, up components of [d] at [at]. */
    fun enu(at: Geodetic, d: Ecef): DoubleArray {
        val lat = Math.toRadians(at.latitude)
        val lon = Math.toRadians(at.longitude)
        val e = -sin(lon) * d.x + cos(lon) * d.y
        val n = -sin(lat) * cos(lon) * d.x - sin(lat) * sin(lon) * d.y + cos(lat) * d.z
        val u = cos(lat) * cos(lon) * d.x + cos(lat) * sin(lon) * d.y + sin(lat) * d.z
        return doubleArrayOf(e, n, u)
    }

    /** Azimuth and elevation, radians, of [sat] seen from [at]. */
    fun azEl(at: Geodetic, receiver: Ecef, sat: Ecef): Pair<Double, Double> {
        val (e, n, u) = enu(at, sat - receiver).let { Triple(it[0], it[1], it[2]) }
        val az = (atan2(e, n) + 2 * PI) % (2 * PI)
        return az to atan2(u, sqrt(e * e + n * n))
    }
}

object Atmosphere {
    /** Klobuchar ionospheric delay on GPS L1, metres (IS-GPS-200 figure 20-4). [gpsSeconds] is GPS time of week. */
    fun klobuchar(k: Klobuchar, at: Geodetic, az: Double, el: Double, gpsSeconds: Double): Double {
        val elSc = el / PI
        val psi = 0.0137 / (elSc + 0.11) - 0.022
        var phiI = at.latitude / 180 + psi * cos(az)
        if (phiI > 0.416) phiI = 0.416
        if (phiI < -0.416) phiI = -0.416
        val lamI = at.longitude / 180 + psi * sin(az) / cos(phiI * PI)
        val phiM = phiI + 0.064 * cos((lamI - 1.617) * PI)
        var t = 4.32e4 * lamI + gpsSeconds
        t -= kotlin.math.floor(t / 86_400.0) * 86_400.0
        val f = 1.0 + 16.0 * (0.53 - elSc).pow(3)
        val amp = max(0.0, k.alpha[0] + phiM * (k.alpha[1] + phiM * (k.alpha[2] + phiM * k.alpha[3])))
        val per = max(72_000.0, k.beta[0] + phiM * (k.beta[1] + phiM * (k.beta[2] + phiM * k.beta[3])))
        val x = 2 * PI * (t - 50_400.0) / per
        val delay = if (abs(x) < 1.57) f * (5e-9 + amp * (1 - x * x / 2 + x * x * x * x / 24)) else f * 5e-9
        return delay * Pseudoranges.C
    }

    /** Saastamoinen tropospheric delay with a standard atmosphere, metres. */
    fun troposphere(at: Geodetic, el: Double): Double {
        if (at.height < -100 || at.height > 1e4 || el <= 0) return 0.0
        val h = max(at.height, 0.0)
        val pressure = 1013.25 * (1 - 2.2557e-5 * h).pow(5.2568)
        val temperature = 15.0 - 6.5e-3 * h + 273.16
        val humidity = 0.7
        val e = 6.108 * humidity * exp((17.15 * temperature - 4684.0) / (temperature - 38.45))
        val z = PI / 2 - el
        val lat = Math.toRadians(at.latitude)
        val dry = 0.0022768 * pressure / (1 - 0.00266 * cos(2 * lat) - 0.00028 * h / 1e3) / cos(z)
        val wet = 0.002277 * (1255 / temperature + 0.05) * e / cos(z)
        return dry + wet
    }
}

/** One satellite's input to the solver: where it was, its clock, and the measured pseudorange. */
data class Observation(
    val constellation: Constellation,
    val svid: Int,
    val satellite: Ecef,
    val satelliteClock: Double,
    val pseudorange: Double,
    val sigma: Double,
    val carrierHz: Double?,
    /** The chip's own delay estimates for this signal, used instead of TPS's models when given. */
    val ionoMeters: Double? = null,
    val tropoMeters: Double? = null,
    // For the super position (SuperPosition): Doppler, weights and the chip's inter-system bias.
    /** The satellite's velocity at transmit time, Earth-fixed, m/s; null when unknown. */
    val satelliteVelocity: Ecef? = null,
    /** How fast the satellite's clock offset grows, m/s. */
    val satelliteClockDriftMps: Double = 0.0,
    /** The measured pseudorange rate (uncorrected for either clock's drift) and its 1σ, m/s. */
    val rateMps: Double? = null,
    val rateSigmaMps: Double? = null,
    val cn0DbHz: Double? = null,
    /** The chip's inter-signal bias for this signal against its reference (GPS L1 C/A), metres. */
    val isbMeters: Double? = null,
    /** The chip flagged multipath on this signal (GnssMeasurement's multipath indicator). */
    val multipathDetected: Boolean = false,
)

data class SolvedSatellite(val constellation: Constellation, val svid: Int, val elevationDeg: Double, val residual: Double)

data class Solution(
    val position: Geodetic,
    val ecef: Ecef,
    /** Receiver clock offset per system, metres. */
    val clocks: Map<Constellation, Double>,
    val satellites: List<SolvedSatellite>,
    val residualRms: Double,
    val hdop: Double?,
    val vdop: Double?,
)

object Solver {
    private const val OMEGA_E = 7.2921151467e-5
    private const val L1 = 1575.42e6
    private const val MASK = 10.0
    private const val MAX_ITERATIONS = 12

    /**
     * Iterative weighted least squares: position and one clock term per system. [start] is
     * a first guess (the chip's fix, say); without one it starts from the Earth's centre.
     * Drops the worst satellite and solves again while a residual is beyond 5 sigma.
     */
    fun solve(observations: List<Observation>, start: Ecef?, klobuchar: Klobuchar?, gpsSeconds: Double): Solution? {
        var obs = observations
        repeat(3) {
            val s = solveOnce(obs, start, klobuchar, gpsSeconds) ?: return null
            val worst = s.satellites.maxByOrNull { abs(it.residual) / sigmaOf(obs, it) } ?: return s
            if (abs(worst.residual) / sigmaOf(obs, worst) < 5.0 || obs.size <= minimum(obs) + 1) return s
            obs = obs.filterNot { it.constellation == worst.constellation && it.svid == worst.svid }
        }
        return solveOnce(obs, start, klobuchar, gpsSeconds)
    }

    private fun sigmaOf(obs: List<Observation>, s: SolvedSatellite): Double {
        val o = obs.first { it.constellation == s.constellation && it.svid == s.svid }
        return weightSigma(o.sigma, Math.toRadians(s.elevationDeg))
    }

    private fun weightSigma(sigma: Double, el: Double) = max(sigma, 3.0 / max(sin(el), 0.1))

    private fun minimum(obs: List<Observation>) = 3 + obs.map { it.constellation }.distinct().size

    private fun solveOnce(obs: List<Observation>, start: Ecef?, klobuchar: Klobuchar?, gpsSeconds: Double): Solution? {
        val systems = obs.map { it.constellation }.distinct()
        val n = 3 + systems.size
        if (obs.size < n) return null
        val x = DoubleArray(n)
        start?.let { x[0] = it.x; x[1] = it.y; x[2] = it.z }
        var used = emptyList<SolvedSatellite>()
        var rows = emptyList<DoubleArray>()
        for (iteration in 0 until MAX_ITERATIONS) {
            val receiver = Ecef(x[0], x[1], x[2])
            val near = receiver.norm() > 6.0e6
            val geo = if (near) Geo.toGeodetic(receiver) else null
            val h = ArrayList<DoubleArray>()
            val v = ArrayList<Double>()
            val w = ArrayList<Double>()
            val sats = ArrayList<SolvedSatellite>()
            for (o in obs) {
                val d = o.satellite - receiver
                // Range, plus the Earth's rotation during the signal's flight (Sagnac).
                val range = d.norm() + OMEGA_E * (o.satellite.x * receiver.y - o.satellite.y * receiver.x) / Pseudoranges.C
                var el = PI / 2
                var correction = 0.0
                if (geo != null) {
                    val (az, e) = Geo.azEl(geo, receiver, o.satellite)
                    el = e
                    if (Math.toDegrees(el) < MASK) continue
                    val scale = o.carrierHz?.let { (L1 / it) * (L1 / it) } ?: 1.0
                    correction += o.ionoMeters ?: ((klobuchar?.let { Atmosphere.klobuchar(it, geo, az, el, gpsSeconds) } ?: 0.0) * scale)
                    correction += o.tropoMeters ?: Atmosphere.troposphere(geo, el)
                }
                val clock = x[3 + systems.indexOf(o.constellation)]
                val predicted = range + clock - Pseudoranges.C * o.satelliteClock + correction
                val row = DoubleArray(n)
                row[0] = -d.x / d.norm()
                row[1] = -d.y / d.norm()
                row[2] = -d.z / d.norm()
                row[3 + systems.indexOf(o.constellation)] = 1.0
                h += row
                v += o.pseudorange - predicted
                val s = weightSigma(o.sigma, el)
                w += 1 / (s * s)
                sats += SolvedSatellite(o.constellation, o.svid, Math.toDegrees(el), o.pseudorange - predicted)
            }
            if (h.size < n || systems.any { sys -> sats.none { it.constellation == sys } }) return null
            val dx = leastSquares(h, v, w, n) ?: return null
            for (i in 0 until n) x[i] += dx[i]
            // Real data can be stranger than any test; give up rather than report NaN.
            if (x.any { it.isNaN() || it.isInfinite() }) return null
            used = sats
            rows = h
            if (sqrt(dx[0] * dx[0] + dx[1] * dx[1] + dx[2] * dx[2]) < 1e-3 && iteration > 0) break
        }
        val ecef = Ecef(x[0], x[1], x[2])
        if (ecef.norm() < 6.0e6 || ecef.norm() > 7.0e6) return null
        val position = Geo.toGeodetic(ecef)
        val dop = enuDop(position, rows, n)
        return Solution(
            position = position,
            ecef = ecef,
            clocks = systems.associateWith { x[3 + systems.indexOf(it)] },
            satellites = used,
            residualRms = sqrt(used.sumOf { it.residual * it.residual } / used.size),
            hdop = dop?.first,
            vdop = dop?.second,
        )
    }

    /** Solves (HᵀWH) dx = HᵀW v. */
    private fun leastSquares(h: List<DoubleArray>, v: List<Double>, w: List<Double>, n: Int): DoubleArray? {
        val a = Array(n) { DoubleArray(n) }
        val b = DoubleArray(n)
        for (k in h.indices) {
            for (i in 0 until n) {
                b[i] += h[k][i] * w[k] * v[k]
                for (j in 0 until n) a[i][j] += h[k][i] * w[k] * h[k][j]
            }
        }
        val inv = DopCalculator.invert(a) ?: return null
        return DoubleArray(n) { i -> (0 until n).sumOf { j -> inv[i][j] * b[j] } }
    }

    /** HDOP and VDOP from the unweighted geometry, in east/north/up. */
    private fun enuDop(at: Geodetic, h: List<DoubleArray>, n: Int): Pair<Double, Double>? {
        val enuRows = h.map { r ->
            val e = Geo.enu(at, Ecef(r[0], r[1], r[2]))
            DoubleArray(n) { i -> if (i < 3) e[i] else r[i] }
        }
        val a = Array(n) { i -> DoubleArray(n) { j -> enuRows.sumOf { it[i] * it[j] } } }
        val q = DopCalculator.invert(a) ?: return null
        return sqrt(q[0][0] + q[1][1]) to sqrt(q[2][2])
    }

    /** Where an own fix lies relative to another position: east, north, up metres. */
    fun offset(from: Geodetic, to: Ecef): DoubleArray = Geo.enu(from, to - Geo.toEcef(from))
}
