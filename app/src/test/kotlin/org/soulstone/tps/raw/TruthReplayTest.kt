package org.soulstone.tps.raw

import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.soulstone.tps.gnss.Constellation

/**
 * Replays recordings whose truth is known (made up by the independent reference model in
 * `tools/reference`, with `Truth` lines) through the real pipeline and prints how far TPS lands
 * from the truth: with the chip's fix at the truth, with it 60 m off, and with none.
 * `./gradlew :app:testDebugUnitTest --tests '*TruthReplayTest*' -Ptps.truth=/a.txt:/b.txt`
 */
class TruthReplayTest {

    private fun line(name: String, v: List<Double>, unit: String = "m"): String {
        if (v.isEmpty()) return "  $name: none"
        val s = v.map { abs(it) }.sorted()
        return "  %-34s median %.3f, 95%% %.3f, max %.3f %s; mean %+.3f (n=%d)".format(
            name, s[s.size / 2], s[(s.size * 0.95).toInt().coerceAtMost(s.size - 1)], s.last(), unit, v.average(), v.size,
        )
    }

    @Test
    fun againstTheTruth() {
        val paths = System.getProperty("tps.truth").orEmpty().split(":").filter { it.isNotBlank() && File(it).isFile }
        assumeTrue("no truth recordings given (-Ptps.truth=…)", paths.isNotEmpty())
        for (path in paths) for (mode in listOf("chip at the truth", "chip 60 m off", "no chip fix")) {
            val recording = File(path).useLines { RecordingFile.read(it) }
            val engine = OwnFixEngine()
            val snapH = ArrayList<Double>(); val snapU = ArrayList<Double>()
            val fusedH = ArrayList<Double>(); val fusedU = ArrayList<Double>(); val fusedE = ArrayList<Double>(); val fusedN = ArrayList<Double>()
            val speed = ArrayList<Double>(); val climb = ArrayList<Double>(); val drift = ArrayList<Double>()
            val nis = ArrayList<Double>(); val rateNis = ArrayList<Double>()
            val nisBySystem = HashMap<Constellation, ArrayList<Double>>()
            val claimH = ArrayList<Double>(); val claimU = ArrayList<Double>()
            var within1 = 0; var within2 = 0; var withinU1 = 0; var shown = 0; var rejected = 0; var rounds = 0
            var firstUsable: Int? = null
            var last: FusedFix? = null
            var lastTruth: RecordingFile.Truth? = null
            var restarts = 0; var jumps = 0; var held = 0; var static = 0
            for ((index, m) in recording.moments.withIndex()) {
                val truth = recording.truth[m.epoch.clock.timeNanos] ?: continue
                rounds++
                val at = Geo.toGeodetic(truth.ecef)
                val nearest = recording.fixNear(m.utcMs)
                val chip = when (mode) {
                    "chip at the truth" -> nearest?.let { Geo.along(it.at, it.utcMs, it.speedMps, it.bearingDeg, m.utcMs) }
                    "chip 60 m off" -> nearest?.let { Geo.along(it.at, it.utcMs, it.speedMps, it.bearingDeg, m.utcMs) }
                        ?.let { Geodetic(it.latitude + 40.0 / 111_320.0, it.longitude - 0.0004, it.height + 35.0) }
                    else -> null
                }
                val motion = m.wallMs?.let { w -> recording.motion.headMap(w + 1).entries.lastOrNull()?.value } ?: Motion.UNKNOWN
                val report = engine.solve(m.epoch, chip, recording.skyAt(m.utcMs), if (chip != null) nearest?.accuracyM else null, motion)
                report.combined?.let { s -> Solver.offset(at, s.ecef).let { e -> snapH += hypot(e[0], e[1]); snapU += e[2] } }
                val f = report.fused ?: continue
                last = f; lastTruth = truth
                restarts = f.restarts; jumps = f.clockJumps
                if (f.suspect != null) held++
                rejected += f.rejected
                if (!f.usable) continue
                if (firstUsable == null) firstUsable = index
                shown++
                val e = Solver.offset(at, f.ecef)
                val h = hypot(e[0], e[1])
                fusedH += h; fusedU += e[2]; fusedE += e[0]; fusedN += e[1]
                if (f.still) static++
                val v = Geo.enu(at, truth.velocity)
                speed += sqrt((f.velocityEast - v[0]).let { it * it } + (f.velocityNorth - v[1]).let { it * it })
                climb += f.velocityUp - v[2]
                drift += f.clockDriftMps - truth.clockDriftMps
                claimH += f.horizontalAccuracyM; claimU += f.verticalAccuracyM
                if (h <= f.horizontalAccuracyM) within1++
                if (h <= 2 * f.horizontalAccuracyM) within2++
                if (abs(e[2]) <= f.verticalAccuracyM) withinU1++
                f.rangeNis?.let { nis += it }
                f.rateNis?.let { rateNis += it }
                f.rangeNisBySystem.forEach { (c, x) -> nisBySystem.getOrPut(c) { ArrayList() } += x }
            }
            println("${File(path).name} — $mode: rounds $rounds, shown $shown (first at ${firstUsable ?: "-"}), rejected $rejected, restarts $restarts, clock jumps $jumps, held $held")
            println(line("snapshot, horizontal", snapH)); println(line("snapshot, up", snapU))
            println(line("super position, horizontal", fusedH)); println(line("super position, up", fusedU))
            println("  mean east %+.3f, north %+.3f m; static mode in $static rounds".format(fusedE.average(), fusedN.average()))
            // How the error goes with time, and how much the position moves about (which at rest is all error).
            if (fusedH.size >= 30) {
                val third = fusedH.size / 3
                fun rms(v: List<Double>) = sqrt(v.sumOf { it * it } / v.size)
                val meanE = fusedE.average(); val meanN = fusedN.average()
                val wander = fusedE.indices.map { hypot(fusedE[it] - meanE, fusedN[it] - meanN) }
                val steps = (1 until fusedE.size).map { hypot(fusedE[it] - fusedE[it - 1], fusedN[it] - fusedN[it - 1]) }
                println("  horizontal error (rms) by thirds: %.2f, %.2f, %.2f m; about its own mean: rms %.2f, max %.2f m; moves %.2f m a round (rms)".format(
                    rms(fusedH.take(third)), rms(fusedH.drop(third).take(third)), rms(fusedH.drop(2 * third)), rms(wander), wander.max(), rms(steps),
                ))
            }
            println(line("ground velocity", speed, "m/s")); println(line("vertical velocity", climb, "m/s")); println(line("clock drift", drift, "m/s"))
            if (shown > 0) {
                println("  claims: ± %.2f m horizontal (median), ± %.2f m vertical; truth within 1× in %.0f%% (expect ~39–68%%), within 2× in %.0f%%; height within 1× in %.0f%% (expect ~68%%)".format(
                    claimH.sorted()[claimH.size / 2], claimU.sorted()[claimU.size / 2], 100.0 * within1 / shown, 100.0 * within2 / shown, 100.0 * withinU1 / shown,
                ))
                println("  NIS: range %.2f, Doppler %.2f; by system %s".format(
                    nis.average(), rateNis.average(), nisBySystem.entries.sortedBy { it.key.ordinal }.joinToString { "${it.key.name} %.2f".format(it.value.average()) },
                ))
            }
            last?.let { f ->
                println("  system biases at the end (reference ${f.reference.name}): " + f.interSystemBiases.entries.joinToString { (c, b) ->
                    "${c.name} %+.3f m (truth %+.3f)".format(b, (lastTruth?.biases?.get(c) ?: 0.0) - (lastTruth?.biases?.get(f.reference) ?: 0.0))
                })
            }
        }
    }
}
