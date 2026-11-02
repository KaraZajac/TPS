package org.soulstone.tps.ui

import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToLong
import org.soulstone.tps.data.Settings.Units

/**
 * Text for numbers on screen. Plain Kotlin (no Android), so it is unit-tested, and
 * always formatted with [Locale.ROOT] so digits and decimal points don't change with
 * the phone's language.
 */
object Format {

    private const val FEET_PER_METRE = 3.280839895
    private const val MPH_PER_MPS = 2.236936292

    /** `1.28680° N 103.85453° E`: five decimals, about a metre. */
    fun latLonDecimal(lat: Double, lon: Double): String = decimal(lat, 'N', 'S') + " " + decimal(lon, 'E', 'W')

    /** `1°17'12.5" N 103°51'16.3" E`: tenths of an arc-second, about 3 m. */
    fun latLonDms(lat: Double, lon: Double): String = dms(lat, 'N', 'S') + " " + dms(lon, 'E', 'W')

    fun decimal(value: Double, positive: Char, negative: Char): String {
        val text = String.format(Locale.ROOT, "%.5f", abs(value))
        // Hemisphere from the rounded value, so -0.000001 reads 0.00000° N, not S.
        val hemisphere = if (value < 0 && text.any { it in '1'..'9' }) negative else positive
        return "$text° $hemisphere"
    }

    fun dms(value: Double, positive: Char, negative: Char): String {
        // Round once, in tenths of a second, so 59.96" carries into the minutes.
        val tenths = (abs(value) * 36_000.0).roundToLong()
        val degrees = tenths / 36_000
        val minutes = (tenths % 36_000) / 600
        val seconds = (tenths % 600) / 10.0
        val hemisphere = if (value < 0 && tenths > 0) negative else positive
        return String.format(Locale.ROOT, "%d°%02d'%04.1f\" %c", degrees, minutes, seconds, hemisphere)
    }

    /** Accuracies and short distances: `3.2 m`, `14 m`, `10 ft`. */
    fun distance(m: Double, units: Units): String = when (units) {
        Units.METRIC -> if (m < 10) String.format(Locale.ROOT, "%.1f m", m) else "${m.roundToLong()} m"
        Units.IMPERIAL -> "${(m * FEET_PER_METRE).roundToLong()} ft"
    }

    /** Heights: `72 m`, `-3 m`, `236 ft`. */
    fun height(m: Double, units: Units): String = when (units) {
        Units.METRIC -> "${m.roundToLong()} m"
        Units.IMPERIAL -> "${(m * FEET_PER_METRE).roundToLong()} ft"
    }

    /** Heights to a decimetre, for the detail card: `11.0 m`. */
    fun heightPrecise(m: Double, units: Units): String = when (units) {
        Units.METRIC -> String.format(Locale.ROOT, "%.1f m", m)
        Units.IMPERIAL -> String.format(Locale.ROOT, "%.1f ft", m * FEET_PER_METRE)
    }

    fun speed(mps: Double, units: Units): String = when (units) {
        Units.METRIC -> String.format(Locale.ROOT, "%.1f m/s", mps)
        Units.IMPERIAL -> String.format(Locale.ROOT, "%.1f mph", mps * MPH_PER_MPS)
    }

    /** Durations: `6.8 s` under ten seconds, `45 s` under two minutes, then `12 min`. */
    fun duration(ms: Long): String = when {
        ms < 10_000 -> String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
        ms < 120_000 -> "${(ms / 1000.0).roundToLong()} s"
        else -> "${ms / 60_000} min"
    }

    /** A clock difference: `+0.04 s`, `-1.20 s`, `+12 s`. */
    fun signedSeconds(ms: Long): String {
        val sign = if (ms < 0) "-" else "+"
        val a = abs(ms)
        return if (a < 10_000) sign + String.format(Locale.ROOT, "%.2f s", a / 1000.0) else "$sign${a / 1000} s"
    }

    fun dop(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

    fun degrees(value: Double): String = "${value.roundToLong()}°"

    /** `19:46:02 UTC`. */
    fun utcTime(epochMs: Long): String {
        val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        c.timeInMillis = epochMs
        return String.format(
            Locale.ROOT, "%02d:%02d:%02d UTC",
            c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), c.get(java.util.Calendar.SECOND)
        )
    }
}
