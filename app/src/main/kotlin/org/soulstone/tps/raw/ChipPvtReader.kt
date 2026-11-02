package org.soulstone.tps.raw

import android.location.GnssMeasurement
import android.os.Build
import android.os.Parcel
import androidx.annotation.RequiresApi

/**
 * Reads the chip's satellite position, velocity and clock ([ChipPvt]) from a measurement.
 * Android delivers them to every app with precise location, but `getSatellitePvt()` is a
 * system API. The measurement's public `writeToParcel` writes them too, so TPS serializes the
 * measurement and parses them out. Only public APIs are involved; if a future Android changes
 * the layout, the reader says so instead of guessing.
 */
@RequiresApi(31)
object ChipPvtReader {
    // GnssMeasurement's own flag bits (from the GNSS HAL), to check the parcel's header with.
    private const val HAS_SNR = 1
    private const val HAS_CARRIER_FREQUENCY = 1 shl 9
    private const val HAS_CODE_TYPE = 1 shl 14
    private const val HAS_BASEBAND_CN0 = 1 shl 15

    fun read(m: GnssMeasurement): PvtRead {
        val parcel = Parcel.obtain()
        return try {
            m.writeToParcel(parcel, 0)
            ChipPvtParser.parse(parcel.marshall(), extras = Build.VERSION.SDK_INT >= 33) { flags, svid, constellation ->
                svid == m.svid &&
                    constellation == m.constellationType &&
                    (flags and HAS_SNR != 0) == m.hasSnrInDb() &&
                    (flags and HAS_CARRIER_FREQUENCY != 0) == m.hasCarrierFrequencyHz() &&
                    (flags and HAS_CODE_TYPE != 0) == m.hasCodeType() &&
                    (flags and HAS_BASEBAND_CN0 != 0) == m.hasBasebandCn0DbHz()
            }
        } catch (e: RuntimeException) {
            PvtRead.Unreadable(e.javaClass.simpleName)
        } finally {
            parcel.recycle()
        }
    }
}
