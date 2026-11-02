package org.soulstone.tps.nav

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's waypoints and which one TPS is navigating to, kept on the phone in
 * SharedPreferences. Like everything in TPS, they never leave it.
 */
class WaypointStore private constructor(private val prefs: SharedPreferences) {

    private val _waypoints = MutableStateFlow(WaypointText.deserialize(prefs.getString(KEY_LIST, "") ?: ""))
    val waypoints: StateFlow<List<Waypoint>> = _waypoints.asStateFlow()

    private val _targetId = MutableStateFlow(
        prefs.getLong(KEY_TARGET, NONE).takeIf { id -> _waypoints.value.any { it.id == id } }
    )
    val targetId: StateFlow<Long?> = _targetId.asStateFlow()

    /** Adds parsed waypoints; ones without a label become "WP 1", "WP 2", …. */
    fun add(parsed: List<WaypointText.Parsed>): List<Waypoint> {
        var next = prefs.getLong(KEY_NEXT_ID, 1L)
        val added = parsed.map { p ->
            val id = next++
            Waypoint(id, p.label?.take(MAX_LABEL) ?: "WP $id", p.latitude, p.longitude)
        }
        prefs.edit { putLong(KEY_NEXT_ID, next) }
        save(_waypoints.value + added)
        return added
    }

    fun remove(id: Long) {
        save(_waypoints.value.filterNot { it.id == id })
        if (_targetId.value == id) setTarget(null)
    }

    fun clear() {
        save(emptyList())
        setTarget(null)
    }

    fun setTarget(id: Long?) {
        val valid = id?.takeIf { i -> _waypoints.value.any { it.id == i } }
        prefs.edit { putLong(KEY_TARGET, valid ?: NONE) }
        _targetId.value = valid
    }

    private fun save(list: List<Waypoint>) {
        prefs.edit { putString(KEY_LIST, WaypointText.serialize(list)) }
        _waypoints.value = list
    }

    companion object {
        private const val PREFS = "tps_waypoints"
        private const val KEY_LIST = "waypoints"
        private const val KEY_TARGET = "target_id"
        private const val KEY_NEXT_ID = "next_id"
        private const val NONE = -1L
        private const val MAX_LABEL = 60

        @Volatile private var INSTANCE: WaypointStore? = null

        fun get(context: Context): WaypointStore = INSTANCE ?: synchronized(this) {
            INSTANCE ?: WaypointStore(
                context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ).also { INSTANCE = it }
        }
    }
}
