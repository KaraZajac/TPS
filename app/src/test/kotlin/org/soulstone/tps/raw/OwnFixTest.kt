package org.soulstone.tps.raw

import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

/**
 * End to end: orbits are encoded into broadcast navigation messages, the measurements a
 * phone would report are synthesised for a known place and clock error, and TPS's own
 * fix has to land on that place. This exercises the bit layouts, the scale factors, the
 * time scales, the satellite clock model and the solver together.
 */
class OwnFixTest {

    private val truth = Geodetic(40.6892, -74.0445, 68.0)
    private val truthEcef = Geo.toEcef(truth)
    private val week = 2400L
    private val receiveTow = SyntheticOrbits.TOE + 1_800.0      // half an hour after the orbits' epoch
    private val receiverClock = 0.000_731                        // the phone's clock is 731 µs off

    /** Iterates the transmit time; returns the satellite's own time stamp (what the chip reports) and elevation. */
    private fun signal(eph: KeplerEphemeris): Pair<Double, Double>? {
        var tx = receiveTow - 0.07
        var sat = Orbits.state(eph, tx).position
        repeat(6) {
            val range = (sat - truthEcef).norm() + 7.2921151467e-5 * (sat.x * truthEcef.y - sat.y * truthEcef.x) / Pseudoranges.C
            tx = receiveTow - range / Pseudoranges.C
            sat = Orbits.state(eph, tx).position
        }
        val el = Math.toDegrees(Geo.azEl(truth, truthEcef, sat).second)
        if (el < 15) return null
        val tropo = Atmosphere.troposphere(truth, Math.toRadians(el))
        // The satellite stamps its own time; the troposphere delays the signal on the way down.
        val svTime = tx + Orbits.clockError(eph, tx) - tropo / Pseudoranges.C
        return svTime to el
    }

    private fun epoch(sats: List<Pair<KeplerEphemeris, Double>>): RawEpoch {
        val measuredReceive = receiveTow + receiverClock
        val ns = measuredReceive * 1e9
        val whole = floor(ns).toLong()
        val timeNanos = 9_000_000_000_000L
        val clock = RawClock(
            timeNanos = timeNanos,
            fullBiasNanos = timeNanos - (week * Pseudoranges.WEEK_NS + whole),
            biasNanos = -(ns - whole),
            leapSecond = 18,
        )
        val measurements = sats.map { (eph, svTime) ->
            RawMeasurement(
                constellation = eph.constellation, svid = eph.svid, timeOffsetNanos = 0.0,
                state = MeasurementState.CODE_LOCK or MeasurementState.TOW_DECODED,
                receivedSvTimeNanos = Math.round(svTime * 1e9), receivedSvTimeUncertaintyNanos = 10,
                cn0DbHz = 42.0, pseudorangeRateMps = 0.0, carrierHz = 1575.42e6,
            )
        }
        return RawEpoch(clock, measurements)
    }

    @Test
    fun gpsAndGalileoFromTheirOwnBroadcasts() {
        val engine = OwnFixEngine()
        val visible = ArrayList<Pair<KeplerEphemeris, Double>>()
        for (eph in SyntheticOrbits.gps()) {
            val (svTime, _) = signal(eph) ?: continue
            LnavEncoder.subframes(eph, tow = 345_000).forEach { engine.accept(NavMessage(NavType.GPS_L1CA, eph.svid, 0, 0, 1, it)) }
            visible += eph to svTime
        }
        for (eph in SyntheticOrbits.galileo()) {
            val (svTime, _) = signal(eph) ?: continue
            InavEncoder.pages(eph).forEach { engine.accept(NavMessage(NavType.GAL_I, eph.svid, 0, 0, 1, it)) }
            visible += eph to svTime
        }
        val gpsCount = visible.count { it.first.constellation == Constellation.GPS }
        val galCount = visible.count { it.first.constellation == Constellation.GALILEO }
        assertTrue("visible GPS $gpsCount, Galileo $galCount", gpsCount >= 5 && galCount >= 5)

        // Start 40 m away, as if from a poor chip fix.
        val start = Geodetic(truth.latitude + 0.0003, truth.longitude - 0.0002, truth.height + 10)
        val report = engine.solve(epoch(visible), start, sky = null)

        assertEquals(mapOf(Constellation.GPS to gpsCount, Constellation.GALILEO to galCount), report.orbits)
        assertEquals(0, report.navRejected)
        for (system in listOf(Constellation.GPS, Constellation.GALILEO)) {
            val r = report.systems.first { it.constellation == system }
            assertNotNull("$system solution", r.solution)
            val s = r.solution!!
            val error = (s.ecef - truthEcef).norm()
            assertTrue("$system off by $error m", error < 0.5)
            assertEquals("$system clock", receiverClock * Pseudoranges.C, s.clocks.getValue(system), 0.5)
        }
        val combined = report.combined!!
        println("own fixes: " + report.systems.joinToString { "${it.constellation} ${it.withOrbit} sats ${"%.3f".format((it.solution!!.ecef - truthEcef).norm())} m" } + ", combined ${"%.3f".format((combined.ecef - truthEcef).norm())} m")
        assertTrue("combined off by ${(combined.ecef - truthEcef).norm()} m", (combined.ecef - truthEcef).norm() < 0.5)
        assertEquals(gpsCount + galCount, combined.satellites.size)
    }

    @Test
    fun anOrbitThatDisagreesWithTheChipIsDropped() {
        val engine = OwnFixEngine()
        val eph = SyntheticOrbits.gps().first { signal(it) != null }
        LnavEncoder.subframes(eph, tow = 345_000).forEach { engine.accept(NavMessage(NavType.GPS_L1CA, eph.svid, 0, 0, 1, it)) }
        val (svTime, _) = signal(eph)!!
        // The chip says the satellite is somewhere else entirely.
        val sky = org.soulstone.tps.gnss.SkySnapshot.of(
            listOf(org.soulstone.tps.gnss.Signal(Constellation.GPS, eph.svid, 40f, 5f, 10f, usedInFix = true))
        )
        val report = engine.solve(epoch(listOf(eph to svTime)), truth, sky)
        assertEquals(1, report.orbitsDisagreeing)
        assertEquals(0, report.orbits[Constellation.GPS] ?: 0)
    }
}
