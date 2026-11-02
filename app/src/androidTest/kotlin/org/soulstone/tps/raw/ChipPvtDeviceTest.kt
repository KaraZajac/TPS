package org.soulstone.tps.raw

import android.location.GnssMeasurement
import android.os.Build
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks, on a real Android runtime, the parcel layout [ChipPvtReader] relies on: a measurement
 * written field by field in the order TPS expects must be read back by Android's own
 * `GnssMeasurement.CREATOR` with every byte used, and TPS must then find the same satellite
 * position in it. A different layout on some Android version fails here, not on a phone.
 */
@RunWith(AndroidJUnit4::class)
class ChipPvtDeviceTest {

    private val svid = 11
    private val gps = 1
    // HAS_SNR | HAS_CARRIER_FREQUENCY | HAS_CODE_TYPE | HAS_BASEBAND_CN0
    private val measurementFlags = 1 or (1 shl 9) or (1 shl 14) or (1 shl 15)
    private val pvtFlags = 0x7f   // everything: position/velocity/clock, delays, issues of data, times

    private fun parcel(withPvt: Boolean): Parcel {
        val p = Parcel.obtain()
        p.writeInt(measurementFlags or if (withPvt) ChipPvtParser.MEASUREMENT_HAS_PVT else 0)
        p.writeInt(svid)
        p.writeInt(gps)
        p.writeDouble(12.5)                  // time offset
        p.writeInt(0x400f)                   // state
        p.writeLong(345_600_123_456_789L)    // received satellite time
        p.writeLong(40)
        p.writeDouble(38.5)                  // C/N0
        p.writeDouble(-512.25)
        p.writeDouble(0.05)
        p.writeInt(16)                       // ADR state
        p.writeDouble(0.0)
        p.writeDouble(0.0)
        p.writeFloat(1.57542e9f)
        p.writeLong(0)
        p.writeDouble(0.0)
        p.writeDouble(0.0)
        p.writeInt(0)                        // multipath
        p.writeDouble(31.0)                  // SNR
        p.writeDouble(0.0)                   // AGC
        p.writeString("C")
        p.writeDouble(34.5)                  // baseband C/N0
        p.writeDouble(0.0)
        p.writeDouble(0.0)
        p.writeDouble(0.0)
        p.writeDouble(0.0)
        if (withPvt) {
            p.writeString("android.location.SatellitePvt")
            p.writeInt(pvtFlags)
            p.writeString("android.location.SatellitePvt\$PositionEcef")
            p.writeDouble(-1.234_567_891e7); p.writeDouble(1.987_654_321e7); p.writeDouble(9.876_543_21e6); p.writeDouble(1.25)
            p.writeString("android.location.SatellitePvt\$VelocityEcef")
            p.writeDouble(-1_234.5); p.writeDouble(-2_345.25); p.writeDouble(2_789.125); p.writeDouble(0.02)
            p.writeString("android.location.SatellitePvt\$ClockInfo")
            p.writeDouble(2.5); p.writeDouble(-38_215.625); p.writeDouble(1.5e-3)
            p.writeDouble(4.75)              // ionosphere
            p.writeDouble(3.125)             // troposphere
            if (Build.VERSION.SDK_INT >= 33) {
                p.writeLong(1_477_152_000L)  // time of clock
                p.writeLong(1_477_152_016L)  // time of ephemeris
                p.writeInt(47)               // IODC
                p.writeInt(46)               // IODE
                p.writeInt(EphemerisSource.SERVER_NORMAL)
            }
        }
        p.setDataPosition(0)
        return p
    }

    @Test
    fun androidReadsTheLayoutAndTpsFindsThePosition() {
        assumeTrue("SatellitePvt is Android 12+", Build.VERSION.SDK_INT >= 31)
        val p = parcel(withPvt = true)
        val written = p.marshall()
        val m = GnssMeasurement.CREATOR.createFromParcel(p)
        // Android consumed exactly what was written, and agrees on the fields it shows.
        assertEquals("bytes left over", 0, p.dataAvail())
        p.recycle()
        assertEquals(svid, m.svid)
        assertEquals(gps, m.constellationType)
        assertEquals(345_600_123_456_789L, m.receivedSvTimeNanos)
        assertEquals(38.5, m.cn0DbHz, 0.0)
        assertEquals(1.57542e9f, m.carrierFrequencyHz, 0f)
        assertEquals("C", m.codeType)
        assertEquals(34.5, m.basebandCn0DbHz, 0.0)

        // Serializing it again gives the same bytes: the layout round-trips.
        val again = Parcel.obtain()
        m.writeToParcel(again, 0)
        assertArrayEquals(written, again.marshall())
        again.recycle()

        val read = ChipPvtReader.read(m)
        assertTrue(read.toString(), read is PvtRead.Found)
        val pvt = (read as PvtRead.Found).pvt
        assertEquals(Ecef(-1.234_567_891e7, 1.987_654_321e7, 9.876_543_21e6), pvt.position)
        assertEquals(Ecef(-1_234.5, -2_345.25, 2_789.125), pvt.velocity)
        assertEquals(-38_218.125, pvt.clockMeters, 0.0)
        assertEquals(4.75, pvt.ionoMeters!!, 0.0)
        assertEquals(3.125, pvt.tropoMeters!!, 0.0)
        assertTrue(pvt.plausible)
        if (Build.VERSION.SDK_INT >= 33) {
            assertEquals(1_477_152_000L, pvt.timeOfClockSeconds)
            assertEquals(1_477_152_016L, pvt.timeOfEphemerisSeconds)
            assertEquals(47, pvt.issueOfDataClock)
            assertEquals(46, pvt.issueOfDataEphemeris)
            assertEquals(EphemerisSource.SERVER_NORMAL, pvt.ephemerisSource)
        }
    }

    @Test
    fun aMeasurementWithoutOneReadsAsAbsent() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)
        val p = parcel(withPvt = false)
        val m = GnssMeasurement.CREATOR.createFromParcel(p)
        assertEquals(0, p.dataAvail())
        p.recycle()
        assertEquals(PvtRead.Absent, ChipPvtReader.read(m))
    }
}
