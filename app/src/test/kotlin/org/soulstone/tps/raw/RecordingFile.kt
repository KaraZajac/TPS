package org.soulstone.tps.raw

import java.util.SortedMap
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.Signal
import org.soulstone.tps.gnss.SkySnapshot

/**
 * A raw recording made by TPS (GnssLogger format plus `SatPvt` lines), read into what the
 * pipeline takes: each round's measurements with the chip's satellite positions, and the
 * chip's own fixes and sky beside them. Shared by the tests that replay one.
 */
class RecordingFile private constructor(
    val moments: List<Moment>,
    val fixes: List<ChipFix>,
    private val status: SortedMap<Long, MutableList<List<String>>>,
    private val statusColumns: Map<String, Int>,
    /** What the phone's own integrity checks showed (dev.8 on): utcTimeMillis, Level, Check, Text. */
    val phoneIntegrity: List<List<String>>,
    /** The motion sensors' verdict (dev.9 on), by wall time. */
    val motion: SortedMap<Long, Motion>,
    /** The barometer's second-by-second mean (dev.10 on), by wall time, hPa. */
    val pressure: SortedMap<Long, Double>,
    val hasSatellitePvt: Boolean,
    /** Where the receiver really was, by the round's TimeNanos: only in recordings made up by the reference model. */
    val truth: Map<Long, Truth>,
    /** The figures behind each motion verdict as the phone recorded them: accelerometer sd, gyroscope rms (by wall time). */
    val motionFigures: SortedMap<Long, Pair<Double?, Double?>> = sortedMapOf(),
    /** The motion sensors themselves (dev.13 on), 25 a second, by the phone's monotonic clock. */
    val accel: List<Sample> = emptyList(),
    val gyro: List<Sample> = emptyList(),
    /** The monotonic clock (ns) and UTC (ms) at one moment, to put the two side by side. */
    val clockPair: Pair<Long, Long>? = null,
) {
    class Sample(val tNs: Long, val x: Double, val y: Double, val z: Double)

    /** [utcMs] on the monotonic clock, ns, if the recording pairs the two. */
    fun elapsedNs(utcMs: Long): Long? = clockPair?.let { (ns, ms) -> ns + (utcMs - ms) * 1_000_000L }

    class Moment(val epoch: RawEpoch, val utcMs: Long, val elapsedMs: Long?, val wallMs: Long?)

    class ChipFix(
        val utcMs: Long, val at: Geodetic, val accuracyM: Double?, val elapsedMs: Long?,
        val speedMps: Double? = null, val bearingDeg: Double? = null,
    )

    /** The truth behind a made-up round: position and velocity (Earth-fixed), clock drift, and each system's bias the chip didn't report, metres. */
    class Truth(val ecef: Ecef, val velocity: Ecef, val clockDriftMps: Double, val biases: Map<Constellation, Double>)

    /** The chip's fix nearest [utcMs], if within 0.6 s. */
    fun fixNear(utcMs: Long): ChipFix? = fixes.minByOrNull { kotlin.math.abs(it.utcMs - utcMs) }?.takeIf { kotlin.math.abs(it.utcMs - utcMs) <= 600 }

    private fun statusAt(utcMs: Long) = status.headMap(utcMs + 1).entries.lastOrNull()?.value

    /** The chip's sky at [utcMs] (its latest status at or before it). */
    fun skyAt(utcMs: Long): SkySnapshot? = statusAt(utcMs)?.let { rows ->
        SkySnapshot.of(rows.map { v ->
            Signal(
                Constellation.fromAndroid(v[statusColumns.getValue("ConstellationType")].toInt()), v[statusColumns.getValue("Svid")].toInt(),
                v[statusColumns.getValue("Cn0DbHz")].toFloat(), v[statusColumns.getValue("ElevationDegrees")].toFloat(),
                v[statusColumns.getValue("AzimuthDegrees")].toFloat(), usedInFix = v[statusColumns.getValue("UsedInFix")] == "1",
            )
        })
    }

    /** The signal levels at [utcMs], as the integrity checks take them. */
    fun levelsAt(utcMs: Long): List<SignalLevel> = statusAt(utcMs).orEmpty().map { v ->
        SignalLevel(
            Constellation.fromAndroid(v[statusColumns.getValue("ConstellationType")].toInt()), v[statusColumns.getValue("Svid")].toInt(),
            v[statusColumns.getValue("Cn0DbHz")].toDouble(), v[statusColumns.getValue("ElevationDegrees")].toDouble(),
        )
    }.filter { it.cn0DbHz > 0 }

    companion object {
        private fun columns(header: String) = header.removePrefix("# ").split(",").drop(1).withIndex().associate { it.value to it.index }

        fun read(lines: Sequence<String>): RecordingFile {
            val headers = HashMap<String, Map<String, Int>>()
            val raw = LinkedHashMap<Long, MutableList<List<String>>>()
            val pvt = HashMap<Triple<Long, Int, Int>, List<String>>()
            val fixes = ArrayList<ChipFix>()
            val status = sortedMapOf<Long, MutableList<List<String>>>()
            // The API 33 AGC list, by the event's wall time: utcTimeMillis, ConstellationType, CarrierFrequencyHz, AgcDb.
            val agcLines = HashMap<Long, MutableList<Agc>>()
            val phoneIntegrity = ArrayList<List<String>>()
            val motionLines = sortedMapOf<Long, Motion>()
            val motionFigures = sortedMapOf<Long, Pair<Double?, Double?>>()
            val accel = ArrayList<Sample>()
            val gyro = ArrayList<Sample>()
            var clockPair: Pair<Long, Long>? = null
            val baroLines = sortedMapOf<Long, Double>()
            val truth = HashMap<Long, Truth>()
            lines.forEach { line ->
                if (line.startsWith("# Clock: ")) {
                    // "# Clock: elapsedRealtimeNanos <ns> at utcTimeMillis <ms>"
                    val w = line.split(" ")
                    clockPair = w.getOrNull(3)?.toLongOrNull()?.let { ns -> w.getOrNull(6)?.toLongOrNull()?.let { ms -> ns to ms } }
                    return@forEach
                }
                if (line.startsWith("# ")) {
                    val kind = line.removePrefix("# ").substringBefore(",")
                    if (line.count { it == ',' } > 3) headers[kind] = columns(line)
                    return@forEach
                }
                val f = line.split(",")
                val values = f.drop(1)
                when (f[0]) {
                    "Raw" -> raw.getOrPut(values[headers.getValue("Raw").getValue("TimeNanos")].toLong()) { ArrayList() } += values
                    "SatPvt" -> headers.getValue("SatPvt").let { h ->
                        pvt[Triple(values[h.getValue("TimeNanos")].toLong(), values[h.getValue("ConstellationType")].toInt(), values[h.getValue("Svid")].toInt())] = values
                    }
                    // The chip's fixes only; "TPS" lines are TPS's own from the phone.
                    "Fix" -> headers.getValue("Fix").takeIf { h -> values[h.getValue("Provider")] == "GPS" }?.let { h ->
                        fixes += ChipFix(
                            values[h.getValue("UnixTimeMillis")].toLong(),
                            Geodetic(
                                values[h.getValue("LatitudeDegrees")].toDouble(),
                                values[h.getValue("LongitudeDegrees")].toDouble(),
                                values[h.getValue("AltitudeMeters")].toDoubleOrNull() ?: 0.0,
                            ),
                            values[h.getValue("AccuracyMeters")].toDoubleOrNull(),
                            values[h.getValue("elapsedRealtimeNanos")].toLongOrNull()?.div(1_000_000),
                            values[h.getValue("SpeedMps")].toDoubleOrNull(),
                            values[h.getValue("BearingDegrees")].toDoubleOrNull(),
                        )
                    }
                    "Status" -> status.getOrPut(values[headers.getValue("Status").getValue("UnixTimeMillis")].toLong()) { ArrayList() } += values
                    "Integrity" -> phoneIntegrity += values
                    // From the figures when the line has them, so today's thresholds judge older recordings too.
                    "Motion" -> {
                        motionLines[values[0].toLong()] = values.getOrNull(2)?.toDoubleOrNull()
                            ?.let { sd -> MotionDetector.classify(sd, values.getOrNull(3)?.toDoubleOrNull()) }
                            ?: runCatching { Motion.valueOf(values[1]) }.getOrDefault(Motion.UNKNOWN)
                        motionFigures[values[0].toLong()] = values.getOrNull(2)?.toDoubleOrNull() to values.getOrNull(3)?.toDoubleOrNull()
                    }
                    "Accel", "Gyro" -> values.mapNotNull { it.toDoubleOrNull() }.takeIf { it.size >= 4 }?.let { v ->
                        (if (f[0] == "Accel") accel else gyro) += Sample(values[0].toLong(), v[1], v[2], v[3])
                    }
                    "Baro" -> values[1].toDoubleOrNull()?.let { baroLines[values[0].toLong()] = it }
                    "Agc" -> agcLines.getOrPut(values[0].toLong()) { ArrayList() } +=
                        Agc(Constellation.fromAndroid(values[1].toInt()), values[2].toDouble().toLong(), values[3].toDouble())
                    "Truth" -> values.map { it.toDouble() }.let { v ->
                        truth[values[0].toLong()] = Truth(
                            Ecef(v[1], v[2], v[3]), Ecef(v[4], v[5], v[6]), v[7],
                            mapOf(Constellation.GALILEO to v[8], Constellation.GLONASS to v[9]),
                        )
                    }
                }
            }
            val rh = headers.getValue("Raw")
            val ph = headers["SatPvt"]
            fun d(v: List<String>, k: String) = v[rh.getValue(k)].toDoubleOrNull()

            val moments = raw.map { (timeNanos, rows) ->
                val r0 = rows.first()
                val clock = RawClock(
                    timeNanos = timeNanos,
                    fullBiasNanos = r0[rh.getValue("FullBiasNanos")].toLongOrNull(),
                    biasNanos = d(r0, "BiasNanos") ?: 0.0,
                    discontinuityCount = r0[rh.getValue("HardwareClockDiscontinuityCount")].toInt(),
                    leapSecond = r0[rh.getValue("LeapSecond")].toIntOrNull(),
                )
                val measurements = rows.map { v ->
                    val ct = v[rh.getValue("ConstellationType")].toInt()
                    val svid = v[rh.getValue("Svid")].toInt()
                    val p = ph?.let { h -> pvt[Triple(timeNanos, ct, svid)]?.let { it to h } }
                    RawMeasurement(
                        constellation = Constellation.fromAndroid(ct),
                        svid = svid,
                        timeOffsetNanos = d(v, "TimeOffsetNanos") ?: 0.0,
                        state = v[rh.getValue("State")].toInt(),
                        receivedSvTimeNanos = v[rh.getValue("ReceivedSvTimeNanos")].toLong(),
                        receivedSvTimeUncertaintyNanos = v[rh.getValue("ReceivedSvTimeUncertaintyNanos")].toLong(),
                        cn0DbHz = d(v, "Cn0DbHz") ?: 0.0,
                        pseudorangeRateMps = d(v, "PseudorangeRateMetersPerSecond") ?: 0.0,
                        pseudorangeRateUncertaintyMps = d(v, "PseudorangeRateUncertaintyMetersPerSecond") ?: 0.0,
                        carrierHz = d(v, "CarrierFrequencyHz"),
                        fullInterSignalBiasNanos = d(v, "FullInterSignalBiasNanos"),
                        multipath = v[rh.getValue("MultipathIndicator")].toIntOrNull() ?: 0,
                        pvt = p?.let { (pv, h) ->
                            fun g(k: String) = pv[h.getValue(k)].toDoubleOrNull()
                            ChipPvt(
                                flags = pv[h.getValue("Flags")].toInt(),
                                position = Ecef(g("XEcefMeters")!!, g("YEcefMeters")!!, g("ZEcefMeters")!!),
                                positionUreMeters = g("UreMeters") ?: 0.0,
                                velocity = Ecef(g("VxEcefMps")!!, g("VyEcefMps")!!, g("VzEcefMps")!!),
                                hardwareCodeBiasMeters = g("HardwareCodeBiasMeters") ?: 0.0,
                                timeCorrectionMeters = g("TimeCorrectionMeters")!!,
                                clockDriftMps = g("ClockDriftMps") ?: 0.0,
                                ionoMeters = g("IonoDelayMeters"),
                                tropoMeters = g("TropoDelayMeters"),
                            )
                        },
                    )
                }
                val gpsNs = clock.fullBiasNanos?.let { timeNanos - it - Math.round(clock.biasNanos) } ?: 0L
                val utcMs = gpsNs / 1_000_000 + 315_964_800_000L - (clock.leapSecond ?: 18) * 1000L
                // The AGC list when the chip filled it, else the per-measurement field, as the app reads them.
                val listed = r0[rh.getValue("utcTimeMillis")].toLongOrNull()?.let { agcLines[it] }.orEmpty()
                val perSignal = AgcBands.fromSignals(rows.mapNotNull { v ->
                    d(v, "AgcDb")?.let { Triple(Constellation.fromAndroid(v[rh.getValue("ConstellationType")].toInt()), d(v, "CarrierFrequencyHz"), it) }
                })
                Moment(
                    RawEpoch(clock, measurements, listed.ifEmpty { perSignal }, agcFromSignals = listed.isEmpty() && perSignal.isNotEmpty()), utcMs,
                    elapsedMs = r0[rh.getValue("ChipsetElapsedRealtimeNanos")].toLongOrNull()?.div(1_000_000),
                    wallMs = r0[rh.getValue("utcTimeMillis")].toLongOrNull(),
                )
            }
            return RecordingFile(
                moments, fixes, status, headers["Status"].orEmpty(), phoneIntegrity, motionLines, baroLines,
                hasSatellitePvt = ph != null, truth = truth,
                motionFigures = motionFigures, accel = accel, gyro = gyro, clockPair = clockPair,
            )
        }
    }
}
