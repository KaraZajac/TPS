package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.nav.Geodesy

/** How worried to be. */
enum class Severity { OK, CAUTION, ALERT }

/** What a warning points to. */
enum class Threat(val label: String) {
    JAMMING("possible jamming"),
    SPOOFING("possible spoofing"),
    FAULT("check the fix"),
}

/** TPS's integrity checks, in the order the INTEGRITY card lists them. */
enum class Check(val title: String) {
    SYSTEMS("systems agree"),
    JUMPS("position jumps"),
    MOTION("motion agrees"),
    STARTUP("since last time"),
    TIME("GNSS time"),
    SOURCE("position source"),
    SIGNALS("signal strength"),
    GAIN("receiver gain"),
    PATTERN("signal pattern"),
    CLOCK("receiver clock"),
    FIT("measurements fit"),
    CHIP("TPS vs chip"),
}

data class CheckResult(val check: Check, val severity: Severity, val threat: Threat?, val text: String)

data class IntegrityReport(
    val level: Severity,
    /** The most serious finding, for the main screen; null while all is well. */
    val headline: CheckResult?,
    /** Every check, for the INTEGRITY card. */
    val checks: List<CheckResult>,
)

/** One satellite's signal strength, from the chip's satellite status (steadier than raw measurement events). */
data class SignalLevel(val constellation: Constellation, val svid: Int, val cn0DbHz: Double, val elevationDeg: Double? = null)

/** One round's evidence. */
data class IntegrityInput(
    /** The measurement's time on the phone's monotonic clock, seconds. */
    val elapsedS: Double,
    /** Every satellite the chip is receiving now (C/N0 above 0). */
    val signals: List<SignalLevel>,
    val agc: List<Agc> = emptyList(),
    val fused: FusedFix? = null,
    val chip: Geodetic? = null,
    val chipAccuracyM: Double? = null,
    /** The chip's own speed and bearing: where it was heading, should its fixes stop for a while. */
    val chipSpeedMps: Double? = null,
    val chipBearingDeg: Double? = null,
    /** GNSS time minus the phone's monotonic clock at the measurement, ms: constant unless GNSS time jumps. */
    val gnssMinusElapsedMs: Long? = null,
    /** GNSS time minus Android's network (NTP) time, ms; null when Android has none. */
    val gnssMinusNetworkMs: Long? = null,
    /** GNSS time minus the phone's wall clock, ms. */
    val gnssMinusWallMs: Long? = null,
    /** Android marks the chip's position as a mock location (another app supplies it). */
    val chipMock: Boolean = false,
    /** GNSS time from the chip's fix, and the phone's monotonic and wall clocks at that fix, ms. */
    val gnssTimeMs: Long? = null,
    val elapsedMs: Long? = null,
    val wallMs: Long? = null,
    /** Android's boot count: the monotonic clock only counts honestly within one boot. */
    val bootCount: Int? = null,
    /** What the phone's motion sensors say. */
    val motion: Motion = Motion.UNKNOWN,
)

/** Where and when TPS last had a position it trusted, kept on the phone between sessions. */
data class Remembered(val position: Geodetic, val gnssTimeMs: Long, val elapsedMs: Long, val wallMs: Long, val bootCount: Int?)

/**
 * Watches for jamming and spoofing. A phone can't prove either: these are the tells that
 * single-antenna receivers can see, each worded with what else it could be.
 *
 * - **Systems agree**: one system's pseudoranges keep failing the super position's checks while
 *   the others' pass. A fake that covers only GPS is the classic cheap attack.
 * - **Position jumps**: the position moves faster than anything on the ground, or the signals
 *   put TPS somewhere it couldn't have reached (then TPS holds instead of following).
 * - **Motion agrees**: the signals move TPS while the motion sensors say the phone lies still
 *   (a drag-off spoof; TPS holds where the phone came to rest).
 * - **Since last time**: at start-up, the position and GNSS time against where and when TPS
 *   last trusted its fix, so an attack already running when TPS opens can still show.
 * - **GNSS time**: GNSS time jumps against the phone's monotonic clock, or sits far from
 *   Android's network time.
 * - **Position source**: Android marks the chip's position as a mock location.
 * - **Signal strength**: every system's signals drop at once (jamming, or a blocked sky), or all
 *   get stronger at once (a transmitter overpowering the satellites). With the receiver gain
 *   reported, a drop with steady gain is a blocked sky, and a change with moving gain is not.
 * - **Receiver gain** (when the chip reports AGC): the gain moves, as it does under
 *   interference (either way: phones disagree on its sign).
 * - **Signal pattern**: every signal at the same strength, or stronger than phones see from orbit.
 * - **Receiver clock**: the whole clock jumps without the chip reporting a reset.
 * - **Measurements fit**: many satellites don't fit with the rest.
 * - **TPS vs chip**: the two positions disagree for a while.
 *
 * A finding stays up for a while after its cause ends, so the warning doesn't flicker.
 * Plain Kotlin; one thread feeds it.
 */
