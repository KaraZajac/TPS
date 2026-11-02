package org.soulstone.tps.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.soulstone.tps.R
import org.soulstone.tps.data.Settings
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.ConstellationSummary
import org.soulstone.tps.gnss.FixType
import org.soulstone.tps.gnss.FrequencyMode
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.gnss.MarkShape
import org.soulstone.tps.gnss.MslSource
import org.soulstone.tps.gnss.SatState
import org.soulstone.tps.gnss.Satellite
import org.soulstone.tps.ui.theme.FixColors
import org.soulstone.tps.ui.theme.color
import org.soulstone.tps.ui.theme.ringColor

/** The display preferences the main screen needs, as one snapshot. */
data class DisplayPrefs(
    val units: Settings.Units,
    val coordinates: Settings.CoordinateFormat,
    val shapes: Boolean,
    val labels: Boolean,
)

/** A line under START/STOP, with an optional button (open settings, turn on location). */
data class Notice(val text: String, val action: String? = null, val onAction: () -> Unit = {})

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    state: GnssEngine.State,
    prefs: DisplayPrefs,
    notice: Notice?,
    onStartStop: () -> Unit,
    onOpenSettings: () -> Unit
) {
    var showSheet by rememberSaveable { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Background first, then insets: the colour runs under the bars and the
            // cutout, and only the content is held clear.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp)
    ) {
        Header(demo = state.wanted && state.demo, onOpenSettings = onOpenSettings)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // The circle gives way first on a short screen; the readouts below it don't.
            val circle = minOf(300.dp, maxWidth, maxHeight - 250.dp).coerceAtLeast(180.dp)
            val topGap = if (maxHeight > 600.dp) 28.dp else 12.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(topGap))
                SkyCircle(
                    state = state,
                    shapes = prefs.shapes,
                    labels = prefs.labels,
                    onTap = { showSheet = true },
                    modifier = Modifier.size(circle)
                )
                Spacer(Modifier.height(10.dp))
                Legend(state, prefs.shapes)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "tap circle for constellation details",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(18.dp))
                PositionReadout(state, prefs)
                Spacer(Modifier.height(16.dp))
                FixSummary(state)
                Spacer(Modifier.height(12.dp))
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onStartStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.wanted) FixColors.Red else FixColors.Green,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = if (state.wanted) "STOP" else "START",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (notice != null) {
                Spacer(Modifier.height(12.dp))
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
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(notice.action, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            ConstellationsSheet(state, prefs)
        }
    }
}

