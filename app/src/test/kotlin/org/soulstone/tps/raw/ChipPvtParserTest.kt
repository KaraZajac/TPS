package org.soulstone.tps.raw

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Writes bytes the way android.os.Parcel does, independently of the code under test. */
class ParcelWriter {
    private val out = ByteArrayOutputStream()

    fun int(v: Int) = apply { for (k in 0 until 4) out.write(v ushr (8 * k)) }
    fun long(v: Long) = apply { int(v.toInt()); int((v ushr 32).toInt()) }
    fun double(v: Double) = long(java.lang.Double.doubleToRawLongBits(v))
    fun float(v: Float) = int(java.lang.Float.floatToRawIntBits(v))

    /** writeString16: length, UTF-16LE, a 16-bit terminator, zero padding to four bytes. */
    fun string(s: String?) = apply {
        if (s == null) {
            int(-1)
        } else {
            int(s.length)
            var n = 0
            for (c in s) {
                out.write(c.code and 0xff); out.write(c.code ushr 8); n += 2
            }
            out.write(0); out.write(0); n += 2
            while (n % 4 != 0) { out.write(0); n++ }
        }
    }

    fun bytes(): ByteArray = out.toByteArray()
}

/**
 * The parcel layouts here are copied from AOSP's GnssMeasurement.writeToParcel and
 * SatellitePvt.writeToParcel (android14-release and android16-release are identical).
 */
class ChipPvtParserTest {

    private val svid = 7
    private val gps = 1
    // HAS_SNR | HAS_CARRIER_FREQUENCY | HAS_CODE_TYPE | HAS_BASEBAND_CN0, as a chip might set.
    private val measurementFlags = 1 or (1 shl 9) or (1 shl 14) or (1 shl 15)

    private val pvt = ChipPvt(
        flags = ChipPvtParser.HAS_POSITION_VELOCITY_CLOCK or ChipPvtParser.HAS_IONO or ChipPvtParser.HAS_TROPO or
            ChipPvtParser.HAS_ISSUE_OF_DATA_CLOCK or ChipPvtParser.HAS_ISSUE_OF_DATA_EPHEMERIS or
            ChipPvtParser.HAS_TIME_OF_CLOCK or ChipPvtParser.HAS_TIME_OF_EPHEMERIS,
        position = Ecef(-1.234_567_891e7, 1.987_654_321e7, 9.876_543_21e6),
        positionUreMeters = 1.25,
        velocity = Ecef(-1_234.5, -2_345.25, 2_789.125),
        hardwareCodeBiasMeters = 2.5,
        timeCorrectionMeters = -38_215.625,
        clockDriftMps = 1.5e-3,
        ionoMeters = 4.75,
        tropoMeters = 3.125,
        timeOfClockSeconds = 1_477_152_000L,
        timeOfEphemerisSeconds = 1_477_152_000L,
        issueOfDataClock = 47,
        issueOfDataEphemeris = 47,
        ephemerisSource = EphemerisSource.SERVER_LONG_TERM,
    )

    /** A GnssMeasurement as writeToParcel writes it. */
    private fun measurement(
        pvt: ChipPvt?,
        extras: Boolean = true,
        flagPvt: Boolean = pvt != null,
        correlationVectors: Boolean = false,
        nullObjects: Boolean = false,
    ): ByteArray {
        val w = ParcelWriter()
        w.int(measurementFlags or (if (flagPvt) ChipPvtParser.MEASUREMENT_HAS_PVT else 0) or (if (correlationVectors) 1 shl 21 else 0))
        w.int(svid).int(gps)
        w.double(12.5)                     // time offset
        w.int(0x400f)                      // state
        w.long(345_600_123_456_789L)       // received satellite time
        w.long(40)
        w.double(38.5).double(-512.25).double(0.05)
        w.int(16).double(0.0).double(0.0)  // carrier phase (ADR)
        w.float(1.57542e9f).long(0).double(0.0).double(0.0)
        w.int(0).double(31.0).double(0.0)  // multipath, SNR, AGC
        w.string("C")
        w.double(34.5).double(0.0).double(0.0).double(0.0).double(0.0)
        if (pvt != null) {
            w.string("android.location.SatellitePvt")
            w.int(pvt.flags)
            if (nullObjects) {
                w.string(null).string(null).string(null)
            } else {
                w.string("android.location.SatellitePvt\$PositionEcef")
                    .double(pvt.position.x).double(pvt.position.y).double(pvt.position.z).double(pvt.positionUreMeters)
                w.string("android.location.SatellitePvt\$VelocityEcef")
                    .double(pvt.velocity.x).double(pvt.velocity.y).double(pvt.velocity.z).double(0.02)
                w.string("android.location.SatellitePvt\$ClockInfo")
                    .double(pvt.hardwareCodeBiasMeters).double(pvt.timeCorrectionMeters).double(pvt.clockDriftMps)
            }
            w.double(pvt.ionoMeters ?: 0.0).double(pvt.tropoMeters ?: 0.0)
            if (extras) {
                w.long(pvt.timeOfClockSeconds ?: 0).long(pvt.timeOfEphemerisSeconds ?: 0)
                w.int(pvt.issueOfDataClock ?: 0).int(pvt.issueOfDataEphemeris ?: 0).int(pvt.ephemerisSource ?: EphemerisSource.OTHER)
            }
        }
        if (correlationVectors) w.int(1).int(1).double(1.5).double(2.5).int(3).int(9).int(8).int(7)
        return w.bytes()
    }

