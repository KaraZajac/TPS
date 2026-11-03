package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import org.soulstone.tps.gnss.Constellation

/** TPS's own position from every satellite at once, filtered over time ([SuperPosition]). */
data class FusedFix(
    val position: Geodetic,
    val ecef: Ecef,
    /** Velocity east, north and up, m/s. */
    val velocityEast: Double,
    val velocityNorth: Double,
    val velocityUp: Double,
    /** About 68% confidence, from the filter's covariance, metres. */
    val horizontalAccuracyM: Double,
    val verticalAccuracyM: Double,
    /** Satellites whose pseudorange went into the latest update. */
    val satellites: Int,
    val systems: Set<Constellation>,
    /** Measurements left out of the latest update because they didn't fit the rest. */
    val rejected: Int,
    /** The system whose clock the others are measured against. */
    val reference: Constellation,
    /** Each other system's clock offset from [reference] that's left after the chip's own correction, metres. */
    val interSystemBiases: Map<Constellation, Double>,
    val clockDriftMps: Double,
    /** Seconds since the last measurement update. */
    val sinceUpdateS: Double,
    /** Measurement updates since the filter (re)started. */
    val updates: Int,
) {
    val speedMps: Double get() = sqrt(velocityEast * velocityEast + velocityNorth * velocityNorth)

    /** Direction of travel, degrees from north; null when barely moving. */
    val bearingDeg: Double?
        get() = if (speedMps < MOVING_MPS) null else (Math.toDegrees(atan2(velocityEast, velocityNorth)) + 360) % 360

    /** Settled and current enough to show as the position. */
    val usable: Boolean get() = updates >= MIN_UPDATES && sinceUpdateS <= MAX_COAST_S && horizontalAccuracyM <= MAX_SHOWN_ACCURACY_M

    companion object {
        const val MOVING_MPS = 0.5
        const val MIN_UPDATES = 3
        const val MAX_COAST_S = 5.0
        const val MAX_SHOWN_ACCURACY_M = 100.0
    }
}

/**
 * TPS's "super position": every satellite of every system in one extended Kalman filter.
 *
 * - **One receiver clock.** Each system's offset from it (its inter-system bias) barely moves,
 *   so it's a slowly varying state. Once it's known, any mix of satellites counts toward a fix,
 *   not four per system. The chip's own inter-system biases, when it reports them, are applied
 *   first, so the filter starts with one clock.
 * - **Memory.** Position, velocity, clock and drift carry from one round to the next, so even
 *   one to three satellites improve the estimate, and noise averages out.
 * - **Doppler.** Each signal's pseudorange rate measures velocity and the clock's drift.
 * - **Robustness.** Every measurement is checked against what the filter expects (5σ) and left
 *   out if it doesn't fit. A jump of the whole clock is recognised and absorbed instead. If most
 *   measurements stop fitting, the filter starts over from a snapshot fix.
 *
 * Pseudoranges are re-referenced to a fixed clock offset (the chip's at the start), so the
 * receiver clock simply drifts, by the same drift the Doppler sees, whether or not the chip
 * steers its clock. Plain Kotlin; one thread feeds it.
 */
class SuperPosition {
    private val x = DoubleArray(N)
    private val p = Array(N) { DoubleArray(N) }
    private var initialized = false
    private var reference = Constellation.GPS
    private var refFullBias = 0L
    private var refBias = 0.0
    private var discontinuity = -1
    private var lastT = 0.0
    private var lastUpdateT = 0.0
    private var updates = 0
    private var badRounds = 0
    private var lastSatellites = 0
    private var lastSystems = emptySet<Constellation>()
    private var lastRejected = 0
    private var smoothedSpeed = 0.0

    fun reset() {
        initialized = false
    }

