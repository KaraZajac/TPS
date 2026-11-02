package org.soulstone.tps.gnss

import org.junit.Assert.assertEquals
import org.junit.Test

class FixQualityTest {

    private fun tier(running: Boolean = true, hasFix: Boolean = true, type: FixType? = FixType.THREE_D, accuracy: Float? = 3f) =
        FixQuality.tier(running, hasFix, type, accuracy)

    @Test
    fun idleAndSearching() {
        assertEquals(FixTier.IDLE, tier(running = false))
        assertEquals(FixTier.SEARCHING, tier(hasFix = false))
        assertEquals(FixTier.SEARCHING, tier(type = FixType.NO_FIX))
    }

    @Test
    fun accuracyTiers() {
        assertEquals(FixTier.GOOD, tier(accuracy = 1.2f))
        assertEquals(FixTier.GOOD, tier(accuracy = 5f))
        assertEquals(FixTier.FAIR, tier(accuracy = 5.1f))
        assertEquals(FixTier.FAIR, tier(accuracy = 15f))
        assertEquals(FixTier.POOR, tier(accuracy = 15.1f))
        assertEquals(FixTier.POOR, tier(accuracy = null))
    }

    @Test
    fun twoDimensionalIsOrangeWhateverTheAccuracy() {
        assertEquals(FixTier.POOR, tier(type = FixType.TWO_D, accuracy = 2f))
    }

    @Test
    fun unknownFixTypeGoesByAccuracy() {
        assertEquals(FixTier.GOOD, tier(type = null, accuracy = 2f))
    }

    @Test
    fun inferredFixType() {
        assertEquals(FixType.NO_FIX, FixQuality.inferFixType(hasFix = false, hasAltitude = true, usedSatellites = 9))
        assertEquals(FixType.TWO_D, FixQuality.inferFixType(hasFix = true, hasAltitude = true, usedSatellites = 3))
        assertEquals(FixType.TWO_D, FixQuality.inferFixType(hasFix = true, hasAltitude = false, usedSatellites = 9))
        assertEquals(FixType.THREE_D, FixQuality.inferFixType(hasFix = true, hasAltitude = true, usedSatellites = 9))
        // Chips that never flag satellites as used still produce 3D fixes.
        assertEquals(FixType.THREE_D, FixQuality.inferFixType(hasFix = true, hasAltitude = true, usedSatellites = 0))
    }
}
