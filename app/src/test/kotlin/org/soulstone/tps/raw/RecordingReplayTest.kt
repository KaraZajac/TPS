package org.soulstone.tps.raw

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
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

    /** A shown round, for the stops: the chip's speed, TPS's position and the chip's. */
    private class Shown(val index: Int, val chipSpeedMps: Double?, val tps: Ecef, val chip: Geodetic)

    private class ChipFix(
        val utcMs: Long, val at: Geodetic, val accuracyM: Double?, val elapsedMs: Long?,
        val speedMps: Double? = null, val bearingDeg: Double? = null,
    )

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
        // The API 33 AGC list, by the event's wall time: utcTimeMillis, ConstellationType, CarrierFrequencyHz, AgcDb.
        val agcLines = HashMap<Long, MutableList<Agc>>()
        // What the phone's own integrity checks showed (dev.8 on): utcTimeMillis, Level, Check, Text.
        val phoneIntegrity = ArrayList<List<String>>()
        // The motion sensors' verdict (dev.9 on), by wall time.
        val motionLines = sortedMapOf<Long, Motion>()
        // The barometer's second-by-second mean (dev.10 on), by wall time.
        val baroLines = sortedMapOf<Long, Double>()
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
                        values[h.getValue("SpeedMps")].toDoubleOrNull(),
                        values[h.getValue("BearingDegrees")].toDoubleOrNull(),
                    )
                }
                "Status" -> status.getOrPut(values[headers.getValue("Status").getValue("UnixTimeMillis")].toLong()) { ArrayList() } += values
                "Integrity" -> phoneIntegrity += values
                // From the figures when the line has them, so today's thresholds judge older recordings too.
                "Motion" -> motionLines[values[0].toLong()] = values.getOrNull(2)?.toDoubleOrNull()
                    ?.let { sd -> MotionDetector.classify(sd, values.getOrNull(3)?.toDoubleOrNull()) }
                    ?: runCatching { Motion.valueOf(values[1]) }.getOrDefault(Motion.UNKNOWN)
                "Baro" -> values[1].toDoubleOrNull()?.let { baroLines[values[0].toLong()] = it }
                "Agc" -> agcLines.getOrPut(values[0].toLong()) { ArrayList() } +=
                    Agc(Constellation.fromAndroid(values[1].toInt()), values[2].toDouble().toLong(), values[3].toDouble())
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

        val engine = OwnFixEngine()
        val fused = ArrayList<Double>()
        val snapshot = ArrayList<Double>()
        var usable = 0
        var rejected = 0
        val vertical = ArrayList<Double>()
        val baroVertical = ArrayList<Double>()
        val climb = ArrayList<Double>()
        val claimed = ArrayList<Double>()
        val nis = ArrayList<Double>()
        val nisBySystem = HashMap<Constellation, ArrayList<Double>>()
        val rateNis = ArrayList<Double>()
        // Per shown round: whether TPS's distance from the chip is within twice their combined claims.
        val withinClaims = ArrayList<Boolean>()
        val shown = ArrayList<Shown>()
        // Each height minus the barometer's (standard atmosphere): how much each wanders.
        val tpsMinusBaro = ArrayList<Double>()
        val chipMinusBaro = ArrayList<Double>()
        val monitor = IntegrityMonitor()
        val findings = LinkedHashSet<String>()
        var worst = Severity.OK
        var lastFused: FusedFix? = null
        var held = 0
        var downweighted = 0
        var atRest = 0
        var firstUsable: Int? = null
        val chipClaims = ArrayList<Double>()
        val chipLeads = ArrayList<Double>()
        val motionSeen = HashMap<Motion, Int>()
        for ((index, m) in moments.withIndex()) {
            // What the motion sensors said at this round (the last verdict at or before it).
            val motion = m.wallMs?.let { w -> motionLines.headMap(w + 1).entries.lastOrNull()?.value } ?: Motion.UNKNOWN
            motionSeen.merge(motion, 1, Int::plus)
            // The barometer's reading at this round, if it's recent.
            val pressure = m.wallMs?.let { w -> baroLines.headMap(w + 1).entries.lastOrNull()?.takeIf { w - it.key <= 2_000 }?.value }
            val nearest = fixes.minByOrNull { kotlin.math.abs(it.utcMs - m.utcMs) }?.takeIf { kotlin.math.abs(it.utcMs - m.utcMs) <= 600 }
            // Where the chip's fix puts the phone at the measurements' moment, as the app has it.
            val chip = nearest?.let { Geo.along(it.at, it.utcMs, it.speedMps, it.bearingDeg, m.utcMs) }
            nearest?.let { chipLeads += (m.utcMs - it.utcMs) / 1_000.0 }
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
            val report = engine.solve(m.epoch, chip, sky, nearest?.accuracyM, motion, pressure)
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
                    agc = m.epoch.agc,
                    motion = motion,
                    fused = report.fused,
                    chip = chip,
                    chipAccuracyM = nearest?.accuracyM,
                    chipSpeedMps = nearest?.speedMps,
                    chipBearingDeg = nearest?.bearingDeg,
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
            lastFused = f
            rejected += f.rejected
            downweighted += f.downweighted
            if (f.suspect != null) held++
            if (f.still) atRest++
            if (f.usable && chip != null) {
                usable++
                if (firstUsable == null) firstUsable = index
                nearest?.accuracyM?.let { chipClaims += it }
                val e = Solver.offset(chip, f.ecef)
                fused += hypot(e[0], e[1])
                vertical += e[2]
                if (chip != null) f.baroHeightM?.let { baroVertical += it - chip.height }
                climb += abs(f.velocityUp)
                claimed += f.horizontalAccuracyM
                withinClaims += hypot(e[0], e[1]) <= 2 * hypot(f.horizontalAccuracyM, nearest?.accuracyM ?: 0.0)
                shown += Shown(index, nearest?.speedMps, f.ecef, chip)
                pressure?.let { Barometer.altitude(it) }?.let { b ->
                    tpsMinusBaro += f.position.height - b
                    chipMinusBaro += chip.height - b
                }
                f.rangeNis?.let { nis += it }
                f.rangeNisBySystem.forEach { (c, v) -> nisBySystem.getOrPut(c) { ArrayList() } += v }
                f.rateNis?.let { rateNis += it }
            }
        }
        fun stats(v: List<Double>): String {
            if (v.isEmpty()) return "none"
            val s = v.sorted()
            return "median %.1f m, 95%% %.1f m, max %.1f m (n=%d)".format(s[s.size / 2], s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)], s.last(), s.size)
        }
        println("rounds ${moments.size}, super position usable in $usable, measurements rejected $rejected")
        if (chipLeads.isNotEmpty()) println("the chip's fixes come %.2f s before the measurements (compared at the measurements' moment)".format(chipLeads.sorted()[chipLeads.size / 2]))
        println("snapshot, all systems: " + stats(snapshot))
        println("super position:        " + stats(fused))
        println("height, TPS minus chip: " + vertical.sorted().let { v -> if (v.isEmpty()) "none" else "median %+.1f m, range %+.1f to %+.1f m".format(v[v.size / 2], v.first(), v.last()) })
        if (baroLines.isNotEmpty()) println("height with the barometer, minus chip: " + baroVertical.sorted().let { v -> if (v.isEmpty()) "none yet" else "median %+.1f m, range %+.1f to %+.1f m (n=%d)".format(v[v.size / 2], v.first(), v.last(), v.size) })
        println("vertical speed (should be ~0 standing): " + stats(climb).replace(" m,", " m/s,").replace(" m (", " m/s ("))
        println("integrity: worst $worst" + findings.joinToString("") { "\n  $it" })
        println("claimed accuracy: " + stats(claimed) + "; consistency (NIS, ~1 when the noise model is right): " +
            nis.sorted().let { v -> if (v.isEmpty()) "none" else "median %.2f, mean %.2f".format(v[v.size / 2], v.average()) })
        println("NIS by system: " + nisBySystem.entries.sortedBy { it.key.ordinal }.joinToString { (c, v) -> "%s mean %.2f (n=%d)".format(c.displayName, v.average(), v.size) } +
            "; Doppler mean " + (if (rateNis.isEmpty()) "none" else "%.2f".format(rateNis.average())))
        println("code minus Doppler (~0 for an honest chip): " + lastFused?.codeMinusDopplerMps.orEmpty().entries.sortedBy { it.key.ordinal }
            .joinToString { (c, v) -> "%s %+.2f m/s".format(c.displayName, v) }.ifEmpty { "none" })
        println("receiver gain: " + moments.count { it.epoch.agc.isNotEmpty() }.let { n ->
            if (n == 0) "not in this recording" else "in $n of ${moments.size} rounds" + if (moments.any { it.epoch.agcFromSignals }) " (per-measurement field)" else ""
        })
        println("motion: " + (if (motionLines.isEmpty()) "not in this recording" else motionSeen.entries.joinToString { "${it.key} ${it.value}" }) +
            "; static mode in $atRest rounds; held against suspect signals in $held rounds, holds that ended by following: ${lastFused?.released ?: 0}; measurements weighed less: $downweighted")
        println("multipath flagged on " + moments.sumOf { m -> m.epoch.measurements.count { it.multipath == 1 } } + " of " + moments.sumOf { it.epoch.measurements.size } + " measurements")
        if (phoneIntegrity.isNotEmpty()) {
            val t0 = moments.firstOrNull()?.wallMs ?: 0L
            println("the phone's warnings:" + phoneIntegrity.joinToString("") { v ->
                "\n  +%.0f s %s %s %s".format((v[0].toLong() - t0) / 1_000.0, v[1], v.getOrElse(2) { "" }, v.getOrElse(3) { "" })
            })
        }
        println("TPS within twice its and the chip's combined claims: ${withinClaims.count { it }} of ${withinClaims.size} rounds shown")
        // Stops: the chip under 0.3 m/s for 8 shown rounds in a row, once TPS has settled (30 s
        // after its first position). How far each wandered meanwhile.
        val stops = ArrayList<List<Shown>>()
        var run = ArrayList<Shown>()
        for (r in shown) {
            val stopped = (r.chipSpeedMps ?: Double.MAX_VALUE) < 0.3 && r.index >= (firstUsable ?: 0) + 30
            if (stopped && run.isNotEmpty() && r.index == run.last().index + 1) {
                run += r
            } else {
                if (run.size >= 8) stops += run
                run = if (stopped) arrayListOf(r) else ArrayList()
            }
        }
        if (run.size >= 8) stops += run
        if (stops.isNotEmpty()) {
            fun wander(s: List<Shown>, of: (Shown) -> Ecef) = s.maxOf { r -> Solver.offset(Geo.toGeodetic(of(s.first())), of(r)).let { hypot(it[0], it[1]) } }
            val tpsWander = stops.map { s -> wander(s) { it.tps } }.sorted()
            val chipWander = stops.map { s -> wander(s) { Geo.toEcef(it.chip) } }.sorted()
            println(
                "stops (the chip under 0.3 m/s for 8 s or more, TPS settled): %d, %d s; TPS wandered up to %.1f m (median of stops; worst %.1f m), the chip %.1f m"
                    .format(stops.size, stops.sumOf { it.size }, tpsWander[tpsWander.size / 2], tpsWander.last(), chipWander[chipWander.size / 2])
            )
        }
        if (tpsMinusBaro.size > 30) {
            fun sd(v: List<Double>) = v.average().let { m -> kotlin.math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }
            println("height against the barometer (wander about its own mean): TPS sd %.1f m, the chip sd %.1f m".format(sd(tpsMinusBaro), sd(chipMinusBaro)))
        }
        // A normal outing: no warning should have shown.
        assertTrue("false alarm: $findings", worst == Severity.OK)
        // Whatever TPS shows, outdoors or in, must keep within what it claims.
        assertTrue("TPS outside its claims in ${withinClaims.count { !it }} of ${withinClaims.size} rounds", withinClaims.count { !it } <= withinClaims.size / 20)
        if (ph == null || fixes.isEmpty()) {
            println("(no SatPvt or Fix lines: TPS's own position can't be checked on this one)")
            return
        }
        // Indoors the chip may hand over a couple of satellites a round. TPS then stays off, or
        // settles on the few rounds with 4 or more and carries on through the rest; either way the
        // claims check above is what counts, not the outdoor accuracy below.
        val perRound = moments.map { m -> m.epoch.measurements.count { it.pvt != null } }.sorted()
        if (perRound[perRound.size / 2] < 4) {
            println("(typically ${perRound[perRound.size / 2]} satellites a round; TPS's own position showed in $usable of ${moments.size} rounds)")
            return
        }
        // Once shown, TPS should stay shown (a recording may start before the chip's first fix).
        val since = moments.size - (firstUsable ?: 0)
        assertTrue("usable in only $usable of the $since rounds since its first", usable >= since * 0.8)
        // Outdoors (Kara's recordings: the chip claiming ±3–5 m) typically within 6 m of the chip's
        // fix and 95% within 15 m; where the chip itself claims less, scaled to its claim.
        val chipClaim = chipClaims.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val sorted = fused.sorted()
        assertTrue("median ${sorted[sorted.size / 2]} m (chip claims ±$chipClaim m)", sorted[sorted.size / 2] < max(6.0, 0.75 * chipClaim))
        assertTrue("95% ${sorted[(sorted.size * 0.95).toInt()]} m (chip claims ±$chipClaim m)", sorted[(sorted.size * 0.95).toInt()] < max(15.0, 1.5 * chipClaim))
    }
}
