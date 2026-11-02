package org.soulstone.tps.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

class DecoderTest {

    @Test
    fun bitFields() {
        val b = Bits(byteArrayOf(0b1011_0000.toByte(), 0xFF.toByte()))
        assertEquals(0b1011L, b.u(0, 4))
        assertEquals(-5L, b.s(0, 4))         // 1011 in two's complement
        assertEquals(0x0FFL, b.u(4, 12))     // 0000 1111 1111
    }

    @Test
    fun crc24qCheckValue() {
        // The standard check: CRC-24Q of the ASCII string "123456789".
        assertEquals(0xCDE703, GalileoInav.crc24q(Bits("123456789".toByteArray()), 0, 72))
    }

    private fun assertSameOrbit(expected: KeplerEphemeris, actual: KeplerEphemeris) {
        val tol = 1e-15
        assertEquals(expected.svid, actual.svid)
        assertEquals(expected.iod, actual.iod)
        assertEquals(expected.toe, actual.toe, 0.0)
        assertEquals(expected.toc, actual.toc, 0.0)
        assertEquals(expected.sqrtA, actual.sqrtA, 1e-9)
        assertEquals(expected.e, actual.e, tol)
        assertEquals(expected.i0, actual.i0, tol)
        assertEquals(expected.omega0, actual.omega0, tol)
        assertEquals(expected.omega, actual.omega, tol)
        assertEquals(expected.m0, actual.m0, tol)
        assertEquals(expected.deltaN, actual.deltaN, tol)
        assertEquals(expected.idot, actual.idot, tol)
        assertEquals(expected.omegaDot, actual.omegaDot, tol)
        assertEquals(expected.cuc, actual.cuc, tol)
        assertEquals(expected.cus, actual.cus, tol)
        assertEquals(expected.crc, actual.crc, 1e-12)
        assertEquals(expected.crs, actual.crs, 1e-12)
        assertEquals(expected.cic, actual.cic, tol)
        assertEquals(expected.cis, actual.cis, tol)
        assertEquals(expected.af0, actual.af0, tol)
        assertEquals(expected.af1, actual.af1, 1e-20)
        assertEquals(expected.af2, actual.af2, 1e-25)
        assertEquals(expected.groupDelay, actual.groupDelay, tol)
    }

    @Test
    fun gpsSubframesRoundTrip() {
        for (alreadyCorrected in listOf(false, true)) {
            val asm = GpsLnavAssembler(Constellation.GPS)
            val e = SyntheticOrbits.gps()[5]
            val subframes = LnavEncoder.subframes(e, tow = 345_000, alreadyCorrected = alreadyCorrected)
            assertNull(asm.accept(e.svid, subframes[0]))
            assertNull(asm.accept(e.svid, subframes[1]))
            val decoded = asm.accept(e.svid, subframes[2])
            assertNotNull(decoded)
            assertSameOrbit(e, decoded!!)
            assertEquals(0, asm.rejected)
        }
    }

    @Test
    fun gpsSubframeIdAndTow() {
        val sub = LnavEncoder.subframes(SyntheticOrbits.gps()[0], tow = 345_000)
        val d = GpsLnav.dataBits(sub[1])!!
        assertEquals(2, GpsLnav.subframeId(d))
        assertEquals(345_006, GpsLnav.towSeconds(d))
    }

    @Test
    fun aFlippedBitFailsParity() {
        val sub = LnavEncoder.subframes(SyntheticOrbits.gps()[0], tow = 345_000)[1].copyOf()
        sub[13] = (sub[13].toInt() xor 0x10).toByte()
        assertNull(GpsLnav.dataBits(sub))
    }

    @Test
    fun mismatchedIssuesOfDataDontMakeAnOrbit() {
        val asm = GpsLnavAssembler(Constellation.GPS)
        val e = SyntheticOrbits.gps()[3]
        val old = LnavEncoder.subframes(e.copy(iod = 40), tow = 345_000)
        val new = LnavEncoder.subframes(e.copy(iod = 41), tow = 345_000)
        asm.accept(e.svid, old[0])
        asm.accept(e.svid, old[1])
        assertNull(asm.accept(e.svid, new[2]))
        assertNotNull(asm.accept(e.svid, old[2]))
    }

    @Test
    fun galileoPagesRoundTrip() {
        val asm = GalileoInavAssembler()
        val e = SyntheticOrbits.galileo()[7]
        val pages = InavEncoder.pages(e)
        var decoded: KeplerEphemeris? = null
        // Word 5 (the group delay) first, as it often is, so the orbit carries it.
        for (p in listOf(pages[4], pages[0], pages[1], pages[2], pages[3])) decoded = asm.accept(e.svid, p) ?: decoded
        assertSameOrbit(e, decoded!!)
    }

    @Test
    fun galileoBadCrcIsRejected() {
        val asm = GalileoInavAssembler()
        val page = InavEncoder.pages(SyntheticOrbits.galileo()[0])[0].copyOf()
        page[10] = (page[10].toInt() xor 0x01).toByte()
        assertNull(GalileoInav.word(page))
        assertNull(asm.accept(1, page))
        assertEquals(1, asm.rejected)
    }
}
