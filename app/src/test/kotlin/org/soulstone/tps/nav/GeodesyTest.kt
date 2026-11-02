package org.soulstone.tps.nav

import org.junit.Assert.assertEquals
import org.junit.Test

class GeodesyTest {

    @Test
    fun oneDegreeOfLatitude() {
        assertEquals(111_195.08, Geodesy.distanceMeters(0.0, 0.0, 1.0, 0.0), 0.01)
    }

    @Test
    fun jfkToLax() {
        assertEquals(3_974_341.7, Geodesy.distanceMeters(40.6413, -73.7781, 33.9416, -118.4085), 1.0)
        assertEquals(273.842, Geodesy.bearingDeg(40.6413, -73.7781, 33.9416, -118.4085), 0.001)
    }

    @Test
    fun samePlaceIsZero() {
        assertEquals(0.0, Geodesy.distanceMeters(44.46044, -110.82814, 44.46044, -110.82814), 1e-9)
    }

    @Test
    fun cardinalBearings() {
        assertEquals(0.0, Geodesy.bearingDeg(0.0, 0.0, 1.0, 0.0), 1e-9)
        assertEquals(90.0, Geodesy.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(180.0, Geodesy.bearingDeg(1.0, 0.0, 0.0, 0.0), 1e-9)
        assertEquals(270.0, Geodesy.bearingDeg(0.0, 1.0, 0.0, 0.0), 1e-9)
    }

    @Test
    fun crossingTheAntimeridian() {
        assertEquals(90.0, Geodesy.bearingDeg(0.0, 179.5, 0.0, -179.5), 1e-6)
        assertEquals(111_195.08, Geodesy.distanceMeters(0.0, 179.5, 0.0, -179.5), 0.01)
    }

    @Test
    fun compassPoints() {
        assertEquals("N", Geodesy.compassPoint(0.0))
        assertEquals("N", Geodesy.compassPoint(359.0))
        assertEquals("NNE", Geodesy.compassPoint(22.5))
        assertEquals("E", Geodesy.compassPoint(90.0))
        assertEquals("WSW", Geodesy.compassPoint(245.0))
        assertEquals("N", Geodesy.compassPoint(-10.0))
    }

    @Test
    fun angleDifferences() {
        assertEquals(20.0, Geodesy.angleDifference(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geodesy.angleDifference(10.0, 350.0), 1e-9)
        assertEquals(180.0, Geodesy.angleDifference(0.0, 180.0), 1e-9)
    }
}
