package org.soulstone.tps.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.soulstone.tps.R
import org.soulstone.tps.data.Settings
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.Fix
import org.soulstone.tps.gnss.FixType
import org.soulstone.tps.gnss.FrequencyMode
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.gnss.MarkShape
import org.soulstone.tps.nav.Geodesy
import org.soulstone.tps.nav.Geohash
import org.soulstone.tps.nav.Waypoint
import org.soulstone.tps.ui.theme.FixColors
import org.soulstone.tps.ui.theme.color
import org.soulstone.tps.ui.theme.KibernetykMono
import org.soulstone.tps.ui.theme.MONO_ADVANCE_EM

/** The display preferences the main screen needs, as one snapshot. */
data class DisplayPrefs(
    val units: Settings.Units,
    val coordinates: Settings.CoordinateFormat,
    val shapes: Boolean,
    val labels: Boolean,
    /** 0 hides the geohash. */
    val geohashPrecision: Int,
)

/** Heading-up mode, for the main screen. [heading] is read only while drawing the circle. */
data class CompassView(val heading: State<Float?>, val accuracyDeg: Float?, val available: Boolean)

/** A line at the bottom of the screen, with an optional button (allow location, open settings). */
data class Notice(val text: String, val action: String? = null, val onAction: () -> Unit = {})

/**
 * The one screen: the sky circle with its fix ring, then the position, the fix, the
 * time from the satellites and, when navigating, the way to the target. It starts
 * listening as soon as TPS is on screen; there is no START button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: GnssEngine.State,
    prefs: DisplayPrefs,
    waypoints: List<Waypoint>,
    target: Waypoint?,
    compass: CompassView?,
    raw: RawUi?,
    notice: Notice?,
    onOpenWaypoints: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val fix = state.fix
    val markers = remember(fix?.latitude, fix?.longitude, fix?.accuracyM, waypoints, target) {
        if (fix == null) {
            emptyList()
        } else {
            // A waypoint you're standing on has no direction, so it gets no marker.
            waypoints.filter { !arrived(fix, it) }.map {
                RingMarker(
                    Geodesy.bearingDeg(fix.latitude, fix.longitude, it.latitude, it.longitude).toFloat(),
                    target = it.id == target?.id
                )
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Background first, then insets: the colour runs under the bars and the
            // cutout, and only the content is held clear.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp)
    ) {
        Header(onOpenWaypoints = onOpenWaypoints, onOpenSettings = onOpenSettings)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // The circle gives way first on a short screen; the readouts below it don't,
            // and navigating adds one.
            val reserve = if (target != null) 420.dp else 330.dp
            val circle = minOf(360.dp, maxWidth, maxHeight - reserve).coerceAtLeast(220.dp)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.size(circle)) {
                    SkyCircle(
                        state = state,
                        shapes = prefs.shapes,
                        labels = prefs.labels,
                        heading = compass?.heading,
                        markers = markers,
                        onTap = { showSheet = true },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (state.wanted && state.demo) DemoBadge(Modifier.align(Alignment.TopStart))
                }
                Spacer(Modifier.height(6.dp))
                Legend(state, prefs.shapes)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hint(compass),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = KibernetykMono,
                    textAlign = TextAlign.Center
                )
                if (target != null) {
                    // Right under the circle: while navigating, it's what you look for.
                    Spacer(Modifier.height(14.dp))
                    TargetReadout(target, fix, state.fixValid, prefs.units, onClick = onOpenWaypoints)
                }
                Spacer(Modifier.height(16.dp))
                PositionReadout(state, prefs)
                Spacer(Modifier.height(14.dp))
                FixSummary(state)
                if (fix != null) {
                    Spacer(Modifier.height(14.dp))
                    SatelliteClock(fix)
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (notice != null) {
            NoticeArea(notice)
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            ConstellationsSheet(state, prefs, raw)
        }
    }
}

private fun hint(compass: CompassView?): String = when {
    compass == null -> "tap circle for constellation details"
    !compass.available -> "no compass sensor: north stays up"
    compass.accuracyDeg != null && compass.accuracyDeg > 30f ->
        "compass ±${compass.accuracyDeg.roundToInt()}°: wave the phone in a figure 8"
    compass.accuracyDeg != null -> "heading up · compass ±${compass.accuracyDeg.roundToInt()}° · tap for details"
    else -> "heading up · tap circle for details"
}

@Composable
private fun Header(onOpenWaypoints: () -> Unit, onOpenSettings: () -> Unit) {
    // A Box (not a Row) so the title is truly centred whatever sits beside it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        IconButton(
            onClick = onOpenWaypoints,
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_star),
                contentDescription = "Waypoints",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "TPS",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = KibernetykMono,
            letterSpacing = 4.sp,
            modifier = Modifier.align(Alignment.Center)
        )
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_settings),
                contentDescription = "Settings",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DemoBadge(modifier: Modifier) {
    Text(
        text = "DEMO",
        color = FixColors.Orange,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = KibernetykMono,
        modifier = modifier
            .border(1.dp, FixColors.Orange, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

/** The constellations in view right now; all seven, dimmed, before any are. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(state: GnssEngine.State, shapes: Boolean) {
    val inView = state.sky.constellations.filter { it.inView > 0 }.map { it.constellation }
    val shown = if (state.running && inView.isNotEmpty()) inView else Constellation.KNOWN
    val dim = !(state.running && inView.isNotEmpty())
    FlowRow(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
    ) {
        for (c in shown) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                val color = if (dim) c.color.copy(alpha = 0.5f) else c.color
                MarkSwatch(if (shapes) c.shape else MarkShape.CIRCLE, color, 9.dp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = c.displayName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (dim) 0.6f else 1f),
                    fontSize = 10.sp,
                    fontFamily = KibernetykMono
                )
            }
        }
    }
}

/** A small grey label on the left and a value on the right, above a readout. */
@Composable
private fun ReadoutHeader(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = KibernetykMono)
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 11.sp, fontFamily = KibernetykMono)
    }
}

