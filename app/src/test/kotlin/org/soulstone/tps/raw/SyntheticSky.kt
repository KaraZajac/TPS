package org.soulstone.tps.raw

import java.util.Random
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import org.soulstone.tps.gnss.Constellation

/** Where the receiver is at second t: east and north metres from [SyntheticSky.home]. */
fun interface Path {
    fun at(t: Double): Pair<Double, Double>
}

/**
 * A sky for the super position and the integrity checks to look at: GPS, Galileo and
 * GLONASS-like satellites, a drifting receiver clock, a GLONASS clock offset, code and Doppler
 * noise, signal strengths that rise with elevation, and hooks for every kind of trouble.
 */
class Sky(
    val path: Path = Path { 0.0 to 0.0 },
    val glonassBias: Double = 1_000.0,
    /** The chip reports the inter-system biases (as Kara's does). */
    val chipBiases: Boolean = true,
    /** The chip steers its clock offset every second (as Kara's does), or leaves it be. */
    val steered: Boolean = true,
    val codeNoise: Double = 2.0,
    /** Slow multipath: each satellite's pseudorange off by a bias of this size, metres, wandering over [multipathTauS]. */
    val multipathM: Double = 0.0,
    val multipathTauS: Double = 60.0,
    val rateNoise: Double = 0.2,
    /** A constant error on a system's Doppler, m/s. */
    val rateBias: (Constellation) -> Double = { 0.0 },
    /** Which satellites are received at second t; all by default. */
    val keep: (t: Int, Constellation, Int) -> Boolean = { _, _, _ -> true },
    /** Extra metres on a satellite's pseudorange at second t. */
    val fault: (t: Int, Constellation, Int) -> Double = { _, _, _ -> 0.0 },
    /** The receiver clock's own jumps, metres, by second. */
    val clockStep: (t: Int) -> Double = { 0.0 },
    /** A hardware clock discontinuity (the count goes up) from this second. */
    val discontinuityAt: Int? = null,
    /** A spoofer: signals of that system at second t are timed for this place instead (east, north metres). */
    val spoof: (t: Int, Constellation) -> Pair<Double, Double>? = { _, _ -> null },
    /** Signal strength, dB-Hz, for a satellite at elevation el (degrees) at second t. */
    val cn0: (t: Int, Constellation, el: Double, Random) -> Double = { _, _, el, r -> 22 + 18 * sin(Math.toRadians(el)) + 2 * r.nextGaussian() },
    /** Receiver gain on L1, dB, by second; null when the chip doesn't report it. */
    val agcDb: (t: Int) -> Double? = { null },
    /** How far GNSS time has jumped against the phone's monotonic clock, ms, by second. */
    val timeJumpMs: (t: Int) -> Long = { 0L },
    /** Metres above [SyntheticSky.home] at second t (stairs, a hill). */
    val climb: (t: Double) -> Double = { 0.0 },
    /** The phone has a barometer; the weather moves sea-level pressure by this many hPa; jumps that aren't climbs (a door, a car's fan). */
    val barometer: Boolean = false,
    val weatherHpa: (t: Double) -> Double = { 0.0 },
    val pressureJumpHpa: (t: Int) -> Double = { 0.0 },
    val seed: Long = 1,
)

/** One second of what the phone would report. */
class Round(
    val t: Double,
    val tow: Double,
    val clock: RawClock,
    val observations: List<Observation>,
    val signals: List<SignalLevel>,
    val agc: List<Agc>,
    val gnssMinusElapsedMs: Long,
    val truth: Ecef,
    val pressureHpa: Double? = null,
)

object SyntheticSky {
    /** The Statue of Liberty: a public place. */
    val home = Geodetic(40.6892, -74.0445, 68.0)
    /** A rough first guess, 50 m off. */
    val start: Ecef = Geo.toEcef(Geodetic(home.latitude + 0.0004, home.longitude - 0.0005, home.height + 30))