    private fun parse(bytes: ByteArray, extras: Boolean = true) =
        ChipPvtParser.parse(bytes, extras) { flags, s, c -> s == svid && c == gps && flags and 0xffff == measurementFlags }

    @Test
    fun readsTheChipsSatellitePositionAsAndroid14WritesIt() {
        val read = parse(measurement(pvt))
        assertTrue(read.toString(), read is PvtRead.Found)
        assertEquals(pvt, (read as PvtRead.Found).pvt)
        assertTrue(read.pvt.plausible)
        assertEquals(-38_218.125, read.pvt.clockMeters, 0.0)
    }

    @Test
    fun android12HasNoTimesOrIssueNumbers() {
        val read = parse(measurement(pvt, extras = false), extras = false) as PvtRead.Found
        assertEquals(pvt.position, read.pvt.position)
        assertNull(read.pvt.timeOfClockSeconds)
        assertNull(read.pvt.ephemerisSource)
    }

    @Test
    fun correlationVectorsAfterItDontConfuseIt() {
        val read = parse(measurement(pvt, correlationVectors = true)) as PvtRead.Found
        assertEquals(pvt, read.pvt)
    }

    @Test
    fun issueNumbersAndTimesOnlyWhenFlagged() {
        val bare = pvt.copy(flags = ChipPvtParser.HAS_POSITION_VELOCITY_CLOCK)
        val read = parse(measurement(bare)) as PvtRead.Found
        assertNull(read.pvt.ionoMeters)
        assertNull(read.pvt.tropoMeters)
        assertNull(read.pvt.issueOfDataClock)
        assertNull(read.pvt.timeOfEphemerisSeconds)
        assertEquals(EphemerisSource.SERVER_LONG_TERM, read.pvt.ephemerisSource)
    }

    @Test
    fun absentWhenTheMeasurementSaysSo() {
        assertEquals(PvtRead.Absent, parse(measurement(null)))
    }

    @Test
    fun noPositionInsideMeansAbsent() {
        val ionoOnly = pvt.copy(flags = ChipPvtParser.HAS_IONO)
        assertEquals(PvtRead.Absent, parse(measurement(ionoOnly, nullObjects = true)))
    }

    @Test
    fun flaggedButMissingIsUnreadable() {
        val read = parse(measurement(null, flagPvt = true))
        assertTrue(read.toString(), read is PvtRead.Unreadable)
    }

    @Test
    fun anUnknownLayoutWithoutTheClassNameIsUnreadable() {
        val read = ChipPvtParser.parse(measurement(null), extras = true) { _, _, _ -> false }
        assertTrue(read.toString(), read is PvtRead.Unreadable)
    }

    @Test
    fun theClassNameIsTrustedEvenIfTheHeaderLooksOdd() {
        val read = ChipPvtParser.parse(measurement(pvt), extras = true) { _, _, _ -> false }
        assertEquals(pvt, (read as PvtRead.Found).pvt)
    }

    @Test
    fun truncatedIsUnreadableNotACrash() {
        val bytes = measurement(pvt)
        for (cut in listOf(200, 260, 300, bytes.size - 40)) {
            val read = parse(bytes.copyOf(cut))
            assertFalse("cut at $cut: $read", read is PvtRead.Found)
        }
    }

    @Test
    fun stringsAreEncodedAsParcelWritesThem() {
        assertArrayEquals(ParcelWriter().string("ab").bytes(), ParcelBytes.encode("ab"))
        assertArrayEquals(ParcelWriter().string("android.location.SatellitePvt").bytes(), ParcelBytes.encode("android.location.SatellitePvt"))
        assertEquals("ab", ParcelBytes(ParcelWriter().string("ab").bytes()).string())
        assertNull(ParcelBytes(ParcelWriter().string(null).bytes()).string())
    }

    @Test
    fun impossibleValuesAreNotPlausible() {
        assertFalse(pvt.copy(position = Ecef(7.0e6, 0.0, 0.0)).plausible)        // inside low Earth orbit
        assertFalse(pvt.copy(velocity = Ecef(9_000.0, 0.0, 0.0)).plausible)
        assertFalse(pvt.copy(timeCorrectionMeters = Double.NaN).plausible)
        assertFalse(pvt.copy(tropoMeters = 900.0).plausible)
        assertTrue(pvt.copy(position = Ecef(4.2164e7, 0.0, 0.0), velocity = Ecef(3.0, 0.0, 0.0)).plausible)  // geostationary
    }
}
