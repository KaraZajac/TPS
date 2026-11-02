package org.soulstone.tps.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

class PseudorangeTest {

    private val week = 2400L
    private val towNs = 345_678_000_000_000L    // receive time: 345678 s into the week

    /** A clock whose GPS time is exactly [towNs] into [week]. */
    private fun clock(leap: Int? = 18) = RawClock(
        timeNanos = 5_000_000_000_000L,
        fullBiasNanos = 5_000_000_000_000L - (week * Pseudoranges.WEEK_NS + towNs),
        biasNanos = 0.25,
        leapSecond = leap,
    )

    private fun measurement(c: Constellation, svTimeNs: Long, state: Int) = RawMeasurement(
        constellation = c, svid = 7, timeOffsetNanos = 0.0, state = state,
        receivedSvTimeNanos = svTimeNs, receivedSvTimeUncertaintyNanos = 20, cn0DbHz = 40.0, pseudorangeRateMps = 0.0,
    )

    @Test
    fun gps() {
        val travel = 72_000_000L    // 72 ms
        val r = Pseudoranges.compute(clock(), measurement(Constellation.GPS, towNs - travel, MeasurementState.TOW_DECODED))!!
        // The 0.25 ns bias makes the receive time 0.25 ns earlier.
        assertEquals((travel - 0.25) * 1e-9 * Pseudoranges.C, r.meters, 1e-6)
        assertEquals(20e-9 * Pseudoranges.C, r.sigmaMeters, 1e-9)
        assertEquals((towNs - travel) * 1e-9, r.transmitTimeSeconds, 1e-9)
    }

    @Test
    fun beidouIsFourteenSecondsBehind() {
        val travel = 70_000_000L
        val bdt = towNs - 14_000_000_000L
        val r = Pseudoranges.compute(clock(), measurement(Constellation.BEIDOU, bdt - travel, MeasurementState.TOW_KNOWN))!!
        assertEquals((travel - 0.25) * 1e-9 * Pseudoranges.C, r.meters, 1e-6)
    }

    @Test
    fun glonassIsMoscowTimeOfDay() {
        val travel = 68_000_000L
        val gpsOfDay = Math.floorMod(towNs, Pseudoranges.DAY_NS)
        val glo = Math.floorMod(gpsOfDay + 3 * 3_600_000_000_000L - 18_000_000_000L, Pseudoranges.DAY_NS)
        val r = Pseudoranges.compute(clock(), measurement(Constellation.GLONASS, glo - travel, MeasurementState.GLO_TOD_DECODED))!!
        assertEquals((travel - 0.25) * 1e-9 * Pseudoranges.C, r.meters, 1e-6)
    }

    @Test
    fun acrossTheWeekBoundary() {
        // Received 10 ms into a new week, sent 62 ms earlier, in the old one.
        val c = RawClock(timeNanos = 1_000_000_000L, fullBiasNanos = 1_000_000_000L - (week * Pseudoranges.WEEK_NS + 10_000_000L))
        val r = Pseudoranges.compute(c, measurement(Constellation.GPS, Pseudoranges.WEEK_NS - 52_000_000L, MeasurementState.TOW_DECODED))!!
        assertEquals(0.062 * Pseudoranges.C, r.meters, 1e-6)
    }

    @Test
    fun unusableMeasurements() {
        val ok = measurement(Constellation.GPS, towNs - 70_000_000L, MeasurementState.TOW_DECODED)
        assertNull("no time of week", Pseudoranges.compute(clock(), ok.copy(state = MeasurementState.CODE_LOCK)))
        assertNull("no GPS time", Pseudoranges.compute(clock().copy(fullBiasNanos = null), ok))
        assertNull("too uncertain", Pseudoranges.compute(clock(), ok.copy(receivedSvTimeUncertaintyNanos = 5_000)))
        assertNull("implausible travel", Pseudoranges.compute(clock(), ok.copy(receivedSvTimeNanos = towNs - 1_000_000L)))
        assertNull("SBAS", Pseudoranges.compute(clock(), ok.copy(constellation = Constellation.SBAS)))
    }

    @Test
    fun stateBitsMatchAndroid() {
        assertEquals(android.location.GnssMeasurement.STATE_TOW_DECODED, MeasurementState.TOW_DECODED)
        assertEquals(android.location.GnssMeasurement.STATE_TOW_KNOWN, MeasurementState.TOW_KNOWN)
        assertEquals(android.location.GnssMeasurement.STATE_GLO_TOD_DECODED, MeasurementState.GLO_TOD_DECODED)
        assertEquals(android.location.GnssMeasurement.STATE_GLO_TOD_KNOWN, MeasurementState.GLO_TOD_KNOWN)
        assertEquals(android.location.GnssNavigationMessage.TYPE_GPS_L1CA, NavType.GPS_L1CA)
        assertEquals(android.location.GnssNavigationMessage.TYPE_GAL_I, NavType.GAL_I)
        assertEquals(android.location.GnssNavigationMessage.TYPE_GLO_L1CA, NavType.GLO_L1CA)
        assertEquals(android.location.GnssNavigationMessage.TYPE_BDS_D1, NavType.BDS_D1)
    }
}
