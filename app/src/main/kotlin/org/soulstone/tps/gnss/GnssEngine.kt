package org.soulstone.tps.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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

/**
 * Listens to the phone's GNSS chip, or to [DemoSky], and publishes one [State].
 *
 * Android only runs the GNSS engine while someone requests GPS_PROVIDER locations, so
 * TPS holds that request (straight from LocationManager, never the fused provider,
 * which hides which satellites made a fix). Satellite status and NMEA arrive alongside
 * it. Everything runs only while TPS is on screen: MainActivity calls [onForeground]
 * and [onBackground], and START/STOP call [start] and [stop].
 *
 * Callbacks arrive on a private thread. Each activation is a new session, and anything
 * still queued from an older one is dropped.
 */
class GnssEngine private constructor(context: Context) {

    enum class Status { IDLE, RUNNING, NEEDS_PERMISSION, APPROXIMATE_ONLY, LOCATION_OFF, NO_GNSS }

    /** Time to first fix: from the chip's `onFirstFix`, or TPS's own START-to-first-fix. */
    data class Ttff(val millis: Long, val fromChip: Boolean)

    data class State(
        /** START was pressed (the button shows STOP). */
        val wanted: Boolean = false,
        val demo: Boolean = false,
        val status: Status = Status.IDLE,
        val startedAtMs: Long = 0,
        val nowMs: Long = 0,
        val sky: SkySnapshot = SkySnapshot.EMPTY,
        val fix: Fix? = null,
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

    @Volatile private var session = 0
    private var active = false
    private var real: RealSession? = null
    private var tickJob: Job? = null
    private var demoJob: Job? = null

    /** START: begin listening (to the chip, or to demo sky). Main thread. */
    fun start(demo: Boolean) {
        deactivate()
        _state.value = State(wanted = true, demo = demo)
        activate()
    }

    /** STOP. Main thread. */
    fun stop() {
        deactivate()
        _state.value = State()
    }

    /** TPS came on screen: resume if START is on. Also re-checks permission and location. */
    fun onForeground() {
        val s = _state.value
        if (s.wanted && (!active || s.status != Status.RUNNING && s.status != Status.LOCATION_OFF)) {
            deactivate()
            activate()
        }
    }

    /** TPS left the screen: stop listening, keep START on. */
    fun onBackground() {
        if (!active) return
        deactivate()
        _state.update { State(wanted = it.wanted, demo = it.demo) }
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
        val fix = s.fix
        val fixValid = running && fix != null && now - fix.elapsedRealtimeMs <= FixQuality.STALE_MS
        val n = s.nmea
        val chipType = n.fixType?.takeIf { now - n.fixTypeAtMs <= NMEA_FRESH_MS && it != FixType.NO_FIX }
        val fixType = when {
            !fixValid -> null
            chipType != null -> chipType
            else -> FixQuality.inferFixType(true, fix.altitudeM != null, s.sky.used)
        }
        val chipDop = n.dop?.takeIf { now - n.dopAtMs <= NMEA_FRESH_MS }
        val dop = if (!fixValid) null else chipDop ?: DopCalculator.compute(s.sky.satellites)
        return s.copy(
            nowMs = now,
            fixValid = fixValid,
            fixType = fixType,
            fixTypeFromChip = fixValid && chipType != null,
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
        val on = LocationManagerCompat.isLocationEnabled(m) && m.isProviderEnabled(LocationManager.GPS_PROVIDER)
        update(id) { it.copy(status = if (on) Status.RUNNING else Status.LOCATION_OFF) }
    }

    private inner class RealSession(private val id: Int) {
        /** Touched only on the GNSS thread. */
        private val tracker = NmeaTracker()
        private var lastNmeaPushMs = 0L

        val location = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val fix = toFix(location, tracker.state)
                update(id) { s ->
                    s.copy(fix = fix, ttff = s.ttff ?: Ttff(fix.elapsedRealtimeMs - s.startedAtMs, fromChip = false))
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
                update(id) { it.copy(status = Status.LOCATION_OFF, sky = SkySnapshot.EMPTY, fix = null) }
            }
        }

        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
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
                        fix = frame.fix ?: s.fix,
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

        @Volatile private var INSTANCE: GnssEngine? = null

        fun get(context: Context): GnssEngine = INSTANCE ?: synchronized(this) {
            INSTANCE ?: GnssEngine(context.applicationContext).also { INSTANCE = it }
        }
    }
}
