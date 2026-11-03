package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

/** The super position on synthetic skies ([SyntheticSky]), each test adding its own trouble. */
class SuperPositionTest {

    private fun horizontalError(f: FusedFix, truth: Ecef): Double =
        Solver.offset(Geo.toGeodetic(truth), f.ecef).let { hypot(it[0], it[1]) }

    @Test
    fun oneClockForEverySystemAndAMetreOrTwoAtRest() {
        val rounds = SyntheticSky.filter(Sky(), 60)
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
        val rounds = SyntheticSky.filter(Sky(chipBiases = false), 60)
        val f = rounds.last().first!!
        assertEquals(1_000.0, f.interSystemBiases.getValue(Constellation.GLONASS), 3.0)
        assertTrue(horizontalError(f, rounds.last().second) < 1.5)
    }

    @Test
    fun aSteeredClockAndAFreeOneGiveTheSameAnswer() {
        val steered = SyntheticSky.filter(Sky(steered = true), 30).last()
        val free = SyntheticSky.filter(Sky(steered = false), 30).last()
        assertTrue((steered.first!!.ecef - free.first!!.ecef).norm() < 0.01)
    }

    @Test
    fun twoSatellitesASecondKeepItGoing() {
        // Thirty seconds of open sky, then one GPS and one Galileo satellite at a time.
        val sparse = Sky(keep = { t, c, svid -> t < 30 || (c == Constellation.GPS && svid % 24 == t % 24) || (c == Constellation.GALILEO && svid % 24 == (t * 7) % 24) })
        val rounds = SyntheticSky.filter(sparse, 60)
        val worst = rounds.drop(30).mapNotNull { (f, truth) -> f?.let { horizontalError(it, truth) } }.max()
        assertTrue("worst $worst m", worst < 5.0)
        assertTrue(rounds.last().first!!.usable)
    }

    @Test
    fun aSatelliteThatLiesIsLeftOut() {
        val liar = Sky(fault = { t, c, svid -> if (t >= 20 && c == Constellation.GPS && svid == 3) 300.0 else 0.0 })
        val rounds = SyntheticSky.filter(liar, 60)
        assertTrue(rounds.drop(20).sumOf { it.first!!.rejected } >= 30)
        val f = rounds.last().first!!
        assertTrue("off by ${horizontalError(f, rounds.last().second)} m", horizontalError(f, rounds.last().second) < 2.0)
    }

    @Test
    fun aClockJumpIsTakenUpNotTreatedAsBadSatellites() {
        val rounds = SyntheticSky.filter(Sky(steered = false, clockStep = { t -> if (t == 30) 600.0 else 0.0 }), 60)
        val after = rounds.drop(31)
        assertTrue("the filter restarted", after.all { it.first!!.updates > 25 })
        val worst = after.maxOf { (f, truth) -> horizontalError(f!!, truth) }
        assertTrue("worst $worst m", worst < 3.0)
    }

    @Test
    fun aClockResetKeepsThePosition() {
        // The chip reports a hardware clock reset and the clock lands somewhere else entirely.
        val rounds = SyntheticSky.filter(Sky(discontinuityAt = 30, clockStep = { t -> if (t == 30) 25_000.0 else 0.0 }), 60)
        assertTrue("restarted", rounds.drop(30).all { it.first!!.restarts == 0 && it.first!!.usable })
        val worst = rounds.drop(30).maxOf { (f, truth) -> horizontalError(f!!, truth) }
        assertTrue("worst $worst m", worst < 3.0)
    }

    @Test
    fun itWaitsToSettleThenHoldsThroughALoss() {
        // Open sky, then nothing at all for 20 s (jammed, or a tunnel), then the sky again.
        val gap = Sky(keep = { t, _, _ -> t < 40 || t >= 60 })
        val rounds = SyntheticSky.filter(gap, 80)
        assertTrue("shown too early", rounds.take(FusedFix.SETTLE_UPDATES).none { it.first?.usable == true })
        assertTrue(rounds[30].first!!.usable)
        // Holding: still shown, still close, honestly less sure.
        val held = rounds[55].first!!
        assertTrue(held.usable && held.holding)
        assertTrue("held ${horizontalError(held, rounds[55].second)} m off", horizontalError(held, rounds[55].second) < 5.0)
        assertTrue(held.horizontalAccuracyM > rounds[39].first!!.horizontalAccuracyM)
        // And back on measurements, no restart needed.
        assertTrue(rounds.last().first!!.restarts == 0 && !rounds.last().first!!.holding)
    }

    @Test
    fun walkingEastShowsInSpeedAndBearing() {
        val rounds = SyntheticSky.filter(Sky(path = { t -> 1.4 * t to 0.0 }), 60)
        val f = rounds.last().first!!
        assertEquals(1.4, f.speedMps, 0.3)
        val bearing = f.bearingDeg
        assertNotNull(bearing)
        assertEquals(90.0, bearing!!, 15.0)
        assertTrue("off by ${horizontalError(f, rounds.last().second)} m", horizontalError(f, rounds.last().second) < 3.0)
    }

    @Test
    fun aDopplerBiasShowsInTheCodeAgainstDopplerCheck() {
        // Galileo's Doppler reads 0.5 m/s high; GPS's and GLONASS's are honest.
        val f = SyntheticSky.filter(Sky(rateBias = { if (it == Constellation.GALILEO) 0.5 else 0.0 }), 180).last().first!!
        assertEquals(-0.5, f.codeMinusDopplerMps.getValue(Constellation.GALILEO), 0.05)
        assertEquals(0.0, f.codeMinusDopplerMps.getValue(Constellation.GPS), 0.05)
        assertEquals(0.0, f.codeMinusDopplerMps.getValue(Constellation.GLONASS), 0.05)
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
        val rounds = SyntheticSky.filter(Sky(path = drive), 60)
        val worst = rounds.drop(10).maxOf { (f, truth) -> horizontalError(f!!, truth) }
        assertTrue("worst $worst m", worst < 10.0)
        val f = rounds.last().first!!
        assertEquals(15.0, f.speedMps, 1.0)
        assertTrue(abs(f.bearingDeg!! - 90.0) < 5.0)
    }
}