class IntegrityMonitor {
    private class Latch(val result: CheckResult, val until: Double)

    private val latched = HashMap<Check, Latch>()
    private val okText = HashMap<Check, String>()
    private var lastS = Double.NaN

    // Signal strength baseline (one value per satellite: its strongest signal).
    private var baseCn0 = Double.NaN
    private var baseCount = Double.NaN
    private val baseBySystem = HashMap<Constellation, Double>()
    private var baselineRounds = 0
    private var dropRounds = 0
    private var riseRounds = 0
    private var uniformRounds = 0
    private var strongRounds = 0

    // Receiver gain baseline per band.
    private val agcBase = HashMap<Pair<Constellation, Long>, Double>()
    private val agcSeen = HashMap<Pair<Constellation, Long>, Int>()
    private var gainRounds = 0
    private var agcEver = false
    /** The last rounds' largest gain change from its baseline (either sign), dB; null without a settled AGC. */
    private val gainHistory = ArrayDeque<Double?>()
    private var blockedUntil = Double.NaN
    private var blockedText = ""

    private var remembered: Remembered? = null
    private var startupChecked = false

    private val systemRounds = HashMap<Constellation, Int>()
    private var fitRounds = 0
    private var chipRounds = 0
    private var seenRestarts = 0
    private var seenReleases = 0
    private var seenClockJumps = 0
    private var timeBase: Long? = null
    private val timeFirst = ArrayList<Long>()
    /** The chip's last fix: when, where, and its speed and bearing then. */
    private class ChipSeen(val t: Double, val at: Geodetic, val speedMps: Double?, val bearingDeg: Double?)
    private var lastChip: ChipSeen? = null

    fun reset() {
        latched.clear(); okText.clear(); lastS = Double.NaN
        baseCn0 = Double.NaN; baseCount = Double.NaN; baseBySystem.clear(); baselineRounds = 0
        dropRounds = 0; riseRounds = 0; uniformRounds = 0; strongRounds = 0
        agcBase.clear(); agcSeen.clear(); gainRounds = 0; agcEver = false; gainHistory.clear(); blockedUntil = Double.NaN
        remembered = null; startupChecked = false
        systemRounds.clear(); fitRounds = 0; chipRounds = 0; seenRestarts = 0; seenReleases = 0; seenClockJumps = 0
        timeBase = null; timeFirst.clear(); lastChip = null
    }

    /** A new session: [last] is where and when TPS last trusted its fix (null on the first run). */
    fun startSession(last: Remembered?) {
        remembered = last
        startupChecked = false
    }

    fun update(input: IntegrityInput): IntegrityReport {
        val now = input.elapsedS
        val dt = if (lastS.isNaN()) 1.0 else (now - lastS).coerceIn(0.0, 30.0)
        lastS = now
        // Gain first: the signal-strength check reads this round's gain change.
        gain(input, now)
        signals(input, now, dt)
        systems(input, now)
        jumps(input, now)
        motion(input, now)
        startup(input, now)
        time(input, now)
        source(input, now)
        clock(input, now)
        fit(input, now)
        chip(input, now)
        latched.entries.removeAll { it.value.until < now }
        val checks = Check.entries.map { c ->
            latched[c]?.result ?: CheckResult(c, Severity.OK, null, okText[c] ?: "waiting")
        }
        val worst = checks.filter { it.severity != Severity.OK }
            .sortedWith(compareByDescending<CheckResult> { it.severity }.thenBy { it.check.ordinal })
            .firstOrNull()
        return IntegrityReport(worst?.severity ?: Severity.OK, worst, checks)
    }

    private fun raise(check: Check, severity: Severity, threat: Threat, text: String, now: Double, holdS: Double? = null, replace: Boolean = false) {
        val current = latched[check]
        // A stronger finding replaces a weaker one; a weaker one doesn't cut a stronger one short
        // (unless it's news about the same thing: a hold that ended).
        if (!replace && current != null && current.result.severity > severity && current.until > now) return
        latched[check] = Latch(CheckResult(check, severity, threat, text), now + (holdS ?: if (severity == Severity.ALERT) ALERT_HOLD_S else CAUTION_HOLD_S))
    }

