package org.soulstone.tps.raw

import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.os.Build
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes raw GNSS data in GnssLogger's text format (`Raw`, `Nav`, `Fix`, `Status` lines),
 * so a recording also opens in Google's own GNSS analysis tools. `SatPvt` lines are TPS's own
 * addition: the chip's satellite positions, which GnssLogger doesn't record (tools skip
 * line types they don't know). `Fix` lines with the provider `TPS` are TPS's own position,
 * next to the chip's (`GPS`). Used only on the GNSS thread.
 */
class RawRecorder(private val dir: File) {
    private var writer: BufferedWriter? = null
    private var lastFullTracking: Boolean? = null
    private var lastIntegrity: String? = null

    var file: File? = null
        private set
    var lines = 0L
        private set
    var startedAtMs = 0L
        private set

    val recording: Boolean get() = writer != null

    fun start(appVersion: String) {
        stop()
        dir.mkdirs()
        // Keep the last few recordings; they hold positions, so they shouldn't pile up.
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
        val name = "tps_raw_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date()) + ".txt"
        val f = File(dir, name)
        writer = f.bufferedWriter()
        file = f
        lines = 0
        lastFullTracking = null
        lastIntegrity = null
        startedAtMs = System.currentTimeMillis()
        line("# TPS $appVersion raw GNSS recording (GnssLogger format)")
        line("# Version: v3.0.1.0 Platform: ${Build.VERSION.RELEASE} Manufacturer: ${Build.MANUFACTURER} Model: ${Build.MODEL}")
        line("# Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos")
        line("# Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees,UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees,elapsedRealtimeNanos,VerticalAccuracyMeters")
        line("# Nav,Svid,Type,Status,MessageId,Sub-messageId,Data(Bytes)")
        line("# Status,UnixTimeMillis,SignalCount,SignalIndex,ConstellationType,Svid,CarrierFrequencyHz,Cn0DbHz,AzimuthDegrees,ElevationDegrees,UsedInFix,HasAlmanacData,HasEphemerisData,BasebandCn0DbHz")
        line("# Agc,utcTimeMillis,ConstellationType,CarrierFrequencyHz,AgcDb")
        line("# Integrity,utcTimeMillis,Level,Check,Text")
        line("# Motion,utcTimeMillis,State,AccelSdMps2,GyroRmsRadps")
        line("# Baro,utcTimeMillis,PressureHpa")
        line("# SatPvt,utcTimeMillis,TimeNanos,Svid,ConstellationType,CarrierFrequencyHz,Flags,XEcefMeters,YEcefMeters,ZEcefMeters,UreMeters,VxEcefMps,VyEcefMps,VzEcefMps,HardwareCodeBiasMeters,TimeCorrectionMeters,ClockDriftMps,IonoDelayMeters,TropoDelayMeters,TimeOfClockSeconds,TimeOfEphemerisSeconds,IssueOfDataClock,IssueOfDataEphemeris,EphemerisSource")
    }

    fun stop(): File? {
        val f = file
        runCatching { writer?.flush(); writer?.close() }
        writer = null
        return f
    }

    private fun line(s: String) {
        val w = writer ?: return
        try {
            w.write(s)
            w.newLine()
            lines++
        } catch (e: java.io.IOException) {
            stop()
        }
    }

    /** TPS's warning when it changes (level or check), so a recording shows what the screen did. */
    fun integrity(report: IntegrityReport) {
        if (writer == null) return
        val h = report.headline
        val key = "${report.level}|${h?.check}"
        if (key == lastIntegrity) return
        lastIntegrity = key
        line(listOf("Integrity", System.currentTimeMillis().toString(), report.level.name, h?.check?.title.orEmpty(), h?.text.orEmpty().replace(',', ';')).joinToString(","))
    }

    /** What the motion sensors said this round (once a measurement event). */
    fun motion(r: MotionReading) {
        if (writer == null) return
        line("Motion,${System.currentTimeMillis()},${r.motion.name},${r.accelSdMps2?.let { "%.4f".format(Locale.ROOT, it) } ?: ""},${r.gyroRmsRadps?.let { "%.4f".format(Locale.ROOT, it) } ?: ""}")
    }

    /** The barometer's mean over the last second, hPa (once a measurement event). */
    fun baro(hPa: Double) {
        if (writer == null) return
        line("Baro,${System.currentTimeMillis()},${"%.3f".format(Locale.ROOT, hPa)}")
    }

    /** [epoch] is the same event as TPS read it, for the chip's satellite positions. */
    fun raw(event: GnssMeasurementsEvent, epoch: RawEpoch? = null) {
        if (writer == null) return
        val c = event.clock
        val now = System.currentTimeMillis()
        // Whether the chip duty-cycles changes its measurements; note it (Android 14+ says).
        val full = epoch?.fullTracking
        if (full != null && full != lastFullTracking) {
            line("# Full tracking: ${if (full) "on" else "off (duty-cycled)"} from $now")
            lastFullTracking = full
        }
        val clock = listOf(
            c.timeNanos.toString(),
            if (c.hasLeapSecond()) c.leapSecond.toString() else "",
            if (c.hasTimeUncertaintyNanos()) c.timeUncertaintyNanos.toString() else "",
            if (c.hasFullBiasNanos()) c.fullBiasNanos.toString() else "",
            if (c.hasBiasNanos()) c.biasNanos.toString() else "",
            if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos.toString() else "",
            if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond.toString() else "",
            if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond.toString() else "",
            c.hardwareClockDiscontinuityCount.toString(),
        ).joinToString(",")
        val chipsetElapsed = if (Build.VERSION.SDK_INT >= 29 && c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos.toString() else ""
        for (m in event.measurements) {
            // Deprecated, but Android names it the fallback when the API 33 AGC list is empty.
            val agc = if (@Suppress("DEPRECATION") m.hasAutomaticGainControlLevelDb()) {
                @Suppress("DEPRECATION") m.automaticGainControlLevelDb.toString()
            } else {
                ""
            }
            val api30 = Build.VERSION.SDK_INT >= 30
            line(
                listOf(
                    "Raw", now.toString(), clock,
                    m.svid.toString(), m.timeOffsetNanos.toString(), m.state.toString(),
                    m.receivedSvTimeNanos.toString(), m.receivedSvTimeUncertaintyNanos.toString(),
                    m.cn0DbHz.toString(), m.pseudorangeRateMetersPerSecond.toString(),
                    m.pseudorangeRateUncertaintyMetersPerSecond.toString(),
                    m.accumulatedDeltaRangeState.toString(), m.accumulatedDeltaRangeMeters.toString(),
                    m.accumulatedDeltaRangeUncertaintyMeters.toString(),
                    if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toString() else "",
                    "", "", "",
                    m.multipathIndicator.toString(),
                    if (m.hasSnrInDb()) m.snrInDb.toString() else "",
                    m.constellationType.toString(),
                    agc,
                    if (api30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz.toString() else "",
                    if (api30 && m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos.toString() else "",
                    if (api30 && m.hasFullInterSignalBiasUncertaintyNanos()) m.fullInterSignalBiasUncertaintyNanos.toString() else "",
                    if (api30 && m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos.toString() else "",
                    if (api30 && m.hasSatelliteInterSignalBiasUncertaintyNanos()) m.satelliteInterSignalBiasUncertaintyNanos.toString() else "",
                    if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) m.codeType else "",
                    chipsetElapsed,
                ).joinToString(",")
            )
        }
        for (m in epoch?.measurements.orEmpty()) {
            val p = m.pvt ?: continue
            line(
                listOf(
                    "SatPvt", now.toString(), c.timeNanos.toString(), m.svid.toString(), m.constellation.androidId.toString(),
                    m.carrierHz?.toString() ?: "", p.flags.toString(),
                    p.position.x.toString(), p.position.y.toString(), p.position.z.toString(), p.positionUreMeters.toString(),
                    p.velocity.x.toString(), p.velocity.y.toString(), p.velocity.z.toString(),
                    p.hardwareCodeBiasMeters.toString(), p.timeCorrectionMeters.toString(), p.clockDriftMps.toString(),
                    p.ionoMeters?.toString() ?: "", p.tropoMeters?.toString() ?: "",
                    p.timeOfClockSeconds?.toString() ?: "", p.timeOfEphemerisSeconds?.toString() ?: "",
                    p.issueOfDataClock?.toString() ?: "", p.issueOfDataEphemeris?.toString() ?: "",
                    p.ephemerisSource?.toString() ?: "",
                ).joinToString(",")
            )
        }
        if (Build.VERSION.SDK_INT >= 33) {
            for (a in event.gnssAutomaticGainControls) {
                line("Agc,$now,${a.constellationType},${a.carrierFrequencyHz},${a.levelDb}")
            }
        }
    }

    fun nav(m: GnssNavigationMessage) {
        if (writer == null) return
        line("Nav,${m.svid},${m.type},${m.status},${m.messageId},${m.submessageId}," + m.data.joinToString(",") { it.toString() })
    }

    fun fix(l: Location) {
        if (writer == null) return
        line(
            listOf(
                "Fix", "GPS", l.latitude.toString(), l.longitude.toString(),
                if (l.hasAltitude()) l.altitude.toString() else "",
                if (l.hasSpeed()) l.speed.toString() else "",
                if (l.hasAccuracy()) l.accuracy.toString() else "",
                if (l.hasBearing()) l.bearing.toString() else "",
                l.time.toString(),
                if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond.toString() else "",
                if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees.toString() else "",
                l.elapsedRealtimeNanos.toString(),
                if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters.toString() else "",
            ).joinToString(",")
        )
    }

    /** TPS's own position (the super position), as a `Fix` line with the provider `TPS`. */
    fun tpsFix(f: org.soulstone.tps.gnss.Fix) {
        if (writer == null) return
        line(
            listOf(
                "Fix", "TPS", f.latitude.toString(), f.longitude.toString(),
                f.altitudeM?.toString() ?: "", f.speedMps?.toString() ?: "", f.accuracyM?.toString() ?: "",
                f.bearingDeg?.toString() ?: "", f.gnssTimeMs.toString(), "", "",
                (f.elapsedRealtimeMs * 1_000_000).toString(), f.verticalAccuracyM?.toString() ?: "",
            ).joinToString(",")
        )
    }

    fun status(s: GnssStatus) {
        if (writer == null) return
        val now = System.currentTimeMillis()
        for (i in 0 until s.satelliteCount) {
            line(
                listOf(
                    "Status", now.toString(), s.satelliteCount.toString(), i.toString(),
                    s.getConstellationType(i).toString(), s.getSvid(i).toString(),
                    if (s.hasCarrierFrequencyHz(i)) s.getCarrierFrequencyHz(i).toString() else "",
                    s.getCn0DbHz(i).toString(), s.getAzimuthDegrees(i).toString(), s.getElevationDegrees(i).toString(),
                    (if (s.usedInFix(i)) 1 else 0).toString(),
                    (if (s.hasAlmanacData(i)) 1 else 0).toString(),
                    (if (s.hasEphemerisData(i)) 1 else 0).toString(),
                    if (Build.VERSION.SDK_INT >= 30 && s.hasBasebandCn0DbHz(i)) s.getBasebandCn0DbHz(i).toString() else "",
                ).joinToString(",")
            )
        }
    }
}
