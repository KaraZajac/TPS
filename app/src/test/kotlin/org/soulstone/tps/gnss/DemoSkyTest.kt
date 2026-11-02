package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoSkyTest {

    private val wall = 1_793_650_000_000L

    private fun frame(ms: Long) = DemoSky().frame(ms, wall + ms, 1_000_000 + ms)

    @Test
    fun silentAtFirst() {
        val f = frame(1_000)
        assertTrue(f.signals.isEmpty())
        assertNull(f.fix)
    }

    @Test
    fun predictedThenAcquiredBeforeTheFix() {
        val f = frame(1_600)
        assertTrue(f.signals.isNotEmpty())
        assertTrue("some only predicted", f.signals.any { it.cn0DbHz == 0f })
        assertTrue("strongest already acquired", f.signals.any { it.cn0DbHz > 0f })
        assertTrue(f.signals.none { it.usedInFix })
        assertNull(f.fix)
        assertNull(f.ttffMs)
    }

    @Test
    fun allSevenSystemsInViewAfterTheFix() {
        val f = frame(10_000)
        val sky = SkySnapshot.of(f.signals)
        for (c in Constellation.KNOWN) {
            assertTrue("$c in view", (sky.summary(c)?.inView ?: 0) > 0)
        }
        assertNotNull(f.fix)
        assertEquals(DemoSky.FIRST_FIX_MS, f.ttffMs)
        assertTrue("used ${sky.used}", sky.used >= 20)
        assertTrue(sky.used < sky.inView)
        assertEquals(FrequencyMode.DUAL_IN_USE, sky.frequencyMode)
        assertNotNull(DopCalculator.compute(sky.satellites))
        println(
            "demo sky at 10 s: ${sky.used} used of ${sky.inView} in view; " +
                sky.constellations.joinToString { "${it.constellation.displayName} ${it.used}/${it.inView}" }
        )
    }

    @Test
    fun itsNmeaParses() {
        val tracker = NmeaTracker()
        frame(10_000).nmea.forEach { assertTrue(it, tracker.accept(it, 0)) }
        assertEquals(FixType.THREE_D, tracker.state.fixType)
        assertNotNull(tracker.state.dop)
        assertEquals(7.0, tracker.state.geoidSeparationM!!, 1e-9)
    }

    @Test
    fun theSkyMoves() {
        val early = SkySnapshot.of(frame(10_000).signals).satellites.filter { it.constellation == Constellation.GPS }
        val later = SkySnapshot.of(frame(70_000).signals).satellites.filter { it.constellation == Constellation.GPS }
        val moved = early.mapNotNull { e -> later.firstOrNull { it.svid == e.svid }?.let { e.azimuthDeg != it.azimuthDeg } }
        assertTrue(moved.isNotEmpty() && moved.all { it })
    }

    @Test
    fun idsStayInRealRanges() {
        val signals = frame(10_000).signals
        assertTrue(signals.filter { it.constellation == Constellation.GLONASS }.all { it.svid in 1..24 })
        assertTrue(signals.filter { it.constellation == Constellation.QZSS }.all { it.svid in 193..206 })
        assertTrue(signals.filter { it.constellation == Constellation.SBAS }.all { Sbas.system(it.svid) != null })
    }

    @Test
    fun deterministic() {
        assertEquals(frame(12_345).signals, frame(12_345).signals)
        assertNotEquals(frame(12_345).signals, frame(42_345).signals)
    }
}