@Composable
private fun Header(demo: Boolean, onOpenSettings: () -> Unit) {
    // A Box (not a Row) so the title is truly centred whatever sits beside it.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        if (demo) {
            Text(
                text = "DEMO",
                color = FixColors.Orange,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .border(1.dp, FixColors.Orange, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = "TPS",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
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
                    fontFamily = FontFamily.Monospace
                )
            }
        }
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
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("position", color = muted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            val accuracy = fix?.accuracyM?.takeIf { state.fixValid }
            Text(
                text = accuracy?.let { "±" + Format.distance(it.toDouble(), prefs.units) } ?: "",
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(6.dp))
        when {
            !state.wanted -> Text(
                text = "Idle — press START to listen for satellites",
                color = muted,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
            fix == null -> Text(
                text = if (state.running) "no fix yet" else "—",
                color = muted,
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace
            )
            else -> {
                val coordinates = when (prefs.coordinates) {
                    Settings.CoordinateFormat.DECIMAL -> Format.latLonDecimal(fix.latitude, fix.longitude)
                    Settings.CoordinateFormat.DMS -> Format.latLonDms(fix.latitude, fix.longitude)
                }
                FitText(
                    text = coordinates,
                    maxSize = 20.sp,
                    color = if (state.fixValid) MaterialTheme.colorScheme.onBackground else muted,
                    modifier = Modifier.clickable { copyPosition(context, coordinates) }
                )
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
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center
                )
                if (fix.mock) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "mock location: another app is supplying this position",
                        color = FixColors.Orange,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

private fun heightText(msl: Double?, ellipsoid: Double?, units: Settings.Units): String? = when {
    msl != null -> "alt ${Format.height(msl, units)} MSL"
    ellipsoid != null -> "alt ${Format.height(ellipsoid, units)} ellipsoid"
    else -> null
}

private fun copyPosition(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("position", text))
    // Android 13+ shows its own confirmation for clipboard writes.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Position copied", Toast.LENGTH_SHORT).show()
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
        fontFamily = FontFamily.Monospace
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

private fun fixTypeText(type: FixType?): String = when (type) {
    FixType.TWO_D -> "2D"
    FixType.THREE_D -> "3D"
    FixType.NO_FIX, null -> "no"
}

/** Single-line monospace text that shrinks to fit its width (long DMS coordinates). */
@Composable
private fun FitText(text: String, maxSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // A monospace glyph is about 0.6 em wide.
        val fitting = with(LocalDensity.current) { (maxWidth / (text.length * 0.62f)).toSp() }
        val size = if (fitting.value < maxSize.value) fitting else maxSize
        Text(
            text = text,
            color = color,
            fontSize = size,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

// ------------------------------------------------------------------ the sheet

@Composable
private fun ConstellationsSheet(state: GnssEngine.State, prefs: DisplayPrefs) {
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp)
    ) {
        Text(
            text = "Constellations",
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
        if (state.wanted && state.demo) {
            Spacer(Modifier.height(4.dp))
            Note("Demo sky: simulated satellites over Singapore. Turn it off in Settings.")
        }
        Spacer(Modifier.height(8.dp))
        for (summary in state.sky.constellations) {
            ConstellationCard(summary, prefs.shapes, sbasCorrections = state.sbasCorrections)
            Spacer(Modifier.height(8.dp))
        }
        PositionCard(state, prefs)
        if (state.sky.satellites.any { it.state == SatState.PREDICTED }) {
            Spacer(Modifier.height(8.dp))
            Note(
                "Not tracked: the chip lists the satellite from its almanac, but isn't " +
                    "receiving it (C/N0 0). Not counted as in view."
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace
    )
}

@Composable
private fun ConstellationCard(summary: ConstellationSummary, shapes: Boolean, sbasCorrections: Boolean) {
    val c = summary.constellation
    var expanded by rememberSaveable(c.name) { mutableStateOf(false) }
    val expandable = summary.satellites.size > ROWS_COLLAPSED
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = expandable) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = c.cardName,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                StatusMark(summary, shapes)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = cardHeadline(summary, sbasCorrections),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            if (summary.satellites.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                val rows = if (expanded) summary.satellites else summary.satellites.take(ROWS_COLLAPSED)
                rows.forEach { SatelliteRow(it) }
                if (expandable) {
                    Text(
                        text = if (expanded) "show less" else "+${summary.satellites.size - ROWS_COLLAPSED} more",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

private const val ROWS_COLLAPSED = 3

/** The constellation's colour if it's in the fix, grey if only tracked, an empty ring if not in view. */
@Composable
private fun StatusMark(summary: ConstellationSummary, shapes: Boolean) {
    val grey = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        summary.used > 0 -> MarkSwatch(
            if (shapes) summary.constellation.shape else MarkShape.CIRCLE,
            summary.constellation.color, 11.dp
        )
        summary.inView > 0 -> MarkSwatch(
            if (shapes) summary.constellation.shape else MarkShape.CIRCLE, grey, 11.dp
        )
        else -> Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .border(1.5.dp, grey, CircleShape)
        )
    }
}

private fun cardHeadline(s: ConstellationSummary, sbasCorrections: Boolean): String {
    val parts = mutableListOf(s.constellation.operator)
    when {
        s.inView > 0 -> {
            parts += "${s.inView} in view"
            parts += "${s.used} used"
            if (s.bands.isNotEmpty()) parts += s.bands.joinToString(" ")
            if (s.predicted > 0) parts += "${s.predicted} not tracked"
        }
        s.predicted > 0 -> parts += "none received · ${s.predicted} not tracked"
        else -> parts += "none in view"
    }
    if (s.constellation == Constellation.SBAS && sbasCorrections) parts += "corrections in use"
    // Non-breaking spaces inside each part, so a long line wraps only between parts.
    return parts.joinToString(" · ") { it.replace(' ', '\u00A0') }
}

@Composable
private fun SatelliteRow(s: Satellite) {
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (s.state == SatState.PREDICTED) base.copy(alpha = 0.55f) else base
    val position = if (s.positionKnown) {
        "el ${Format.degrees(s.elevationDeg.toDouble())} az ${Format.degrees(s.azimuthDeg.toDouble())}"
    } else {
        "position unknown"
    }
    val signal = if (s.state == SatState.PREDICTED) "no signal" else "${s.cn0DbHz.roundToInt()} dB-Hz"
    val status = when {
        s.sbasSystem != null -> s.sbasSystem + if (s.state == SatState.USED) " used" else ""
        s.state == SatState.USED -> "used"
        else -> ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Cell(s.label, 44.dp, color)
        Cell(signal, 66.dp, color)
        Cell(position, 104.dp, color)
        Text(
            text = listOf(s.bands.joinToString("+"), status).filter { it.isNotEmpty() }.joinToString("  "),
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun Cell(text: String, width: Dp, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        modifier = Modifier.width(width)
    )
}

@Composable
private fun PositionCard(state: GnssEngine.State, prefs: DisplayPrefs) {
    val fix = state.fix
    val units = prefs.units
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "POSITION",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(state.tier.ringColor(MaterialTheme.colorScheme.surfaceVariant))
                )
            }
            Spacer(Modifier.height(6.dp))
            if (fix == null) {
                Note(if (state.running) "no fix yet" else "press START to listen for satellites")
                return@Column
            }
            KeyValue("decimal", Format.latLonDecimal(fix.latitude, fix.longitude))
            KeyValue("DMS", Format.latLonDms(fix.latitude, fix.longitude))
            val heights = listOfNotNull(
                fix.altitudeM?.let { Format.heightPrecise(it, units) + " ellipsoid" },
                fix.mslAltitudeM?.let { Format.heightPrecise(it, units) + " MSL" }
            )
            KeyValue("height", heights.joinToString(" · ").ifEmpty { "—" })
            fix.mslSource?.let { KeyValue("MSL from", mslSourceText(it)) }
            KeyValue(
                "accuracy",
                listOfNotNull(
                    fix.accuracyM?.let { "±" + Format.distance(it.toDouble(), units) + " horiz." },
                    fix.verticalAccuracyM?.let { "±" + Format.distance(it.toDouble(), units) + " vert." }
                ).joinToString(" · ").ifEmpty { "—" }
            )
            KeyValue(
                "speed",
                fix.speedMps?.let { s ->
                    Format.speed(s.toDouble(), units) +
                        (fix.speedAccuracyMps?.let { " ±" + Format.speed(it.toDouble(), units) } ?: "")
                } ?: "—"
            )
            KeyValue(
                "bearing",
                fix.bearingDeg?.let { b ->
                    Format.degrees(b.toDouble()) + (fix.bearingAccuracyDeg?.let { " ±" + Format.degrees(it.toDouble()) } ?: "")
                } ?: "—"
            )
            KeyValue(
                "fix",
                if (state.fixValid) {
                    "${fixTypeText(state.fixType)} · ${state.sky.used} of ${state.sky.inView} satellites" +
                        if (state.fixTypeFromChip) "" else " (2D/3D inferred)"
                } else {
                    "lost ${Format.duration(state.fixAgeMs ?: 0)} ago"
                }
            )
            state.dop?.let { d ->
                KeyValue(
                    "DOP",
                    "P ${Format.dop(d.pdop)} · H ${Format.dop(d.hdop)} · V ${Format.dop(d.vdop)}" +
                        if (state.dopFromChip) " (chip)" else " (from geometry)"
                )
            }
            state.ttff?.let {
                KeyValue(
                    "TTFF",
                    if (it.fromChip) Format.duration(it.millis) + " (chip)"
                    else "first fix ${Format.duration(it.millis)} after START"
                )
            }
            KeyValue("GNSS time", Format.utcTime(fix.gnssTimeMs))
            fix.clockOffsetMs?.let { KeyValue("phone clock", Format.signedSeconds(it) + " vs GNSS") }
            KeyValue("fix age", Format.duration(state.fixAgeMs ?: 0))
            if (fix.mock) KeyValue("source", "mock location (another app)")
        }
    }
}

private fun mslSourceText(source: MslSource): String = when (source) {
    MslSource.PLATFORM -> "Android"
    MslSource.CONVERTER -> "Android's geoid model"
    MslSource.NMEA -> "chip's geoid (NMEA)"
    MslSource.DEMO -> "demo"
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = key,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}