    /**
     * One round of measurements. [start] is a rough position to begin from (the chip's fix);
     * it only matters for the very first snapshot. Returns the filter's position, or null
     * while it has none yet.
     */
    fun step(clock: RawClock, observations: List<Observation>, start: Ecef?, klobuchar: Klobuchar?, gpsSeconds: Double): FusedFix? {
        val fullBias = clock.fullBiasNanos ?: return output()
        val t = clock.timeNanos * 1e-9
        val restart = !initialized || clock.discontinuityCount != discontinuity || t < lastT || t - lastT > MAX_GAP_S
        if (restart) {
            initialized = initialize(t, clock, fullBias, observations, start, klobuchar, gpsSeconds)
            return output()
        }
        predict(t)
        // The chip's correction of its own clock since the reference, put back into the pseudoranges.
        val steer = ((fullBias - refFullBias) + (clock.biasNanos - refBias)) * 1e-9 * Pseudoranges.C
        update(t, observations, steer, klobuchar, gpsSeconds)
        if (x.any { it.isNaN() || it.isInfinite() }) initialized = false
        return output()
    }

    // ------------------------------------------------------------------ start

    private fun initialize(
        t: Double, clock: RawClock, fullBias: Long, obs: List<Observation>, start: Ecef?, klobuchar: Klobuchar?, gpsSeconds: Double,
    ): Boolean {
        // The chip's inter-system biases first: with them every system shares one clock.
        val corrected = obs.filter { it.constellation in SYSTEMS }.map { it.copy(pseudorange = it.pseudorange - (it.isbMeters ?: 0.0)) }
        val bySystem = corrected.groupBy { it.constellation }
        val solution = Solver.solve(corrected, start, klobuchar, gpsSeconds)
            ?: Solver.solve(corrected.filter { (bySystem[it.constellation]?.size ?: 0) >= 2 }, start, klobuchar, gpsSeconds)
            ?: bySystem.values.maxByOrNull { it.size }?.let { Solver.solve(it, start, klobuchar, gpsSeconds) }
            ?: return false
        reference = if (Constellation.GPS in solution.clocks) Constellation.GPS else solution.clocks.keys.maxBy { bySystem[it]?.size ?: 0 }
        x.fill(0.0)
        for (row in p) row.fill(0.0)
        x[0] = solution.ecef.x; x[1] = solution.ecef.y; x[2] = solution.ecef.z
        x[CLOCK] = solution.clocks.getValue(reference)
        for (i in 0 until 3) p[i][i] = 30.0 * 30.0
        for (i in 3 until 6) p[i][i] = 10.0 * 10.0
        p[CLOCK][CLOCK] = 30.0 * 30.0
        p[DRIFT][DRIFT] = 300.0 * 300.0
        for (c in SYSTEMS) {
            if (c == reference) continue
            val k = isbIndex(c)
            val solved = solution.clocks[c]
            val chipKnows = obs.any { it.constellation == c && it.isbMeters != null }
            x[k] = if (solved != null) solved - x[CLOCK] else 0.0
            // Without the chip's figure, GLONASS can sit a kilometre off GPS.
            p[k][k] = if (solved != null || chipKnows) 30.0 * 30.0 else 3_000.0 * 3_000.0
        }
        refFullBias = fullBias
        refBias = clock.biasNanos
        discontinuity = clock.discontinuityCount
        lastT = t
        lastUpdateT = t
        updates = 0
        badRounds = 0
        lastSatellites = solution.satellites.size
        lastSystems = solution.clocks.keys
        lastRejected = 0
        smoothedSpeed = 0.0
        return true
    }

    // ------------------------------------------------------------------ predict

