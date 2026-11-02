package org.soulstone.tps.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.ConstellationSummary
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.gnss.MarkShape
import org.soulstone.tps.gnss.MslSource
import org.soulstone.tps.gnss.SatState
import org.soulstone.tps.gnss.Satellite
import org.soulstone.tps.nav.Geohash
import org.soulstone.tps.ui.theme.color
import org.soulstone.tps.ui.theme.ringColor
import org.soulstone.tps.ui.theme.KibernetykMono
import org.soulstone.tps.ui.theme.monoWidth

/*
 * The constellations sheet: what the circle shows, one system per card, and a POSITION
 * card with everything the main screen leaves out.
 */

@Composable
internal fun ConstellationsSheet(state: GnssEngine.State, prefs: DisplayPrefs, raw: RawUi?) {
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
            fontFamily = KibernetykMono
        )
        if (state.wanted && state.demo) {
            Spacer(Modifier.height(4.dp))
            Note("Demo sky: simulated satellites over Singapore. Turn it off in Settings.")
        }
        Spacer(Modifier.height(8.dp))
        for (summary in state.sky.constellations) {
            ConstellationCard(
                summary, prefs.shapes, sbasCorrections = state.sbasCorrections,
                supported = raw?.chip?.supported?.let { summary.constellation in it || summary.constellation == Constellation.UNKNOWN },
            )
            Spacer(Modifier.height(8.dp))
        }
        PositionCard(state, prefs)
        if (raw != null) {
            Spacer(Modifier.height(8.dp))
            OwnFixesCard(state, prefs, raw)
            Spacer(Modifier.height(8.dp))
            ChipCard(state, raw)
        }
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
internal fun Note(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 11.sp,
        fontFamily = KibernetykMono
    )
}

@Composable
private fun ConstellationCard(summary: ConstellationSummary, shapes: Boolean, sbasCorrections: Boolean, supported: Boolean?) {
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
                    fontFamily = KibernetykMono
                )
                StatusMark(summary, shapes)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = cardHeadline(summary, sbasCorrections, supported),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 11.sp,
                fontFamily = KibernetykMono
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

private fun cardHeadline(s: ConstellationSummary, sbasCorrections: Boolean, supported: Boolean?): String {
    val parts = mutableListOf(s.constellation.operator)
    when {
        s.inView > 0 -> {
            parts += "${s.inView} in view"
            parts += "${s.used} used"
            if (s.bands.isNotEmpty()) parts += s.bands.joinToString(" ")
            if (s.predicted > 0) parts += "${s.predicted} not tracked"
        }
        s.predicted > 0 -> parts += "none received · ${s.predicted} not tracked"
        // Only when the chip lists its signals (API 34+) can TPS tell the two apart.
        supported == false -> parts += "not supported by this phone"
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
        "no position"
    }
    val signal = if (s.state == SatState.PREDICTED) "no signal" else "${s.cn0DbHz.roundToInt()} dB-Hz"
    val status = when {
        s.sbasSystem != null -> s.sbasSystem + if (s.state == SatState.USED) " used" else ""
        s.state == SatState.USED -> "used"
        else -> ""
    }
    // Columns sized from the font: "R k=+6", "no signal", "el 71° az 222°".
    val gap = 6.dp
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Cell(s.label, monoWidth(6, ROW_TEXT) + gap, color)
        Cell(signal, monoWidth(9, ROW_TEXT) + gap, color)
        Cell(position, monoWidth(14, ROW_TEXT) + gap, color)
        Text(
            text = listOf(s.bands.joinToString("+"), status).filter { it.isNotEmpty() }.joinToString("  "),
            color = color,
            fontSize = ROW_TEXT,
            fontFamily = KibernetykMono,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

private val ROW_TEXT = 10.5.sp

@Composable
private fun Cell(text: String, width: Dp, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = ROW_TEXT,
        fontFamily = KibernetykMono,
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
                    fontFamily = KibernetykMono
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
            KeyValue("geohash", Geohash.encode(fix.latitude, fix.longitude, prefs.geohashPrecision.takeIf { it > 0 } ?: 9))
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
internal fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = key,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
            fontFamily = KibernetykMono,
            modifier = Modifier.width(monoWidth(12, 11.sp))
        )
        Text(
            text = value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontFamily = KibernetykMono,
            modifier = Modifier.weight(1f)
        )
    }
}
