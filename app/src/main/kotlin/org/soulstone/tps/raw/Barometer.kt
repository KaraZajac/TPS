package org.soulstone.tps.raw

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The phone's barometer, averaged over the last second. A phone's pressure sensor is noisy by
 * about 0.03 hPa sample to sample (a quarter of a metre), precise for changes over minutes, and
 * off by the weather in absolute terms (1 hPa is about 8 m). The super position learns that
 * offset; see [SuperPosition]. Plain Kotlin; one thread feeds it.
 */
class Barometer {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    /** A pressure sample, hPa, at [tNs] on the phone's monotonic clock. */
    fun sample(tNs: Long, hPa: Double) {
        if (!hPa.isFinite() || hPa !in MIN_HPA..MAX_HPA) return
        samples.addLast(tNs to hPa)
        while (samples.isNotEmpty() && tNs - samples.first().first > KEEP_NS) samples.removeFirst()
    }

    fun clear() = samples.clear()

    /** The mean of the last second's samples, hPa; null without any. */
    fun reading(nowNs: Long): Double? {
        val recent = samples.filter { nowNs - it.first in 0..WINDOW_NS }
        return if (recent.isEmpty()) null else recent.sumOf { it.second } / recent.size
    }

    companion object {
        private const val WINDOW_NS = 1_000_000_000L
        private const val KEEP_NS = 2_000_000_000L
        /** Beyond what any phone on Earth (or in a plane's cabin) reads. */
        const val MIN_HPA = 300.0
        const val MAX_HPA = 1_100.0

        /** Height in the standard atmosphere, metres: right for changes, off by the weather in absolute terms. */
        fun altitude(hPa: Double): Double = 44_330.0 * (1 - (hPa / 1_013.25).pow(1 / 5.255))
    }
}

/**
 * TPS's height with the barometer: height = barometer + offset, where the offset (TPS's height
 * minus the barometer's standard-atmosphere height: the weather and the geoid) is the running
 * mean of the last [WINDOW] seconds of that difference, taken while TPS trusts its fix.
 *
 * Every change in height (stairs, a hill, a lift) comes from the barometer within a second. The
 * GNSS height's errors come from reflections that last minutes; a mean over ten minutes averages
 * them down, and follows the weather (a few hPa an hour) with a lag of minutes. At first the
 * mean is short, so the height is no better than TPS's own, but no early error is locked in.
 * A difference that jumps far from the mean for [MISSES] rounds (a plane's cabin, not a climb)
 * starts the mean over.
 */
class BaroHeight {
    /** (difference, weight) per second: TPS's height minus the barometer's, weighted 1/σ². */
    private val samples = ArrayDeque<Pair<Double, Double>>()
    private var weightedSum = 0.0
    private var weightedSquares = 0.0
    private var weights = 0.0
    private var lastSampleT = 0.0
    private var misses = 0

    fun reset() {
        samples.clear(); weightedSum = 0.0; weightedSquares = 0.0; weights = 0.0; misses = 0
    }

    /**
     * One round at [t] seconds: the barometer's [hPa] (null without a reading), and TPS's GNSS
     * height with its 68% vertical uncertainty while TPS trusts it (null otherwise). Returns the
     * height and its uncertainty, or null until there are [MIN_SAMPLES] differences to go on.
     */
    fun step(t: Double, hPa: Double?, gnssHeight: Double?, gnssSigma: Double): Pair<Double, Double>? {
        if (hPa == null) return null
        val baro = Barometer.altitude(hPa)
        if (gnssHeight != null) {
            val d = gnssHeight - baro
            val mean = if (samples.size >= MIN_SAMPLES) weightedSum / weights else null
            if (mean != null && kotlin.math.abs(d - mean) > max(JUMP_SIGMAS * max(gnssSigma, FLOOR_M), JUMP_FLOOR_M)) {
                // Far from the mean: a pressure jump that isn't a climb, if it lasts.
                if (++misses >= MISSES) reset()
            } else {
                misses = 0
                // TPS is least sure of its height just after it settles: those seconds count for less.
                val w = 1 / max(gnssSigma, FLOOR_M).let { it * it }
                samples.addLast(d to w); weightedSum += d * w; weightedSquares += d * d * w; weights += w
                while (samples.size > WINDOW) samples.removeFirst().let { (od, ow) -> weightedSum -= od * ow; weightedSquares -= od * od * ow; weights -= ow }
                lastSampleT = t
            }
        }
        // Not while the barometer disagrees (it may be a jump that isn't a climb).
        if (samples.size < MIN_SAMPLES || misses > 0) return null
        val offset = weightedSum / weights
        // The mean's uncertainty, from the larger of what TPS claims for its height and how much
        // its height actually wanders around the mean (its claim knows nothing of reflections
        // that last minutes); samples share their reflections for about CORRELATION_S. Plus the
        // weather since the last sample, plus the sensor.
        val n = samples.size.toDouble()
        val claimed = n / weights
        val wander = max(0.0, weightedSquares / weights - offset * offset)
        val independent = max(1.0, n / CORRELATION_S)
        val sigma = sqrt(max(claimed, wander) / independent + OFFSET_PSD * max(0.0, t - lastSampleT) + BARO_SIGMA * BARO_SIGMA)
        return baro + offset to sigma
    }

    companion object {
        /** Seconds of differences in the mean (at most), and before it's used. */
        const val WINDOW = 600
        const val MIN_SAMPLES = 30
        /** GNSS height errors stay alike for about this long (reflections), seconds. */
        const val CORRELATION_S = 120.0
        /** GNSS height's uncertainty is never taken as better than this, metres. */
        const val FLOOR_M = 3.0
        /** A difference this far from the mean (5σ, at least 30 m) for [MISSES] rounds starts it over. */
        const val JUMP_SIGMAS = 5.0
        const val JUMP_FLOOR_M = 30.0
        const val MISSES = 3
        /** The barometer's height noise after a second's averaging, metres; the weather's drift of the offset, m²/s. */
        const val BARO_SIGMA = 0.3
        const val OFFSET_PSD = 0.02
    }
}
