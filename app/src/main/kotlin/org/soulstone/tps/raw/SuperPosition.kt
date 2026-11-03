package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
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
    /** Shown as the position: settled to begin with, then held through short losses. */
    val usable: Boolean,
    /** This round's pseudoranges that fitted, and that didn't, per system. */
    val acceptedBySystem: Map<Constellation, Int> = emptyMap(),
    val rejectedBySystem: Map<Constellation, Int> = emptyMap(),
    /** Jumps of the whole clock taken up (no hardware reset reported), and the latest one's size, metres. */
    val clockJumps: Int = 0,
    val lastClockJumpM: Double = 0.0,
    /** Restarts from a fresh snapshot fix; for the latest, how far the position moved and after how long without data. */
    val restarts: Int = 0,
    val lastRestartMoveM: Double? = null,
    val lastRestartGapS: Double? = null,
    /**
     * How surprised the filter was by this round's pseudoranges: the mean of innovation² over
     * its expected variance. About 1 when its noise model is right; well below 1 means its ±
     * is too pessimistic, well above too optimistic.
     */
    val rangeNis: Double? = null,
    /** The same per system, and for the Doppler (pseudorange rate) measurements. */
    val rangeNisBySystem: Map<Constellation, Double> = emptyMap(),
    val rateNis: Double? = null,
    /**
     * Code against Doppler since the start, per system, m/s: how fast each satellite's
     * pseudorange drifts from what its Doppler adds up to, pooled over every satellite's
     * track. About 0 for an honest chip; a calibration check (Peng et al. 2023 found a
     * constant one on a Xiaomi Mi 8).
     */
    val codeMinusDopplerMps: Map<Constellation, Double> = emptyMap(),
    /** Static mode this round: the phone lay still, so the velocity was held at zero. */
    val still: Boolean = false,
    /**
     * Holding instead of following the signals, and why: they put TPS farther than it could
     * have gone, or they moved it while the phone lay still. Null while all is well.
     */
    val suspect: Suspect? = null,
    /** How far the signals would have moved TPS, metres, and for how long it has held, seconds. */
    val suspectMoveM: Double = 0.0,
    val suspectForS: Double = 0.0,
    /** Holds that ended with TPS taking the signals' position once it was within reach; for the latest, after how long and how far. */
    val released: Int = 0,
    val lastReleaseAfterS: Double? = null,
    val lastReleaseMoveM: Double? = null,
    /** Measurements this round that counted for less because they sat well off the fit. */
    val downweighted: Int = 0,
) {
    val speedMps: Double get() = sqrt(velocityEast * velocityEast + velocityNorth * velocityNorth)

    /** Direction of travel, degrees from north; null when barely moving. */
    val bearingDeg: Double?
        get() = if (speedMps < MOVING_MPS) null else (Math.toDegrees(atan2(velocityEast, velocityNorth)) + 360) % 360

    /** Holding a predicted position: no measurement fitted for a while (jamming, a tunnel), or the signals are suspect. */
    val holding: Boolean get() = sinceUpdateS > 2.5 || suspect != null

    companion object {
        const val MOVING_MPS = 0.5
        /** To be shown at first: this many updates, and this accurate. */
        const val SETTLE_UPDATES = 10
        const val SETTLE_ACCURACY_M = 15.0
        /** Once shown: held this long without measurements, while it stays this accurate. */
        const val HOLD_S = 30.0
        const val HOLD_ACCURACY_M = 50.0
        /** Holding against suspect signals: shown for as long as it's this good (no time limit). */
        const val SUSPECT_HOLD_ACCURACY_M = 100.0
    }
}

