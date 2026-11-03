package org.soulstone.tps.raw

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import org.soulstone.tps.gnss.Carrier
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.SkySnapshot
import org.soulstone.tps.gnss.Tier

/** One system's part of an epoch: what was measured, what could be used, and its own fix. */
data class SystemReport(
    val constellation: Constellation,
    /** Signals the chip measured. */
    val signals: Int,
    /** Of those, with the satellite's time decoded, so a pseudorange could be formed. */
    val ranged: Int,
    /** Of those, with an orbit TPS trusts: decoded by TPS, or the chip's own satellite position. */
    val withOrbit: Int,
    val solution: Solution?,
    /** Of [withOrbit], from the chip's satellite positions. */
    val fromChip: Int = 0,
)

data class OwnFixReport(
    val systems: List<SystemReport>,
    /** TPS's own fix from every system together. */
    val combined: Solution?,
    /** Decoded orbits held, per system. */
    val orbits: Map<Constellation, Int>,
    /** Navigation messages received, by type. */
    val navMessages: Map<Int, Int>,
    /** Messages that failed parity or CRC. */
    val navRejected: Int,
    /** Decoded orbits dropped because they put the satellite somewhere the chip doesn't. */
    val orbitsDisagreeing: Int,
    /** This epoch's signals with the chip's satellite position, used. */
    val chipOrbits: Int = 0,
    /** This epoch's chip positions dropped: implausible, or not where the chip's own sky has the satellite. */
    val chipOrbitsDropped: Int = 0,
    /** Where the chip's orbits came from ([EphemerisSource] to signals; -1 when it doesn't say). */
    val chipOrbitSources: Map<Int, Int> = emptyMap(),
    /** The super position: every satellite, one clock, filtered over time. */
    val fused: FusedFix? = null,
)

/**
 * TPS's own position fixes from the chip's raw measurements. A satellite's orbit comes from
 * the broadcast navigation messages TPS decodes itself or, failing that, from the satellite
 * position the chip computed and sent along with the measurement ([ChipPvt]). Either way the
 * fix is TPS's: one per system, and one from all together. Plain Kotlin; not thread-safe
 * (one thread feeds it).
 */
class OwnFixEngine {
    private val gps = GpsLnavAssembler(Constellation.GPS)
    private val qzss = GpsLnavAssembler(Constellation.QZSS)
    private val galileo = GalileoInavAssembler()
    private val orbits = HashMap<Pair<Constellation, Int>, KeplerEphemeris>()
    private val navCounts = HashMap<Int, Int>()
    private var disagreeing = 0
    private val fusion = SuperPosition()

    val klobuchar: Klobuchar? get() = gps.klobuchar ?: qzss.klobuchar

    fun accept(message: NavMessage) {
        navCounts[message.type] = (navCounts[message.type] ?: 0) + 1
        val eph = when (message.type) {
            NavType.GPS_L1CA -> gps.accept(message.svid, message.data)
            NavType.QZS_L1CA -> qzss.accept(message.svid, message.data)
            NavType.GAL_I -> galileo.accept(message.svid, message.data)
            else -> null
        } ?: return
        orbits[eph.constellation to eph.svid] = eph
    }

    /** For tests and replays: an orbit from elsewhere. */
    fun addOrbit(eph: KeplerEphemeris) {
        orbits[eph.constellation to eph.svid] = eph
    }

    /**
     * Solves one epoch. [chip] is the chip's latest fix (the starting guess, and the place
     * the orbit check looks from); [sky] is the chip's satellite status, to check orbits against.
     */
    fun solve(epoch: RawEpoch, chip: Geodetic?, sky: SkySnapshot?, chipAccuracyM: Double? = null): OwnFixReport {
        // One signal per satellite, on the upper L band (L1, E1, G1, B1): the broadcast clocks
        // and group delays are for those, so a dual-frequency phone's L5/E5a signals are left out.
        val primary = epoch.measurements
            .filter { m -> Carrier.of(m.carrierHz).let { it.tier == Tier.UPPER_L || it == Carrier.UNKNOWN } }
            .groupBy { it.constellation to it.svid }
            .map { (_, list) -> list.maxBy { it.cn0DbHz } }
        val ranges = primary.mapNotNull { Pseudoranges.compute(epoch.clock, it) }
        val receiveGpsSeconds = epoch.clock.fullBiasNanos?.let {
            Math.floorMod(epoch.clock.timeNanos - it, Pseudoranges.WEEK_NS) * 1e-9
        } ?: 0.0
        val chipEcef = chip?.let { Geo.toEcef(it) }

        val observations = ArrayList<Observation>()
        val fromChip = HashSet<Pair<Constellation, Int>>()
        var chipDropped = 0
        val chipSources = HashMap<Int, Int>()
        for (r in ranges) {
            val m = r.measurement
            val key = m.constellation to m.svid
            val eph = orbits[key]
            if (eph != null) {
                // A satellite flagged unhealthy is left out, whatever else knows its orbit.
                if (!eph.healthy) continue
                val own = decodedObservation(r, eph, chip, chipEcef, sky)
                if (own != null) {
                    observations += own
                    continue
                }
            }
            val pvt = m.pvt ?: continue
            if (!pvt.plausible || (chip != null && chipEcef != null && !agreesWithChip(chip, chipEcef, pvt.position, m, sky))) {
                chipDropped++
                continue
            }
            observations += withDoppler(
                Observation(
                    m.constellation, m.svid, pvt.position, pvt.clockMeters / Pseudoranges.C, r.meters, r.sigmaMeters, m.carrierHz,
                    ionoMeters = pvt.ionoMeters, tropoMeters = pvt.tropoMeters,
                    satelliteVelocity = pvt.velocity, satelliteClockDriftMps = pvt.clockDriftMps,
                ),
                m,
            )
            fromChip += key
            val source = pvt.ephemerisSource ?: -1
            chipSources[source] = (chipSources[source] ?: 0) + 1
        }

        val systems = epoch.measurements.map { it.constellation }.distinct()
            .filter { it != Constellation.SBAS && it != Constellation.UNKNOWN }
            .sortedBy { it.ordinal }
            .map { c ->
                val own = observations.filter { it.constellation == c }
                SystemReport(
                    constellation = c,
                    signals = primary.count { it.constellation == c },
                    ranged = ranges.count { it.measurement.constellation == c },
                    withOrbit = own.size,
                    solution = if (own.size >= 4) Solver.solve(own, chipEcef, klobuchar, receiveGpsSeconds) else null,
                    fromChip = own.count { (it.constellation to it.svid) in fromChip },
                )
            }
        val combined = if (observations.map { it.constellation }.distinct().size > 1) {
            Solver.solve(observations, chipEcef, klobuchar, receiveGpsSeconds)
        } else {
            null
        }
        val fused = fusion.step(
            epoch.clock, observations, chipEcef ?: combined?.ecef, klobuchar, receiveGpsSeconds,
            startAccuracyM = chipAccuracyM?.takeIf { chipEcef != null },
        )
        return OwnFixReport(
            systems = systems,
            combined = combined,
            orbits = orbits.keys.groupingBy { it.first }.eachCount(),
            navMessages = HashMap(navCounts),
            navRejected = gps.rejected + qzss.rejected + galileo.rejected,
            orbitsDisagreeing = disagreeing,
            chipOrbits = fromChip.size,
            chipOrbitsDropped = chipDropped,
            chipOrbitSources = chipSources,
            fused = fused,
        )
    }

