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
    /** Of those, with a decoded orbit TPS trusts. */
    val withOrbit: Int,
    val solution: Solution?,
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
)

/**
 * TPS's own position fixes, computed from the chip's raw measurements and the broadcast
 * navigation messages TPS decodes itself. Plain Kotlin; not thread-safe (one thread feeds it).
 */
class OwnFixEngine {
    private val gps = GpsLnavAssembler(Constellation.GPS)
    private val qzss = GpsLnavAssembler(Constellation.QZSS)
    private val galileo = GalileoInavAssembler()
    private val orbits = HashMap<Pair<Constellation, Int>, KeplerEphemeris>()
    private val navCounts = HashMap<Int, Int>()
    private var disagreeing = 0

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
    fun solve(epoch: RawEpoch, chip: Geodetic?, sky: SkySnapshot?): OwnFixReport {
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
        for (r in ranges) {
            val m = r.measurement
            val key = m.constellation to m.svid
            val eph = orbits[key] ?: continue
            if (!eph.healthy || Orbits.isBeidouGeo(m.constellation, m.svid)) continue
            // The satellite's clock was off by dts when it stamped the signal.
            var t = r.transmitTimeSeconds
            t -= Orbits.clockError(eph, t)
            if (abs(Orbits.sinceEpoch(t, eph.toe)) > MAX_AGE_S) continue
            val state = Orbits.state(eph, t)
            if (chip != null && chipEcef != null && !agreesWithChip(chip, chipEcef, state.position, m, sky)) {
                orbits.remove(key)
                disagreeing++
                continue
            }
            observations += Observation(
                m.constellation, m.svid, state.position, state.clockError, r.meters, r.sigmaMeters, m.carrierHz,
            )
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
                )
            }
        val combined = if (observations.map { it.constellation }.distinct().size > 1) {
            Solver.solve(observations, chipEcef, klobuchar, receiveGpsSeconds)
        } else {
            null
        }
        return OwnFixReport(
            systems = systems,
            combined = combined,
            orbits = orbits.keys.groupingBy { it.first }.eachCount(),
            navMessages = HashMap(navCounts),
            navRejected = gps.rejected + qzss.rejected + galileo.rejected,
            orbitsDisagreeing = disagreeing,
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
    }
}