@Composable
private fun PositionReadout(state: GnssEngine.State, prefs: DisplayPrefs) {
    val context = LocalContext.current
    val fix = state.fix
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val accuracy = fix?.accuracyM?.takeIf { state.fixValid }
        ReadoutHeader("position", accuracy?.let { "±" + Format.distance(it.toDouble(), prefs.units) } ?: "")
        Spacer(Modifier.height(6.dp))
        if (fix == null) {
            Text(
                text = if (state.running) "no fix yet" else "—",
                color = muted,
                fontSize = 18.sp,
                fontFamily = KibernetykMono
            )
            return@Column
        }
        val textColor = if (state.fixValid) MaterialTheme.colorScheme.onBackground else muted
        val coordinates = when (prefs.coordinates) {
            Settings.CoordinateFormat.DECIMAL -> Format.latLonDecimal(fix.latitude, fix.longitude)
            Settings.CoordinateFormat.DMS -> Format.latLonDms(fix.latitude, fix.longitude)
        }
        FitText(
            text = coordinates,
            maxSize = 20.sp,
            color = textColor,
            modifier = Modifier.clickable { copy(context, "position", coordinates) }
        )
        if (prefs.geohashPrecision > 0) {
            val hash = Geohash.encode(fix.latitude, fix.longitude, prefs.geohashPrecision)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "geohash $hash",
                color = textColor,
                fontSize = 15.sp,
                fontFamily = KibernetykMono,
                modifier = Modifier.clickable { copy(context, "geohash", hash) }
            )
        }
        Spacer(Modifier.height(6.dp))
        val parts = if (state.fixValid) {
            listOfNotNull(
                heightText(fix.mslAltitudeM, fix.altitudeM, prefs.units),
                fix.speedMps?.let { Format.speed(it.toDouble(), prefs.units) },
                "WGS 84"
            )
        } else {
            listOf("last fix ${Format.duration(state.fixAgeMs ?: 0)} ago")
        }
        Text(
            text = parts.joinToString("  •  "),
            color = muted,
            fontSize = 12.sp,
            fontFamily = KibernetykMono,
            textAlign = TextAlign.Center
        )
        if (fix.mock) {
            Spacer(Modifier.height(4.dp))
            Text(
                "mock location: another app is supplying this position",
                color = FixColors.Orange,
                fontSize = 11.sp,
                fontFamily = KibernetykMono,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun heightText(msl: Double?, ellipsoid: Double?, units: Settings.Units): String? = when {
    msl != null -> "alt ${Format.height(msl, units)} MSL"
    ellipsoid != null -> "alt ${Format.height(ellipsoid, units)} ellipsoid"
    else -> null
}

private fun copy(context: Context, what: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(what, text))
    // Android 13+ shows its own confirmation for clipboard writes.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied $what", Toast.LENGTH_SHORT).show()
}

@Composable
private fun FixSummary(state: GnssEngine.State) {
    if (!state.running) return
    val sky = state.sky
    val headline = if (state.fixValid) {
        "${fixTypeText(state.fixType)} fix  •  ${sky.used} of ${sky.inView} sats"
    } else {
        "searching  •  ${sky.inView} sats in view"
    }
    Text(
        text = headline,
        color = MaterialTheme.colorScheme.onBackground,
        fontSize = 16.sp,
        fontFamily = KibernetykMono
    )
    Spacer(Modifier.height(4.dp))
    val details = buildList {
        if (!state.fixValid) add("listening ${Format.duration(state.listeningMs)}")
        state.ttff?.let { add((if (it.fromChip) "TTFF " else "first fix ") + Format.duration(it.millis)) }
        state.dop?.let { add("HDOP ${Format.dop(it.hdop)}") }
        when (sky.frequencyMode) {
            FrequencyMode.DUAL_IN_USE -> add("dual-frequency")
            FrequencyMode.DUAL_TRACKED -> add("dual-frequency tracked")
            FrequencyMode.SINGLE -> add("single-frequency")
            FrequencyMode.UNKNOWN -> Unit
        }
    }
    if (details.isNotEmpty()) {
        Text(
            text = details.joinToString(" • "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

internal fun fixTypeText(type: FixType?): String = when (type) {
    FixType.TWO_D -> "2D"
    FixType.THREE_D -> "3D"
    FixType.NO_FIX, null -> "no"
}

/**
 * The time from the satellites: each fix carries UTC from GNSS, and between fixes the
 * phone's monotonic clock carries it forward, so it stays right even after the fix is
 * lost. The phone's own clock is shown against it. Ticks on its own, once a second, so
 * only this readout recomposes.
 */
@Composable
private fun SatelliteClock(fix: Fix) {
    val context = LocalContext.current
    val is24Hour = remember { DateFormat.is24HourFormat(context) }
    val zone = remember { ZoneId.systemDefault() }
    val latest by rememberUpdatedState(fix)
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            val f = latest
            val gnss = f.gnssTimeMs + (SystemClock.elapsedRealtime() - f.elapsedRealtimeMs)
            // Wake just after the next whole second of satellite time.
            delay(1_000 - Math.floorMod(gnss, 1_000L) + 5)
            nowElapsed = SystemClock.elapsedRealtime()
        }
    }
    val gnssNow = fix.gnssTimeMs + (nowElapsed - fix.elapsedRealtimeMs)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ReadoutHeader("satellite time", fix.clockOffsetMs?.let { "phone " + Format.signedSeconds(it) } ?: "")
        Spacer(Modifier.height(4.dp))
        Text(
            text = Format.localClock(gnssNow, zone, is24Hour),
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 18.sp,
            fontFamily = KibernetykMono
        )
        Text(
            text = Format.utcTime(gnssNow) + "  •  " + Format.date(gnssNow, zone),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = KibernetykMono
        )
    }
}

/** Where the target is from here. Tapping it opens the waypoint list. */
@Composable
private fun TargetReadout(target: Waypoint, fix: Fix?, fixValid: Boolean, units: Settings.Units, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ReadoutHeader("navigating to", "")
        Spacer(Modifier.height(4.dp))
        Text(
            text = "▲ " + target.label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = KibernetykMono,
            textAlign = TextAlign.Center
        )
        val line = if (fix == null) {
            "waiting for a fix"
        } else {
            val d = Geodesy.distanceMeters(fix.latitude, fix.longitude, target.latitude, target.longitude)
            val b = Geodesy.bearingDeg(fix.latitude, fix.longitude, target.latitude, target.longitude)
            if (arrived(fix, target)) {
                "you're there (${Format.longDistance(d, units)})"
            } else {
                Format.longDistance(d, units) + "  •  " + Format.bearing(b) + " " + Geodesy.compassPoint(b) +
                    if (fixValid) "" else "  •  from last fix"
            }
        }
        Text(
            text = line,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontFamily = KibernetykMono,
            textAlign = TextAlign.Center
        )
    }
}

/** "There": within the fix's own uncertainty, and never closer than 8 m. */
internal fun arrived(fix: Fix, w: Waypoint): Boolean =
    Geodesy.distanceMeters(fix.latitude, fix.longitude, w.latitude, w.longitude) <=
        max(8.0, (fix.accuracyM ?: 0f).toDouble())

@Composable
private fun NoticeArea(notice: Notice) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = notice.text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        if (notice.action != null) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = notice.onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = FixColors.Green,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(notice.action, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = KibernetykMono)
            }
        }
    }
}

/** Single-line monospace text that shrinks to fit its width (long DMS coordinates). */
@Composable
private fun FitText(text: String, maxSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // Every Kibernetyk Mono glyph is the same width; a little slack for rounding.
        val fitting = with(LocalDensity.current) { (maxWidth / (text.length * (MONO_ADVANCE_EM + 0.02f))).toSp() }
        val size = if (fitting.value < maxSize.value) fitting else maxSize
        Text(
            text = text,
            color = color,
            fontSize = size,
            fontFamily = KibernetykMono,
            maxLines = 1,
            softWrap = false
        )
    }
}
