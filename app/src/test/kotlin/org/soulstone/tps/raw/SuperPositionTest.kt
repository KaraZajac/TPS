package org.soulstone.tps.raw

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

/**
 * The super position on synthetic skies: GPS, Galileo and GLONASS-like satellites seen from a
 * known place (the Statue of Liberty), with a drifting receiver clock, a GLONASS clock offset,
 * code and Doppler noise, and whatever trouble each test adds.
 */
class SuperPositionTest {

    private val home = Geodetic(40.6892, -74.0445, 68.0)

    /** Where the receiver is at second [t], east/north metres from [home]. */
    private fun interface Path {
        fun at(t: Double): Pair<Double, Double>
    }

    private class Sky(
        val path: Path = Path { 0.0 to 0.0 },
        val glonassBias: Double = 1_000.0,
        /** The chip reports the inter-system biases (as Kara's does). */
        val chipBiases: Boolean = true,
        /** The chip steers its clock offset every second (as Kara's does), or leaves it be. */
        val steered: Boolean = true,
        val codeNoise: Double = 2.0,
        val rateNoise: Double = 0.2,
        /** Which satellites to keep at second t; all by default. */
        val keep: (t: Int, Constellation, Int) -> Boolean = { _, _, _ -> true },
        /** Extra metres on a satellite's pseudorange at second t. */
        val fault: (t: Int, Constellation, Int) -> Double = { _, _, _ -> 0.0 },
        /** The receiver clock's own jumps, metres, by second. */
        val clockStep: (t: Int) -> Double = { 0.0 },
        /** A hardware clock discontinuity (the count goes up) from this second. */
        val discontinuityAt: Int? = null,
        val seed: Long = 1,
    )

    private val glonass = List(24) { k ->
        val plane = k / 8
        SyntheticOrbits.gps()[0].copy(
            svid = k + 1, sqrtA = 5050.7, e = 0.001, i0 = Math.toRadians(64.8),
            omega0 = Math.toRadians(120.0 * plane - 160), m0 = Math.toRadians(45.0 * (k % 8) + 15.0 * plane - 180),
        )
    }
    private val constellations = SyntheticOrbits.gps().map { Constellation.GPS to it } +
        SyntheticOrbits.galileo().map { Constellation.GALILEO to it } +
        glonass.map { Constellation.GLONASS to it }

    private fun position(sky: Sky, t: Double): Ecef {
        val (e, n) = sky.path.at(t)
        val lat = home.latitude + n / 111_132.0
        val lon = home.longitude + e / (111_320.0 * cos(Math.toRadians(home.latitude)))
        return Geo.toEcef(Geodetic(lat, lon, home.height))
    }

