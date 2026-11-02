package org.soulstone.tps.raw

import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.Signal
import org.soulstone.tps.gnss.SkySnapshot

/**
 * Own fixes on a phone that sends no navigation messages but does send the chip's satellite
 * positions (as Kara's does): the measurements a phone would report are synthesised for a
 * known place, each with the chip's position and clock for its satellite, and TPS's own fix
 * per system, GLONASS included, has to land on that place.
 */
class OwnFixChipTest {

    private val truth = Geodetic(40.6892, -74.0445, 68.0)
    private val truthEcef = Geo.toEcef(truth)
    private val week = 2400L
    private val receiveTow = SyntheticOrbits.TOE + 1_800.0
    private val receiverClock = 0.000_731
    private val leapSeconds = 18

    /** GLONASS-like orbits (25,510 km, 64.8°), only to place satellites: the chip's positions are what TPS uses. */
    private fun glonass(): List<KeplerEphemeris> = List(24) { k ->
        val plane = k / 8
        SyntheticOrbits.gps()[0].copy(
            constellation = Constellation.GPS, svid = k + 1, sqrtA = 5050.7, e = 0.001,
            i0 = Math.toRadians(64.8), omega0 = Math.toRadians(120.0 * plane - 160), m0 = Math.toRadians(45.0 * (k % 8) + 15.0 * plane - 180),
        )
    }

    private class Sat(val system: Constellation, val svid: Int, val svTimeNanos: Long, val pvt: ChipPvt, val az: Double, val el: Double)

    /** The signal from [eph] as the chip reports it, with the chip's position and clock for it; null below 15°. */
    private fun sat(system: Constellation, eph: KeplerEphemeris, source: Int = EphemerisSource.SERVER_LONG_TERM): Sat? {
        var tx = receiveTow - 0.07
        var state = Orbits.state(eph, tx)
        repeat(6) {
            val sat = state.position
            val range = (sat - truthEcef).norm() + 7.2921151467e-5 * (sat.x * truthEcef.y - sat.y * truthEcef.x) / Pseudoranges.C
            tx = receiveTow - range / Pseudoranges.C
            state = Orbits.state(eph, tx)
        }
        val (az, el) = Geo.azEl(truth, truthEcef, state.position)
        if (Math.toDegrees(el) < 15) return null
        val tropo = Atmosphere.troposphere(truth, el)
        val clock = Orbits.clockError(eph, tx)
        // The satellite stamps its own time; the troposphere delays the signal on the way down.
        val svTowSeconds = tx + clock - tropo / Pseudoranges.C
        val svTimeNanos = if (system == Constellation.GLONASS) {
            // GLONASS stamps Moscow time of day: GPS time, less the leap seconds, plus three hours.
            Math.floorMod(Math.round(svTowSeconds * 1e9) + Pseudoranges.GLO_OFFSET_NS - leapSeconds * 1_000_000_000L, Pseudoranges.DAY_NS)
        } else {
            Math.round(svTowSeconds * 1e9)
        }
        val pvt = ChipPvt(
            flags = ChipPvtParser.HAS_POSITION_VELOCITY_CLOCK or ChipPvtParser.HAS_IONO or ChipPvtParser.HAS_TROPO,
            position = state.position, positionUreMeters = 1.0, velocity = Ecef(0.0, 0.0, 0.0),
            hardwareCodeBiasMeters = 0.0, timeCorrectionMeters = clock * Pseudoranges.C, clockDriftMps = 0.0,
            ionoMeters = 0.0, tropoMeters = tropo, ephemerisSource = source,
        )
        return Sat(system, eph.svid, svTimeNanos, pvt, Math.toDegrees(az), Math.toDegrees(el))
    }

    private fun epoch(sats: List<Sat>): RawEpoch {
        val ns = (receiveTow + receiverClock) * 1e9
        val whole = floor(ns).toLong()
        val timeNanos = 9_000_000_000_000L
        val clock = RawClock(
            timeNanos = timeNanos,
            fullBiasNanos = timeNanos - (week * Pseudoranges.WEEK_NS + whole),
            biasNanos = -(ns - whole),
            leapSecond = leapSeconds,
        )
        return RawEpoch(clock, sats.map { s ->
            val glonass = s.system == Constellation.GLONASS
            RawMeasurement(
                constellation = s.system, svid = s.svid, timeOffsetNanos = 0.0,
                state = MeasurementState.CODE_LOCK or if (glonass) MeasurementState.GLO_TOD_DECODED else MeasurementState.TOW_DECODED,
                receivedSvTimeNanos = s.svTimeNanos, receivedSvTimeUncertaintyNanos = 10,
                cn0DbHz = 40.0, pseudorangeRateMps = 0.0, carrierHz = if (glonass) 1_602.5625e6 else 1575.42e6,
                pvt = s.pvt,
            )
        })
    }

