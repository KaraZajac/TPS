package org.soulstone.tps.nav

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Which way the phone points, from Android's rotation-vector sensor. Plain Kotlin, unit-tested. */
object HeadingMath {

    /**
     * The heading of the top of the screen, in degrees clockwise from north, from a
     * row-major 3×3 rotation matrix that takes phone axes to east/north/up (what
     * `SensorManager.getRotationMatrixFromVector` returns).
     *
     * Held flat, the screen's top edge (the phone's Y axis) points the way. Held upright,
     * that edge points at the sky, and the phone's back (−Z, where the camera looks)
     * points the way. Adding the two horizontal directions blends smoothly between
     * them, so the heading doesn't jump as the phone tilts. Null when the phone lies on
     * its back with the top tipped towards you, where neither works.
     */
    fun fromRotationMatrix(r: FloatArray): Float? {
        val east = r[1] - r[2]
        val north = r[4] - r[5]
        if (hypot(east, north) < 0.1f) return null
        return normalize(Math.toDegrees(atan2(east.toDouble(), north.toDouble())).toFloat())
    }

    /**
     * Exponential smoothing for angles, done on unit vectors so 359° and 1° average to 0°,
     * not 180°. [alpha] is the weight of the new reading.
     */
    fun smooth(previous: Float?, next: Float, alpha: Float): Float {
        if (previous == null) return normalize(next)
        val p = Math.toRadians(previous.toDouble())
        val n = Math.toRadians(next.toDouble())
        val x = (1 - alpha) * cos(p) + alpha * cos(n)
        val y = (1 - alpha) * sin(p) + alpha * sin(n)
        return normalize(Math.toDegrees(atan2(y, x)).toFloat())
    }

    fun normalize(deg: Float): Float = ((deg % 360f) + 360f) % 360f
}