/** Why TPS holds its position instead of following the signals. */
enum class Suspect(val label: String) {
    OUT_OF_REACH("farther than it could have gone"),
    DRAGGED("moved while the phone lay still"),
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
 *   out if it doesn't fit; within that, one well off the fit counts for less (Huber weights,
 *   re-solved up to three times). A jump of the whole clock is recognised and absorbed instead.
 *   If most measurements stop fitting, the filter starts over from a snapshot fix, but only one
 *   within reach of where it was.
 * - **Holding.** A fix the phone couldn't have reached since the last good update (nowhere
 *   while it lies still, 50 m/s otherwise), or signals that move the position while the motion
 *   sensors say the phone lies still, aren't followed: TPS keeps its position and says why,
 *   until the signals agree with it again or the new place comes within reach.
 * - **Static mode.** While the motion sensors say still, and the signals agree, the velocity is
 *   held at zero: the position stops wandering, and the height settles.
 *
 * Pseudoranges are re-referenced to a fixed clock offset (the chip's at the start), so the
 * receiver clock simply drifts, by the same drift the Doppler sees, whether or not the chip
 * steers its clock. Plain Kotlin; one thread feeds it.
 */
class SuperPosition(private val robust: Boolean = true) {
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
    private var trusted = false
    private var startedFromChip = false
    private var hadState = false
    private var clockJumps = 0
    private var lastClockJumpM = 0.0
    private var restarts = 0
    private var lastRestartMoveM: Double? = null
    private var lastRestartGapS: Double? = null
    private var acceptedBySystem = emptyMap<Constellation, Int>()
    private var rejectedBySystem = emptyMap<Constellation, Int>()
    private var rangeNis: Double? = null
    private var rangeNisBySystem = emptyMap<Constellation, Double>()
    private var rateNis: Double? = null

    private var motion = Motion.UNKNOWN
    /** Where the phone came to rest (the sensors and the signals both said still), and how sure TPS was then. */
    private var anchor: Ecef? = null
    private var anchorSigma = 0.0
    private val anchorP = Array(3) { DoubleArray(3) }
    private var suspect: Suspect? = null
    private var suspectSinceT = 0.0
    private var suspectMoveM = 0.0
    private var agreeRounds = 0
    private var wantRestart = false
    private var released = 0
    private var lastReleaseAfterS: Double? = null
    private var lastReleaseMoveM: Double? = null
    private var stillApplied = false
    private var lastDownweighted = 0
    /** Re-centred at rest: the next update re-fits the clock before judging the signals. */
    private var refitClock = false
    /** Seconds the phone may have been moving since the last good update (all of them, without motion sensors). */
    private var movingS = 0.0

    /** One satellite's track for the code-against-Doppler check. */
    private class Arc(val t0: Double, val start: Double, var lastT: Double, var lastRate: Double) {
        /** Range change its Doppler adds up to, metres. */
        var doppler = 0.0
        var n = 0
        private var sx = 0.0
        private var sxx = 0.0
        private var sy = 0.0
        private var sxy = 0.0
        fun add(x: Double, y: Double) { n++; sx += x; sxx += x * x; sy += y; sxy += x * y }
        val cxy: Double get() = sxy - sx * sy / n
        val cxx: Double get() = sxx - sx * sx / n
    }
    private val arcs = HashMap<Pair<Constellation, Int>, Arc>()
    /** Per system: finished tracks' summed co-variation and variation in time. */
    private val codeDoppler = HashMap<Constellation, DoubleArray>()

    fun reset() {
        initialized = false
        hadState = false
    }

    /**
     * One round of measurements. [start] is a position to begin from. With [startAccuracyM]
     * (the chip's fix and its 68% radius), the filter starts there: from weak signals a
     * one-second snapshot can be 50 m off sideways and 100 m in height, and the chip has had
     * longer. Without, [start] only guides the first snapshot. Returns the filter's position,
     * or null while it has none yet.
     */
    fun step(
        clock: RawClock, observations: List<Observation>, start: Ecef?, klobuchar: Klobuchar?, gpsSeconds: Double,
        startAccuracyM: Double? = null, motion: Motion = Motion.UNKNOWN,
    ): FusedFix? {
        this.motion = motion
        // Picked up: where it lay is no longer where it is.
        if (motion != Motion.STILL) anchor = null
        val fullBias = clock.fullBiasNanos
        val t = clock.timeNanos * 1e-9
        // A long gap without measurements: nothing vouches the phone stayed put, so the resting
        // place goes; and the filter restarts, unless it's holding against suspect signals.
        if (initialized && t - lastT > MAX_GAP_S) anchor = null
        if (initialized && (t < lastT || (t - lastT > MAX_GAP_S && suspect == null))) initialized = false
        // Started cold from a snapshot, and the chip now has a decent fix: start again from that,
        // once, before anything was shown. Not a restart: no position of TPS's was ever on screen.
        if (initialized && !trusted && !startedFromChip && fullBias != null && start != null &&
            startAccuracyM != null && startAccuracyM <= LATE_START_ACCURACY_M
        ) {
            initialized = initialize(t, clock, fullBias, observations, start, klobuchar, gpsSeconds, startAccuracyM)
            return output()
        }
        if (!initialized) {
            if (fullBias == null) return null
            // Only a restart after TPS's position was on screen can show as a jump.
            val previous = if (hadState && trusted) Ecef(x[0], x[1], x[2]) else null
            val gap = t - lastUpdateT
            initialized = initialize(t, clock, fullBias, observations, start, klobuchar, gpsSeconds, startAccuracyM)
            if (initialized && previous != null) {
                restarts++
                lastRestartMoveM = (Ecef(x[0], x[1], x[2]) - previous).norm()
                lastRestartGapS = gap
            }
            hadState = hadState || initialized
            return output()
        }
        predict(t)
        if (fullBias != null) {
            // A hardware clock reset: the clock starts over, the position and velocity don't.
            if (clock.discontinuityCount != discontinuity) resetClock(clock, fullBias)
            // The chip's correction of its own clock since the reference, put back into the pseudoranges.
            val steer = ((fullBias - refFullBias) + (clock.biasNanos - refBias)) * 1e-9 * Pseudoranges.C
            update(t, observations, steer, klobuchar, gpsSeconds)
        }
        if (wantRestart) {
            wantRestart = false
            restartOrHold(t, observations, klobuchar, gpsSeconds)
        }
        if (initialized) {
            zeroVelocity()
            watchTheRestingPlace(t)
        }
        if (x.any { it.isNaN() || it.isInfinite() }) {
            initialized = false
            hadState = false
        }
        return output()
    }

    /**
     * The receiver clock (per system, unless [oneClock]) that best fits the pseudoranges if the
     * receiver is at [at]: the median of what's left after range, satellite clock and delays.
     */
    private fun clocksAt(at: Ecef, obs: List<Observation>, klobuchar: Klobuchar?, gpsSeconds: Double, oneClock: Boolean): Map<Constellation, Double>? {
        val geo = Geo.toGeodetic(at)
        val residuals = obs.mapNotNull { o ->
            val (az, el) = Geo.azEl(geo, at, o.satellite)
            if (Math.toDegrees(el) < MASK_DEG) return@mapNotNull null
            val range = (o.satellite - at).norm() + OMEGA_E * (o.satellite.x * at.y - o.satellite.y * at.x) / Pseudoranges.C
            val scale = o.carrierHz?.let { (L1 / it) * (L1 / it) } ?: 1.0
            val iono = o.ionoMeters ?: ((klobuchar?.let { Atmosphere.klobuchar(it, geo, az, el, gpsSeconds) } ?: 0.0) * scale)
            val tropo = o.tropoMeters ?: Atmosphere.troposphere(geo, el)
            o.constellation to (o.pseudorange - (range - Pseudoranges.C * o.satelliteClock + iono + tropo))
        }
        if (residuals.size < 3) return null
        fun median(v: List<Double>) = v.sorted()[v.size / 2]
        if (oneClock) {
            val b = median(residuals.map { it.second })
            return residuals.map { it.first }.distinct().associateWith { b }
        }
        val clocks = residuals.groupBy({ it.first }, { it.second }).mapValues { median(it.value) }
        return clocks.takeIf { reference in it }
    }

    private fun resetClock(clock: RawClock, fullBias: Long) {
        endArcs()
        refFullBias = fullBias
        refBias = clock.biasNanos
        discontinuity = clock.discontinuityCount
        x[CLOCK] = 0.0
        for (i in 0 until N) {
            p[CLOCK][i] = 0.0
            p[i][CLOCK] = 0.0
        }
        p[CLOCK][CLOCK] = 1.0e5 * 1.0e5
        p[DRIFT][DRIFT] += 100.0 * 100.0
    }

    // ------------------------------------------------------------------ start

    private fun initialize(
        t: Double, clock: RawClock, fullBias: Long, obs: List<Observation>, start: Ecef?, klobuchar: Klobuchar?, gpsSeconds: Double,
        startAccuracyM: Double?,
    ): Boolean {
        // A new start takes a new clock reference: the code-against-Doppler tracks start again.
        endArcs()
        // The chip's inter-system biases first: with them every system shares one clock.
        val corrected = obs.filter { it.constellation in SYSTEMS }.map { it.copy(pseudorange = it.pseudorange - (it.isbMeters ?: 0.0)) }
        val bySystem = corrected.groupBy { it.constellation }
        val chipBiases = corrected.isNotEmpty() && corrected.all { it.isbMeters != null }
        reference = if (Constellation.GPS in bySystem) Constellation.GPS else bySystem.keys.maxByOrNull { bySystem[it]?.size ?: 0 } ?: Constellation.GPS
        // With every offset known, solve with one clock: a clock per system from a weak snapshot
        // comes out tens of metres wrong, and the filter would hold on to it.
        val single = if (chipBiases) Solver.solve(corrected.map { it.copy(constellation = reference) }, start, klobuchar, gpsSeconds) else null
        val solution = single
            ?: Solver.solve(corrected, start, klobuchar, gpsSeconds)
            ?: Solver.solve(corrected.filter { (bySystem[it.constellation]?.size ?: 0) >= 2 }, start, klobuchar, gpsSeconds)
            ?: bySystem.values.maxByOrNull { it.size }?.let { Solver.solve(it, start, klobuchar, gpsSeconds) }
            ?: return false
        if (single == null) {
            reference = if (Constellation.GPS in solution.clocks) Constellation.GPS else solution.clocks.keys.maxBy { bySystem[it]?.size ?: 0 }
        }
        x.fill(0.0)
        for (row in p) row.fill(0.0)
        // From the chip's fix when there is one, with the clock that fits there; else the snapshot.
        val fromChip = if (start != null && startAccuracyM != null) clocksAt(start, corrected, klobuchar, gpsSeconds, chipBiases) else null
        val origin = if (fromChip != null) start!! else solution.ecef
        startedFromChip = fromChip != null
        x[0] = origin.x; x[1] = origin.y; x[2] = origin.z
        x[CLOCK] = fromChip?.get(reference) ?: solution.clocks.getValue(reference)
        val axes = localAxes(Geo.toGeodetic(origin))
        val position = if (fromChip != null) {
            val a = startAccuracyM!!.coerceIn(5.0, 50.0)
            localCovariance(axes, (2 * a) * (2 * a), (4 * a) * (4 * a))
        } else {
            localCovariance(axes, 100.0 * 100.0, 100.0 * 100.0)
        }
        for (i in 0 until 3) for (j in 0 until 3) p[i][j] = position[i][j]
        // Velocity: unknown along the ground, but nobody starts out climbing at 10 m/s.
        val start = localCovariance(axes, 10.0 * 10.0, 1.0 * 1.0)
        for (i in 0 until 3) for (j in 0 until 3) p[3 + i][3 + j] = start[i][j]
        p[CLOCK][CLOCK] = 30.0 * 30.0
        p[DRIFT][DRIFT] = 300.0 * 300.0
        for (c in SYSTEMS) {
            if (c == reference) continue
            val k = isbIndex(c)
            val chipKnows = obs.any { it.constellation == c && it.isbMeters != null }
            val solved = (if (fromChip != null) fromChip[c] else solution.clocks[c]?.takeIf { single == null })
            x[k] = when {
                // What's left after the chip's own figure: a few metres (6 m at most on Kara's phone).
                chipKnows -> 0.0
                solved != null -> solved - x[CLOCK]
                else -> 0.0
            }
            p[k][k] = when {
                chipKnows -> 10.0 * 10.0
                solved != null -> 30.0 * 30.0
                // Without the chip's figure, GLONASS can sit a kilometre off GPS.
                else -> 3_000.0 * 3_000.0
            }
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
        trusted = false
        // Not the resting place: a restart doesn't move the phone. Only the motion sensors clear it.
        suspect = null
        agreeRounds = 0
        wantRestart = false
        movingS = 0.0
        acceptedBySystem = emptyMap()
        rejectedBySystem = emptyMap()
        return true
    }

    // ------------------------------------------------------------------ predict

    private fun predict(t: Double) {
        val dt = t - lastT
        lastT = t
        if (motion != Motion.STILL) movingS += dt
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
        val geo = Geo.toGeodetic(Ecef(x[0], x[1], x[2]))
        val ground = Geo.enu(geo, Ecef(x[3], x[4], x[5]))
        smoothedSpeed += (sqrt(ground[0] * ground[0] + ground[1] * ground[1]) - smoothedSpeed) * minOf(1.0, dt / SPEED_SMOOTHING_S)
        val accel = if (motion == Motion.STILL) ACCEL_STILL else accelerationNoise(smoothedSpeed)
        // People and cars turn and brake, but hardly ever accelerate up or down: far less
        // freedom vertically, or a noisy vertical speed carries the height away.
        val q = localCovariance(localAxes(geo), accel, accel * VERTICAL_SHARE)
        for (i in 0 until 3) for (j in 0 until 3) {
            p[i][j] += q[i][j] * dt3 / 3
            p[i][3 + j] += q[i][j] * dt2 / 2
            p[3 + i][j] += q[i][j] * dt2 / 2
            p[3 + i][3 + j] += q[i][j] * dt
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
        val measuredBy = HashMap<Observation, Double>()
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
            measuredBy[o] = measured
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
                // The chip's Doppler uncertainty is pessimistic (NIS 0.26–0.73 on Kara's recordings).
                val s = max(rateSigma * RATE_SIGMA_SCALE, MIN_RATE_SIGMA)
                rows += Row(hr, rate - (closing + x[DRIFT] - o.satelliteClockDriftMps), s * s, range = false, satellite = o)
            }
        }
        if (rows.isEmpty()) return

        if (suspect != null || refitClock) {
            // Holding, or just put back where the phone came to rest: the position stays, and the
            // clock and its drift follow the signals (their median), so whether they fit is
            // judged against the held position alone.
            refitClock = false
            followMedian(rows.filter { it.range }, CLOCK)
            followMedian(rows.filter { !it.range }, DRIFT)
        } else {
            // A jump of the whole clock moves every pseudorange (or every rate) by the same amount:
            // take it up in the clock rather than reject every satellite.
            absorbCommonJump(rows.filter { it.range }, CLOCK)?.let { jump ->
                clockJumps++
                lastClockJumpM = jump
                // Every pseudorange jumped: the tracks start again.
                endArcs()
            }
            absorbCommonJump(rows.filter { !it.range }, DRIFT)
        }

        // Gate: a measurement far from what the filter expects is left out, not averaged in.
        val accepted = rows.filter { row ->
            val s = quadratic(row.h) + row.variance
            row.innovation * row.innovation / s < GATE
        }
        val ranges = rows.count { it.range }
        val rangesRejected = ranges - accepted.count { it.range }
        lastRejected = rows.size - accepted.size
        val acceptedSet = accepted.toHashSet()
        fun nis(row: Row) = row.innovation * row.innovation / (quadratic(row.h) + row.variance)
        rangeNis = accepted.filter { it.range }.map(::nis).takeIf { it.isNotEmpty() }?.average()
        rangeNisBySystem = accepted.filter { it.range }.groupBy { it.satellite.constellation }.mapValues { (_, rows) -> rows.map(::nis).average() }
        rateNis = accepted.filter { !it.range }.map(::nis).takeIf { it.isNotEmpty() }?.average()
        trackCodeDoppler(t, accepted, measuredBy)
        acceptedBySystem = rows.filter { it.range && it in acceptedSet }.groupingBy { it.satellite.constellation }.eachCount()
        rejectedBySystem = rows.filter { it.range && it !in acceptedSet }.groupingBy { it.satellite.constellation }.eachCount()
        if (suspect != null) {
            // Holding: the signals don't move the position until they agree with it again, or
            // come within reach. Agree: for three rounds, three in four pseudoranges within the
            // gate, and their typical surprise (median NIS) what honest signals give (~0.45),
            // not what a drag of a few σ leaves behind.
            val surprise = accepted.filter { it.range }.map(::nis).sorted().let { if (it.isEmpty()) Double.MAX_VALUE else it[it.size / 2] }
            val agree = ranges >= 4 && (ranges - rangesRejected) * 4 >= ranges * 3 && surprise <= AGREE_MEDIAN_NIS
            agreeRounds = if (agree) agreeRounds + 1 else 0
            if (agreeRounds < AGREE_ROUNDS) {
                wantRestart = true
                return
            }
            suspect = null
            badRounds = 0
        }
        badRounds = if (ranges >= 4 && rangesRejected * 2 > ranges) badRounds + 1 else 0
        if (badRounds >= MAX_BAD_ROUNDS) {
            // Most satellites disagree with the filter for several rounds: start over, if within reach.
            wantRestart = true
            return
        }
        if (accepted.isEmpty()) return

        // Sequential scalar updates, all linearised at the predicted state. Then, while any
        // measurement sits more than HUBER_K σ off the result, solved again from the same
        // prediction with that one counting for less, by k/|e| (Huber's M-estimator: convex,
        // so it can't run away), up to ROBUST_PASSES in all.
        val x0 = x.copyOf()
        val p0 = Array(N) { p[it].copyOf() }
        val weights = DoubleArray(accepted.size) { 1.0 }
        for (pass in 0 until (if (robust) ROBUST_PASSES else 1)) {
            if (pass > 0) {
                var changed = false
                for ((k, row) in accepted.withIndex()) {
                    var v = row.innovation
                    for (i in 0 until N) v -= row.h[i] * (x[i] - x0[i])
                    val e = abs(v) / sqrt(row.variance)
                    val w = if (e <= HUBER_K) 1.0 else HUBER_K / e
                    if (abs(w - weights[k]) > 0.02) changed = true
                    weights[k] = w
                }
                if (!changed) break
                x0.copyInto(x)
                for (i in 0 until N) p0[i].copyInto(p[i])
            }
            for ((k, row) in accepted.withIndex()) {
                var v = row.innovation
                for (i in 0 until N) v -= row.h[i] * (x[i] - x0[i])
                val ph = DoubleArray(N) { i -> (0 until N).sumOf { j -> p[i][j] * row.h[j] } }
                val s = (0 until N).sumOf { i -> row.h[i] * ph[i] } + row.variance / weights[k]
                for (i in 0 until N) x[i] += ph[i] / s * v
                for (i in 0 until N) for (j in 0 until N) p[i][j] -= ph[i] * ph[j] / s
            }
        }
        lastDownweighted = weights.count { it < 0.99 }
        // Keep P symmetric against rounding.
        for (i in 0 until N) for (j in i + 1 until N) {
            val m = (p[i][j] + p[j][i]) / 2
            p[i][j] = m; p[j][i] = m
        }
        val usedRanges = accepted.filter { it.range }.map { it.satellite }
        lastSatellites = usedRanges.size
        lastSystems = usedRanges.map { it.constellation }.toSet()
        lastUpdateT = t
        movingS = 0.0
        updates++
    }

    /**
     * Code against Doppler: for each satellite whose pseudorange and Doppler both fitted, how
     * far the pseudorange has moved from where its Doppler, added up, says it should be. The
     * slope of that over time is the bias between code rate and Doppler.
     */
    private fun trackCodeDoppler(t: Double, accepted: List<Row>, measuredBy: Map<Observation, Double>) {
        val withRate = accepted.filter { !it.range }.mapTo(HashSet()) { it.satellite }
        for (row in accepted) {
            if (!row.range || row.satellite !in withRate) continue
            val o = row.satellite
            val rate = o.rateMps ?: continue
            val measured = measuredBy[o] ?: continue
            val key = o.constellation to o.svid
            val arc = arcs[key]
            if (arc == null || t - arc.lastT > ARC_GAP_S || t <= arc.lastT) {
                arc?.let { endArc(key.first, it) }
                arcs[key] = Arc(t, measured, t, rate).also { it.add(0.0, 0.0) }
                continue
            }
            arc.doppler += (t - arc.lastT) * (rate + arc.lastRate) / 2
            arc.lastT = t
            arc.lastRate = rate
            arc.add(t - arc.t0, measured - arc.start - arc.doppler)
        }
        // Satellites gone for good (set, or blocked) end their tracks.
        arcs.entries.removeAll { (key, arc) -> (t - arc.lastT > ARC_GAP_S).also { if (it) endArc(key.first, arc) } }
    }

    private fun endArc(system: Constellation, arc: Arc) {
        if (arc.n < MIN_ARC_POINTS) return
        val sums = codeDoppler.getOrPut(system) { DoubleArray(2) }
        sums[0] += arc.cxy
        sums[1] += arc.cxx
    }

    private fun endArcs() {
        for ((key, arc) in arcs) endArc(key.first, arc)
        arcs.clear()
    }

    /** Per system, the pooled slope over finished and running tracks, m/s. */
    private fun codeMinusDoppler(): Map<Constellation, Double> {
        val sums = HashMap<Constellation, DoubleArray>()
        for ((system, s) in codeDoppler) sums[system] = s.copyOf()
        for ((key, arc) in arcs) {
            if (arc.n < MIN_ARC_POINTS) continue
            val s = sums.getOrPut(key.first) { DoubleArray(2) }
            s[0] += arc.cxy
            s[1] += arc.cxx
        }
        return sums.filterValues { it[1] > 0 }.mapValues { (_, s) -> s[0] / s[1] }
    }

    /** Moves state [index] (the clock or its drift) by the median of [rows]' innovations, and takes it out of them. */
    private fun followMedian(rows: List<Row>, index: Int) {
        if (rows.isEmpty()) return
        val median = rows.map { it.innovation }.sorted()[rows.size / 2]
        x[index] += median
        for (row in rows) row.innovation -= median
    }

    /** If [rows] all share one large offset, moves state [index] by it (the clock or its drift jumped) and returns it. */
    private fun absorbCommonJump(rows: List<Row>, index: Int): Double? {
        if (rows.size < 3) return null
        val sorted = rows.map { it.innovation }.sorted()
        val median = sorted[sorted.size / 2]
        val spread = sorted.map { abs(it - median) }.sorted()[sorted.size / 2]
        val expected = sqrt(p[index][index] + rows.map { it.variance }.sorted()[rows.size / 2])
        if (abs(median) > JUMP_SIGMAS * expected && spread < abs(median) / 4) {
            x[index] += median
            p[index][index] += median * median
            for (row in rows) row.innovation -= median
            return median
        }
        return null
    }

    /** East, north and up unit vectors at [g], in Earth-fixed coordinates. */
    private fun localAxes(g: Geodetic): Array<DoubleArray> {
        val lat = Math.toRadians(g.latitude)
        val lon = Math.toRadians(g.longitude)
        return arrayOf(
            doubleArrayOf(-sin(lon), cos(lon), 0.0),
            doubleArrayOf(-sin(lat) * cos(lon), -sin(lat) * sin(lon), cos(lat)),
            doubleArrayOf(cos(lat) * cos(lon), cos(lat) * sin(lon), sin(lat)),
        )
    }

    /** An Earth-fixed 3×3 covariance with [horizontal] along the ground and [vertical] up. */
    private fun localCovariance(axes: Array<DoubleArray>, horizontal: Double, vertical: Double): Array<DoubleArray> =
        Array(3) { i ->
            DoubleArray(3) { j -> horizontal * (axes[0][i] * axes[0][j] + axes[1][i] * axes[1][j]) + vertical * axes[2][i] * axes[2][j] }
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
        // Each system's share, from its own NIS on Kara's recordings (Qualcomm chip): Galileo's
        // BOC signal ranges better than the chip's reported uncertainty says (NIS 0.07–0.24 at
        // 1×); GLONASS, each satellite on its own frequency, worse (NIS 1.3–2.3 at 1.5×).
        val system = SYSTEM_RANGE_FACTOR[o.constellation] ?: 1.0
        // A chip that flags multipath on a signal gets believed.
        val multipath = if (o.multipathDetected) MULTIPATH_FACTOR else 1.0
        // Overall scale calibrated so the innovations' NIS averages ~1.
        return base * weak * system * multipath * RANGE_SIGMA_SCALE
    }

    // ------------------------------------------------------------------ holding

    /**
     * Most measurements stopped fitting (or, while holding, still don't). Before TPS's position
     * was ever shown, start over as before. At rest, go back to where the phone came to rest,
     * and hold there if the signals' own fix lies beyond what their noise explains. Otherwise
     * start over only at a place within reach of where TPS was; else hold, and check each round.
     */
    private fun restartOrHold(t: Double, obs: List<Observation>, klobuchar: Klobuchar?, gpsSeconds: Double) {
        val here = Ecef(x[0], x[1], x[2])
        val rest = anchor
        if (rest == null && !trusted && suspect == null) {
            initialized = false
            return
        }
        val there = snapshot(obs, rest ?: here, klobuchar, gpsSeconds)
        if (rest != null) {
            val fromRest = distance(rest, there?.ecef ?: here)
            // While holding, the position is already at rest.
            if (suspect == null) recenterAtRest()
            if (fromRest > restMargin(there)) {
                if (suspect == null) hold(Suspect.DRAGGED, fromRest, t) else suspectMoveM = fromRest
            } else {
                // The signals put the phone back where it lies: the hold just ends.
                suspect = null
            }
            return
        }
        if (there == null) {
            // Too few satellites to say where the signals put TPS: keep holding, or start over as before.
            if (suspect == null) initialized = false
            return
        }
        val move = distance(here, there.ecef)
        if (move <= reach()) {
            release(t, move)
            // The next round starts over there.
            initialized = false
            return
        }
        if (suspect == null) hold(Suspect.OUT_OF_REACH, move, t) else suspectMoveM = move
    }

    /**
     * How far the phone could have gone from TPS's position: its uncertainty, a margin for a
     * weak snapshot, and 50 m/s for every second it may have been moving since the last good
     * update (none while the motion sensors say still).
     */
    private fun reach(): Double = REACH_FLOOR_M + 3 * horizontalSigma() + REACH_SPEED_MPS * movingS

    /**
     * At rest, how far the signals may put the phone from where it came to rest before that
     * counts as being dragged: 25 m, or three times TPS's uncertainty then, or three times the
     * snapshot's own (indoors a one-second fix can be 50–80 m off by itself).
     */
    private fun restMargin(snapshot: Solution?): Double {
        val snap = snapshot?.let { (it.hdop ?: 3.0) * max(it.residualRms, SNAPSHOT_RANGE_FLOOR_M) } ?: 0.0
        return maxOf(DRAG_FLOOR_M, 3 * anchorSigma, 3 * snap)
    }

    private fun hold(reason: Suspect, move: Double, t: Double) {
        suspectSinceT = t
        suspect = reason
        suspectMoveM = move
        agreeRounds = 0
        badRounds = 0
    }

    /** A hold ends with TPS taking the signals' place, now within reach of where it was. */
    private fun release(t: Double, move: Double) {
        if (suspect == null) return
        released++
        lastReleaseAfterS = t - suspectSinceT
        lastReleaseMoveM = move
        suspect = null
    }

    /**
     * Back to where the phone came to rest, as sure as TPS was then. The clock is re-fitted to
     * the signals at the next update (it took up part of whatever pulled the position away).
     */
    private fun recenterAtRest() {
        val a = anchor ?: return
        x[0] = a.x; x[1] = a.y; x[2] = a.z
        for (i in 3 until 6) x[i] = 0.0
        for (i in 0 until N) for (j in 0 until 3) {
            p[i][j] = 0.0
            p[j][i] = 0.0
        }
        for (i in 0 until 3) for (j in 0 until 3) p[i][j] = anchorP[i][j]
        // Velocity: at rest.
        for (i in 3 until 6) p[i][i] = max(p[i][i], ZUPT_SIGMA * ZUPT_SIGMA)
        refitClock = true
        badRounds = 0
    }

    private fun distance(a: Ecef, b: Ecef): Double = Solver.offset(Geo.toGeodetic(a), b).let { hypot(it[0], it[1]) }

    /** Where the signals alone put the receiver this round, as a fresh start would solve it; null with too few. */
    private fun snapshot(obs: List<Observation>, guess: Ecef, klobuchar: Klobuchar?, gpsSeconds: Double): Solution? {
        val corrected = obs.filter { it.constellation in SYSTEMS }.map { it.copy(pseudorange = it.pseudorange - (it.isbMeters ?: 0.0)) }
        if (corrected.size < 4) return null
        val bySystem = corrected.groupBy { it.constellation }
        val single = if (corrected.all { it.isbMeters != null }) Solver.solve(corrected.map { it.copy(constellation = reference) }, guess, klobuchar, gpsSeconds) else null
        return single
            ?: Solver.solve(corrected, guess, klobuchar, gpsSeconds)
            ?: Solver.solve(corrected.filter { (bySystem[it.constellation]?.size ?: 0) >= 2 }, guess, klobuchar, gpsSeconds)
    }

    /**
     * Static mode: while the motion sensors say still, and the signals agree (barely moving) or
     * the phone came to rest with both agreeing, the velocity is held at zero. Not otherwise: on
     * a smooth ride the sensors can feel still while the signals say 30 m/s.
     */
    private fun zeroVelocity() {
        stillApplied = false
        if (motion != Motion.STILL) return
        if (anchor == null && horizontalSpeed() >= STILL_SIGNALS_MPS) return
        for (axis in 3 until 6) {
            val ph = DoubleArray(N) { i -> p[i][axis] }
            val s = p[axis][axis] + ZUPT_SIGMA * ZUPT_SIGMA
            val v = -x[axis]
            for (i in 0 until N) x[i] += ph[i] / s * v
            for (i in 0 until N) for (j in 0 until N) p[i][j] -= ph[i] * ph[j] / s
        }
        stillApplied = true
    }

    /**
     * Notes where the phone came to rest (sensors still, signals barely moving, TPS's position
     * shown). While it stays at rest, the filter mustn't wander from there by more than
     * [DRAG_FLOOR_M], or three times its uncertainty then: if it does, it goes back and holds.
     */
    private fun watchTheRestingPlace(t: Double) {
        if (motion != Motion.STILL) return
        val here = Ecef(x[0], x[1], x[2])
        val a = anchor
        if (a == null) {
            if (trusted && suspect == null && horizontalSpeed() < STILL_SIGNALS_MPS) {
                anchor = here
                anchorSigma = horizontalSigma()
                for (i in 0 until 3) for (j in 0 until 3) anchorP[i][j] = p[i][j]
            }
            return
        }
        if (suspect != null) return
        val moved = distance(a, here)
        if (moved > max(DRAG_FLOOR_M, 3 * anchorSigma)) {
            recenterAtRest()
            hold(Suspect.DRAGGED, moved, t)
        }
    }

    private fun horizontalSigma(): Double {
        val axes = localAxes(Geo.toGeodetic(Ecef(x[0], x[1], x[2])))
        var s = 0.0
        for (k in 0 until 2) for (i in 0 until 3) for (j in 0 until 3) s += axes[k][i] * p[i][j] * axes[k][j]
        return sqrt(max(s, 0.0))
    }

    private fun horizontalSpeed(): Double {
        val v = Geo.enu(Geo.toGeodetic(Ecef(x[0], x[1], x[2])), Ecef(x[3], x[4], x[5]))
        return hypot(v[0], v[1])
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
        val horizontal = sqrt(variance(east) + variance(north))
        val since = lastT - lastUpdateT
        trusted = when {
            // Holding against suspect signals: for as long as the held position stays useful.
            suspect != null -> horizontal <= FusedFix.SUSPECT_HOLD_ACCURACY_M
            !trusted -> updates >= FusedFix.SETTLE_UPDATES && horizontal <= FusedFix.SETTLE_ACCURACY_M && since <= 2.5
            // Lying still, the position holds through a loss for as long as it stays good.
            else -> (since <= FusedFix.HOLD_S || anchor != null) && horizontal <= FusedFix.HOLD_ACCURACY_M
        }
        return FusedFix(
            position = geo,
            ecef = ecef,
            velocityEast = v[0],
            velocityNorth = v[1],
            velocityUp = v[2],
            horizontalAccuracyM = horizontal,
            verticalAccuracyM = sqrt(variance(up)),
            satellites = lastSatellites,
            systems = lastSystems,
            rejected = lastRejected,
            reference = reference,
            interSystemBiases = SYSTEMS.filter { it != reference && p[isbIndex(it)][isbIndex(it)] < 1_000.0 * 1_000.0 }
                .associateWith { x[isbIndex(it)] },
            clockDriftMps = x[DRIFT],
            sinceUpdateS = since,
            updates = updates,
            usable = trusted,
            acceptedBySystem = acceptedBySystem,
            rejectedBySystem = rejectedBySystem,
            clockJumps = clockJumps,
            lastClockJumpM = lastClockJumpM,
            restarts = restarts,
            lastRestartMoveM = lastRestartMoveM,
            lastRestartGapS = lastRestartGapS,
            rangeNis = rangeNis,
            rangeNisBySystem = rangeNisBySystem,
            rateNis = rateNis,
            codeMinusDopplerMps = codeMinusDoppler(),
            still = stillApplied,
            suspect = suspect,
            suspectMoveM = suspectMoveM,
            suspectForS = if (suspect != null) lastT - suspectSinceT else 0.0,
            released = released,
            lastReleaseAfterS = lastReleaseAfterS,
            lastReleaseMoveM = lastReleaseMoveM,
            downweighted = lastDownweighted,
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
        /** Vertical acceleration noise as a share of the horizontal. */
        private const val VERTICAL_SHARE = 0.05
        private const val CLOCK_PSD = 1.0
        private const val DRIFT_PSD = 0.1
        private const val ISB_PSD = 0.01

        private const val MIN_RATE_SIGMA = 0.2
        /** The code-against-Doppler check: a gap this long ends a satellite's track; tracks this short don't count. */
        private const val ARC_GAP_S = 3.0
        private const val MIN_ARC_POINTS = 10
        private const val RATE_SIGMA_SCALE = 0.65
        private const val MULTIPATH_FACTOR = 2.0
        /** Pseudorange noise per system, relative to GPS. Galileo is held back from its measured 0.4. */
        private val SYSTEM_RANGE_FACTOR = mapOf(
            Constellation.GALILEO to 0.5,
            Constellation.GLONASS to 2.0,
        )
        private const val RANGE_SIGMA_SCALE = 0.6
        /** A cold start from a snapshot is redone from the chip's fix once the chip is this sure. */
        private const val LATE_START_ACCURACY_M = 20.0
        private const val WEAK_CN0 = 30.0
        /** 5σ, squared. */
        private const val GATE = 25.0
        /** Huber's threshold, σ, and how many times an update may be solved. */
        private const val HUBER_K = 1.5
        private const val ROBUST_PASSES = 3
        /** A restart must land within this, plus 3σ, plus 50 m/s for each second the phone may have moved. */
        private const val REACH_FLOOR_M = 100.0
        private const val REACH_SPEED_MPS = 50.0
        /** Rounds of agreement that end a hold, and the most typical surprise (median NIS) that counts as agreeing. */
        private const val AGREE_ROUNDS = 3
        private const val AGREE_MEDIAN_NIS = 2.0
        /** Signals this slow count as still (static mode, coming to rest). */
        private const val STILL_SIGNALS_MPS = 0.5
        /** The zero-velocity update's noise, m/s. */
        private const val ZUPT_SIGMA = 0.05
        /** At rest, signals moving TPS more than this (or 3σ at rest) are dragging it. */
        private const val DRAG_FLOOR_M = 25.0
        /** A snapshot's pseudorange noise is at least this, metres (with four satellites its residuals are zero). */
        private const val SNAPSHOT_RANGE_FLOOR_M = 5.0
        private const val JUMP_SIGMAS = 5.0
        private const val MAX_BAD_ROUNDS = 3
    }
}
