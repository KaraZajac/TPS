package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
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
    fun robustWeightsPullLessOnBiasedSatellites() {
        // Every fourth satellite 8 m long from second 30 (reflections): inside the gate.
        val biased = Sky(fault = { t, _, svid -> if (t >= 30 && svid % 4 == 1) 8.0 else 0.0 })
        fun mean(sky: Sky, robust: Boolean) = SyntheticSky.filter(sky, 180, robust = robust).drop(60).map { (f, t) -> horizontalError(f!!, t) }.average()
        val plain = mean(biased, false)
        val robust = mean(biased, true)
        assertTrue("robust %.2f m against plain %.2f m".format(robust, plain), robust < plain * 0.9)
        // On a clean sky they cost nothing.
        assertTrue(mean(Sky(), true) <= mean(Sky(), false) * 1.1)
    }

    @Test
    fun aPhoneAtRestHoldsStillAndItsHeightSettles() {
        fun heightRms(m: Motion) = SyntheticSky.filter(Sky(), 300, motion = { m }).drop(60)
            .map { (f, t) -> Solver.offset(Geo.toGeodetic(t), f!!.ecef)[2] }.let { v -> sqrt(v.sumOf { it * it } / v.size) }
        val unknown = heightRms(Motion.UNKNOWN)
        val still = heightRms(Motion.STILL)
        assertTrue("at rest %.2f m against %.2f m".format(still, unknown), still < unknown / 2)
        val last = SyntheticSky.filter(Sky(), 120, motion = { Motion.STILL }).last().first!!
        assertTrue(last.still)
        assertEquals(0.0, last.speedMps, 0.05)
    }

    @Test
    fun aWholeSkyTakeoverIsHeldOffAtRest() {
        val r = SyntheticSky.filter(Sky(spoof = { t, _ -> if (t >= 60) 5_000.0 to 0.0 else null }), 240, motion = { Motion.STILL })
        val (f, truth) = r.last()
        assertEquals(Suspect.DRAGGED, f!!.suspect)
        assertTrue(f.usable)
        assertTrue("off by ${horizontalError(f, truth)} m", horizontalError(f, truth) < 5.0)
        assertEquals(5_000.0, f.suspectMoveM, 50.0)
    }

    @Test
    fun withoutMotionSensorsATakeoverIsHeldUntilItCouldBeReached() {
        val r = SyntheticSky.filter(Sky(spoof = { t, _ -> if (t >= 60) 5_000.0 to 0.0 else null }), 240)
        val (held, truth) = r[100]
        assertEquals(Suspect.OUT_OF_REACH, held!!.suspect)
        assertTrue("off by ${horizontalError(held, truth)} m", horizontalError(held, truth) < 10.0)
        // 5 km is within 50 m/s after about 100 s: then TPS takes the signals' place.
        val last = r.last().first!!
        assertEquals(null, last.suspect)
        assertEquals(1, last.released)
        assertEquals(5_000.0, last.lastReleaseMoveM!!, 100.0)
    }

    @Test
    fun aDragWhileThePhoneLiesStillIsHeldOff() {
        // Every signal timed for a place moving east at 1 m/s from second 60, Doppler to match.
        val r = SyntheticSky.filter(Sky(spoof = { t, _ -> if (t >= 60) (t - 60) * 1.0 to 0.0 else null }), 300, motion = { Motion.STILL })
        // Pulled at most about the drag margin before going back to rest.
        val worst = r.drop(60).maxOf { (f, t) -> horizontalError(f!!, t) }
        assertTrue("pulled $worst m", worst <= 30.0)
        val (f, truth) = r.last()
        assertEquals(Suspect.DRAGGED, f!!.suspect)
        assertTrue(horizontalError(f, truth) < 5.0)
        assertEquals(240.0, f.suspectMoveM, 20.0)
    }

    @Test
    fun aSpooferThatStopsEndsTheHold() {
        // A minute of a 5 km takeover, then the honest sky again; the phone at rest throughout.
        val r = SyntheticSky.filter(Sky(spoof = { t, _ -> if (t in 60 until 120) 5_000.0 to 0.0 else null }), 180, motion = { Motion.STILL })
        val (f, truth) = r.last()
        assertEquals(null, f!!.suspect)
        assertEquals(0, f.released)
        assertTrue(horizontalError(f, truth) < 3.0)
    }

    @Test
    fun pickedUpDuringAHoldItLetsGoOnceThePlaceIsWithinReach() {
        val r = SyntheticSky.filter(
            Sky(spoof = { t, _ -> if (t >= 60) 5_000.0 to 0.0 else null }), 300,
            motion = { k -> if (k < 150) Motion.STILL else Motion.MOVING },
        )
        assertEquals(Suspect.DRAGGED, r[149].first!!.suspect)
        assertEquals(1, r.last().first!!.released)
    }

    @Test
    fun aSmoothRideIsNotTakenForRest() {
        // 20 m/s east while the motion sensors feel nothing (a very smooth ride).
        val r = SyntheticSky.filter(Sky(path = Path { t -> 20.0 * t to 0.0 }), 120, motion = { Motion.STILL })
        assertTrue(r.none { it.first?.suspect != null || it.first?.still == true })
        assertTrue(r.drop(20).all { (f, t) -> horizontalError(f!!, t) < 3.0 })
    }

    @Test
    fun atRestThePositionHoldsThroughALoss() {
        val loss = Sky(keep = { t, _, _ -> t < 60 || t >= 150 })
        val still = SyntheticSky.filter(loss, 200, motion = { Motion.STILL })
        assertTrue((60 until 150).all { still[it].first?.usable == true })
        assertTrue(horizontalError(still[149].first!!, still[149].second) < 2.0)
        // Without the sensors it lets go after 30 s, as before.
        assertTrue(SyntheticSky.filter(loss, 200)[100].first?.usable != true)
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
