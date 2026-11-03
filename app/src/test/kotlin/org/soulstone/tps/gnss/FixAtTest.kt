package org.soulstone.tps.gnss

import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Test
import org.soulstone.tps.raw.Geo
import org.soulstone.tps.raw.Geodetic
import org.soulstone.tps.raw.Solver

/** The chip's fix moved to another moment: its fixes and its raw measurements come 0.42 s apart. */
class FixAtTest {
    // The Statue of Liberty, heading north-east at 20 m/s.
    private val fix = Fix(
        latitude = 40.689247, longitude = -74.044502, accuracyM = 5f, altitudeM = 10.0,
        speedMps = 20f, bearingDeg = 45f, gnssTimeMs = 1_000_000L, elapsedRealtimeMs = 0L,
    )
    private val at = Geodetic(fix.latitude, fix.longitude, 10.0)

    private fun enu(g: Geodetic) = Solver.offset(at, Geo.toEcef(g))

    @Test
    fun movesAlongItsOwnSpeedAndBearing() {
        val d = enu(fix.positionAt(fix.gnssTimeMs + 420))
        // 20 m/s for 0.42 s: 8.4 m, half east and half north.
        assertEquals(8.4, hypot(d[0], d[1]), 0.01)
        assertEquals(d[0], d[1], 0.01)
        assertEquals(0.0, d[2], 0.01)
        // And back for a moment before the fix.
        val back = enu(fix.positionAt(fix.gnssTimeMs - 1_000))
        assertEquals(-20.0 / Math.sqrt(2.0), back[1], 0.01)
    }

    @Test
    fun staysPutWithoutASpeedOrOnAnotherClock() {
        fun moved(f: Fix, utcMs: Long?) = enu(f.positionAt(utcMs)).let { hypot(it[0], it[1]) }
        assertEquals(0.0, moved(fix.copy(speedMps = null), fix.gnssTimeMs + 420), 1e-6)
        assertEquals(0.0, moved(fix.copy(bearingDeg = null), fix.gnssTimeMs + 420), 1e-6)
        assertEquals(0.0, moved(fix, null), 1e-6)
        // An emulator's raw clock runs years off: no move, rather than one to the far side of the Earth.
        assertEquals(0.0, moved(fix, fix.gnssTimeMs + 3_600_000), 1e-6)
    }

    @Test
    fun movedAndBackIsWhereItStarted() {
        val there = Geo.moved(at, 1_234.0, -567.0, 8.0)
        val d = enu(there)
        assertEquals(1_234.0, d[0], 0.5)
        assertEquals(-567.0, d[1], 0.5)
        assertEquals(8.0, d[2], 0.5)
    }
}
