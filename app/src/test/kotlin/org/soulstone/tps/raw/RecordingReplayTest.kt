package org.soulstone.tps.raw

import java.io.File
import kotlin.math.abs
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

    private class Moment(val epoch: RawEpoch, val utcMs: Long, val elapsedMs: Long?, val wallMs: Long?)

    private class ChipFix(val utcMs: Long, val at: Geodetic, val accuracyM: Double?, val elapsedMs: Long?)

    private fun columns(header: String) = header.removePrefix("# ").split(",").drop(1).withIndex().associate { it.value to it.index }

    @Test
    fun superPositionOnARealRecording() {
        val path = System.getProperty("tps.recording").orEmpty()
        assumeTrue("no recording given (-Ptps.recording=…)", path.isNotBlank() && File(path).isFile)

        val headers = HashMap<String, Map<String, Int>>()
        val raw = LinkedHashMap<Long, MutableList<List<String>>>()
        val pvt = HashMap<Triple<Long, Int, Int>, List<String>>()
        val fixes = ArrayList<ChipFix>()
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
                    fixes += ChipFix(
                        values[h.getValue("UnixTimeMillis")].toLong(),
                        Geodetic(
                            values[h.getValue("LatitudeDegrees")].toDouble(),
                            values[h.getValue("LongitudeDegrees")].toDouble(),
                            values[h.getValue("AltitudeMeters")].toDoubleOrNull() ?: 0.0,
                        ),
                        values[h.getValue("AccuracyMeters")].toDoubleOrNull(),
                        values[h.getValue("elapsedRealtimeNanos")].toLongOrNull()?.div(1_000_000),
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
            Moment(
                RawEpoch(clock, measurements), utcMs,
                elapsedMs = r0[rh.getValue("ChipsetElapsedRealtimeNanos")].toLongOrNull()?.div(1_000_000),
                wallMs = r0[rh.getValue("utcTimeMillis")].toLongOrNull(),
            )
        }

        val engine = OwnFixEngine()
        val fused = ArrayList<Double>()
        val snapshot = ArrayList<Double>()
        var usable = 0
        var rejected = 0
        val vertical = ArrayList<Double>()
        val climb = ArrayList<Double>()
        val claimed = ArrayList<Double>()
        val nis = ArrayList<Double>()
        val monitor = IntegrityMonitor()
        val findings = LinkedHashSet<String>()
        var worst = Severity.OK
        for (m in moments) {
            val nearest = fixes.minByOrNull { kotlin.math.abs(it.utcMs - m.utcMs) }?.takeIf { kotlin.math.abs(it.utcMs - m.utcMs) <= 600 }
            val chip = nearest?.at
            val statusRows = status.headMap(m.utcMs + 1).entries.lastOrNull()?.value
            // The chip's sky at that moment, for the orbit check.
            val sky = statusRows?.let { rows ->
                SkySnapshot.of(rows.map { v ->
                    Signal(
                        Constellation.fromAndroid(v[sh.getValue("ConstellationType")].toInt()), v[sh.getValue("Svid")].toInt(),
                        v[sh.getValue("Cn0DbHz")].toFloat(), v[sh.getValue("ElevationDegrees")].toFloat(), v[sh.getValue("AzimuthDegrees")].toFloat(),
                        usedInFix = v[sh.getValue("UsedInFix")] == "1",
                    )
                })
            }
            val report = engine.solve(m.epoch, chip, sky, nearest?.accuracyM)
            if (chip != null) report.combined?.let { snapshot += Solver.offset(chip, it.ecef).let { e -> hypot(e[0], e[1]) } }
            // The integrity checks, fed as the app feeds them.
            val integrity = monitor.update(
                IntegrityInput(
                    elapsedS = (m.elapsedMs ?: (m.epoch.clock.timeNanos / 1_000_000)) / 1_000.0,
                    signals = statusRows.orEmpty().map { v ->
                        SignalLevel(
                            Constellation.fromAndroid(v[sh.getValue("ConstellationType")].toInt()), v[sh.getValue("Svid")].toInt(),
                            v[sh.getValue("Cn0DbHz")].toDouble(), v[sh.getValue("ElevationDegrees")].toDouble(),
                        )
                    }.filter { it.cn0DbHz > 0 },
                    fused = report.fused,
                    chip = chip,
                    chipAccuracyM = nearest?.accuracyM,
                    // Time from the chip's fix, as the app does.
                    gnssMinusElapsedMs = nearest?.elapsedMs?.let { nearest.utcMs - it },
                    gnssMinusWallMs = nearest?.elapsedMs?.let { fe ->
                        m.wallMs?.let { w -> m.elapsedMs?.let { re -> nearest.utcMs - (fe + (w - re)) } }
                    },
                )
            )
            if (integrity.level > worst) worst = integrity.level
            integrity.checks.filter { it.severity != Severity.OK }.forEach { findings += "${it.severity} ${it.check.title}: ${it.text}" }
            val f = report.fused ?: continue
            rejected += f.rejected
            if (f.usable && chip != null) {
                usable++
                val e = Solver.offset(chip, f.ecef)
                fused += hypot(e[0], e[1])
                vertical += e[2]
                climb += abs(f.velocityUp)
                claimed += f.horizontalAccuracyM
                f.rangeNis?.let { nis += it }
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
        println("height, TPS minus chip: " + vertical.sorted().let { v -> if (v.isEmpty()) "none" else "median %+.1f m, range %+.1f to %+.1f m".format(v[v.size / 2], v.first(), v.last()) })
        println("vertical speed (should be ~0 standing): " + stats(climb).replace(" m,", " m/s,").replace(" m (", " m/s ("))
        println("integrity: worst $worst" + findings.joinToString("") { "\n  $it" })
        println("claimed accuracy: " + stats(claimed) + "; consistency (NIS, ~1 when the noise model is right): " +
            nis.sorted().let { v -> if (v.isEmpty()) "none" else "median %.2f, mean %.2f".format(v[v.size / 2], v.average()) })
        // A normal outing: no warning should have shown.
        assertTrue("false alarm: $findings", worst == Severity.OK)
        if (ph == null || fixes.isEmpty()) {
            println("(no SatPvt or Fix lines: TPS's own position can't be checked on this one)")
            return
        }
        // Indoors the chip may hand over a couple of satellites a round: then TPS rightly stays
        // off, and there's no accuracy to check.
        val perRound = moments.map { m -> m.epoch.measurements.count { it.pvt != null } }.sorted()
        if (perRound[perRound.size / 2] < 4) {
            println("(typically ${perRound[perRound.size / 2]} satellites a round: too few for TPS's own position, which showed in $usable of ${moments.size} rounds)")
            assertTrue("TPS showed a position from too few satellites", usable < moments.size / 10)
            return
        }
        assertTrue("usable in only $usable of ${moments.size} rounds", usable >= moments.size * 0.8)
        val sorted = fused.sorted()
        assertTrue("median ${sorted[sorted.size / 2]} m", sorted[sorted.size / 2] < 6.0)
        assertTrue("95% ${sorted[(sorted.size * 0.95).toInt()]} m", sorted[(sorted.size * 0.95).toInt()] < 15.0)
    }
}
