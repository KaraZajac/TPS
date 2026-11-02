package org.soulstone.tps.nav

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and direction between two positions, on a sphere of the Earth's mean
 * radius. Within 0.5% of the ellipsoidal answer, which is plenty for walking to a
 * waypoint, and plain Kotlin so it's unit-tested.
 */
object Geodesy {

    const val EARTH_RADIUS_M = 6_371_008.8

    /** Great-circle distance (haversine). */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
    }

    /** Initial bearing from the first position to the second, degrees clockwise from true north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    private val POINTS = listOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )

    /** One of the sixteen compass points: 245° is WSW. */
    fun compassPoint(bearingDeg: Double): String =
        POINTS[(((bearingDeg % 360 + 360) % 360) / 22.5).roundToInt() % 16]

    /** The signed difference b − a, in (−180, 180]. */
    fun angleDifference(a: Double, b: Double): Double {
        val d = ((b - a) % 360 + 360) % 360
        return if (d > 180) d - 360 else d
    }
}
