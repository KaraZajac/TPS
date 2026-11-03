package org.soulstone.tps.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssNavigationMessage
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.location.altitude.AltitudeConverter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import org.soulstone.tps.raw.Agc
import org.soulstone.tps.raw.AgcBands
import org.soulstone.tps.raw.ChipInfo
import org.soulstone.tps.raw.ChipPvt
import org.soulstone.tps.raw.ChipPvtReader
import org.soulstone.tps.raw.FusedFix
import org.soulstone.tps.raw.IntegrityInput
import org.soulstone.tps.raw.IntegrityMonitor
import org.soulstone.tps.raw.IntegrityReport
import org.soulstone.tps.raw.SignalLevel
import org.soulstone.tps.raw.Geodetic
import org.soulstone.tps.raw.NavMessage
import org.soulstone.tps.raw.OwnFixEngine
import org.soulstone.tps.raw.PvtRead
import org.soulstone.tps.raw.RawClock
import org.soulstone.tps.raw.RawEpoch
import org.soulstone.tps.raw.RawMeasurement
import org.soulstone.tps.raw.RawRecorder
import org.soulstone.tps.raw.RawState
import org.soulstone.tps.raw.RawStatus
import org.soulstone.tps.raw.RecorderState
import org.soulstone.tps.raw.Remembered
import org.soulstone.tps.raw.Severity

/**
 * Listens to the phone's GNSS chip, or to [DemoSky], and publishes one [State].
 *
 * Android only runs the GNSS engine while someone requests GPS_PROVIDER locations, so
 * TPS holds that request (straight from LocationManager, never the fused provider,
 * which hides which satellites made a fix). Satellite status and NMEA arrive alongside
 * it. Everything runs only while TPS is on screen: MainActivity calls [start] when it
 * comes on screen and [stop] when it leaves.
 *
 * Callbacks arrive on a private thread. Each activation is a new session, and anything
 * still queued from an older one is dropped.
 */
class GnssEngine private constructor(context: Context) {

    enum class Status { IDLE, RUNNING, NEEDS_PERMISSION, APPROXIMATE_ONLY, LOCATION_OFF, NO_GNSS }

    /** Time to first fix: from the chip's `onFirstFix`, or TPS's own START-to-first-fix. */
    data class Ttff(val millis: Long, val fromChip: Boolean)

    data class State(
        /** Listening was asked for (TPS is on screen). */
        val wanted: Boolean = false,
        val demo: Boolean = false,
        val status: Status = Status.IDLE,
        val startedAtMs: Long = 0,
        val nowMs: Long = 0,
        val sky: SkySnapshot = SkySnapshot.EMPTY,
        /** The position to show: TPS's own ([tpsFix]) when it has one, else the chip's. Derived. */
        val fix: Fix? = null,
        /** The chip's own fix (Android's GPS provider). */
        val chipFix: Fix? = null,
        /** TPS's super position as a fix, while it has a usable one. */
        val tpsFix: Fix? = null,
        val nmea: NmeaTracker.State = NmeaTracker.State(),
        val ttff: Ttff? = null,
        // Derived on every update (see derive()).
        val fixValid: Boolean = false,
        val fixType: FixType? = null,
        val fixTypeFromChip: Boolean = false,
        val dop: Dop? = null,
        val dopFromChip: Boolean = false,
        val sbasCorrections: Boolean = false,
        val tier: FixTier = FixTier.IDLE,
        val fixSource: FixSource = FixSource.CHIP,
        val chipFixValid: Boolean = false,
        /** Satellites behind the shown fix. */
        val usedSatellites: Int = 0,
        val raw: RawState = RawState(),
        /** Signs of jamming or spoofing; null without raw measurements (demo sky, older phones). */
        val integrity: IntegrityReport? = null,
    ) {
        val running: Boolean get() = wanted && status == Status.RUNNING
        val fixAgeMs: Long? get() = fix?.let { (nowMs - it.elapsedRealtimeMs).coerceAtLeast(0) }
        val listeningMs: Long get() = if (running) (nowMs - startedAtMs).coerceAtLeast(0) else 0
    }

