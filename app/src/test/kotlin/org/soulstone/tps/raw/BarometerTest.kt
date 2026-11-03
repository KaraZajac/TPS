package org.soulstone.tps.raw

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The barometer's reading and the height filter built on it, on staged pressures. */
class BarometerTest {

    @Test
    fun theStandardAtmosphere() {
        assertEquals(0.0, Barometer.altitude(1_013.25), 0.01)
        assertEquals(1_000.0, Barometer.altitude(898.75), 1.0)
        // About 8 m a hectopascal near sea level.
        assertEquals(8.3, Barometer.altitude(1_012.25) - Barometer.altitude(1_013.25), 0.2)
    }

    @Test
    fun aReadingIsTheLastSecondsMean() {
        val b = Barometer()
        assertNull(b.reading(0))
        for (k in 0 until 10) b.sample(k * 200_000_000L, 1_000.0 + k)
        // The last second, edges included: samples at 0.8–1.8 s.
        assertEquals(1_006.5, b.reading(1_800_000_000L)!!, 1e-9)
        b.sample(2_000_000_000L, 2_000.0)   // nonsense: ignored
        assertEquals(1_007.0, b.reading(2_000_000_000L)!!, 1e-9)
        assertNull(b.reading(10_000_000_000L))
    }

    /** Pressure at [height] m (ellipsoid), 33 m of geoid, and [weather] hPa on sea level. */
    private fun pressure(height: Double, weather: Double = 0.0, noise: Double = 0.0) =
        (1_013.25 + weather) * (1 - (height - 33.0) / 44_330.0).pow(5.255) + noise

    /**
     * Ten minutes of a phone at 100 m whose GNSS height wanders like reflections do (±8 m over
     * five minutes, plus noise), then whatever [climb] does. Returns (true, GNSS, barometer) heights.
     */
    private fun run(seconds: Int, climb: (Int) -> Double = { 0.0 }, jump: (Int) -> Double = { 0.0 }, weather: (Int) -> Double = { 0.0 }): List<Triple<Double, Double, Double?>> {
        val random = Random(3)
        val filter = BaroHeight()
        return List(seconds) { k ->
            val truth = 100.0 + climb(k)
            val gnss = truth + 8 * sin(2 * PI * k / 300.0) + 2 * random.nextGaussian()
            val hPa = pressure(truth, weather(k), 0.02 * random.nextGaussian()) + jump(k)
            Triple(truth, gnss, filter.step(k.toDouble(), hPa, gnss, 1.5)?.first)
        }
    }

    private fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)

    @Test
    fun overMinutesTheReflectionsAverageOut() {
        val r = run(900).drop(600)
        val gnss = rms(r.map { it.second - it.first })
        val baro = rms(r.map { it.third!! - it.first })
        assertTrue("barometer %.2f m against GNSS %.2f m".format(baro, gnss), baro < gnss / 3)
    }

    @Test
    fun aClimbShowsAtOnce() {
        // Up 30 m at 0.5 m/s from minute ten (stairs, a slope).
        val r = run(800, climb = { k -> if (k < 600) 0.0 else min(30.0, (k - 600) * 0.5) })
        val during = r.subList(600, 700).map { abs(it.third!! - it.first) }
        assertTrue("worst %.2f m".format(during.max()), during.max() < 3.0)
    }

    @Test
    fun aPressureJumpThatIsntAClimbShowsNoHeightThenStartsOver() {
        // 30 hPa at minute five: a plane's cabin, not 250 m of climbing.
        val r = run(500, jump = { k -> if (k >= 300) 30.0 else 0.0 })
        // Never a height far off: none at all while it doesn't fit, then a fresh start.
        assertTrue(r.all { (truth, _, baro) -> baro == null || abs(baro - truth) < 15.0 })
        assertNull(r[301].third)
        assertTrue(r[400].third != null)
    }

    @Test
    fun aWeatherFrontIsFollowed() {
        // 3 hPa an hour (a front), about 25 m of the barometer's own height.
        val r = run(3_600, weather = { k -> 3.0 * k / 3_600 }).drop(600)
        assertTrue(rms(r.map { it.third!! - it.first }) < 4.0)
    }

    @Test
    fun noBarometerNoHeight() {
        assertNull(BaroHeight().step(0.0, null, 100.0, 2.0))
    }
}
