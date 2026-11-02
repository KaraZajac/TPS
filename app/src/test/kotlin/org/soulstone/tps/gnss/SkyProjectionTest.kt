package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Test

class SkyProjectionTest {

    private fun assertAt(x: Float, y: Float, p: SkyPoint) {
        assertEquals("x", x, p.x, 1e-5f)
        assertEquals("y", y, p.y, 1e-5f)
    }

    @Test
    fun overheadIsTheCentre() = assertAt(0f, 0f, SkyProjection.project(123f, 90f))

    @Test
    fun northUpEastRightOnTheHorizon() {
        assertAt(0f, -1f, SkyProjection.project(0f, 0f))
        assertAt(1f, 0f, SkyProjection.project(90f, 0f))
        assertAt(0f, 1f, SkyProjection.project(180f, 0f))
        assertAt(-1f, 0f, SkyProjection.project(270f, 0f))
    }

    @Test
    fun radiusIsLinearInZenithAngle() {
        assertAt(0.5f, 0f, SkyProjection.project(90f, 45f))
        assertAt(0f, -2f / 3f, SkyProjection.project(0f, 30f))
        assertAt(0f, -1f / 3f, SkyProjection.project(0f, 60f))
    }

    @Test
    fun headingRotatesTheSky() {
        // Facing east, east is up.
        assertAt(0f, -1f, SkyProjection.project(90f, 0f, headingDeg = 90f))
        assertAt(-1f, 0f, SkyProjection.project(0f, 0f, headingDeg = 90f))
    }

    @Test
    fun belowTheHorizonClampsToTheEdge() = assertAt(0f, -1f, SkyProjection.project(0f, -10f))
}