    private fun predict(t: Double) {
        val dt = t - lastT
        lastT = t
        for (i in 0 until 3) x[i] += x[3 + i] * dt
        x[CLOCK] += x[DRIFT] * dt
        // P = F P Fᵀ: F moves position by velocity and the clock by its drift.
        for (j in 0 until N) {
            for (i in 0 until 3) p[i][j] += dt * p[3 + i][j]
            p[CLOCK][j] += dt * p[DRIFT][j]
        }
        for (i in 0 until N) {
            for (j in 0 until 3) p[i][j] += dt * p[i][3 + j]
            p[i][CLOCK] += dt * p[i][DRIFT]
        }
        // Process noise: how much the motion can change, a quartz clock, biases that hardly move.
        val dt2 = dt * dt
        val dt3 = dt2 * dt
        // Horizontal speed only (the vertical velocity is poorly seen), smoothed over a few seconds
        // so one noisy round doesn't count as setting off.
        val ground = Geo.enu(Geo.toGeodetic(Ecef(x[0], x[1], x[2])), Ecef(x[3], x[4], x[5]))
        smoothedSpeed += (sqrt(ground[0] * ground[0] + ground[1] * ground[1]) - smoothedSpeed) * minOf(1.0, dt / SPEED_SMOOTHING_S)
        val accel = accelerationNoise(smoothedSpeed)
        for (i in 0 until 3) {
            p[i][i] += accel * dt3 / 3
            p[i][3 + i] += accel * dt2 / 2
            p[3 + i][i] += accel * dt2 / 2
            p[3 + i][3 + i] += accel * dt
        }
        p[CLOCK][CLOCK] += CLOCK_PSD * dt + DRIFT_PSD * dt3 / 3
        p[CLOCK][DRIFT] += DRIFT_PSD * dt2 / 2
        p[DRIFT][CLOCK] += DRIFT_PSD * dt2 / 2
        p[DRIFT][DRIFT] += DRIFT_PSD * dt
        for (c in SYSTEMS) {
            val k = isbIndex(c)
            p[k][k] += ISB_PSD * dt
        }
    }

    // ------------------------------------------------------------------ update

    private class Row(val h: DoubleArray, var innovation: Double, val variance: Double, val range: Boolean, val satellite: Observation)

