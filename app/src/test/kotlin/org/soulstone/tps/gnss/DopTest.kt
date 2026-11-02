package org.soulstone.tps.gnss

import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DopTest {

    private var nextSvid = 1

    private fun sat(c: Constellation, el: Float, az: Float, state: SatState = SatState.USED) =
        Satellite(c, nextSvid++, "x", el, az, 40f, listOf("L1"), state, dualFrequency = false, dualFrequencyInUse = false)

    /** One satellite overhead and three on the horizon 120° apart. */
    private fun tetrahedron(c: Constellation) = listOf(
        sat(c, 90f, 0f), sat(c, 0f, 30f), sat(c, 0f, 150f), sat(c, 0f, 270f),
    )

    @Test
    fun overheadPlusThreeOnTheHorizon() {
        // GᵀG works out to diag(1.5, 1.5) for east/north and [[1, -1], [-1, 4]] for
        // up/clock, so HDOP = √(4/3), VDOP = √(4/3), PDOP = √(8/3).
        val dop = DopCalculator.compute(tetrahedron(Constellation.GPS))!!
        assertEquals(sqrt(4.0 / 3), dop.hdop, 1e-9)
        assertEquals(sqrt(4.0 / 3), dop.vdop, 1e-9)
        assertEquals(sqrt(8.0 / 3), dop.pdop, 1e-9)
    }

    @Test
    fun aSecondSystemWithTheSameGeometryImprovesByRootTwo() {
        val dop = DopCalculator.compute(tetrahedron(Constellation.GPS) + tetrahedron(Constellation.GALILEO))!!
        assertEquals(sqrt(4.0 / 3) / sqrt(2.0), dop.hdop, 1e-9)
        assertEquals(sqrt(4.0 / 3) / sqrt(2.0), dop.vdop, 1e-9)
    }

    @Test
    fun aLoneSatelliteOfAnotherSystemAddsNothing() {
        // Its system's own clock term absorbs it.
        val alone = DopCalculator.compute(tetrahedron(Constellation.GPS))!!
        val withOne = DopCalculator.compute(tetrahedron(Constellation.GPS) + sat(Constellation.BEIDOU, 40f, 200f))!!
        assertEquals(alone.hdop, withOne.hdop, 1e-9)
        assertEquals(alone.vdop, withOne.vdop, 1e-9)
    }

    @Test
    fun onlySatellitesUsedInTheFixCount() {
        val base = DopCalculator.compute(tetrahedron(Constellation.GPS))!!
        val extra = tetrahedron(Constellation.GPS) +
            sat(Constellation.GPS, 50f, 60f, SatState.TRACKED) +
            sat(Constellation.GPS, 20f, 300f, SatState.PREDICTED)
        assertEquals(base.hdop, DopCalculator.compute(extra)!!.hdop, 1e-9)
    }

    @Test
    fun tooFewSatellitesGiveNoDop() {
        assertNull(DopCalculator.compute(tetrahedron(Constellation.GPS).take(3)))
        assertNull(DopCalculator.compute(emptyList()))
    }

    @Test
    fun degenerateGeometryGivesNoDop() {
        val clump = List(6) { sat(Constellation.GPS, 45f, 90f) }
        assertNull(DopCalculator.compute(clump))
    }

    @Test
    fun aRealisticSkyGivesSensibleNumbers() {
        val sky = listOf(
            sat(Constellation.GPS, 71f, 65f), sat(Constellation.GPS, 52f, 143f), sat(Constellation.GPS, 57f, 134f),
            sat(Constellation.GPS, 25f, 300f), sat(Constellation.GPS, 15f, 210f), sat(Constellation.GPS, 35f, 20f),
        )
        val dop = DopCalculator.compute(sky)!!
        assertTrue(dop.hdop in 0.5..3.0)
        assertTrue(dop.pdop >= dop.hdop && dop.pdop >= dop.vdop)
    }

    @Test
    fun invertsAMatrix() {
        val inv = DopCalculator.invert(arrayOf(doubleArrayOf(4.0, 7.0), doubleArrayOf(2.0, 6.0)))!!
        assertEquals(0.6, inv[0][0], 1e-12)
        assertEquals(-0.7, inv[0][1], 1e-12)
        assertEquals(-0.2, inv[1][0], 1e-12)
        assertEquals(0.4, inv[1][1], 1e-12)
        assertNull(DopCalculator.invert(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0))))
    }
}