    private val app = context.applicationContext
    private val lm: LocationManager? = app.getSystemService(LocationManager::class.java)
    private val thread = HandlerThread("tps-gnss").apply { start() }
    private val handler = Handler(thread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * TPS's own fixes. Kept for the whole process: decoding a satellite's orbit from its
     * broadcast takes 18–30 s, and orbits stay good for hours. GNSS thread only.
     */
    private val ownFix = OwnFixEngine()

    /** GNSS thread only. */
    private val recorder = RawRecorder(File(app.filesDir, "recordings"))

    /** Jamming and spoofing checks. GNSS thread only; starts afresh with each session. */
    private val integrity = IntegrityMonitor()

    /** Where and when TPS last trusted its own position, for the next start-up's check. */
    private val lastTrusted = LastTrusted(app)
    private var rememberedAtMs: Long? = null

    /** Android's boot count: the monotonic clock only counts honestly within one boot. */
    private val bootCount: Int? = runCatching {
        android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT)
    }.getOrNull()
    private val _recorder = MutableStateFlow(RecorderState())
    val recorderState: StateFlow<RecorderState> = _recorder.asStateFlow()

    @Volatile private var fullTracking = false

    @Volatile private var session = 0
    private var active = false
    private var real: RealSession? = null
    private var tickJob: Job? = null
    private var demoJob: Job? = null

    /**
     * Listen to the chip, or to demo sky. Safe to call every time TPS comes on screen:
     * it does nothing if already listening in that mode, and otherwise starts afresh,
     * re-checking permission and location. Main thread.
     */
    fun start(demo: Boolean) {
        val s = _state.value
        if (active && s.demo == demo && (s.status == Status.RUNNING || s.status == Status.LOCATION_OFF)) return
        deactivate()
        _state.value = State(wanted = true, demo = demo)
        activate()
    }

    /** Stop listening and forget the session. A recording in progress is closed. Main thread. */
    fun stop() {
        deactivate()
        _state.value = State()
        stopRecording()
    }

    /** What the chip says about itself, for the CHIP card. */
    fun chipInfo(): ChipInfo = ChipInfo.read(lm)

    /** Asks for continuous raw measurements (API 31+) instead of duty-cycled ones; costs battery. */
    fun setFullTracking(on: Boolean) {
        if (fullTracking == on) return
        fullTracking = on
        val r = real ?: return
        lm?.let { m ->
            runCatching { m.unregisterGnssMeasurementsCallback(r.measurements) }
            registerMeasurements(m, r)
        }
    }

    /** Starts writing raw data to a new file. */
    fun startRecording(appVersion: String) {
        handler.post {
            recorder.start(appVersion)
            publishRecorder()
        }
    }

    /** Closes the file; [done] gets it on the main thread. */
    fun stopRecording(done: (File?) -> Unit = {}) {
        handler.post {
            val f = if (recorder.recording) recorder.stop() else null
            publishRecorder()
            scope.launch { done(f) }
        }
    }

    fun recording(name: String): File = File(File(app.filesDir, "recordings"), name)

    private fun publishRecorder() {
        _recorder.value = RecorderState(
            active = if (recorder.recording) recorder.file?.name else null,
            startedAtMs = recorder.startedAtMs,
            lines = recorder.lines,
            last = recorder.file?.name ?: _recorder.value.last,
        )
    }

