package org.soulstone.tps.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashTest {

    @Test
    fun encodesTheReferenceExample() {
        assertEquals("u4pruydqqvj", Geohash.encode(57.64911, 10.40744, 11))
    }

    @Test
    fun encodesAtEveryPrecision() {
        assertEquals("9xchyjc2v", Geohash.encode(44.46044, -110.82814, 9))
        assertEquals("9xchyjc", Geohash.encode(44.46044, -110.82814, 7))
        assertEquals("w21z76281", Geohash.encode(1.28680, 103.85453, 9))
        assertEquals("r3gx2f9c3", Geohash.encode(-33.86785, 151.20732, 9))
    }

    @Test
    fun cornersOfTheWorld() {
        assertEquals("s0000", Geohash.encode(0.0, 0.0, 5))
        assertEquals("zzzzz", Geohash.encode(90.0, 180.0, 5))
        assertEquals("00000", Geohash.encode(-90.0, -180.0, 5))
    }

    @Test
    fun decodesToTheCellCentre() {
        val cell = Geohash.decode("ezs42")!!
        assertEquals(42.60498046875, cell.latitude, 1e-12)
        assertEquals(-5.60302734375, cell.longitude, 1e-12)
        assertEquals(0.02197265625, cell.latitudeError, 1e-12)
        assertEquals(0.02197265625, cell.longitudeError, 1e-12)
    }

    @Test
    fun roundTripsWithinTheCell() {
        val cell = Geohash.decode(Geohash.encode(44.46044, -110.82814, 9))!!
        assertTrue(kotlin.math.abs(cell.latitude - 44.46044) <= cell.latitudeError)
        assertTrue(kotlin.math.abs(cell.longitude + 110.82814) <= cell.longitudeError)
    }

    @Test
    fun acceptsUppercaseRejectsOtherLetters() {
        assertEquals(Geohash.decode("9xchyjc2v"), Geohash.decode("9XCHYJC2V"))
        assertFalse(Geohash.isValid("dqcha6sy1"))  // 'a' isn't in the alphabet
        assertFalse(Geohash.isValid(""))
        assertFalse(Geohash.isValid("9xchyjc2v9xchy"))  // longer than 12
        assertNull(Geohash.decode("hello"))  // 'l' and 'o' aren't either
    }

    @Test
    fun cellSizes() {
        val (w9, h9) = Geohash.cellSizeMeters(9, 0.0)
        // The usual table: 4.77 m × 4.77 m at 9 characters, 38.2 m × 19.1 m at 8.
        assertEquals(4.78, w9, 0.01)
        assertEquals(4.78, h9, 0.01)
        val (w8, h8) = Geohash.cellSizeMeters(8, 0.0)
        assertEquals(38.2, w8, 0.1)
        assertEquals(19.1, h8, 0.1)
        // Cells narrow towards the poles.
        assertTrue(Geohash.cellSizeMeters(9, 60.0).first < w9 * 0.51)
    }
}
