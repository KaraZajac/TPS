package org.soulstone.tps.raw

import org.junit.Assert.assertEquals
import org.junit.Test

/** The recordings' motion samples: 50 or 100 a second in, 25 a second out, each the mean of its slot. */
class SlotAveragerTest {
    private fun run(hz: Int, seconds: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val a = SlotAverager { t, x, y, z -> out += doubleArrayOf(t.toDouble(), x, y, z) }
        val step = 1_000_000_000L / hz
        var t = 1_000_000_000_007L      // the sensors' own clock, nowhere near a slot boundary
        val end = t + (seconds * 1e9).toLong()
        while (t < end) {
            val s = t * 1e-9
            a.add(t, s, 2 * s, 9.81)
            t += step
        }
        a.flush()
        return out
    }

    @Test
    fun fiftyAndAHundredASecondBothBecomeTwentyFive() {
        for (hz in listOf(50, 100)) {
            val out = run(hz, 4.0)
            assertEquals("$hz Hz", 100.0, out.size.toDouble(), 1.0)
            for (o in out) {
                // A straight line averages to its value at the mean time.
                assertEquals(o[0] * 1e-9, o[1], 1e-6)
                assertEquals(2 * o[0] * 1e-9, o[2], 1e-6)
                assertEquals(9.81, o[3], 1e-9)
            }
            val gaps = out.zipWithNext { p, q -> (q[0] - p[0]) * 1e-6 }
            assertEquals(40.0, gaps.average(), 0.5)
        }
    }

    @Test
    fun nothingInNothingOut() {
        var n = 0
        val a = SlotAverager { _, _, _, _ -> n++ }
        a.flush()
        assertEquals(0, n)
    }
}
