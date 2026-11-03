package org.soulstone.tps.gnss

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.soulstone.tps.raw.Geodetic
import org.soulstone.tps.raw.Remembered

/**
 * Where and when TPS last trusted its own position, for the integrity checks at the next
 * start-up (raw/Integrity.kt). One record, on the phone only, like the waypoints.
 */
internal class LastTrusted(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): Remembered? = runCatching {
        if (!prefs.contains(KEY_LAT)) return null
        Remembered(
            position = Geodetic(double(KEY_LAT), double(KEY_LON), double(KEY_HEIGHT)),
            gnssTimeMs = prefs.getLong(KEY_GNSS, 0),
            elapsedMs = prefs.getLong(KEY_ELAPSED, 0),
            wallMs = prefs.getLong(KEY_WALL, 0),
            bootCount = prefs.getInt(KEY_BOOT, -1).takeIf { it >= 0 },
        )
    }.getOrNull()

    fun save(r: Remembered) {
        prefs.edit {
            putLong(KEY_LAT, r.position.latitude.toRawBits())
            putLong(KEY_LON, r.position.longitude.toRawBits())
            putLong(KEY_HEIGHT, r.position.height.toRawBits())
            putLong(KEY_GNSS, r.gnssTimeMs)
            putLong(KEY_ELAPSED, r.elapsedMs)
            putLong(KEY_WALL, r.wallMs)
            putInt(KEY_BOOT, r.bootCount ?: -1)
        }
    }

    private fun double(key: String) = Double.fromBits(prefs.getLong(key, 0))

    private companion object {
        const val PREFS = "tps_last_trusted"
        const val KEY_LAT = "latitude"
        const val KEY_LON = "longitude"
        const val KEY_HEIGHT = "height"
        const val KEY_GNSS = "gnss_time_ms"
        const val KEY_ELAPSED = "elapsed_ms"
        const val KEY_WALL = "wall_ms"
        const val KEY_BOOT = "boot_count"
    }
}
