package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandsTest {

    @Test
    fun sharedL1CarrierWithinTolerance() {
        assertEquals(Carrier.L1, Carrier.of(1575.42e6))
        assertEquals(Carrier.L1, Carrier.of(1575.42e6 + 0.9e6))
        assertEquals(Carrier.L1, Carrier.of(1575.42e6 - 0.9e6))
        assertEquals(Carrier.UNKNOWN, Carrier.of(1575.42e6 + 1.5e6))
    }

    @Test
    fun everyGlonassChannelOnG1AndG2() {
        for (k in -7..6) {
            val g1 = 1602e6 + k * 0.5625e6
            val g2 = 1246e6 + k * 0.4375e6
            assertEquals("G1 k=$k", Carrier.G1, Carrier.of(g1))
            assertEquals("G2 k=$k", Carrier.G2, Carrier.of(g2))
            assertEquals(k, Bands.glonassChannel(g1))
            assertEquals(k, Bands.glonassChannel(g2))
        }
        assertNull(Bands.glonassChannel(1575.42e6))
    }

    @Test
    fun otherCarriers() {
        assertEquals(Carrier.B1I, Carrier.of(1561.098e6))
        assertEquals(Carrier.L2, Carrier.of(1227.60e6))
        assertEquals(Carrier.G3, Carrier.of(1202.025e6))
        assertEquals(Carrier.L5, Carrier.of(1176.45e6))
        assertEquals(Carrier.E5B, Carrier.of(1207.14e6))
        assertEquals(Carrier.E6, Carrier.of(1278.75e6))
        assertEquals(Carrier.B3I, Carrier.of(1268.52e6))
        assertEquals(Carrier.S, Carrier.of(2492.028e6))
        assertEquals(Carrier.UNKNOWN, Carrier.of(1590e6))
    }

    @Test
    fun missingCarrierIsUnknown() {
        assertEquals(Carrier.UNKNOWN, Carrier.of(null))
        assertEquals(Carrier.UNKNOWN, Carrier.of(0.0))
        assertEquals(Carrier.UNKNOWN, Carrier.of(-1.0))
        assertEquals(Carrier.UNKNOWN, Carrier.of(Double.NaN))
    }

    @Test
    fun eachSystemNamesItsOwnBands() {
        assertEquals("L1", Bands.label(Constellation.GPS, 1, Carrier.L1))
        assertEquals("E1", Bands.label(Constellation.GALILEO, 1, Carrier.L1))
        assertEquals("B1C", Bands.label(Constellation.BEIDOU, 30, Carrier.L1))
        assertEquals("L1", Bands.label(Constellation.NAVIC, 10, Carrier.L1))
        assertEquals("L5", Bands.label(Constellation.GPS, 1, Carrier.L5))
        assertEquals("E5a", Bands.label(Constellation.GALILEO, 1, Carrier.L5))
        assertEquals("B2a", Bands.label(Constellation.BEIDOU, 30, Carrier.L5))
        assertEquals("B2I", Bands.label(Constellation.BEIDOU, 5, Carrier.E5B))
        assertEquals("B2b", Bands.label(Constellation.BEIDOU, 30, Carrier.E5B))
        assertEquals("E5b", Bands.label(Constellation.GALILEO, 1, Carrier.E5B))
        assertEquals("L6", Bands.label(Constellation.QZSS, 194, Carrier.E6))
        assertEquals("E6", Bands.label(Constellation.GALILEO, 1, Carrier.E6))
        assertEquals("L1S", Bands.label(Constellation.QZSS, 184, Carrier.L1))
        assertEquals("L1", Bands.label(Constellation.QZSS, 194, Carrier.L1))
    }

    @Test
    fun whoBroadcastsWhere() {
        assertEquals(true, Carrier.L1.broadcastBy(Constellation.NAVIC))
        assertEquals(false, Carrier.L1.broadcastBy(Constellation.GLONASS))
        assertEquals(false, Carrier.G1.broadcastBy(Constellation.GPS))
        assertEquals(true, Carrier.G1.broadcastBy(Constellation.GLONASS))
        assertEquals(false, Carrier.B1I.broadcastBy(Constellation.GALILEO))
        assertEquals(true, Carrier.E6.broadcastBy(Constellation.QZSS))
        assertEquals(true, Carrier.S.broadcastBy(Constellation.UNKNOWN))
    }

    @Test
    fun tiersMakeDualFrequency() {
        assertEquals(Tier.UPPER_L, Carrier.L1.tier)
        assertEquals(Tier.UPPER_L, Carrier.G1.tier)
        assertEquals(Tier.LOWER_L, Carrier.L5.tier)
        assertEquals(Tier.LOWER_L, Carrier.E5B.tier)
        assertEquals(Tier.S_BAND, Carrier.S.tier)
        assertNull(Carrier.UNKNOWN.tier)
    }

    @Test
    fun bandsSortUpperFirst() {
        val sorted = listOf("B2a", "L5", "E1", "S", "B1I", "L1", "?").sortedBy { Bands.rank(it) }
        assertEquals(listOf("L1", "E1", "B1I", "L5", "B2a", "S", "?"), sorted)
    }
}