    // ------------------------------------------------------------ signal strength and pattern

    private fun signals(input: IntegrityInput, now: Double, dt: Double) {
        // One value per satellite (its strongest signal), from the ranging systems.
        val tracked = input.signals
            .filter { it.cn0DbHz > 0 && it.constellation != Constellation.SBAS && it.constellation != Constellation.UNKNOWN }
            .groupBy { it.constellation to it.svid }
            .map { (key, list) -> key.first to list.maxOf { it.cn0DbHz } }
        val count = tracked.size
        val median = if (tracked.isEmpty()) Double.NaN else median(tracked.map { it.second })
        val bySystem = tracked.groupBy({ it.first }, { it.second }).mapValues { median(it.value) }

        val established = baselineRounds >= BASELINE_ROUNDS
        if (established) {
            val drop = baseCn0 - (if (median.isNaN()) 0.0 else median)
            val lost = baseCount - count
            val dropped = (median.isNaN() || drop >= DROP_DB || (count <= baseCount * 0.5 && lost >= 4))
            val everySystemDropped = baseBySystem.all { (c, b) -> bySystem[c]?.let { it <= b - SYSTEM_DROP_DB } ?: true }
            dropRounds = if (dropped && everySystemDropped) dropRounds + 1 else 0
            val rise = median - baseCn0
            val everySystemRose = bySystem.isNotEmpty() && baseBySystem.all { (c, b) -> bySystem[c]?.let { it >= b + SYSTEM_RISE_DB } ?: false }
            riseRounds = if (!median.isNaN() && rise >= RISE_DB && everySystemRose) riseRounds + 1 else 0
            // The receiver gain's verdict, when the chip reports it: moved, steady, or unknown.
            val shifted = gainVerdict()
            val gainMoved = gainHistory.filterNotNull().maxByOrNull { abs(it) } ?: 0.0
            if (dropRounds >= PERSIST_ROUNDS) {
                val how = if (median.isNaN()) "Every signal is gone" else "Signals dropped %.0f dB on every system at once".format(drop)
                when (shifted) {
                    true -> raise(Check.SIGNALS, Severity.ALERT, Threat.JAMMING, "$how and the receiver gain moved %+.0f dB: interference in the band.".format(gainMoved), now)
                    // Steady gain: no extra power in the band, so the sky got blocked.
                    false -> {
                        blockedUntil = now + CAUTION_HOLD_S
                        blockedText = "$how with the receiver gain steady: the sky got blocked (indoors, a pocket), not jamming"
                        accept(median, count, bySystem, tracked)
                    }
                    null -> raise(Check.SIGNALS, Severity.CAUTION, Threat.JAMMING, "$how: jamming, or the sky got blocked (indoors, a pocket).", now)
                }
            }
            if (riseRounds >= PERSIST_ROUNDS) {
                when {
                    shifted == true -> raise(
                        Check.SIGNALS, Severity.ALERT, Threat.SPOOFING,
                        "Every signal got %.0f dB stronger and the receiver gain moved %+.0f dB: a transmitter overpowering the satellites.".format(rise, gainMoved), now,
                    )
                    // Only a rise to levels phones don't get from orbit says a transmitter outshines the sky.
                    median >= RISE_LEVEL_DB -> raise(
                        Check.SIGNALS, Severity.CAUTION, Threat.SPOOFING,
                        "Every signal got %.0f dB stronger at once: something nearby may be overpowering the satellites.".format(rise), now,
                    )
                    // Out of a building or a basement: the new normal.
                    else -> accept(median, count, bySystem, tracked)
                }
            }
            // A change that lasts becomes the new normal (indoors now), so a warning doesn't
            // stay up for as long as the phone stays put.
            // Not while the gain says interference: that warning stays up as long as it lasts. (A
            // rise is taken at once unless it's stronger than orbit, which stays suspect.)
            if (shifted != true && dropRounds >= NEW_NORMAL_ROUNDS) accept(median, count, bySystem, tracked)
        }
        // The baseline follows slow changes (satellites rising and setting), not sudden ones.
        if (dropRounds == 0 && riseRounds == 0 && count >= MIN_SIGNALS && !median.isNaN()) {
            val a = if (baselineRounds < BASELINE_ROUNDS) 1.0 / (baselineRounds + 1) else min(1.0, dt / BASELINE_TAU_S)
            baseCn0 = if (baseCn0.isNaN()) median else baseCn0 + a * (median - baseCn0)
            baseCount = if (baseCount.isNaN()) count.toDouble() else baseCount + a * (count - baseCount)
            for ((c, m) in bySystem) {
                val sats = tracked.count { it.first == c }
                if (sats < 2) continue
                baseBySystem[c] = baseBySystem[c]?.let { it + a * (m - it) } ?: m
            }
            baselineRounds++
        }
        okText[Check.SIGNALS] = when {
            !blockedUntil.isNaN() && now <= blockedUntil -> blockedText
            tracked.isEmpty() -> "no signals"
            !established -> "learning what's normal here (%d signals, median %.0f dB-Hz)".format(count, median)
            else -> "normal: %d signals, median %.0f dB-Hz (usual %.0f)".format(count, median, baseCn0)
        }

        // Pattern: one transmitter puts every signal at about the same strength; orbit doesn't.
        val values = tracked.map { it.second }
        if (values.size >= UNIFORM_MIN_SIGNALS) {
            val sd = sd(values)
            uniformRounds = if (sd < UNIFORM_SD_DB) uniformRounds + 1 else 0
            if (uniformRounds >= UNIFORM_ROUNDS) {
                raise(Check.PATTERN, Severity.CAUTION, Threat.SPOOFING, "All %d signals arrive within ±%.1f dB: typical of one transmitter, not satellites all over the sky.".format(values.size, sd), now)
            }
        } else {
            uniformRounds = 0
        }
        strongRounds = if (!median.isNaN() && median >= STRONG_DB && count >= MIN_SIGNALS) strongRounds + 1 else 0
        if (strongRounds >= PERSIST_ROUNDS) {
            raise(Check.PATTERN, Severity.CAUTION, Threat.SPOOFING, "Signals unusually strong (median %.0f dB-Hz); phones rarely see more than 45 from orbit.".format(median), now)
        }
        okText[Check.PATTERN] = when {
            values.size >= UNIFORM_MIN_SIGNALS -> "varied, as from orbit (spread ±%.1f dB)".format(sd(values))
            values.size >= 2 -> "spread ±%.1f dB (too few signals to judge)".format(sd(values))
            else -> "too few signals to judge"
        }
    }

