package org.soulstone.tps.raw

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The motion sensors as a recording keeps them, read back the way the replay tests read it. */
class RawRecorderTest {
    @Test
    fun motionSamplesGoInAtTwentyFiveASecondAndComeBackOut() {
        val dir = Files.createTempDirectory("tps-rec").toFile()
        val start = 7_000_000_000_000L
        val recorder = RawRecorder(dir) { start }
        // Nothing is kept before a recording starts.
        recorder.accel(start - 1_000_000_000L, 0.0, 0.0, 9.81)
        recorder.start("test")
        var t = start + 3_000_000L
        while (t < start + 2_000_000_000L) {
            recorder.accel(t, 0.01, -0.02, 9.81)
            recorder.gyro(t + 1_000_000L, 0.001, 0.002, -0.003)
            t += 20_000_000L     // 50 a second
        }
        val file = recorder.stop()!!
        // Nor after it stops.
        recorder.gyro(t, 1.0, 1.0, 1.0)
        val r = RecordingFile.read(file.readLines().asSequence())
        assertEquals(50.0, r.accel.size.toDouble(), 1.0)
        assertEquals(50.0, r.gyro.size.toDouble(), 1.0)
        assertTrue(r.accel.all { it.tNs in start until start + 2_000_000_000L })
        assertEquals(9.81, r.accel.map { it.z }.average(), 1e-3)
        assertEquals(-0.003, r.gyro.map { it.z }.average(), 1e-4)
        // The recording pairs the sensors' clock with UTC.
        assertEquals(start, r.clockPair!!.first)
        dir.deleteRecursively()
    }
}