    /** The observation from an orbit TPS decoded, or null when it's too old or disagrees with the chip (then dropped). */
    private fun decodedObservation(r: Pseudorange, eph: KeplerEphemeris, chip: Geodetic?, chipEcef: Ecef?, sky: SkySnapshot?): Observation? {
        val m = r.measurement
        // TPS doesn't model BeiDou's geostationary orbits; the chip's position covers them.
        if (Orbits.isBeidouGeo(m.constellation, m.svid)) return null
        // The satellite's clock was off by dts when it stamped the signal.
        var t = r.transmitTimeSeconds
        t -= Orbits.clockError(eph, t)
        if (abs(Orbits.sinceEpoch(t, eph.toe)) > MAX_AGE_S) return null
        val state = Orbits.state(eph, t)
        if (chip != null && chipEcef != null && !agreesWithChip(chip, chipEcef, state.position, m, sky)) {
            orbits.remove(m.constellation to m.svid)
            disagreeing++
            return null
        }
        // Velocity and clock drift for the Doppler, from the orbit half a second either side.
        val before = Orbits.state(eph, t - 0.5)
        val after = Orbits.state(eph, t + 0.5)
        return withDoppler(
            Observation(
                m.constellation, m.svid, state.position, state.clockError, r.meters, r.sigmaMeters, m.carrierHz,
                satelliteVelocity = after.position - before.position,
                satelliteClockDriftMps = (after.clockError - before.clockError) * Pseudoranges.C,
            ),
            m,
        )
    }

    /** Adds what the super position needs from the measurement itself. */
    private fun withDoppler(o: Observation, m: RawMeasurement): Observation {
        val rateSigma = m.pseudorangeRateUncertaintyMps.takeIf { it > 0 && it < MAX_RATE_SIGMA }
        return o.copy(
            rateMps = m.pseudorangeRateMps.takeIf { rateSigma != null && it.isFinite() },
            rateSigmaMps = rateSigma,
            cn0DbHz = m.cn0DbHz,
            isbMeters = m.fullInterSignalBiasNanos?.takeIf { it.isFinite() }?.times(1e-9 * Pseudoranges.C),
        )
    }

    /**
     * Does the decoded orbit put the satellite where the chip says it is? The chip reports
     * azimuth and elevation from its own orbit data; a decoding slip moves a satellite by
     * far more than [MAX_DISAGREEMENT_DEG]. With nothing to compare against, it passes.
     */
    private fun agreesWithChip(chip: Geodetic, chipEcef: Ecef, sat: Ecef, m: RawMeasurement, sky: SkySnapshot?): Boolean {
        val reported = sky?.satellites?.firstOrNull { it.constellation == m.constellation && it.svid == m.svid && it.positionKnown }
            ?: return true
        val (az, el) = Geo.azEl(chip, chipEcef, sat)
        val a1 = Math.toRadians(reported.azimuthDeg.toDouble())
        val e1 = Math.toRadians(reported.elevationDeg.toDouble())
        val cosSep = sin(el) * sin(e1) + cos(el) * cos(e1) * cos(az - a1)
        return Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0))) <= MAX_DISAGREEMENT_DEG
    }

    private companion object {
        /** Broadcast orbits are good for about four hours around their reference time. */
        const val MAX_AGE_S = 4 * 3600.0
        const val MAX_DISAGREEMENT_DEG = 3.0
        /** Rates less certain than this (m/s) are left out of the super position. */
        const val MAX_RATE_SIGMA = 20.0
    }
}
