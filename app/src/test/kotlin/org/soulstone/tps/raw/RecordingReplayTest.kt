package org.soulstone.tps.raw

import java.io.File
import kotlin.math.hypot
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.Signal
import org.soulstone.tps.gnss.SkySnapshot

/**
 * Replays a raw recording made by TPS (GnssLogger format plus `SatPvt` lines) through the real
 * pipeline: pseudoranges, the chip's satellite positions, own fixes and the super position,
 * compared with the chip's fix at each moment.
 *
 * Recordings hold the place they were made, so none is in the repo: pass one with
 * `./gradlew :app:testDebugUnitTest -Ptps.recording=/path/to/tps_raw_….txt`. Without one the
 * test is skipped. It prints offsets from the chip's fix only, never coordinates.
 */
class RecordingReplayTest {

    private class Moment(val epoch: RawEpoch, val utcMs: Long)

    private fun columns(header: String) = header.removePrefix("# ").split(",").drop(1).withIndex().associate { it.value to it.index }

    @Test
    fun superPositionOnARealRecording() {
        val path = System.getProperty("tps.recording").orEmpty()
        assumeTrue("no recording given (-Ptps.recording=…)", path.isNotBlank() && File(path).isFile)

        val headers = HashMap<String, Map<String, Int>>()
        val raw = LinkedHashMap<Long, MutableList<List<String>>>()
        val pvt = HashMap<Triple<Long, Int, Int>, List<String>>()
        val fixes = ArrayList<Pair<Long, Geodetic>>()
        val status = sortedMapOf<Long, MutableList<List<String>>>()
        File(path).forEachLine { line ->
            if (line.startsWith("# ")) {
                val kind = line.removePrefix("# ").substringBefore(",")
                if (line.count { it == ',' } > 3) headers[kind] = columns(line)
                return@forEachLine
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
                    fixes += values[h.getValue("UnixTimeMillis")].toLong() to Geodetic(
                        values[h.getValue("LatitudeDegrees")].toDouble(),
                        values[h.getValue("LongitudeDegrees")].toDouble(),
                        values[h.getValue("AltitudeMeters")].toDoubleOrNull() ?: 0.0,
                    )
                }
                "Status" -> status.getOrPut(values[headers.getValue("Status").getValue("UnixTimeMillis")].toLong()) { ArrayList() } += values
            }
        }
        val rh = headers.getValue("Raw")
        val ph = headers["SatPvt"]
        val sh = headers.getValue("Status")
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
            Moment(RawEpoch(clock, measurements), utcMs)
        }

        val engine = OwnFixEngine()
        val fused = ArrayList<Double>()
        val snapshot = ArrayList<Double>()
        var usable = 0
        var rejected = 0
        for (m in moments) {
            val chip = fixes.minByOrNull { kotlin.math.abs(it.first - m.utcMs) }?.takeIf { kotlin.math.abs(it.first - m.utcMs) <= 600 }?.second ?: continue
            // The chip's sky at that moment, for the orbit check.
            val sky = status.headMap(m.utcMs + 1).entries.lastOrNull()?.value?.let { rows ->
                SkySnapshot.of(rows.map { v ->
                    Signal(
                        Constellation.fromAndroid(v[sh.getValue("ConstellationType")].toInt()), v[sh.getValue("Svid")].toInt(),
                        v[sh.getValue("Cn0DbHz")].toFloat(), v[sh.getValue("ElevationDegrees")].toFloat(), v[sh.getValue("AzimuthDegrees")].toFloat(),
                        usedInFix = v[sh.getValue("UsedInFix")] == "1",
                    )
                })
            }
            val report = engine.solve(m.epoch, chip, sky)
            report.combined?.let { snapshot += Solver.offset(chip, it.ecef).let { e -> hypot(e[0], e[1]) } }
            val f = report.fused ?: continue
            rejected += f.rejected
            if (f.usable) {
                usable++
                fused += Solver.offset(chip, f.ecef).let { e -> hypot(e[0], e[1]) }
            }
        }
        fun stats(v: List<Double>): String {
            if (v.isEmpty()) return "none"
            val s = v.sorted()
            return "median %.1f m, 95%% %.1f m, max %.1f m (n=%d)".format(s[s.size / 2], s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)], s.last(), s.size)
        }
        println("rounds ${moments.size}, super position usable in $usable, measurements rejected $rejected")
        println("snapshot, all systems: " + stats(snapshot))
        println("super position:        " + stats(fused))
        assertTrue("usable in only $usable of ${moments.size} rounds", usable >= moments.size * 0.8)
        val sorted = fused.sorted()
        assertTrue("median ${sorted[sorted.size / 2]} m", sorted[sorted.size / 2] < 6.0)
        assertTrue("95% ${sorted[(sorted.size * 0.95).toInt()]} m", sorted[(sorted.size * 0.95).toInt()] < 15.0)
    }
}