    private const val OMEGA_E = 7.2921151467e-5
    private const val DRIFT = 43.0   // m/s, like Kara's phone

    private val glonass = List(24) { k ->
        val plane = k / 8
        SyntheticOrbits.gps()[0].copy(
            svid = k + 1, sqrtA = 5050.7, e = 0.001, i0 = Math.toRadians(64.8),
            omega0 = Math.toRadians(120.0 * plane - 160), m0 = Math.toRadians(45.0 * (k % 8) + 15.0 * plane - 180),
        )
    }
    private val constellations = SyntheticOrbits.gps().map { Constellation.GPS to it } +
        SyntheticOrbits.galileo().map { Constellation.GALILEO to it } +
        glonass.map { Constellation.GLONASS to it }

    fun position(path: Path, t: Double, extra: Pair<Double, Double> = 0.0 to 0.0, up: Double = 0.0): Ecef {
        val (e, n) = path.at(t)
        val lat = home.latitude + (n + extra.second) / 111_132.0
        val lon = home.longitude + (e + extra.first) / (111_320.0 * cos(Math.toRadians(home.latitude)))
        return Geo.toEcef(Geodetic(lat, lon, home.height + up))
    }

    fun rounds(sky: Sky, seconds: Int): List<Round> {
        val random = Random(sky.seed)
        var clockSteps = 0.0
        val multipath = HashMap<Pair<Constellation, Int>, Double>()
        // Its own stream, so turning the barometer on doesn't change the GNSS errors.
        val baroNoise = Random(sky.seed + 1_000)
        val keepShare = kotlin.math.exp(-1.0 / sky.multipathTauS)
        val fullBias0 = -1_477_146_410_228_455_804L
        return List(seconds) { k ->
            val t = k.toDouble()
            clockSteps += sky.clockStep(k)
            val receiverClock = 120.0 + DRIFT * t + clockSteps          // metres, against GPS time
            val truth = position(sky.path, t, up = sky.climb(t))
            val velocity = position(sky.path, t + 0.5, up = sky.climb(t + 0.5)) - position(sky.path, t - 0.5, up = sky.climb(t - 0.5))
            val geo = Geo.toGeodetic(truth)
            val tow = SyntheticOrbits.TOE + 1_800.0 + t
            // What the chip took off its clock this second: all of it (steered) or nothing.
            val steerNs = if (sky.steered) (receiverClock - 120.0) / Pseudoranges.C * 1e9 else 0.0
            val reported = receiverClock - steerNs * 1e-9 * Pseudoranges.C
            val observations = ArrayList<Observation>()
            val signals = ArrayList<SignalLevel>()
            for ((system, eph) in constellations) {
                if (!sky.keep(k, system, eph.svid)) continue
                // A spoofer times its signals for somewhere else, and (a competent one) gives them
                // the Doppler of that place's motion.
                val fake = sky.spoof(k, system)
                val receiver = fake?.let { position(sky.path, t, it, sky.climb(t)) } ?: truth
                val receiverVelocity = if (fake == null) {
                    velocity
                } else {
                    val after = position(sky.path, t + 1, sky.spoof(k + 1, system) ?: fake, sky.climb(t + 1))
                    val before = position(sky.path, t - 1, sky.spoof(k - 1, system) ?: fake, sky.climb(t - 1))
                    Ecef((after.x - before.x) / 2, (after.y - before.y) / 2, (after.z - before.z) / 2)
                }
                var tx = tow - 0.075
                var sat = Orbits.state(eph, tx).position
                repeat(4) {
                    val range = (sat - receiver).norm() + OMEGA_E * (sat.x * receiver.y - sat.y * receiver.x) / Pseudoranges.C
                    tx = tow - range / Pseudoranges.C
                    sat = Orbits.state(eph, tx).position
                }
                val (_, el) = Geo.azEl(geo, truth, sat)
                if (Math.toDegrees(el) < 12) continue
                val satVelocity = Orbits.state(eph, tx + 0.5).position - Orbits.state(eph, tx - 0.5).position
                val dts = Orbits.clockError(eph, tx)
                val satDrift = (Orbits.clockError(eph, tx + 0.5) - Orbits.clockError(eph, tx - 0.5)) * Pseudoranges.C
                val d = sat - receiver
                val r = d.norm()
                val range = r + OMEGA_E * (sat.x * receiver.y - sat.y * receiver.x) / Pseudoranges.C
                val tropo = Atmosphere.troposphere(geo, el)
                val bias = if (system == Constellation.GLONASS) sky.glonassBias else 0.0
                val reflection = if (sky.multipathM > 0) {
                    val key = system to eph.svid
                    val next = (multipath[key] ?: sky.multipathM * random.nextGaussian()) * keepShare +
                        sky.multipathM * kotlin.math.sqrt(1 - keepShare * keepShare) * random.nextGaussian()
                    multipath[key] = next
                    next
                } else {
                    0.0
                }
                val pseudorange = range + reported + bias - Pseudoranges.C * dts + tropo + reflection +
                    sky.codeNoise * random.nextGaussian() + sky.fault(k, system, eph.svid)
                val closing = ((satVelocity.x - receiverVelocity.x) * d.x + (satVelocity.y - receiverVelocity.y) * d.y + (satVelocity.z - receiverVelocity.z) * d.z) / r
                val strength = sky.cn0(k, system, Math.toDegrees(el), random)
                observations += Observation(
                    system, eph.svid, sat, dts, pseudorange, 3.0,
                    carrierHz = if (system == Constellation.GLONASS) 1_602.0e6 else 1_575.42e6,
                    ionoMeters = 0.0, tropoMeters = tropo,
                    satelliteVelocity = satVelocity, satelliteClockDriftMps = satDrift,
                    rateMps = closing + DRIFT - satDrift + sky.rateBias(system) + sky.rateNoise * random.nextGaussian(), rateSigmaMps = 0.3,
                    cn0DbHz = strength, isbMeters = if (sky.chipBiases) bias else null,
                )
                signals += SignalLevel(system, eph.svid, strength, Math.toDegrees(el))
            }
            val whole = floor(steerNs).toLong()
            val clock = RawClock(
                timeNanos = 5_000_000_000_000L + k * 1_000_000_000L,
                fullBiasNanos = fullBias0 + whole,
                biasNanos = steerNs - whole,
                discontinuityCount = if (sky.discontinuityAt != null && k >= sky.discontinuityAt) 8 else 7,
                leapSecond = 18,
            )
            Round(
                t, tow, clock, observations, signals,
                agc = sky.agcDb(k)?.let { listOf(Agc(Constellation.GPS, 1_575_420_000L, it)) }.orEmpty(),
                gnssMinusElapsedMs = 1_700_000_000_000L + sky.timeJumpMs(k),
                truth = truth,
                // The standard atmosphere from 33 m below the ellipsoid height (the geoid, roughly), the weather on top.
                pressureHpa = if (sky.barometer) {
                    (1_013.25 + sky.weatherHpa(t)) * Math.pow(1 - (home.height + sky.climb(t) - 33.0) / 44_330.0, 5.255) +
                        sky.pressureJumpHpa(k) + 0.02 * baroNoise.nextGaussian()
                } else {
                    null
                },
            )
        }
    }

    /**
     * The super position over [seconds] of [sky]: its answer and the true position, each second.
     * [motion] is what the motion sensors say at each second.
     */
    fun filter(sky: Sky, seconds: Int, motion: (Int) -> Motion = { Motion.UNKNOWN }, robust: Boolean = true): List<Pair<FusedFix?, Ecef>> {
        val filter = SuperPosition(robust)
        return rounds(sky, seconds).mapIndexed { k, r -> filter.step(r.clock, r.observations, start, null, r.tow, motion = motion(k), pressureHpa = r.pressureHpa) to r.truth }
    }
}