    private fun update(t: Double, obs: List<Observation>, steer: Double, klobuchar: Klobuchar?, gpsSeconds: Double) {
        val receiver = Ecef(x[0], x[1], x[2])
        val geo = Geo.toGeodetic(receiver)
        val rows = ArrayList<Row>()
        for (o in obs) {
            if (o.constellation !in SYSTEMS) continue
            val d = o.satellite - receiver
            val r = d.norm()
            val u = doubleArrayOf(d.x / r, d.y / r, d.z / r)
            val (az, el) = Geo.azEl(geo, receiver, o.satellite)
            if (Math.toDegrees(el) < MASK_DEG) continue
            val k = isbIndex(o.constellation)
            val own = o.constellation != reference

            // Pseudorange.
            val range = r + OMEGA_E * (o.satellite.x * receiver.y - o.satellite.y * receiver.x) / Pseudoranges.C
            val scale = o.carrierHz?.let { (L1 / it) * (L1 / it) } ?: 1.0
            val iono = o.ionoMeters ?: ((klobuchar?.let { Atmosphere.klobuchar(it, geo, az, el, gpsSeconds) } ?: 0.0) * scale)
            val tropo = o.tropoMeters ?: Atmosphere.troposphere(geo, el)
            val predicted = range + x[CLOCK] + (if (own) x[k] else 0.0) - Pseudoranges.C * o.satelliteClock + iono + tropo
            val measured = o.pseudorange - (o.isbMeters ?: 0.0) + steer
            val h = DoubleArray(N)
            h[0] = -u[0]; h[1] = -u[1]; h[2] = -u[2]
            h[CLOCK] = 1.0
            if (own) h[k] = 1.0
            val sigma = rangeSigma(o, el)
            rows += Row(h, measured - predicted, sigma * sigma, range = true, satellite = o)

            // Pseudorange rate (Doppler).
            val vs = o.satelliteVelocity
            val rate = o.rateMps
            val rateSigma = o.rateSigmaMps
            if (vs != null && rate != null && rateSigma != null) {
                val closing = (vs.x - x[3]) * u[0] + (vs.y - x[4]) * u[1] + (vs.z - x[5]) * u[2]
                val hr = DoubleArray(N)
                hr[3] = -u[0]; hr[4] = -u[1]; hr[5] = -u[2]
                hr[DRIFT] = 1.0
                val s = max(rateSigma, MIN_RATE_SIGMA)
                rows += Row(hr, rate - (closing + x[DRIFT] - o.satelliteClockDriftMps), s * s, range = false, satellite = o)
            }
        }
        if (rows.isEmpty()) return

        // A jump of the whole clock moves every pseudorange (or every rate) by the same amount:
        // take it up in the clock rather than reject every satellite.
        absorbCommonJump(rows.filter { it.range }, CLOCK)
        absorbCommonJump(rows.filter { !it.range }, DRIFT)

        // Gate: a measurement far from what the filter expects is left out, not averaged in.
        val accepted = rows.filter { row ->
            val s = quadratic(row.h) + row.variance
            row.innovation * row.innovation / s < GATE
        }
        val ranges = rows.count { it.range }
        val rangesRejected = ranges - accepted.count { it.range }
        lastRejected = rows.size - accepted.size
        badRounds = if (ranges >= 4 && rangesRejected * 2 > ranges) badRounds + 1 else 0
        if (badRounds >= MAX_BAD_ROUNDS) {
            // Most satellites disagree with the filter for several rounds: start over.
            initialized = false
            return
        }
        if (accepted.isEmpty()) return

        // Sequential scalar updates, all linearised at the predicted state.
        val x0 = x.copyOf()
        for (row in accepted) {
            var v = row.innovation
            for (i in 0 until N) v -= row.h[i] * (x[i] - x0[i])
            val ph = DoubleArray(N) { i -> (0 until N).sumOf { j -> p[i][j] * row.h[j] } }
            val s = (0 until N).sumOf { i -> row.h[i] * ph[i] } + row.variance
            for (i in 0 until N) x[i] += ph[i] / s * v
            for (i in 0 until N) for (j in 0 until N) p[i][j] -= ph[i] * ph[j] / s
        }
        // Keep P symmetric against rounding.
        for (i in 0 until N) for (j in i + 1 until N) {
            val m = (p[i][j] + p[j][i]) / 2
            p[i][j] = m; p[j][i] = m
        }
        val usedRanges = accepted.filter { it.range }.map { it.satellite }
        lastSatellites = usedRanges.size
        lastSystems = usedRanges.map { it.constellation }.toSet()
        lastUpdateT = t
        updates++
    }

    /** If [rows] all share one large offset, moves state [index] by it (the clock or its drift jumped). */
    private fun absorbCommonJump(rows: List<Row>, index: Int) {
        if (rows.size < 3) return
        val sorted = rows.map { it.innovation }.sorted()
        val median = sorted[sorted.size / 2]
        val spread = sorted.map { abs(it - median) }.sorted()[sorted.size / 2]
        val expected = sqrt(p[index][index] + rows.map { it.variance }.sorted()[rows.size / 2])
        if (abs(median) > JUMP_SIGMAS * expected && spread < abs(median) / 4) {
            x[index] += median
            p[index][index] += median * median
            for (row in rows) row.innovation -= median
        }
    }

    /**
     * Standing still, the position is held tightly and averages down; walking, it may turn and
     * stop; in a vehicle, it may brake and corner. The Doppler shows which, within a second.
     */
    private fun accelerationNoise(speedMps: Double): Double = when {
        speedMps < STILL_MPS -> ACCEL_STILL
        speedMps < WALKING_MPS -> ACCEL_WALKING
        else -> ACCEL_DRIVING
    }

    private fun quadratic(h: DoubleArray): Double {
        var s = 0.0
        for (i in 0 until N) {
            if (h[i] == 0.0) continue
            for (j in 0 until N) if (h[j] != 0.0) s += h[i] * p[i][j] * h[j]
        }
        return s
    }

