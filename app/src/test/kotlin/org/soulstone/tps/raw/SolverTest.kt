package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

class SolverTest {

    private val home = Geodetic(40.6892, -74.0445, 68.0)
    private val homeEcef = Geo.toEcef(home)

    @Test
    fun geodeticRoundTrip() {
        for (g in listOf(home, Geodetic(-33.86785, 151.20732, 5.0), Geodetic(89.9, 10.0, 1000.0), Geodetic(0.0, 0.0, -20.0))) {
            val back = Geo.toGeodetic(Geo.toEcef(g))
            assertEquals(g.latitude, back.latitude, 1e-9)
            assertEquals(g.longitude, back.longitude, 1e-9)
            assertEquals(g.height, back.height, 1e-4)
        }
    }

    @Test
    fun keplerOrbitIsOnItsEllipse() {
        val e = SyntheticOrbits.gps()[0]
        val s = Orbits.state(e, SyntheticOrbits.TOE + 1234.0)
        val a = e.sqrtA * e.sqrtA
        val r = s.position.norm()
        assertTrue("radius $r", r in a * (1 - e.e) - 1000..a * (1 + e.e) + 1000)
    }

    @Test
    fun ionosphereAndTroposphereArePlausible() {
        val k = Klobuchar(doubleArrayOf(1.1e-8, 1.5e-8, -6.0e-8, -6.0e-8), doubleArrayOf(90112.0, 0.0, -196608.0, -65536.0))
        val iono = Atmosphere.klobuchar(k, home, 1.0, Math.toRadians(45.0), 50_400.0)
        assertTrue("iono $iono", iono in 1.0..20.0)
        val zenith = Atmosphere.troposphere(home, Math.toRadians(90.0))
        assertEquals(2.4, zenith, 0.2)
        assertTrue(Atmosphere.troposphere(home, Math.toRadians(10.0)) > 5 * zenith)
    }

    /** Synthetic observations from satellites at given directions, 20 200 km up. */
    private fun observations(clocks: Map<Constellation, Double>, directions: List<Pair<Double, Double>>, c: (Int) -> Constellation): List<Observation> =
        directions.mapIndexed { i, (azDeg, elDeg) ->
            val az = Math.toRadians(azDeg)
            val el = Math.toRadians(elDeg)
            val range = 22_000_000.0
            val e = range * kotlin.math.cos(el) * kotlin.math.sin(az)
            val n = range * kotlin.math.cos(el) * kotlin.math.cos(az)
            val u = range * kotlin.math.sin(el)
            val lat = Math.toRadians(home.latitude)
            val lon = Math.toRadians(home.longitude)
            val sat = Ecef(
                homeEcef.x - kotlin.math.sin(lon) * e - kotlin.math.sin(lat) * kotlin.math.cos(lon) * n + kotlin.math.cos(lat) * kotlin.math.cos(lon) * u,
                homeEcef.y + kotlin.math.cos(lon) * e - kotlin.math.sin(lat) * kotlin.math.sin(lon) * n + kotlin.math.cos(lat) * kotlin.math.sin(lon) * u,
                homeEcef.z + kotlin.math.cos(lat) * n + kotlin.math.sin(lat) * u,
            )
            val sagnac = 7.2921151467e-5 * (sat.x * homeEcef.y - sat.y * homeEcef.x) / Pseudoranges.C
            val pr = (sat - homeEcef).norm() + sagnac + clocks.getValue(c(i)) + Atmosphere.troposphere(home, el)
            Observation(c(i), i + 1, sat, 0.0, pr, 3.0, 1575.42e6)
        }

    private val sky = listOf(0.0 to 80.0, 45.0 to 40.0, 120.0 to 30.0, 200.0 to 55.0, 280.0 to 25.0, 330.0 to 60.0, 160.0 to 15.0, 90.0 to 20.0)

    private fun distance(s: Solution) = (s.ecef - homeEcef).norm()

    @Test
    fun recoversThePositionFromAFarStart() {
        val obs = observations(mapOf(Constellation.GPS to 12_345.0), sky) { Constellation.GPS }
        val s = Solver.solve(obs, start = null, klobuchar = null, gpsSeconds = 0.0)!!
        assertEquals(0.0, distance(s), 1e-3)
        assertEquals(12_345.0, s.clocks.getValue(Constellation.GPS), 1e-3)
        assertTrue(s.residualRms < 1e-3)
        assertTrue(s.hdop!! in 0.5..3.0)
    }

    @Test
    fun aClockPerSystem() {
        val obs = observations(mapOf(Constellation.GPS to 1_000.0, Constellation.GALILEO to 1_030.0), sky) {
            if (it % 2 == 0) Constellation.GPS else Constellation.GALILEO
        }
        val s = Solver.solve(obs, homeEcef, null, 0.0)!!
        assertEquals(0.0, distance(s), 1e-3)
        assertEquals(1_030.0 - 1_000.0, s.clocks.getValue(Constellation.GALILEO) - s.clocks.getValue(Constellation.GPS), 1e-3)
    }

    @Test
    fun anOutlierIsDropped() {
        val good = observations(mapOf(Constellation.GPS to 500.0), sky) { Constellation.GPS }
        val bad = good.mapIndexed { i, o -> if (i == 3) o.copy(pseudorange = o.pseudorange + 400.0) else o }
        val s = Solver.solve(bad, homeEcef, null, 0.0)!!
        assertTrue("off by ${distance(s)} m", distance(s) < 0.01)
        assertEquals(good.size - 1, s.satellites.size)
    }

    @Test
    fun tooFewSatellites() {
        val obs = observations(mapOf(Constellation.GPS to 0.0), sky.take(3)) { Constellation.GPS }
        assertNull(Solver.solve(obs, homeEcef, null, 0.0))
    }

    @Test
    fun offsetIsEastNorthUp() {
        val east = Geo.toEcef(Geodetic(home.latitude, home.longitude + 0.0001, home.height))
        val d = Solver.offset(home, east)
        assertEquals(8.45, d[0], 0.05)          // 0.0001° of longitude at 40.69°N
        assertTrue(abs(d[1]) < 0.01 && abs(d[2]) < 0.01)
        assertEquals(sqrt(d[0] * d[0]), abs(d[0]), 0.0)
        assertEquals(0.0, floor(0.0), 0.0)
    }
}
