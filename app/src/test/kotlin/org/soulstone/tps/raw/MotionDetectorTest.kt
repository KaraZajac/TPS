package org.soulstone.tps.raw

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Test

/** The stillness detector on staged sensor streams. */
class MotionDetectorTest {
    private val random = Random(7)

    /** [seconds] of samples at 50 Hz from t = [from]; returns the time after the last one, ns. */
    private fun feed(
        d: MotionDetector, seconds: Double, from: Double = 0.0, gyro: Boolean = true,
        accel: (Double) -> DoubleArray, rotation: (Double) -> DoubleArray = { doubleArrayOf(0.0, 0.0, 0.0) },
    ): Long {
        var t = from
        while (t < from + seconds) {
            val ns = (t * 1e9).toLong()
            accel(t).let { d.accel(ns, it[0], it[1], it[2]) }
            if (gyro) rotation(t).let { d.gyro(ns, it[0], it[1], it[2]) }
            t += 0.02
        }
        return (t * 1e9).toLong()
    }

    private fun noise(sd: Double) = sd * random.nextGaussian()

    private val table: (Double) -> DoubleArray = { doubleArrayOf(noise(0.005), noise(0.005), 9.81 + noise(0.005)) }
    private val quietGyro: (Double) -> DoubleArray = { doubleArrayOf(noise(0.001), noise(0.001), noise(0.001)) }

    @Test
    fun aPhoneOnATableIsStillOnceAWholeWindowIsIn() {
        val d = MotionDetector()
        val early = feed(d, 1.0, accel = table, rotation = quietGyro)
        assertEquals(Motion.UNKNOWN, d.reading(early).motion)
        val now = feed(d, 2.0, from = 1.0, accel = table, rotation = quietGyro)
        assertEquals(Motion.STILL, d.reading(now).motion)
    }

    @Test
    fun walkingIsMoving() {
        val d = MotionDetector()
        val now = feed(d, 3.0,
            accel = { t -> doubleArrayOf(noise(0.3), 0.8 * sin(2 * PI * t), 9.81 + 2 * sin(4 * PI * t)) },
            rotation = { t -> doubleArrayOf(0.4 * sin(2 * PI * t), noise(0.1), noise(0.1)) })
        assertEquals(Motion.MOVING, d.reading(now).motion)
    }

    @Test
    fun aSmoothPullAwayIsNotStillWhileItBuilds() {
        // 0.3 m/s² building up along x over a second: gravity's size hardly changes (+0.005), its
        // direction does. Quiet, not still: no resting place, and the signals decide the speed.
        val d = MotionDetector()
        feed(d, 2.0, accel = table, rotation = quietGyro)
        val now = feed(d, 1.0, from = 2.0, accel = { t -> doubleArrayOf(0.3 * (t - 2.0) + noise(0.005), noise(0.005), 9.81 + noise(0.005)) }, rotation = quietGyro)
        assertEquals(Motion.QUIET, d.reading(now).motion)
        // Harder, 1.5 m/s²: moving.
        val later = feed(d, 1.0, from = 3.0, accel = { t -> doubleArrayOf(1.5 * (t - 3.0) + noise(0.005), noise(0.005), 9.81 + noise(0.005)) }, rotation = quietGyro)
        assertEquals(Motion.MOVING, d.reading(later).motion)
    }

    @Test
    fun aSlowTurnIsNotStillAndAQuickOneIsMoving() {
        val d = MotionDetector()
        val now = feed(d, 3.0, accel = table, rotation = { doubleArrayOf(noise(0.001), noise(0.001), 0.05) })
        assertEquals(Motion.QUIET, d.reading(now).motion)
        val later = feed(d, 3.0, from = 3.0, accel = table, rotation = { doubleArrayOf(noise(0.001), noise(0.001), 0.2) })
        assertEquals(Motion.MOVING, d.reading(later).motion)
    }

    @Test
    fun aCarAtTheLightsIsQuietAndOnTheRoadMoving() {
        // As in Kara's car: ~0.14 m/s² and 0.02 rad/s at a stop, ~0.8 m/s² and 0.14 rad/s driving.
        val d = MotionDetector()
        val idle = feed(d, 3.0, accel = { doubleArrayOf(noise(0.14), noise(0.14), 9.81 + noise(0.14)) }, rotation = { doubleArrayOf(noise(0.012), noise(0.012), noise(0.012)) })
        assertEquals(Motion.QUIET, d.reading(idle).motion)
        val road = feed(d, 3.0, from = 3.0, accel = { doubleArrayOf(noise(0.8), noise(0.8), 9.81 + noise(0.8)) }, rotation = { doubleArrayOf(noise(0.08), noise(0.08), noise(0.08)) })
        assertEquals(Motion.MOVING, d.reading(road).motion)
    }

    @Test
    fun withoutAGyroscopeTheAccelerometerDecides() {
        val d = MotionDetector()
        val now = feed(d, 3.0, gyro = false, accel = table)
        val r = d.reading(now)
        assertEquals(Motion.STILL, r.motion)
        assertEquals(null, r.gyroRmsRadps)
    }

    @Test
    fun stoppedSensorsSayNothing() {
        val d = MotionDetector()
        val end = feed(d, 3.0, accel = table, rotation = quietGyro)
        assertEquals(Motion.UNKNOWN, d.reading(end + 1_000_000_000L).motion)
    }
}