    /** Code noise grows as the signal weakens and as the satellite sinks (multipath). */
    private fun rangeSigma(o: Observation, el: Double): Double {
        val base = max(o.sigma, 3.0 / max(sin(el), 0.1))
        val weak = o.cn0DbHz?.let { Math.pow(10.0, max(0.0, WEAK_CN0 - it) / 20) } ?: 1.0
        // GLONASS: each satellite on its own frequency, with its own small receiver bias.
        val glonass = if (o.constellation == Constellation.GLONASS) 1.5 else 1.0
        return base * weak * glonass
    }

    // ------------------------------------------------------------------ out

    private fun output(): FusedFix? {
        if (!initialized) return null
        val ecef = Ecef(x[0], x[1], x[2])
        val geo = Geo.toGeodetic(ecef)
        val v = Geo.enu(geo, Ecef(x[3], x[4], x[5]))
        val lat = Math.toRadians(geo.latitude)
        val lon = Math.toRadians(geo.longitude)
        val east = doubleArrayOf(-sin(lon), cos(lon), 0.0)
        val north = doubleArrayOf(-sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat))
        val up = doubleArrayOf(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat))
        fun variance(a: DoubleArray): Double {
            var s = 0.0
            for (i in 0 until 3) for (j in 0 until 3) s += a[i] * p[i][j] * a[j]
            return max(s, 0.0)
        }
        return FusedFix(
            position = geo,
            ecef = ecef,
            velocityEast = v[0],
            velocityNorth = v[1],
            velocityUp = v[2],
            horizontalAccuracyM = sqrt(variance(east) + variance(north)),
            verticalAccuracyM = sqrt(variance(up)),
            satellites = lastSatellites,
            systems = lastSystems,
            rejected = lastRejected,
            reference = reference,
            interSystemBiases = SYSTEMS.filter { it != reference && p[isbIndex(it)][isbIndex(it)] < 1_000.0 * 1_000.0 }
                .associateWith { x[isbIndex(it)] },
            clockDriftMps = x[DRIFT],
            sinceUpdateS = lastT - lastUpdateT,
            updates = updates,
        )
    }

    private fun isbIndex(c: Constellation) = ISB0 + SYSTEMS.indexOf(c)

    companion object {
        /** Systems with their own clock offset; SBAS is left out (it carries corrections, not ranging TPS uses). */
        val SYSTEMS = listOf(
            Constellation.GPS, Constellation.GLONASS, Constellation.GALILEO,
            Constellation.BEIDOU, Constellation.QZSS, Constellation.NAVIC,
        )
        private const val CLOCK = 6
        private const val DRIFT = 7
        private const val ISB0 = 8
        private val N = ISB0 + SYSTEMS.size

        private const val OMEGA_E = 7.2921151467e-5
        private const val L1 = 1575.42e6
        private const val MASK_DEG = 10.0
        /** Restart after this long without measurements. */
        private const val MAX_GAP_S = 30.0

        /** Acceleration noise, (m/s²)²·s, by how fast the filter thinks it's moving. */
        private const val ACCEL_STILL = 0.25
        private const val ACCEL_WALKING = 1.0
        private const val ACCEL_DRIVING = 4.0
        private const val STILL_MPS = 0.7
        private const val WALKING_MPS = 3.5
        private const val SPEED_SMOOTHING_S = 3.0
        private const val CLOCK_PSD = 1.0
        private const val DRIFT_PSD = 0.1
        private const val ISB_PSD = 0.01

        private const val MIN_RATE_SIGMA = 0.3
        private const val WEAK_CN0 = 30.0
        /** 5σ, squared. */
        private const val GATE = 25.0
        private const val JUMP_SIGMAS = 5.0
        private const val MAX_BAD_ROUNDS = 3
    }
}