    /**
     * Moved: 2 of the last 3 rounds off by [GAIN_CORROBORATE_DB]. Steady: all 3 within it.
     * Anything else, or a round without gain, is unknown. On Kara's Sony the gain wanders
     * ±0.7 dB, with single-round dips near 3 dB, so one round must not decide either way.
     */
    private fun gainVerdict(): Boolean? {
        if (gainHistory.size < GAIN_WINDOW || gainHistory.any { it == null }) return null
        val off = gainHistory.count { abs(it!!) >= GAIN_CORROBORATE_DB }
        return when {
            off >= 2 -> true
            off == 0 -> false
            else -> null
        }
    }

    /** Takes the current signal levels as the new baseline. */
    private fun accept(median: Double, count: Int, bySystem: Map<Constellation, Double>, tracked: List<Pair<Constellation, Double>>) {
        if (median.isNaN()) return
        baseCn0 = median
        baseCount = count.toDouble()
        baseBySystem.clear()
        for ((c, m) in bySystem) if (tracked.count { it.first == c } >= 2) baseBySystem[c] = m
        dropRounds = 0
        riseRounds = 0
    }

    // ------------------------------------------------------------ receiver gain (AGC)

    private fun gain(input: IntegrityInput, now: Double) {
        if (input.agc.isEmpty()) {
            recordGain(null)
            okText[Check.GAIN] = if (agcEver) "not reported this round" else "not reported by this chip"
            return
        }
        agcEver = true
        var biggest: Double? = null
        var band: Pair<Constellation, Long>? = null
        for (a in input.agc) {
            val key = a.constellation to a.carrierHz / 1_000_000
            val seen = agcSeen[key] ?: 0
            val base = agcBase[key]
            if (base == null || seen < BASELINE_ROUNDS) {
                agcBase[key] = base?.let { it + (a.levelDb - it) / (seen + 1) } ?: a.levelDb
                agcSeen[key] = seen + 1
                continue
            }
            // Interference moves the gain; which way depends on the phone.
            val change = a.levelDb - base
            if (biggest == null || abs(change) > abs(biggest)) { biggest = change; band = key }
            // The baseline follows slow drift, not interference.
            if (abs(change) < GAIN_CHANGE_DB) agcBase[key] = base + (a.levelDb - base) * 0.02
        }
        recordGain(biggest)
        gainRounds = if (biggest != null && abs(biggest) >= GAIN_CHANGE_DB) gainRounds + 1 else 0
        if (gainRounds >= PERSIST_ROUNDS && band != null) {
            raise(
                Check.GAIN, Severity.CAUTION, Threat.JAMMING,
                "Receiver gain on %s %d MHz moved %+.0f dB: strong interference in the band.".format(band.first.displayName, band.second, biggest), now,
            )
        }
        okText[Check.GAIN] = if (band == null) "learning what's normal" else "steady (%+.1f dB from usual)".format(biggest)
    }