    /** Runs [seconds] rounds; returns the filter's answer each second and the true position. */
    private fun run(sky: Sky, seconds: Int): List<Pair<FusedFix?, Ecef>> {
        val random = Random(sky.seed)
        val filter = SuperPosition()
        val drift = 43.0                      // m/s, like Kara's phone
        var clockSteps = 0.0
        val fullBias0 = -1_477_146_410_228_455_804L
        val out = ArrayList<Pair<FusedFix?, Ecef>>()
        for (k in 0 until seconds) {
            val t = k.toDouble()
            clockSteps += sky.clockStep(k)
            val receiverClock = 120.0 + drift * t + clockSteps          // metres, against GPS time
            val receiver = position(sky, t)
            val velocity = (position(sky, t + 0.5) - position(sky, t - 0.5))
            val geo = Geo.toGeodetic(receiver)
            val tow = SyntheticOrbits.TOE + 1_800.0 + t
            // What the chip took off its clock this second: all of it (steered) or nothing.
            val steerNs = if (sky.steered) (receiverClock - 120.0) / Pseudoranges.C * 1e9 else 0.0
            val reported = receiverClock - steerNs * 1e-9 * Pseudoranges.C
            val observations = ArrayList<Observation>()
            for ((system, eph) in constellations) {
                if (!sky.keep(k, system, eph.svid)) continue
                var tx = tow - 0.075
                var sat = Orbits.state(eph, tx).position
                repeat(4) {
                    val range = (sat - receiver).norm() + 7.2921151467e-5 * (sat.x * receiver.y - sat.y * receiver.x) / Pseudoranges.C
                    tx = tow - range / Pseudoranges.C
                    sat = Orbits.state(eph, tx).position
                }
                val (_, el) = Geo.azEl(geo, receiver, sat)
                if (Math.toDegrees(el) < 12) continue
                val satVelocity = Orbits.state(eph, tx + 0.5).position - Orbits.state(eph, tx - 0.5).position
                val dts = Orbits.clockError(eph, tx)
                val satDrift = (Orbits.clockError(eph, tx + 0.5) - Orbits.clockError(eph, tx - 0.5)) * Pseudoranges.C
                val d = sat - receiver
                val r = d.norm()
                val range = r + 7.2921151467e-5 * (sat.x * receiver.y - sat.y * receiver.x) / Pseudoranges.C
                val tropo = Atmosphere.troposphere(geo, el)
                val bias = if (system == Constellation.GLONASS) sky.glonassBias else 0.0
                val pseudorange = range + reported + bias - Pseudoranges.C * dts + tropo +
                    sky.codeNoise * random.nextGaussian() + sky.fault(k, system, eph.svid)
                val closing = ((satVelocity.x - velocity.x) * d.x + (satVelocity.y - velocity.y) * d.y + (satVelocity.z - velocity.z) * d.z) / r
                observations += Observation(
                    system, eph.svid, sat, dts, pseudorange, 3.0,
                    carrierHz = if (system == Constellation.GLONASS) 1_602.0e6 else 1_575.42e6,
                    ionoMeters = 0.0, tropoMeters = tropo,
                    satelliteVelocity = satVelocity, satelliteClockDriftMps = satDrift,
                    rateMps = closing + drift - satDrift + sky.rateNoise * random.nextGaussian(), rateSigmaMps = 0.3,
                    cn0DbHz = 40.0, isbMeters = if (sky.chipBiases) bias else null,
                )
            }
            val whole = floor(steerNs).toLong()
            val clock = RawClock(
                timeNanos = 5_000_000_000_000L + k * 1_000_000_000L,
                fullBiasNanos = fullBias0 + whole,
                biasNanos = steerNs - whole,
                discontinuityCount = if (sky.discontinuityAt != null && k >= sky.discontinuityAt) 8 else 7,
                leapSecond = 18,
            )
            val start = Geo.toEcef(Geodetic(home.latitude + 0.0004, home.longitude - 0.0005, home.height + 30))
            out += filter.step(clock, observations, start, null, tow) to receiver
        }
        return out
    }

    private fun horizontalError(f: FusedFix, truth: Ecef): Double =
        Solver.offset(Geo.toGeodetic(truth), f.ecef).let { hypot(it[0], it[1]) }

    @Test
    fun oneClockForEverySystemAndAMetreOrTwoAtRest() {
        val rounds = run(Sky(), 60)
        val last = rounds.last()
        val f = last.first!!
        assertTrue(f.usable)
        assertEquals(setOf(Constellation.GPS, Constellation.GALILEO, Constellation.GLONASS), f.systems)
        assertTrue("off by ${horizontalError(f, last.second)} m", horizontalError(f, last.second) < 1.5)
        assertEquals(43.0, f.clockDriftMps, 0.5)
        assertTrue("reports ±${f.horizontalAccuracyM} m", f.horizontalAccuracyM < 3.0)
        assertEquals(0, rounds.drop(5).sumOf { it.first!!.rejected })
    }

    @Test
    fun learnsAGlonassOffsetTheChipDoesntReport() {
        val rounds = run(Sky(chipBiases = false), 60)
        val f = rounds.last().first!!
        assertEquals(1_000.0, f.interSystemBiases.getValue(Constellation.GLONASS), 3.0)
        assertTrue(horizontalError(f, rounds.last().second) < 1.5)
    }

