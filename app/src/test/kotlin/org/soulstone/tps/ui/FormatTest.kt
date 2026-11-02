package org.soulstone.tps.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.soulstone.tps.data.Settings.Units

class FormatTest {

    @Test
    fun decimalDegrees() {
        assertEquals("1.28680° N 103.85453° E", Format.latLonDecimal(1.28680, 103.85453))
        assertEquals("40.68925° N 74.04450° W", Format.latLonDecimal(40.689250, -74.044500))
        assertEquals("33.86785° S 151.20732° E", Format.latLonDecimal(-33.867850, 151.207320))
    }

    @Test
    fun tinyNegativeRoundsToTheNorthernHemisphere() {
        assertEquals("0.00000° N", Format.decimal(-0.000001, 'N', 'S'))
    }

    @Test
    fun degreesMinutesSeconds() {
        assertEquals("40°41'21.3\" N", Format.dms(40.68925, 'N', 'S'))
        assertEquals("74°02'40.2\" W", Format.dms(-74.04450, 'E', 'W'))
    }

    @Test
    fun dmsRoundingCarries() {
        assertEquals("2°00'00.0\" N", Format.dms(1.9999999, 'N', 'S'))
        assertEquals("0°00'00.0\" N", Format.dms(-0.00001, 'N', 'S'))
    }

    @Test
    fun distancesAndHeights() {
        assertEquals("3.2 m", Format.distance(3.24, Units.METRIC))
        assertEquals("14 m", Format.distance(14.4, Units.METRIC))
        assertEquals("10 ft", Format.distance(3.048, Units.IMPERIAL))
        assertEquals("72 m", Format.height(72.4, Units.METRIC))
        assertEquals("-3 m", Format.height(-3.4, Units.METRIC))
        assertEquals("236 ft", Format.height(72.0, Units.IMPERIAL))
        assertEquals("11.0 m", Format.heightPrecise(11.0, Units.METRIC))
    }

    @Test
    fun speeds() {
        assertEquals("0.0 m/s", Format.speed(0.0, Units.METRIC))
        assertEquals("22.4 mph", Format.speed(10.0, Units.IMPERIAL))
    }

    @Test
    fun durations() {
        assertEquals("6.8 s", Format.duration(6_800))
        assertEquals("45 s", Format.duration(45_200))
        assertEquals("2 min", Format.duration(125_000))
    }

    @Test
    fun clockOffsets() {
        assertEquals("+0.04 s", Format.signedSeconds(40))
        assertEquals("-1.20 s", Format.signedSeconds(-1_200))
        assertEquals("+12 s", Format.signedSeconds(12_345))
    }

    @Test
    fun utcTimes() {
        assertEquals("00:00:00 UTC", Format.utcTime(0))
        assertEquals("23:59:59 UTC", Format.utcTime(86_399_000))
    }
}