    private fun recordGain(change: Double?) {
        gainHistory.addLast(change)
        while (gainHistory.size > GAIN_WINDOW) gainHistory.removeFirst()
    }

    // ------------------------------------------------------------ the super position's evidence

    private fun systems(input: IntegrityInput, now: Double) {
        val f = input.fused
        if (f == null) {
            okText[Check.SYSTEMS] = "needs TPS's own position"
            return
        }
        val accepted = f.acceptedBySystem
        val rejected = f.rejectedBySystem
        var flagged: Constellation? = null
        for (c in (accepted.keys + rejected.keys)) {
            val bad = rejected[c] ?: 0
            val good = accepted[c] ?: 0
            val othersGood = accepted.filterKeys { it != c }.values.sum()
            val mostlyRejected = bad >= 2 && good * 3 <= bad && othersGood >= 4
            systemRounds[c] = if (mostlyRejected) (systemRounds[c] ?: 0) + 1 else 0
            if ((systemRounds[c] ?: 0) >= SYSTEM_ROUNDS) flagged = c
        }
        if (flagged != null) {
            val others = accepted.keys.filter { it != flagged }.sortedBy { it.ordinal }.joinToString(" and ") { it.displayName }
            raise(
                Check.SYSTEMS, Severity.ALERT, Threat.SPOOFING,
                "${flagged.displayName} doesn't agree with $others (${rejected[flagged]} of ${(rejected[flagged] ?: 0) + (accepted[flagged] ?: 0)} left out): a fake ${flagged.displayName} signal is the classic attack.",
                now,
            )
        }
        val agreeing = accepted.keys.sortedBy { it.ordinal }.joinToString(", ") { it.displayName }
        okText[Check.SYSTEMS] = if (accepted.keys.size >= 2) "$agreeing agree" else "only ${agreeing.ifEmpty { "no system" }} to compare"
    }

    private fun jumps(input: IntegrityInput, now: Double) {
        val f = input.fused
        if (f != null && f.restarts > seenRestarts) {
            seenRestarts = f.restarts
            val move = f.lastRestartMoveM
            val gap = f.lastRestartGapS ?: 0.0
            if (move != null && gap < JUMP_WINDOW_S && move > JUMP_FLOOR_M + GROUND_SPEED_MPS * gap) {
                raise(Check.JUMPS, Severity.ALERT, Threat.SPOOFING, "TPS's position jumped ${distance(move)} in %.0f s, faster than anything on the ground: the satellites now place you elsewhere.".format(max(gap, 1.0)), now)
            }
        }
        // Holding: the signals put TPS somewhere it couldn't have reached.
        if (f != null && f.suspect == Suspect.OUT_OF_REACH) {
            raise(
                Check.JUMPS, if (f.suspectMoveM > JUMP_FLOOR_M) Severity.ALERT else Severity.CAUTION, Threat.SPOOFING,
                "The signals put TPS ${distance(f.suspectMoveM)} away, farther than it could have gone: it holds its last position instead of following (${duration(f.suspectForS)} so far).", now,
            )
        }
        // A hold that ended with TPS taking the signals' position: within reach by then, but it may be false.
        if (f != null && f.released > seenReleases) {
            seenReleases = f.released
            raise(
                Check.JUMPS, Severity.CAUTION, Threat.SPOOFING,
                "TPS held for ${duration(f.lastReleaseAfterS ?: 0.0)}, then took the signals' position ${distance(f.lastReleaseMoveM ?: 0.0)} away, as far as it could have gone by then: it may be false.", now, RELEASE_HOLD_S,
                replace = true,
            )
        }
        val chip = input.chip
        if (chip != null) {
            lastChip?.let { seen ->
                val gap = now - seen.t
                // From where it was heading: a fast train comes out of a tunnel kilometres on.
                val heading = if (seen.speedMps != null && seen.bearingDeg != null && gap > 0) {
                    val b = Math.toRadians(seen.bearingDeg)
                    Geo.moved(seen.at, seen.speedMps * gap * kotlin.math.sin(b), seen.speedMps * gap * kotlin.math.cos(b))
                } else {
                    seen.at
                }
                val move = Solver.offset(heading, Geo.toEcef(chip)).let { hypot(it[0], it[1]) }
                if (gap in 0.0..JUMP_WINDOW_S && move > CHIP_JUMP_FLOOR_M + GROUND_SPEED_MPS * gap) {
                    raise(Check.JUMPS, Severity.ALERT, Threat.SPOOFING, "The chip's position jumped ${distance(move)} in %.0f s, faster than anything on the ground.".format(max(gap, 1.0)), now)
                }
            }
            lastChip = ChipSeen(now, chip, input.chipSpeedMps, input.chipBearingDeg)
        }
        okText[Check.JUMPS] = "none"
    }

