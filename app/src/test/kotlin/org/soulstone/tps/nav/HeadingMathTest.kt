package org.soulstone.tps.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingMathTest {

    /** Row-major matrix whose columns are the phone's X, Y and Z axes in east/north/up. */
    private fun axes(x: FloatArray, y: FloatArray, z: FloatArray) =
        floatArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])

    private val east = floatArrayOf(1f, 0f, 0f)
    private val west = floatArrayOf(-1f, 0f, 0f)
    private val north = floatArrayOf(0f, 1f, 0f)
    private val south = floatArrayOf(0f, -1f, 0f)
    private val up = floatArrayOf(0f, 0f, 1f)
    private val down = floatArrayOf(0f, 0f, -1f)

    @Test
    fun flatWithTheTopPointingNorth() = assertEquals(0f, HeadingMath.fromRotationMatrix(axes(east, north, up))!!, 1e-4f)

    @Test
    fun flatWithTheTopPointingEast() = assertEquals(90f, HeadingMath.fromRotationMatrix(axes(south, east, up))!!, 1e-4f)

    @Test
    fun uprightFacingNorthUsesTheBack() =
        assertEquals(0f, HeadingMath.fromRotationMatrix(axes(east, up, south))!!, 1e-4f)

    @Test
    fun uprightFacingEast() = assertEquals(90f, HeadingMath.fromRotationMatrix(axes(south, up, west))!!, 1e-4f)

    @Test
    fun tiltedHalfwayStillAgrees() {
        // Pitched 45° forward while facing west: Y and the back both lean west.
        val s = Math.sqrt(0.5).toFloat()
        val y = floatArrayOf(-s, 0f, s)
        val z = floatArrayOf(s, 0f, s)
        assertEquals(270f, HeadingMath.fromRotationMatrix(axes(south, y, z))!!, 1e-3f)
    }

    @Test
    fun uprightUpsideDownStillWorks() =
        // Screen facing north with the top pointing down: the back looks south.
        assertEquals(180f, HeadingMath.fromRotationMatrix(axes(east, down, north))!!, 1e-4f)

    @Test
    fun undefinedWhenTopAndBackPointOppositeWays() {
        // Top edge tipped up towards the north, screen tipped down towards it: the top's
        // and the back's horizontal directions cancel.
        val s = Math.sqrt(0.5).toFloat()
        assertNull(HeadingMath.fromRotationMatrix(axes(west, floatArrayOf(0f, s, s), floatArrayOf(0f, s, -s))))
    }

    @Test
    fun smoothingWrapsAroundNorth() {
        assertEquals(0f, HeadingMath.smooth(350f, 10f, 0.5f), 1e-3f)
        assertEquals(355f, HeadingMath.smooth(350f, 0f, 0.5f), 1e-3f)
        assertEquals(42f, HeadingMath.smooth(null, 42f, 0.2f), 1e-6f)
    }

    @Test
    fun normalizes() {
        assertEquals(350f, HeadingMath.normalize(-10f), 1e-6f)
        assertEquals(10f, HeadingMath.normalize(370f), 1e-6f)
    }
}
