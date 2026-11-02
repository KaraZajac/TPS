package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConstellationTest {

    @Test
    fun androidIds() {
        assertEquals(Constellation.UNKNOWN, Constellation.fromAndroid(0))
        assertEquals(Constellation.GPS, Constellation.fromAndroid(1))
        assertEquals(Constellation.SBAS, Constellation.fromAndroid(2))
        assertEquals(Constellation.GLONASS, Constellation.fromAndroid(3))
        assertEquals(Constellation.QZSS, Constellation.fromAndroid(4))
        assertEquals(Constellation.BEIDOU, Constellation.fromAndroid(5))
        assertEquals(Constellation.GALILEO, Constellation.fromAndroid(6))
        assertEquals(Constellation.NAVIC, Constellation.fromAndroid(7))
        assertEquals(Constellation.UNKNOWN, Constellation.fromAndroid(42))
    }

    @Test
    fun knownSystemsInCardOrder() {
        assertEquals(
            listOf("GPS", "GALILEO", "BEIDOU", "GLONASS", "QZSS", "NAVIC", "SBAS"),
            Constellation.KNOWN.map { it.cardName }
        )
    }

    @Test
    fun everySystemHasItsOwnShape() {
        val shapes = Constellation.KNOWN.map { it.shape }
        assertEquals(shapes.size, shapes.toSet().size)
    }

    @Test
    fun satelliteLabels() {
        assertEquals("G07", SatelliteLabels.label(Constellation.GPS, 7))
        assertEquals("G32", SatelliteLabels.label(Constellation.GPS, 32))
        assertEquals("E36", SatelliteLabels.label(Constellation.GALILEO, 36))
        assertEquals("C01", SatelliteLabels.label(Constellation.BEIDOU, 1))
        assertEquals("I10", SatelliteLabels.label(Constellation.NAVIC, 10))
        assertEquals("S131", SatelliteLabels.label(Constellation.SBAS, 131))
        assertEquals("?3", SatelliteLabels.label(Constellation.UNKNOWN, 3))
    }

    @Test
    fun glonassSlotOrFrequencyChannel() {
        assertEquals("R05", SatelliteLabels.label(Constellation.GLONASS, 5))
        assertEquals("R24", SatelliteLabels.label(Constellation.GLONASS, 24))
        assertEquals("R k=-7", SatelliteLabels.label(Constellation.GLONASS, 93))
        assertEquals("R k=0", SatelliteLabels.label(Constellation.GLONASS, 100))
        assertEquals("R k=+6", SatelliteLabels.label(Constellation.GLONASS, 106))
    }

    @Test
    fun qzssPrns() {
        assertEquals("J01", SatelliteLabels.label(Constellation.QZSS, 193))
        assertEquals("J07", SatelliteLabels.label(Constellation.QZSS, 199))
        assertEquals("J14", SatelliteLabels.label(Constellation.QZSS, 206))
        // L1S PRN = L1 C/A PRN - 10: 184 is J02's L1S signal.
        assertEquals("J02", SatelliteLabels.label(Constellation.QZSS, 184))
    }

    @Test
    fun sbasSystemsFromThePrnTable() {
        assertEquals("WAAS", Sbas.system(131))
        assertEquals("WAAS", Sbas.system(133))
        assertEquals("WAAS", Sbas.system(135))
        assertEquals("WAAS", Sbas.system(138))
        assertEquals("EGNOS", Sbas.system(121))
        assertEquals("EGNOS", Sbas.system(136))
        assertEquals("GAGAN", Sbas.system(127))
        assertEquals("MSAS", Sbas.system(137))
        assertEquals("SouthPAN", Sbas.system(122))
        assertEquals("KASS", Sbas.system(134))
        assertEquals("BDSBAS", Sbas.system(143))
        assertEquals("SDCM", Sbas.system(125))
        assertEquals("UK SBAS", Sbas.system(158))
        assertEquals("SLAS", Sbas.system(186))
        assertNull("reserved", Sbas.system(126))
        assertNull("unallocated", Sbas.system(146))
    }
}
