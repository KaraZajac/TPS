package org.soulstone.tps.gnss

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.soulstone.tps.raw.Geodetic
import org.soulstone.tps.raw.Remembered

/** The last trusted position survives the trip through SharedPreferences exactly. */
@RunWith(AndroidJUnit4::class)
class LastTrustedDeviceTest {
    @Test
    fun savesAndLoadsExactly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = LastTrusted(context)
        // The Statue of Liberty: a public place.
        val r = Remembered(Geodetic(40.689247, -74.044502, 93.5), 1_793_600_000_123L, 86_400_123L, 1_793_600_000_456L, 7)
        store.save(r)
        assertEquals(r, LastTrusted(context).load())
        val noBootCount = r.copy(bootCount = null)
        store.save(noBootCount)
        assertEquals(noBootCount, LastTrusted(context).load())
    }
}
