package org.soulstone.tps.raw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The recording parser on the lines a dev.13 recording adds. */
class RecordingFileTest {
    private val header = listOf(
        "# Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos",
        "# Motion,utcTimeMillis,State,AccelSdMps2,GyroRmsRadps",
        "# Accel,elapsedRealtimeNanos,XMps2,YMps2,ZMps2",
        "# Gyro,elapsedRealtimeNanos,XRadps,YRadps,ZRadps",
        "# Clock: elapsedRealtimeNanos 5000000000000 at utcTimeMillis 1794576554000",
    )

    @Test
    fun motionSamplesAndTheClockPair() {
        val r = RecordingFile.read((header + listOf(
            "Accel,5000000020000,0.012,-0.034,9.811",
            "Gyro,5000000021000,0.0010,-0.0020,0.0030",
            "Accel,5000000060000,0.010,-0.030,9.809",
            "Motion,1794576556000,STILL,0.0071,0.0012",
            "Accel,broken",
        )).asSequence())
        assertEquals(2, r.accel.size)
        assertEquals(1, r.gyro.size)
        assertEquals(9.811, r.accel[0].z, 1e-9)
        assertEquals(-0.002, r.gyro[0].y, 1e-9)
        assertEquals(5_000_000_060_000L, r.accel[1].tNs)
        // Two seconds after the pairing on UTC is two seconds on the monotonic clock.
        assertEquals(5_002_000_000_000L, r.elapsedNs(1_794_576_556_000L))
        assertEquals(0.0071 to 0.0012, r.motionFigures[1_794_576_556_000L])
        assertEquals(Motion.STILL, r.motion[1_794_576_556_000L])
    }

    @Test
    fun olderRecordingsHaveNone() {
        val r = RecordingFile.read(header.take(2).asSequence())
        assertEquals(0, r.accel.size)
        assertNull(r.elapsedNs(1_794_576_556_000L))
    }
}