    private fun motion(input: IntegrityInput, now: Double) {
        val f = input.fused
        if (f?.suspect == Suspect.DRAGGED) {
            // Far beyond what reflections do: an alert. Within it, a caution (indoors, a still
            // phone's signals can wander tens of metres).
            if (f.suspectMoveM > JUMP_FLOOR_M) {
                raise(
                    Check.MOTION, Severity.ALERT, Threat.SPOOFING,
                    "The signals moved TPS ${distance(f.suspectMoveM)} while the phone lay still: false signals dragging the position, or the phone is on something moving very smoothly. TPS holds where the phone came to rest.", now,
                )
            } else {
                raise(
                    Check.MOTION, Severity.CAUTION, Threat.SPOOFING,
                    "The signals moved TPS ${distance(f.suspectMoveM)} while the phone lay still: false signals, or reflections. TPS holds where the phone came to rest.", now,
                )
            }
        }
        okText[Check.MOTION] = when (input.motion) {
            Motion.UNKNOWN -> "motion sensors not reporting"
            Motion.STILL -> if (f?.still == true) "phone at rest; TPS's position held still" else "phone at rest"
            Motion.QUIET -> if (f?.still == true) "phone steady; TPS's position held still" else "phone steady"
            Motion.MOVING -> "phone moving"
        }
    }

    private fun time(input: IntegrityInput, now: Double) {
        val offset = input.gnssMinusElapsedMs
        if (offset != null) {
            val base = timeBase
            if (base == null) {
                // The first few fixes set what's steady (the chip's fix time holds to ~15 ms).
                timeFirst += offset
                if (timeFirst.size >= TIME_BASELINE_ROUNDS) timeBase = timeFirst.sorted()[timeFirst.size / 2]
            } else if (abs(offset - base) > TIME_JUMP_MS) {
                raise(Check.TIME, Severity.ALERT, Threat.SPOOFING, "GNSS time jumped %s against the phone's own steady clock: false signals shifting time.".format(seconds(offset - base)), now)
                timeBase = offset
            }
        }
        val network = input.gnssMinusNetworkMs
        val wall = input.gnssMinusWallMs
        if (network != null && abs(network) > NETWORK_TIME_MS) {
            raise(Check.TIME, Severity.ALERT, Threat.SPOOFING, "GNSS time is %s off Android's network time.".format(seconds(network)), now)
        } else if (network != null && abs(network) > NETWORK_CAUTION_MS) {
            raise(
                Check.TIME, Severity.CAUTION, Threat.SPOOFING,
                "GNSS time is %s off Android's network time: false signals shifting time, or the phone's network time is stale.".format(seconds(network)), now,
            )
        } else if (network == null && wall != null && abs(wall) > WALL_TIME_MS) {
            raise(Check.TIME, Severity.CAUTION, Threat.SPOOFING, "GNSS time is %s off the phone's clock (or the phone's clock is wrong).".format(seconds(wall)), now)
        }
        okText[Check.TIME] = when {
            network != null -> "steady, ${seconds(network)} from network time"
            wall != null -> "steady; phone clock ${seconds(-wall)}"
            else -> "steady"
        }
    }