    /** The chip's own sky view; [moved] is put 20° higher or lower than it really is. */
    private fun sky(sats: List<Sat>, moved: Sat? = null) = SkySnapshot.of(sats.map { s ->
        val el = if (s === moved) (if (s.el > 50) s.el - 20 else s.el + 20) else s.el
        Signal(s.system, s.svid, 40f, el.toFloat(), s.az.toFloat(), usedInFix = true)
    })

    private fun visible(): List<Sat> =
        SyntheticOrbits.gps().mapNotNull { sat(Constellation.GPS, it) } +
            SyntheticOrbits.galileo().mapNotNull { sat(Constellation.GALILEO, it) } +
            glonass().mapNotNull { sat(Constellation.GLONASS, it) }

    @Test
    fun theChipsPositionsAloneGiveAFixPerSystemGlonassIncluded() {
        val sats = visible()
        val counts = sats.groupingBy { it.system }.eachCount()
        assertTrue("visible $counts", counts.values.all { it >= 5 } && counts.size == 3)

        val start = Geodetic(truth.latitude + 0.0003, truth.longitude - 0.0002, truth.height + 10)
        val report = OwnFixEngine().solve(epoch(sats), start, sky(sats))

        assertTrue("no orbits decoded", report.orbits.isEmpty())
        assertEquals(sats.size, report.chipOrbits)
        assertEquals(0, report.chipOrbitsDropped)
        assertEquals(mapOf(EphemerisSource.SERVER_LONG_TERM to sats.size), report.chipOrbitSources)
        for (system in listOf(Constellation.GPS, Constellation.GALILEO, Constellation.GLONASS)) {
            val r = report.systems.first { it.constellation == system }
            assertEquals("$system from the chip", r.withOrbit, r.fromChip)
            assertNotNull("$system solution", r.solution)
            val error = (r.solution!!.ecef - truthEcef).norm()
            assertTrue("$system off by $error m", error < 0.5)
            assertEquals("$system clock", receiverClock * Pseudoranges.C, r.solution.clocks.getValue(system), 0.5)
        }
        val combined = report.combined!!
        println("own fixes from the chip's positions: " + report.systems.joinToString { "${it.constellation} ${it.withOrbit} sats ${"%.3f".format((it.solution!!.ecef - truthEcef).norm())} m" } + ", combined ${"%.3f".format((combined.ecef - truthEcef).norm())} m")
        assertTrue((combined.ecef - truthEcef).norm() < 0.5)
        assertEquals(sats.size, combined.satellites.size)
    }

    @Test
    fun anOrbitTpsDecodedWinsOverTheChipsPosition() {
        val engine = OwnFixEngine()
        val sats = ArrayList<Sat>()
        for (eph in SyntheticOrbits.gps()) {
            val s = sat(Constellation.GPS, eph) ?: continue
            LnavEncoder.subframes(eph, tow = 345_000).forEach { engine.accept(NavMessage(NavType.GPS_L1CA, eph.svid, 0, 0, 1, it)) }
            // The chip's position is 2 km off; TPS's decoded orbit must be the one used.
            sats += Sat(s.system, s.svid, s.svTimeNanos, s.pvt.copy(position = Ecef(s.pvt.position.x + 2_000.0, s.pvt.position.y, s.pvt.position.z)), s.az, s.el)
        }
        sats += SyntheticOrbits.galileo().mapNotNull { sat(Constellation.GALILEO, it, EphemerisSource.DEMODULATED) }

        val report = engine.solve(epoch(sats), truth, sky = null)
        val gps = report.systems.first { it.constellation == Constellation.GPS }
        val galileo = report.systems.first { it.constellation == Constellation.GALILEO }
        assertEquals(0, gps.fromChip)
        assertEquals(galileo.withOrbit, galileo.fromChip)
        assertTrue((gps.solution!!.ecef - truthEcef).norm() < 0.5)
        assertTrue((report.combined!!.ecef - truthEcef).norm() < 0.5)
        assertEquals(mapOf(EphemerisSource.DEMODULATED to galileo.withOrbit), report.chipOrbitSources)
    }

    @Test
    fun aChipPositionNotWhereTheChipSeesTheSatelliteIsLeftOut() {
        val sats = visible()
        val moved = sats.first { it.system == Constellation.GALILEO }
        val report = OwnFixEngine().solve(epoch(sats), truth, sky(sats, moved))
        assertEquals(1, report.chipOrbitsDropped)
        assertEquals(sats.size - 1, report.chipOrbits)
    }

    @Test
    fun impossibleChipPositionsAreLeftOut() {
        val sats = visible()
        val bad = sats.first()
        val broken = sats.map { if (it === bad) Sat(it.system, it.svid, it.svTimeNanos, it.pvt.copy(position = Ecef(1.0e6, 0.0, 0.0)), it.az, it.el) else it }
        val report = OwnFixEngine().solve(epoch(broken), truth, sky = null)
        assertEquals(1, report.chipOrbitsDropped)
        assertTrue((report.combined!!.ecef - truthEcef).norm() < 0.5)
    }
}