    private fun activate() {
        val id = ++session
        active = true
        val now = SystemClock.elapsedRealtime()
        _state.update { it.copy(startedAtMs = now, nowMs = now) }
        if (_state.value.demo) startDemo(id) else startReal(id)
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                update(id) { it }
            }
        }
    }

    private fun deactivate() {
        session++
        active = false
        tickJob?.cancel()
        tickJob = null
        demoJob?.cancel()
        demoJob = null
        real?.let { r ->
            lm?.let { m ->
                runCatching { m.removeUpdates(r.location) }
                runCatching { m.unregisterGnssStatusCallback(r.status) }
                runCatching { m.removeNmeaListener(r.nmea) }
                runCatching { m.unregisterGnssMeasurementsCallback(r.measurements) }
                runCatching { m.unregisterGnssNavigationMessageCallback(r.navigation) }
            }
        }
        real = null
    }

    /** Applies [transform] if session [id] is still current, then re-derives. Any thread. */
    private fun update(id: Int, transform: (State) -> State) {
        _state.update { s -> if (session != id) s else derive(transform(s)) }
    }

    private fun derive(s: State): State {
        val now = SystemClock.elapsedRealtime()
        val running = s.wanted && s.status == Status.RUNNING
        fun fresh(f: Fix?) = running && f != null && now - f.elapsedRealtimeMs <= FixQuality.STALE_MS
        // TPS's own position when it has a current one; the chip's otherwise.
        val tps = s.tpsFix?.takeIf { fresh(it) }
        val chipValid = fresh(s.chipFix)
        val fix = tps ?: s.chipFix
        val fixValid = tps != null || chipValid
        val n = s.nmea
        val chipType = n.fixType?.takeIf { now - n.fixTypeAtMs <= NMEA_FRESH_MS && it != FixType.NO_FIX }
        val fixType = when {
            !fixValid -> null
            // TPS solves for height every time.
            tps != null -> FixType.THREE_D
            chipType != null -> chipType
            else -> FixQuality.inferFixType(true, fix?.altitudeM != null, s.sky.used)
        }
        val chipDop = n.dop?.takeIf { now - n.dopAtMs <= NMEA_FRESH_MS }
        val dop = if (!fixValid) null else chipDop ?: DopCalculator.compute(s.sky.satellites)
        return s.copy(
            nowMs = now,
            fix = fix,
            fixValid = fixValid,
            fixSource = if (tps != null) FixSource.TPS else FixSource.CHIP,
            chipFixValid = chipValid,
            usedSatellites = if (tps != null) s.raw.report?.fused?.satellites ?: s.sky.used else s.sky.used,
            fixType = fixType,
            fixTypeFromChip = fixValid && tps == null && chipType != null,
            dop = dop,
            dopFromChip = fixValid && chipDop != null,
            sbasCorrections = fixValid && n.ggaQuality == 2 && now - n.ggaAtMs <= NMEA_FRESH_MS,
            tier = FixQuality.tier(running, fixValid, fixType, fix?.accuracyM),
        )
    }

    // ---------------------------------------------------------------- real GNSS

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun startReal(id: Int) {
        val m = lm
        val blocked = when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) ->
                if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) Status.APPROXIMATE_ONLY
                else Status.NEEDS_PERMISSION
            m == null || LocationManager.GPS_PROVIDER !in m.allProviders -> Status.NO_GNSS
            else -> null
        }
        if (blocked != null || m == null) {
            update(id) { it.copy(status = blocked ?: Status.NO_GNSS) }
            return
        }
        val r = RealSession(id)
        try {
            // Registered even with location off: onProviderEnabled then starts the flow
            // the moment the user turns it on, without leaving TPS.
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, r.location, thread.looper)
            m.registerGnssStatusCallback(r.status, handler)
            m.addNmeaListener(r.nmea, handler)
        } catch (e: SecurityException) {
            runCatching { m.removeUpdates(r.location) }
            runCatching { m.unregisterGnssStatusCallback(r.status) }
            runCatching { m.removeNmeaListener(r.nmea) }
            update(id) { it.copy(status = Status.NEEDS_PERMISSION) }
            return
        }
        real = r
        handler.post {
            integrity.reset()
            integrity.startSession(lastTrusted.load())
        }
        val on = LocationManagerCompat.isLocationEnabled(m) && m.isProviderEnabled(LocationManager.GPS_PROVIDER)
        update(id) { it.copy(status = if (on) Status.RUNNING else Status.LOCATION_OFF) }
        // Raw data is optional: a phone without it still gets everything else.
        registerMeasurements(m, r)
        val navOk = runCatching { m.registerGnssNavigationMessageCallback(r.navigation, handler) }.getOrDefault(false)
        if (!navOk) update(id) { it.copy(raw = it.raw.copy(navigation = RawStatus.NOT_SUPPORTED)) }
    }

    @SuppressLint("MissingPermission")
    private fun registerMeasurements(m: LocationManager, r: RealSession) {
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                val request = GnssMeasurementRequest.Builder().setFullTracking(fullTracking).build()
                m.registerGnssMeasurementsCallback(request, { handler.post(it) }, r.measurements)
            } else {
                m.registerGnssMeasurementsCallback(r.measurements, handler)
            }
        }.getOrDefault(false)
        if (!ok) update(r.id) { it.copy(raw = it.raw.copy(measurements = RawStatus.NOT_SUPPORTED)) }
    }

    private fun status(code: Int) = when (code) {
        0 -> RawStatus.NOT_SUPPORTED
        1 -> RawStatus.READY
        2 -> RawStatus.LOCATION_DISABLED
        3 -> RawStatus.NOT_ALLOWED
        else -> RawStatus.UNKNOWN
    }

    private inner class RealSession(val id: Int) {
        /** Touched only on the GNSS thread. */
        private val tracker = NmeaTracker()
        private var lastNmeaPushMs = 0L

        val location = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                recorder.fix(location)
                val fix = toFix(location, tracker.state)
                update(id) { s ->
                    s.copy(chipFix = fix, ttff = s.ttff ?: Ttff(fix.elapsedRealtimeMs - s.startedAtMs, fromChip = false))
                }
            }

            // Implemented, not left to the interface defaults: before API 30 these
            // methods are abstract, and the platform calls them.
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

            override fun onProviderEnabled(provider: String) {
                update(id) { it.copy(status = Status.RUNNING) }
            }

            override fun onProviderDisabled(provider: String) {
                update(id) { it.copy(status = Status.LOCATION_OFF, sky = SkySnapshot.EMPTY, chipFix = null, tpsFix = null) }
            }
        }

        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                recorder.status(status)
                val signals = List(status.satelliteCount) { i ->
                    Signal(
                        constellation = Constellation.fromAndroid(status.getConstellationType(i)),
                        svid = status.getSvid(i),
                        cn0DbHz = status.getCn0DbHz(i),
                        elevationDeg = status.getElevationDegrees(i),
                        azimuthDeg = status.getAzimuthDegrees(i),
                        usedInFix = status.usedInFix(i),
                        hasEphemeris = status.hasEphemerisData(i),
                        hasAlmanac = status.hasAlmanacData(i),
                        carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                    )
                }
                val sky = SkySnapshot.of(signals)
                update(id) { it.copy(sky = sky) }
            }

            override fun onFirstFix(ttffMillis: Int) {
                update(id) { it.copy(ttff = Ttff(ttffMillis.toLong(), fromChip = true)) }
            }
        }

        val measurements = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                val epoch = toEpoch(event)
                recorder.raw(event, epoch)
                val s = _state.value
                // Own fixes are compared with, and checked against, the chip's fix: never TPS's own.
                // A mock location (another app's) is neither a start nor a reference.
                val chipFix = s.chipFix?.takeIf { s.chipFixValid && !it.mock }
                val chip = chipFix?.let { Geodetic(it.latitude, it.longitude, it.altitudeM ?: 0.0) }
                // Defensive: one odd measurement mustn't take down the GNSS thread.
                val report = runCatching { ownFix.solve(epoch, chip, s.sky, chipFix?.accuracyM?.toDouble()) }.getOrNull()
                val withPvt = epoch.measurements.mapNotNull { it.pvt }
                // The super position, as the fix TPS shows; dropped as soon as it isn't usable.
                val tps = if (report == null) s.tpsFix else report.fused?.takeIf { it.usable }?.let { fusedToFix(it, epoch.clock, s.chipFix) }
                if (report != null) tps?.let { recorder.tpsFix(it) }
                val checks = runCatching { integrity.update(integrityInput(epoch, report?.fused, s, chip)) }.getOrNull()
                report?.fused?.let { f -> if (checks?.level == Severity.OK) remember(f, chipFix) }
                update(id) {
                    it.copy(
                        tpsFix = tps,
                        integrity = checks ?: it.integrity,
                        raw = it.raw.copy(
                            measurements = RawStatus.READY,
                            epochs = it.raw.epochs + 1,
                            signals = epoch.measurements.size,
                            fullTracking = epoch.fullTracking,
                            agc = epoch.agc,
                            agcFromSignals = epoch.agcFromSignals,
                            report = report ?: it.raw.report,
                            pvtSignals = withPvt.size,
                            pvtUnreadable = epoch.pvtUnreadable,
                            pvtWhy = epoch.pvtWhy ?: it.raw.pvtWhy,
                            pvtSeen = it.raw.pvtSeen || withPvt.isNotEmpty(),
                            pvtSources = withPvt.groupingBy { p -> p.ephemerisSource ?: -1 }.eachCount(),
                        )
                    )
                }
                if (recorder.recording) publishRecorder()
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(status: Int) {
                val st = status(status)
                update(id) { it.copy(raw = it.raw.copy(measurements = if (it.raw.epochs > 0) RawStatus.READY else st)) }
            }
        }

        private var navSinceUpdate = 0
        private var lastNavPushMs = 0L

        val navigation = object : GnssNavigationMessage.Callback() {
            override fun onGnssNavigationMessageReceived(message: GnssNavigationMessage) {
                recorder.nav(message)
                runCatching {
                    ownFix.accept(
                        NavMessage(message.type, message.svid, message.messageId, message.submessageId, message.status, message.data)
                    )
                }
                navSinceUpdate++
                val now = SystemClock.elapsedRealtime()
                if (now - lastNavPushMs >= 1_000) {
                    val n = navSinceUpdate
                    navSinceUpdate = 0
                    lastNavPushMs = now
                    update(id) { it.copy(raw = it.raw.copy(navigation = RawStatus.READY, navMessages = it.raw.navMessages + n)) }
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(status: Int) {
                val st = status(status)
                update(id) { it.copy(raw = it.raw.copy(navigation = if (it.raw.navMessages > 0) RawStatus.READY else st)) }
            }
        }

        /** The last NMEA values published, without timestamps. */
        private var lastPushed: NmeaTracker.State? = null

        val nmea = OnNmeaMessageListener { message, _ ->
            val now = SystemClock.elapsedRealtime()
            if (!tracker.accept(message, now)) return@OnNmeaMessageListener
            // Chips send several sentences a second. Publish when a value changes, and
            // otherwise refresh the timestamps at most once a second.
            val values = tracker.state.copyWithoutTimes()
            if (values != lastPushed || now - lastNmeaPushMs >= 1_000) {
                lastPushed = values
                lastNmeaPushMs = now
                val snapshot = tracker.state
                update(id) { it.copy(nmea = snapshot) }
            }
        }
    }

    private fun NmeaTracker.State.copyWithoutTimes() = copy(fixTypeAtMs = 0, dopAtMs = 0, ggaAtMs = 0)

    private fun toEpoch(e: GnssMeasurementsEvent): RawEpoch {
        val c = e.clock
        val clock = RawClock(
            timeNanos = c.timeNanos,
            fullBiasNanos = if (c.hasFullBiasNanos()) c.fullBiasNanos else null,
            biasNanos = if (c.hasBiasNanos()) c.biasNanos else 0.0,
            biasUncertaintyNanos = if (c.hasBiasUncertaintyNanos()) c.biasUncertaintyNanos else null,
            driftNanosPerSecond = if (c.hasDriftNanosPerSecond()) c.driftNanosPerSecond else null,
            driftUncertaintyNanosPerSecond = if (c.hasDriftUncertaintyNanosPerSecond()) c.driftUncertaintyNanosPerSecond else null,
            timeUncertaintyNanos = if (c.hasTimeUncertaintyNanos()) c.timeUncertaintyNanos else null,
            discontinuityCount = c.hardwareClockDiscontinuityCount,
            leapSecond = if (c.hasLeapSecond()) c.leapSecond else null,
            elapsedRealtimeNanos = if (Build.VERSION.SDK_INT >= 29 && c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else null,
        )
        var unreadable = 0
        var why: String? = null
        val measurements = e.measurements.map { m ->
            // The chip's own satellite position, when it sends one (Android 12+).
            val pvt: ChipPvt? = if (Build.VERSION.SDK_INT >= 31) {
                when (val read = ChipPvtReader.read(m)) {
                    is PvtRead.Found -> read.pvt
                    is PvtRead.Unreadable -> {
                        unreadable++
                        if (why == null) why = read.why
                        null
                    }
                    PvtRead.Absent -> null
                }
            } else {
                null
            }
            RawMeasurement(
                constellation = Constellation.fromAndroid(m.constellationType),
                svid = m.svid,
                timeOffsetNanos = m.timeOffsetNanos,
                state = m.state,
                receivedSvTimeNanos = m.receivedSvTimeNanos,
                receivedSvTimeUncertaintyNanos = m.receivedSvTimeUncertaintyNanos,
                cn0DbHz = m.cn0DbHz,
                pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
                pseudorangeRateUncertaintyMps = m.pseudorangeRateUncertaintyMetersPerSecond,
                adrState = m.accumulatedDeltaRangeState,
                adrMeters = m.accumulatedDeltaRangeMeters,
                adrUncertaintyMeters = m.accumulatedDeltaRangeUncertaintyMeters,
                carrierHz = if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null,
                multipath = m.multipathIndicator,
                snrDb = if (m.hasSnrInDb()) m.snrInDb else null,
                basebandCn0DbHz = if (Build.VERSION.SDK_INT >= 30 && m.hasBasebandCn0DbHz()) m.basebandCn0DbHz else null,
                codeType = if (Build.VERSION.SDK_INT >= 29 && m.hasCodeType()) m.codeType else null,
                fullInterSignalBiasNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasFullInterSignalBiasNanos()) m.fullInterSignalBiasNanos else null,
                satelliteInterSignalBiasNanos = if (Build.VERSION.SDK_INT >= 30 && m.hasSatelliteInterSignalBiasNanos()) m.satelliteInterSignalBiasNanos else null,
                pvt = pvt,
            )
        }
        val listed = if (Build.VERSION.SDK_INT >= 33) {
            e.gnssAutomaticGainControls.map { Agc(Constellation.fromAndroid(it.constellationType), it.carrierFrequencyHz, it.levelDb) }
        } else {
            emptyList()
        }
        // Android names the deprecated per-measurement gain as the fallback when the list is empty.
        val perSignal = if (listed.isEmpty()) {
            AgcBands.fromSignals(
                e.measurements.mapNotNull { m ->
                    @Suppress("DEPRECATION")
                    if (m.hasAutomaticGainControlLevelDb()) {
                        Triple(Constellation.fromAndroid(m.constellationType), if (m.hasCarrierFrequencyHz()) m.carrierFrequencyHz.toDouble() else null, m.automaticGainControlLevelDb)
                    } else {
                        null
                    }
                }
            )
        } else {
            emptyList()
        }
        val full = if (Build.VERSION.SDK_INT >= 34 && e.hasIsFullTracking()) e.isFullTracking else null
        return RawEpoch(
            clock, measurements, listed.ifEmpty { perSignal }, agcFromSignals = perSignal.isNotEmpty(),
            fullTracking = full, pvtUnreadable = unreadable, pvtWhy = why,
        )
    }

    /** Runs on the GNSS thread, where AltitudeConverter's blocking first load is fine. */
    private fun toFix(l: Location, nmea: NmeaTracker.State): Fix {
        var msl: Double? = null
        var source: MslSource? = null
        if (Build.VERSION.SDK_INT >= 34) {
            Api34.msl(app, l)?.let { msl = it.first; source = it.second }
        }
        if (msl == null && l.hasAltitude()) {
            val n = nmea.geoidSeparationM?.takeIf { SystemClock.elapsedRealtime() - nmea.ggaAtMs <= 10_000 }
            if (n != null) {
                msl = l.altitude - n
                source = MslSource.NMEA
            }
        }
        val wallAtFix = System.currentTimeMillis() -
            (SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        return Fix(
            latitude = l.latitude,
            longitude = l.longitude,
            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            altitudeM = if (l.hasAltitude()) l.altitude else null,
            mslAltitudeM = msl,
            mslSource = source,
            verticalAccuracyM = if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else null,
            speedMps = if (l.hasSpeed()) l.speed else null,
            speedAccuracyMps = if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else null,
            bearingDeg = if (l.hasBearing()) l.bearing else null,
            bearingAccuracyDeg = if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees else null,
            gnssTimeMs = l.time,
            elapsedRealtimeMs = l.elapsedRealtimeNanos / 1_000_000,
            clockOffsetMs = if (l.time > 0) wallAtFix - l.time else null,
            mock = if (Build.VERSION.SDK_INT >= 31) l.isMock else @Suppress("DEPRECATION") l.isFromMockProvider,
        )
    }

    /** UTC from the chip's GNSS clock at the measurement, ms; null before it knows GPS time. */
    private fun gnssUtcMs(clock: RawClock): Long? {
        val leap = clock.leapSecond ?: org.soulstone.tps.raw.Pseudoranges.DEFAULT_LEAP_SECONDS
        return clock.fullBiasNanos?.let { (clock.timeNanos - it - Math.round(clock.biasNanos)) / 1_000_000 + GPS_EPOCH_UNIX_MS - leap * 1_000L }
    }

    /**
     * Keeps TPS's position, when all checks pass, for the next start-up to compare with: every
     * 15 s, with the chip's GNSS time (the time the checks use) and the phone's clocks. GNSS thread.
     */
    private fun remember(f: FusedFix, chipFix: Fix?) {
        if (!f.usable || f.holding) return
        val fix = chipFix?.takeIf { it.gnssTimeMs > 0 } ?: return
        val now = SystemClock.elapsedRealtime()
        if (rememberedAtMs?.let { now - it < REMEMBER_EVERY_MS } == true) return
        rememberedAtMs = now
        lastTrusted.save(
            Remembered(
                position = f.position,
                gnssTimeMs = fix.gnssTimeMs,
                elapsedMs = fix.elapsedRealtimeMs,
                wallMs = System.currentTimeMillis() - (now - fix.elapsedRealtimeMs),
                bootCount = bootCount,
            )
        )
    }

    /** Everything the integrity checks look at this round. */
    private fun integrityInput(epoch: RawEpoch, fused: FusedFix?, s: State, chip: Geodetic?): IntegrityInput {
        val elapsedNs = epoch.clock.elapsedRealtimeNanos ?: SystemClock.elapsedRealtimeNanos()
        // Time comes from the chip's fix (any phone has one; it's the time TPS shows), not the
        // raw clock: the emulator's raw clock, for one, runs years off. Not from a mock fix,
        // whose time is whatever the other app says.
        val fix = s.chipFix?.takeIf { s.chipFixValid && !it.mock }
        val gnssMs = fix?.gnssTimeMs?.takeIf { it > 0 }
        val ageMs = fix?.let { SystemClock.elapsedRealtime() - it.elapsedRealtimeMs } ?: 0L
        // Android's network (NTP) time, when it has one (Android 13+): no network use of TPS's own.
        val network = if (Build.VERSION.SDK_INT >= 33 && gnssMs != null) {
            runCatching { gnssMs - (SystemClock.currentNetworkTimeClock().millis() - ageMs) }.getOrNull()
        } else {
            null
        }
        return IntegrityInput(
            elapsedS = elapsedNs * 1e-9,
            signals = s.sky.satellites.filter { it.cn0DbHz > 0 }.map {
                SignalLevel(it.constellation, it.svid, it.cn0DbHz.toDouble(), it.elevationDeg.toDouble().takeIf { _ -> it.positionKnown })
            },
            agc = epoch.agc,
            fused = fused,
            chip = chip,
            chipAccuracyM = s.chipFix?.accuracyM?.toDouble(),
            gnssMinusElapsedMs = gnssMs?.let { it - fix.elapsedRealtimeMs },
            gnssMinusNetworkMs = network,
            gnssMinusWallMs = gnssMs?.let { it - (System.currentTimeMillis() - ageMs) },
            chipMock = s.chipFix?.mock == true,
            gnssTimeMs = gnssMs,
            elapsedMs = fix?.elapsedRealtimeMs,
            wallMs = fix?.let { System.currentTimeMillis() - ageMs },
            bootCount = bootCount,
        )
    }

    /** TPS's super position as a [Fix], for the screen. */
    private fun fusedToFix(f: FusedFix, clock: RawClock, chip: Fix?): Fix {
        val gnssMs = gnssUtcMs(clock)
        val elapsedNs = clock.elapsedRealtimeNanos ?: SystemClock.elapsedRealtimeNanos()
        val wallAtFix = System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - elapsedNs) / 1_000_000
        // Height above sea level: the geoid's height under the chip's fix is the same a few metres away.
        val undulation = chip?.let { c -> c.altitudeM?.let { h -> c.mslAltitudeM?.let { h - it } } }
        return Fix(
            latitude = f.position.latitude,
            longitude = f.position.longitude,
            accuracyM = f.horizontalAccuracyM.toFloat(),
            altitudeM = f.position.height,
            mslAltitudeM = undulation?.let { f.position.height - it },
            mslSource = if (undulation != null) chip.mslSource else null,
            verticalAccuracyM = f.verticalAccuracyM.toFloat(),
            speedMps = f.speedMps.toFloat(),
            bearingDeg = f.bearingDeg?.toFloat(),
            gnssTimeMs = gnssMs ?: chip?.gnssTimeMs ?: wallAtFix,
            elapsedRealtimeMs = elapsedNs / 1_000_000,
            clockOffsetMs = gnssMs?.let { wallAtFix - it } ?: chip?.clockOffsetMs,
        )
    }

    @RequiresApi(34)
    private object Api34 {
        private var converter: AltitudeConverter? = null

        /** The platform's MSL height, or AltitudeConverter's from the bundled geoid model. */
        fun msl(context: Context, l: Location): Pair<Double, MslSource>? {
            if (l.hasMslAltitude()) return l.mslAltitudeMeters to MslSource.PLATFORM
            if (!l.hasAltitude()) return null
            return try {
                val c = converter ?: AltitudeConverter().also { converter = it }
                c.addMslAltitudeToLocation(context, l)
                if (l.hasMslAltitude()) l.mslAltitudeMeters to MslSource.CONVERTER else null
            } catch (e: Exception) {
                null
            }
        }
    }

    // ---------------------------------------------------------------- demo sky

    private fun startDemo(id: Int) {
        val sky = DemoSky()
        val tracker = NmeaTracker()
        update(id) { it.copy(status = Status.RUNNING) }
        demoJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val frame = sky.frame(now - start, System.currentTimeMillis(), now)
                frame.nmea.forEach { tracker.accept(it, now) }
                val snapshot = SkySnapshot.of(frame.signals)
                val nmea = tracker.state
                update(id) { s ->
                    s.copy(
                        sky = snapshot,
                        chipFix = frame.fix ?: s.chipFix,
                        nmea = nmea,
                        ttff = frame.ttffMs?.let { Ttff(it, fromChip = true) } ?: s.ttff,
                    )
                }
                delay(1_000)
            }
        }
    }

    companion object {
        /** NMEA values older than this are ignored (the chip stopped sending them). */
        private const val NMEA_FRESH_MS = 3_000L

        /** 1980-01-06, the GPS epoch, in Unix milliseconds. */
        private const val GPS_EPOCH_UNIX_MS = 315_964_800_000L

        private const val REMEMBER_EVERY_MS = 15_000L

        @Volatile private var INSTANCE: GnssEngine? = null

        fun get(context: Context): GnssEngine = INSTANCE ?: synchronized(this) {
            INSTANCE ?: GnssEngine(context.applicationContext).also { INSTANCE = it }
        }
    }
}