    private fun startup(input: IntegrityInput, now: Double) {
        val last = remembered
        if (last == null) {
            okText[Check.STARTUP] = "nothing to compare with yet"
            return
        }
        if (startupChecked) return
        okText[Check.STARTUP] = "waiting for the first position"
        val fused = input.fused?.takeIf { it.usable }
        val here = fused?.position ?: input.chip ?: return
        val accuracy = (fused?.horizontalAccuracyM ?: input.chipAccuracyM)?.takeIf { it <= STARTUP_ACCURACY_M } ?: return
        val gnss = input.gnssTimeMs ?: return
        startupChecked = true
        // Within one boot the monotonic clock counts honestly, whatever the satellites say.
        val elapsedNow = input.elapsedMs
        val counted = if (input.bootCount != null && input.bootCount == last.bootCount && elapsedNow != null && elapsedNow >= last.elapsedMs) {
            elapsedNow - last.elapsedMs
        } else {
            null
        }
        val elapsedS = when {
            counted != null -> counted / 1_000.0
            input.wallMs != null -> (input.wallMs - last.wallMs) / 1_000.0
            else -> (gnss - last.gnssTimeMs) / 1_000.0
        }
        // A phone's clock crystal runs up to ~100 ppm off while it sleeps.
        val toleranceMs = STARTUP_TIME_MS + abs(elapsedS) * 1_000 * CLOCK_RATE_TOLERANCE
        if (counted != null) {
            val off = gnss - (last.gnssTimeMs + counted)
            if (abs(off) > toleranceMs) {
                raise(Check.STARTUP, Severity.ALERT, Threat.SPOOFING, "GNSS time is %s off what the phone's own clock counted since TPS last ran.".format(seconds(off)), now, STARTUP_HOLD_S)
            }
        } else if (gnss < last.gnssTimeMs - toleranceMs) {
            raise(Check.STARTUP, Severity.ALERT, Threat.SPOOFING, "GNSS time is ${duration((last.gnssTimeMs - gnss) / 1_000.0)} earlier than when TPS last ran.", now, STARTUP_HOLD_S)
        }
        val moved = Geodesy.distanceMeters(last.position.latitude, last.position.longitude, here.latitude, here.longitude)
        // Beyond what the position's own uncertainty explains.
        val beyond = moved - accuracy
        if (elapsedS > 0 && beyond > STARTUP_FLOOR_M && beyond / elapsedS > AIRLINER_MPS) {
            raise(
                Check.STARTUP, Severity.ALERT, Threat.SPOOFING,
                "TPS starts ${distance(moved)} from where it last was ${duration(elapsedS)} ago: faster than an airliner.", now, STARTUP_HOLD_S,
            )
        }
        val summary = "${distance(moved)} from last time, ${duration(abs(elapsedS))} ago"
        // Once the alert has faded, the row still says what start-up found.
        okText[Check.STARTUP] = when {
            latched[Check.STARTUP] != null -> "flagged at start-up: $summary"
            counted != null -> "$summary; GNSS time matches the phone's clock"
            else -> summary
        }
    }

    private fun source(input: IntegrityInput, now: Double) {
        if (input.chipMock) {
            raise(Check.SOURCE, Severity.ALERT, Threat.SPOOFING, "Android marks the chip's position as a mock location: another app is supplying it. TPS's own position doesn't use it.", now)
        }
        okText[Check.SOURCE] = "the GNSS chip, not a mock-location app"
    }

    private fun clock(input: IntegrityInput, now: Double) {
        val f = input.fused ?: run { okText[Check.CLOCK] = "needs TPS's own position"; return }
        if (f.clockJumps > seenClockJumps) {
            seenClockJumps = f.clockJumps
            val us = abs(f.lastClockJumpM) / Pseudoranges.C * 1e6
            raise(Check.CLOCK, Severity.CAUTION, Threat.SPOOFING, "The receiver clock jumped %.1f µs without the chip reporting a reset: false signals shifting time, or a chip glitch.".format(us), now)
        }
        okText[Check.CLOCK] = "steady (drift %.0f m/s)".format(f.clockDriftMps)
    }

    private fun fit(input: IntegrityInput, now: Double) {
        val f = input.fused ?: run { okText[Check.FIT] = "needs TPS's own position"; return }
        val bad = f.rejectedBySystem.values.sum()
        val total = bad + f.acceptedBySystem.values.sum()
        fitRounds = if (total >= 6 && bad >= total * FIT_SHARE) fitRounds + 1 else 0
        if (fitRounds >= FIT_ROUNDS) {
            raise(Check.FIT, Severity.CAUTION, Threat.FAULT, "$bad of $total satellites don't fit with the rest: strong reflections, or false signals.", now)
        }
        okText[Check.FIT] = if (total == 0) "no measurements this round" else "${total - bad} of $total fit"
    }

