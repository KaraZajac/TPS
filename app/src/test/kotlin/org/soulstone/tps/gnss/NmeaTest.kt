package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NmeaTest {

    // The classic examples from the NMEA references, with their published checksums.
    private val gga = "\$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47"
    private val gsa = "\$GPGSA,A,3,04,05,,09,12,,,24,,,,,2.5,1.3,2.1*39"

    /** For sentences where the checksum isn't what's being tested. */
    private fun withChecksum(body: String) = "$" + body + "*" + Nmea.checksum(body).toString(16).padStart(2, '0')

    @Test
    fun checksumOfAKnownSentence() {
        assertEquals(0x47, Nmea.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
    }

    @Test
    fun parsesGga() {
        val s = Nmea.parse(gga) as NmeaSentence.Gga
        assertEquals(1, s.quality)
        assertEquals(8, s.satellites)
        assertEquals(0.9, s.hdop!!, 1e-9)
        assertEquals(545.4, s.mslAltitudeM!!, 1e-9)
        assertEquals(46.9, s.geoidSeparationM!!, 1e-9)
    }

    @Test
    fun parsesGsa() {
        val s = Nmea.parse(gsa) as NmeaSentence.Gsa
        assertEquals(FixType.THREE_D, s.fixType)
        assertEquals(2.5, s.pdop!!, 1e-9)
        assertEquals(1.3, s.hdop!!, 1e-9)
        assertEquals(2.1, s.vdop!!, 1e-9)
        assertEquals(listOf(4, 5, 9, 12, 24), s.svids)
        assertNull(s.systemId)
    }

    @Test
    fun gsaSystemIdFromNmea410() {
        val s = Nmea.parse("\$GNGSA,A,3,80,71,73,79,69,,,,,,,,1.83,1.09,1.47,2*09") as NmeaSentence.Gsa
        assertEquals(2, s.systemId)
        assertEquals(1.09, s.hdop!!, 1e-9)
    }

    @Test
    fun noFixSentinelDopIsDropped() {
        val s = Nmea.parse("\$GPGSA,A,1,,,,,,,,,,,,,99.99,99.99,99.99,1*2D") as NmeaSentence.Gsa
        assertEquals(FixType.NO_FIX, s.fixType)
        assertNull(s.pdop)
        assertNull(s.hdop)
        assertNull(s.vdop)
    }

    @Test
    fun badChecksumIsRejected() {
        assertNull(Nmea.parse(gga.replace("*47", "*48")))
        assertNull(Nmea.parse(gga.replace("545.4", "545.5")))
    }

    @Test
    fun truncatedChecksumIsRejected() {
        assertNull(Nmea.parse(gsa.dropLast(1)))
    }

    @Test
    fun lowercaseChecksumAndDifferentialQuality() {
        val s = Nmea.parse("\$GNGGA,092750.000,5321.6802,N,00630.3372,W,2,08,1.03,61.7,M,55.2,M,,*5b") as NmeaSentence.Gga
        assertEquals(2, s.quality)
    }

    @Test
    fun sentenceWithoutChecksumIsAccepted() {
        val s = Nmea.parse("\$GPGSA,A,2,04,05,09,,,,,,,,,,3.1,2.0,2.4") as NmeaSentence.Gsa
        assertEquals(FixType.TWO_D, s.fixType)
    }

    @Test
    fun otherSentencesAreIgnored() {
        assertNull(Nmea.parse(withChecksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W")))
        assertNull(Nmea.parse(withChecksum("PGLOR,1,STA,0")))
        assertNull(Nmea.parse("garbage"))
        assertNull(Nmea.parse(""))
        assertNull(Nmea.parse(withChecksum("GPGSA,A,3,04")))
    }

    @Test
    fun trackerKeepsTheLatestValues() {
        val t = NmeaTracker()
        assertTrue(t.accept(gsa, 1_000))
        assertEquals(FixType.THREE_D, t.state.fixType)
        assertEquals(Dop(2.5, 1.3, 2.1), t.state.dop)
        assertEquals(1_000L, t.state.dopAtMs)
        assertTrue(t.accept(gga, 1_500))
        assertEquals(46.9, t.state.geoidSeparationM!!, 1e-9)
        assertEquals(1, t.state.ggaQuality)
        assertFalse(t.accept("garbage", 2_000))
        // A no-fix GSA updates the fix type but keeps the last good DOP.
        assertTrue(t.accept("\$GPGSA,A,1,,,,,,,,,,,,,99.99,99.99,99.99,1*2D", 3_000))
        assertEquals(FixType.NO_FIX, t.state.fixType)
        assertEquals(Dop(2.5, 1.3, 2.1), t.state.dop)
        t.reset()
        assertNull(t.state.fixType)
    }

    @Test
    fun zeroGeoidSeparationMeansMissing() {
        val t = NmeaTracker()
        t.accept(withChecksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,0.0,M,,"), 0)
        assertNull(t.state.geoidSeparationM)
    }
}
