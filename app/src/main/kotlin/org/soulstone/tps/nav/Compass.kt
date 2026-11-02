package org.soulstone.tps.nav

import android.app.Activity
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which way the top of the screen points, in degrees from true north, for heading-up
 * mode. Uses Android's rotation-vector sensor, corrected for the screen's rotation and
 * for magnetic declination (from Android's built-in World Magnetic Model, offline).
 * Runs only between [start] and [stop]; MainActivity ties those to heading-up mode and
 * to TPS being on screen. Sensors need no permission.
 */
class Compass(private val activity: Activity) : SensorEventListener {

    private val sensors: SensorManager? = activity.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        ?: sensors?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

    val available: Boolean get() = sensor != null

    private val _heading = MutableStateFlow<Float?>(null)

    /** True heading once a position has given the declination; magnetic before that. Null when stopped. */
    val heading: StateFlow<Float?> = _heading.asStateFlow()

    private val _accuracyDeg = MutableStateFlow<Float?>(null)

    /** The sensor's own estimate of its heading error, when it gives one. */
    val accuracyDeg: StateFlow<Float?> = _accuracyDeg.asStateFlow()

    private val _trueNorth = MutableStateFlow(false)
    val trueNorth: StateFlow<Boolean> = _trueNorth.asStateFlow()

    private var running = false
    private var smoothed: Float? = null
    private var declination = 0f
    private var declinationAt: Pair<Double, Double>? = null
    private val matrix = FloatArray(9)
    private val remapped = FloatArray(9)

    fun start() {
        val s = sensor ?: return
        if (running) return
        running = true
        sensors?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        if (!running) return
        running = false
        sensors?.unregisterListener(this)
        smoothed = null
        _heading.value = null
        _accuracyDeg.value = null
    }

    /** Sets the declination from the current position; recomputed after moving about 50 km. */
    fun setPosition(latitude: Double, longitude: Double, altitudeM: Double?) {
        val last = declinationAt
        if (last != null && abs(last.first - latitude) < 0.5 && abs(last.second - longitude) < 0.5) return
        declination = GeomagneticField(
            latitude.toFloat(), longitude.toFloat(), (altitudeM ?: 0.0).toFloat(), System.currentTimeMillis()
        ).declination
        declinationAt = latitude to longitude
        _trueNorth.value = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        // Keep "up" the top of the screen as displayed, in any screen rotation.
        val (x, y) = when (displayRotation()) {
            Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
            Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
            Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
            else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
        }
        SensorManager.remapCoordinateSystem(matrix, x, y, remapped)
        val raw = HeadingMath.fromRotationMatrix(remapped) ?: return
        val next = HeadingMath.smooth(smoothed, raw + declination, SMOOTHING)
        smoothed = next
        val shown = _heading.value
        if (shown == null || abs(Geodesy.angleDifference(shown.toDouble(), next.toDouble())) >= 0.5) {
            _heading.value = next
        }
        // values[4] is the estimated heading accuracy in radians, or -1 when unknown.
        if (event.values.size > 4 && event.values[4] > 0f) {
            _accuracyDeg.value = Math.toDegrees(event.values[4].toDouble()).toFloat()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= 30) activity.display?.rotation ?: Surface.ROTATION_0
        else @Suppress("DEPRECATION") activity.windowManager.defaultDisplay.rotation

    private companion object {
        /** Weight of each new reading (about 15 a second): steady, but follows a turn in well under a second. */
        const val SMOOTHING = 0.25f
    }
}
