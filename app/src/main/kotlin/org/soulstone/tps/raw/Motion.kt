package org.soulstone.tps.raw

import kotlin.math.sqrt

/** What the phone's motion sensors say. */
enum class Motion {
    UNKNOWN,

    /** Resting on something: a table, a shelf, a bed. */
    STILL,

    /** No steps and no road under it: held steadily, a vehicle at a stop, or a very smooth ride. */
    QUIET,

    MOVING,
}

/** The motion sensors' verdict, with the figures behind it (for recordings and the card). */
data class MotionReading(val motion: Motion, val accelSdMps2: Double? = null, val gyroRmsRadps: Double? = null)

/**
 * Stillness from the accelerometer and gyroscope. Still means: for the last [WINDOW_S], every
 * accelerometer axis within [STILL_ACCEL_SD] of its own mean and the rotation rate under
 * [STILL_GYRO_RMS]. That's a phone resting on something, or held very steadily; walking, a
 * pocket or a running engine are all well above it. Quiet is the next step up, under
 * [QUIET_ACCEL_SD] and [QUIET_GYRO_RMS]: no steps, no road. In Kara's car at a stop the phone
 * read ~0.14 m/s² and 0.02 rad/s, driving ~0.8 m/s² and 0.14 rad/s.
 *
 * Per axis, not the size of the acceleration: a smooth acceleration (a train pulling away)
 * tilts the gravity the phone feels without changing its size. A phone without a gyroscope is
 * judged on the accelerometer alone. No permission is needed. Plain Kotlin; one thread feeds it.
 */
class MotionDetector {
    private class Sample(val tNs: Long, val x: Double, val y: Double, val z: Double)

    private val accel = ArrayDeque<Sample>()
    private val gyro = ArrayDeque<Sample>()

    /** An accelerometer sample, m/s², at [tNs] on the phone's monotonic clock. */
    fun accel(tNs: Long, x: Double, y: Double, z: Double) = add(accel, Sample(tNs, x, y, z))

    /** A gyroscope sample, rad/s. */
    fun gyro(tNs: Long, x: Double, y: Double, z: Double) = add(gyro, Sample(tNs, x, y, z))

    fun clear() {
        accel.clear()
        gyro.clear()
    }

    private fun add(q: ArrayDeque<Sample>, s: Sample) {
        q.addLast(s)
        while (q.isNotEmpty() && s.tNs - q.first().tNs > WINDOW_NS) q.removeFirst()
    }

    fun reading(nowNs: Long): MotionReading {
        val a = accel.filter { nowNs - it.tNs <= WINDOW_NS }
        // Not running, stopped, or not yet a full window: say nothing.
        if (a.size < MIN_SAMPLES || nowNs - a.last().tNs > STALE_NS || a.last().tNs - a.first().tNs < WINDOW_NS * 0.8) {
            return MotionReading(Motion.UNKNOWN)
        }
        val sd = maxOf(sd(a) { it.x }, sd(a) { it.y }, sd(a) { it.z })
        val g = gyro.filter { nowNs - it.tNs <= WINDOW_NS }
        val rms = if (g.size >= MIN_SAMPLES) sqrt(g.sumOf { it.x * it.x + it.y * it.y + it.z * it.z } / g.size) else null
        return MotionReading(classify(sd, rms), sd, rms)
    }

    private inline fun sd(v: List<Sample>, axis: (Sample) -> Double): Double {
        val mean = v.sumOf(axis) / v.size
        return sqrt(v.sumOf { (axis(it) - mean).let { d -> d * d } } / v.size)
    }

    companion object {
        /** The verdict from a window's figures (also for recordings, which keep them). */
        fun classify(accelSd: Double, gyroRms: Double?): Motion = when {
            accelSd < STILL_ACCEL_SD && (gyroRms == null || gyroRms < STILL_GYRO_RMS) -> Motion.STILL
            accelSd < QUIET_ACCEL_SD && (gyroRms == null || gyroRms < QUIET_GYRO_RMS) -> Motion.QUIET
            else -> Motion.MOVING
        }

        const val WINDOW_S = 2.0
        private const val WINDOW_NS = (WINDOW_S * 1e9).toLong()
        private const val STALE_NS = 500_000_000L
        private const val MIN_SAMPLES = 10
        /** m/s²: a phone on a table shows ~0.005–0.02; held in the hand, 0.05 and up. */
        const val STILL_ACCEL_SD = 0.05
        /** rad/s (about 1°/s). */
        const val STILL_GYRO_RMS = 0.02
        /** m/s²: walking is 1 and up, a car on the road ~0.5–1.4, at a stop ~0.1–0.4. */
        const val QUIET_ACCEL_SD = 0.3
        /** rad/s (about 3.5°/s). */
        const val QUIET_GYRO_RMS = 0.06
    }
}
