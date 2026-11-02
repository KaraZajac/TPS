package org.soulstone.tps.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide preferences, backed by SharedPreferences (the OVERWATCH pattern: no
 * DataStore). Each one is a [StateFlow] for Compose; setters write through to disk
 * and update the flow synchronously.
 */
class Settings private constructor(private val prefs: SharedPreferences) {

    enum class ThemeMode { SYSTEM, DARK, LIGHT }
    enum class Units { METRIC, IMPERIAL }
    enum class CoordinateFormat { DECIMAL, DMS }

    private inline fun <reified T : Enum<T>> enumPref(key: String, default: T): T =
        runCatching { enumValueOf<T>(prefs.getString(key, null) ?: default.name) }.getOrDefault(default)

    private val _themeMode = MutableStateFlow(enumPref(KEY_THEME, ThemeMode.DARK))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _units = MutableStateFlow(enumPref(KEY_UNITS, Units.METRIC))
    val units: StateFlow<Units> = _units.asStateFlow()

    private val _coordinates = MutableStateFlow(enumPref(KEY_COORDINATES, CoordinateFormat.DECIMAL))
    val coordinates: StateFlow<CoordinateFormat> = _coordinates.asStateFlow()

    // On by default: with colour alone, GPS and BeiDou look the same to someone with
    // red-green colour blindness (see PLAN.md §3).
    private val _shapes = MutableStateFlow(prefs.getBoolean(KEY_SHAPES, true))
    val shapes: StateFlow<Boolean> = _shapes.asStateFlow()

    private val _labels = MutableStateFlow(prefs.getBoolean(KEY_LABELS, false))
    val labels: StateFlow<Boolean> = _labels.asStateFlow()

    private val _keepScreenOn = MutableStateFlow(prefs.getBoolean(KEY_KEEP_SCREEN_ON, true))
    val keepScreenOn: StateFlow<Boolean> = _keepScreenOn.asStateFlow()

    private val _demoSky = MutableStateFlow(prefs.getBoolean(KEY_DEMO_SKY, false))
    val demoSky: StateFlow<Boolean> = _demoSky.asStateFlow()

    fun setThemeMode(v: ThemeMode) { prefs.edit { putString(KEY_THEME, v.name) }; _themeMode.value = v }
    fun setUnits(v: Units) { prefs.edit { putString(KEY_UNITS, v.name) }; _units.value = v }
    fun setCoordinates(v: CoordinateFormat) { prefs.edit { putString(KEY_COORDINATES, v.name) }; _coordinates.value = v }
    fun setShapes(v: Boolean) { prefs.edit { putBoolean(KEY_SHAPES, v) }; _shapes.value = v }
    fun setLabels(v: Boolean) { prefs.edit { putBoolean(KEY_LABELS, v) }; _labels.value = v }
    fun setKeepScreenOn(v: Boolean) { prefs.edit { putBoolean(KEY_KEEP_SCREEN_ON, v) }; _keepScreenOn.value = v }
    fun setDemoSky(v: Boolean) { prefs.edit { putBoolean(KEY_DEMO_SKY, v) }; _demoSky.value = v }

    companion object {
        private const val PREFS = "tps_settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_UNITS = "units"
        private const val KEY_COORDINATES = "coordinate_format"
        private const val KEY_SHAPES = "constellation_shapes"
        private const val KEY_LABELS = "satellite_labels"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_DEMO_SKY = "demo_sky"

        @Volatile private var INSTANCE: Settings? = null

        fun get(context: Context): Settings = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Settings(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ).also { INSTANCE = it }
        }
    }
}
