package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyTest {

    private val l1 = 1575.42e6
    private val l5 = 1176.45e6

    private fun sig(
        c: Constellation,
        svid: Int,
        cn0: Float,
        used: Boolean = false,
        hz: Double? = l1,
        el: Float = 45f,
        az: Float = 90f,
    ) = Signal(c, svid, cn0, el, az, used, carrierHz = hz)

    @Test
    fun dualBandSatelliteMergesIntoOne() {
        val sky = SkySnapshot.of(
            listOf(
                sig(Constellation.GPS, 18, 44f, used = true),
                sig(Constellation.GPS, 18, 41f, used = true, hz = l5),
            )
        )
        assertEquals(1, sky.satellites.size)
        val s = sky.satellites.single()
        assertEquals("G18", s.label)
        assertEquals(listOf("L1", "L5"), s.bands)
        assertEquals(44f, s.cn0DbHz)
        assertEquals(SatState.USED, s.state)
        assertTrue(s.dualFrequency)
        assertTrue(s.dualFrequencyInUse)
        assertEquals(FrequencyMode.DUAL_IN_USE, sky.frequencyMode)
        assertEquals(1, sky.inView)
        assertEquals(1, sky.used)
    }

    @Test
    fun sameSvidInDifferentSystemsStaysSeparate() {
        val sky = SkySnapshot.of(listOf(sig(Constellation.GPS, 5, 40f), sig(Constellation.GALILEO, 5, 40f)))
        assertEquals(2, sky.satellites.size)
    }

    @Test
    fun predictedSatellitesAreListedButNotInView() {
        val sky = SkySnapshot.of(
            listOf(
                sig(Constellation.GPS, 3, 0f),
                sig(Constellation.GPS, 2, 25f),
                sig(Constellation.GPS, 1, 30f, used = true),
            )
        )
        assertEquals(listOf(SatState.USED, SatState.TRACKED, SatState.PREDICTED), sky.satellites.map { it.state })
        assertEquals(2, sky.inView)
        assertEquals(1, sky.used)
        val gps = sky.summary(Constellation.GPS)!!
        assertEquals(2, gps.inView)
        assertEquals(1, gps.used)
        assertEquals(1, gps.predicted)
    }

    @Test
    fun dualTrackedButUsedOnOneBand() {
        val sky = SkySnapshot.of(
            listOf(
                sig(Constellation.GALILEO, 11, 40f, used = true),
                sig(Constellation.GALILEO, 11, 35f, used = false, hz = l5),
            )
        )
        val s = sky.satellites.single()
        assertEquals(listOf("E1", "E5a"), s.bands)
        assertTrue(s.dualFrequency)
        assertFalse(s.dualFrequencyInUse)
        assertEquals(FrequencyMode.DUAL_TRACKED, sky.frequencyMode)
    }

    @Test
    fun noCarrierFrequenciesMeansUnknownMode() {
        val sky = SkySnapshot.of(listOf(sig(Constellation.GPS, 1, 40f, used = true, hz = null)))
        assertEquals(FrequencyMode.UNKNOWN, sky.frequencyMode)
        assertEquals(listOf("?"), sky.satellites.single().bands)
        assertTrue(sky.summary(Constellation.GPS)!!.bands.isEmpty())
    }

    @Test
    fun aBandTheSystemDoesntHaveIsUnknown() {
        // What the emulator does: GPS satellites on GLONASS channel -4.
        val sky = SkySnapshot.of(listOf(sig(Constellation.GPS, 3, 30f, hz = 1599.75e6)))
        assertEquals(listOf("?"), sky.satellites.single().bands)
        assertEquals(FrequencyMode.UNKNOWN, sky.frequencyMode)
        val glonassOnL1 = SkySnapshot.of(listOf(sig(Constellation.GLONASS, 3, 30f, hz = l1)))
        assertEquals(listOf("?"), glonassOnL1.satellites.single().bands)
    }

    @Test
    fun singleFrequency() {
        val sky = SkySnapshot.of(listOf(sig(Constellation.GPS, 1, 40f, used = true)))
        assertEquals(FrequencyMode.SINGLE, sky.frequencyMode)
    }

    @Test
    fun unknownPositionIsListedButNotPlotted() {
        val sky = SkySnapshot.of(
            listOf(
                sig(Constellation.GPS, 1, 30f, el = 0f, az = 0f),
                sig(Constellation.GPS, 2, 30f, el = -2f, az = 100f),
                sig(Constellation.GPS, 3, 30f, el = 0f, az = 10f),
            )
        )
        val byId = sky.satellites.associateBy { it.svid }
        assertFalse(byId.getValue(1).positionKnown)
        assertFalse(byId.getValue(1).plottable)
        assertTrue(byId.getValue(2).positionKnown)
        assertFalse("below the horizon", byId.getValue(2).plottable)
        assertTrue(byId.getValue(3).plottable)
    }

    @Test
    fun positionComesFromASignalThatHasOne() {
        val sky = SkySnapshot.of(
            listOf(
                sig(Constellation.GPS, 9, 44f, el = 0f, az = 0f),
                sig(Constellation.GPS, 9, 30f, hz = l5, el = 52f, az = 143f),
            )
        )
        val s = sky.satellites.single()
        assertEquals(52f, s.elevationDeg)
        assertEquals(143f, s.azimuthDeg)
    }

    @Test
    fun allSevenSystemsAlwaysListedWithThoseInViewFirst() {
        val sky = SkySnapshot.of(listOf(sig(Constellation.SBAS, 131, 39f), sig(Constellation.GALILEO, 1, 40f)))
        assertEquals(
            listOf(
                Constellation.GALILEO, Constellation.SBAS,
                Constellation.GPS, Constellation.BEIDOU, Constellation.GLONASS, Constellation.QZSS, Constellation.NAVIC,
            ),
            sky.constellations.map { it.constellation }
        )
        assertEquals("WAAS", sky.satellites.first { it.constellation == Constellation.SBAS }.sbasSystem)
    }

    @Test
    fun anUnknownSystemGetsAnEighthCard() {
        val sky = SkySnapshot.of(listOf(sig(Constellation.UNKNOWN, 3, 30f)))
        assertEquals(8, sky.constellations.size)
        assertEquals(Constellation.UNKNOWN, sky.constellations.first().constellation)
    }

    @Test
    fun summaryBandsInCarrierOrder() {
        val sky = SkySnapshot.of(
            listOf(
                sig(Constellation.GPS, 1, 45f, hz = l5),
                sig(Constellation.GPS, 2, 30f, hz = l1),
            )
        )
        assertEquals(listOf("L1", "L5"), sky.summary(Constellation.GPS)!!.bands)
    }

    @Test
    fun emptySky() {
        assertTrue(SkySnapshot.EMPTY.isEmpty)
        assertEquals(7, SkySnapshot.EMPTY.constellations.size)
        assertEquals(0, SkySnapshot.EMPTY.inView)
        assertEquals(FrequencyMode.UNKNOWN, SkySnapshot.EMPTY.frequencyMode)
    }
}