    @Test
    fun aSteeredClockAndAFreeOneGiveTheSameAnswer() {
        val steered = run(Sky(steered = true), 30).last()
        val free = run(Sky(steered = false), 30).last()
        assertTrue((steered.first!!.ecef - free.first!!.ecef).norm() < 0.01)
    }

    @Test
    fun twoSatellitesASecondKeepItGoing() {
        // Thirty seconds of open sky, then one GPS and one Galileo satellite at a time.
        val sparse = Sky(keep = { t, c, svid -> t < 30 || (c == Constellation.GPS && svid % 24 == t % 24) || (c == Constellation.GALILEO && svid % 24 == (t * 7) % 24) })
        val rounds = run(sparse, 60)
        val worst = rounds.drop(30).mapNotNull { (f, truth) -> f?.let { horizontalError(it, truth) } }.max()
        assertTrue("worst $worst m", worst < 5.0)
        assertTrue(rounds.last().first!!.usable)
    }

    @Test
    fun aSatelliteThatLiesIsLeftOut() {
        val liar = Sky(fault = { t, c, svid -> if (t >= 20 && c == Constellation.GPS && svid == 3) 300.0 else 0.0 })
        val rounds = run(liar, 60)
        assertTrue(rounds.drop(20).sumOf { it.first!!.rejected } >= 30)
        val f = rounds.last().first!!
        assertTrue("off by ${horizontalError(f, rounds.last().second)} m", horizontalError(f, rounds.last().second) < 2.0)
    }

    @Test
    fun aClockJumpIsTakenUpNotTreatedAsBadSatellites() {
        val rounds = run(Sky(steered = false, clockStep = { t -> if (t == 30) 600.0 else 0.0 }), 60)
        val after = rounds.drop(31)
        assertTrue("the filter restarted", after.all { it.first!!.updates > 25 })
        val worst = after.maxOf { (f, truth) -> horizontalError(f!!, truth) }
        assertTrue("worst $worst m", worst < 3.0)
    }

    @Test
    fun aClockDiscontinuityStartsAfresh() {
        val rounds = run(Sky(discontinuityAt = 30, clockStep = { t -> if (t == 30) 25_000.0 else 0.0 }), 60)
        assertTrue(rounds[31].first!!.updates < 3)
        val f = rounds.last().first!!
        assertTrue(f.usable)
        assertTrue(horizontalError(f, rounds.last().second) < 2.0)
    }

    @Test
    fun walkingEastShowsInSpeedAndBearing() {
        val rounds = run(Sky(path = { t -> 1.4 * t to 0.0 }), 60)
        val f = rounds.last().first!!
        assertEquals(1.4, f.speedMps, 0.3)
        val bearing = f.bearingDeg
        assertNotNull(bearing)
        assertEquals(90.0, bearing!!, 15.0)
        assertTrue("off by ${horizontalError(f, rounds.last().second)} m", horizontalError(f, rounds.last().second) < 3.0)
    }

    @Test
    fun drivingThroughATurnKeepsUp() {
        // 15 m/s north for 30 s, a 90° turn over 6 s, then east.
        val drive = Path { t ->
            when {
                t <= 30 -> 0.0 to 15.0 * t
                t <= 36 -> {
                    val a = (t - 30) / 6 * Math.PI / 2
                    val radius = 15.0 * 6 / (Math.PI / 2)
                    radius * (1 - cos(a)) to 450.0 + radius * sin(a)
                }
                else -> {
                    val radius = 15.0 * 6 / (Math.PI / 2)
                    radius + 15.0 * (t - 36) to 450.0 + radius
                }
            }
        }
        val rounds = run(Sky(path = drive), 60)
        val worst = rounds.drop(10).maxOf { (f, truth) -> horizontalError(f!!, truth) }
        assertTrue("worst $worst m", worst < 10.0)
        val f = rounds.last().first!!
        assertEquals(15.0, f.speedMps, 1.0)
        assertTrue(abs(f.bearingDeg!! - 90.0) < 5.0)
    }
}