    private fun chip(input: IntegrityInput, now: Double) {
        val f = input.fused?.takeIf { it.usable }
        val chip = input.chip
        if (f == null || chip == null) {
            chipRounds = 0
            okText[Check.CHIP] = "needs both fixes"
            return
        }
        val d = Solver.offset(chip, f.ecef).let { hypot(it[0], it[1]) }
        val limit = max(CHIP_FLOOR_M, 4 * sqrt(f.horizontalAccuracyM * f.horizontalAccuracyM + (input.chipAccuracyM ?: 10.0).let { it * it }))
        chipRounds = if (d > limit) chipRounds + 1 else 0
        if (chipRounds >= CHIP_ROUNDS) {
            raise(Check.CHIP, Severity.CAUTION, Threat.FAULT, "TPS and the chip disagree by ${distance(d)}.", now)
        }
        okText[Check.CHIP] = "${distance(d)} apart"
    }

    private fun distance(m: Double) = when {
        m >= 100_000 -> "%,.0f km".format(m / 1_000)
        m >= 1_000 -> "%.1f km".format(m / 1_000)
        else -> "%.0f m".format(m)
    }

    private fun seconds(ms: Long) = "%+.1f s".format(ms / 1_000.0)

    private fun duration(s: Double) = when {
        s < 120 -> "%.0f s".format(s)
        s < 7_200 -> "%.0f min".format(s / 60)
        s < 172_800 -> "%.0f h".format(s / 3_600)
        else -> "%.0f days".format(s / 86_400)
    }

    private fun median(v: List<Double>): Double {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    private fun sd(v: List<Double>): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    companion object {
        const val ALERT_HOLD_S = 30.0
        const val CAUTION_HOLD_S = 20.0
        /** Rounds a condition must hold before it counts. */
        const val PERSIST_ROUNDS = 3

        const val BASELINE_ROUNDS = 20
        const val BASELINE_TAU_S = 60.0
        const val MIN_SIGNALS = 4
        const val DROP_DB = 10.0
        const val SYSTEM_DROP_DB = 6.0
        const val RISE_DB = 6.0
        const val SYSTEM_RISE_DB = 4.0
        /** A rise counts only to a median this strong (open sky gives phones ~30–38 dB-Hz). */
        const val RISE_LEVEL_DB = 42.0
        const val UNIFORM_MIN_SIGNALS = 8
        const val UNIFORM_SD_DB = 2.0
        const val UNIFORM_ROUNDS = 10
        const val STRONG_DB = 48.0
        const val GAIN_CHANGE_DB = 6.0
        /** A gain change this big confirms a signal change as interference; smaller is steady. */
        const val GAIN_CORROBORATE_DB = 3.0
        /** Rounds the gain's verdict looks back over. */
        const val GAIN_WINDOW = 3
        /** A signal change lasting this many rounds becomes the new normal. */
        const val NEW_NORMAL_ROUNDS = 30

        const val SYSTEM_ROUNDS = 3
        const val JUMP_WINDOW_S = 60.0
        const val JUMP_FLOOR_M = 200.0
        const val CHIP_JUMP_FLOOR_M = 500.0
        /** Faster than this over the ground is a car at 180 km/h. */
        const val GROUND_SPEED_MPS = 50.0
        const val TIME_JUMP_MS = 500L
        const val TIME_BASELINE_ROUNDS = 3
        const val NETWORK_TIME_MS = 5_000L
        /**
         * Android refreshes its network time about every 18–24 h and counts on with the phone's
         * own clock in between, which can drift by a second; some chips also stamp their fix a
         * few hundred ms late. So 2 s, not 1.
         */
        const val NETWORK_CAUTION_MS = 2_000L
        const val STARTUP_TIME_MS = 1_000L
        /** How far the phone's clock may run off while it sleeps (100 ppm). */
        const val CLOCK_RATE_TOLERANCE = 1e-4
        const val STARTUP_FLOOR_M = 2_000.0
        /** The start-up check waits for a position at least this good. */
        const val STARTUP_ACCURACY_M = 1_000.0
        /** Faster than this between sessions is faster than an airliner (with a jet stream behind it). */
        const val AIRLINER_MPS = 400.0
        const val STARTUP_HOLD_S = 120.0
        /** How long the caution after a hold that ended by following the signals stays up. */
        const val RELEASE_HOLD_S = 300.0
        const val WALL_TIME_MS = 60_000L
        const val FIT_SHARE = 0.3
        const val FIT_ROUNDS = 5
        const val CHIP_FLOOR_M = 50.0
        const val CHIP_ROUNDS = 5
    }
}
