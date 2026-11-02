package org.soulstone.tps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.math.sqrt
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.gnss.MarkShape
import org.soulstone.tps.nav.Geodesy
import android.os.Build
import org.soulstone.tps.raw.ChipInfo
import org.soulstone.tps.raw.EphemerisSource
import org.soulstone.tps.raw.Geodetic
import org.soulstone.tps.raw.NavType
import org.soulstone.tps.raw.OwnFixReport
import org.soulstone.tps.raw.RawStatus
import org.soulstone.tps.raw.RecorderState
import org.soulstone.tps.raw.Solution
import org.soulstone.tps.raw.Solver
import org.soulstone.tps.raw.SystemReport
import org.soulstone.tps.ui.theme.FixColors
import org.soulstone.tps.ui.theme.KibernetykMono
import org.soulstone.tps.ui.theme.SkyColors
import org.soulstone.tps.ui.theme.color

/** What the CHIP and OWN FIXES cards need besides the engine state. */
data class RawUi(
    val chip: ChipInfo,
    val recorder: RecorderState,
    val fullTracking: Boolean,
    val onFullTracking: (Boolean) -> Unit,
    val onRecord: () -> Unit,
    val onStopRecording: () -> Unit,
    val onShare: (String) -> Unit,
)

/** Systems TPS can decode orbits for so far; the others wait for v0.4. */
private val DECODED = setOf(Constellation.GPS, Constellation.GALILEO, Constellation.QZSS)

/** An own fix relative to the chip's: east, north, up metres. */
private class Offset3(val east: Double, val north: Double, val up: Double) {
    val horizontal: Double get() = sqrt(east * east + north * north)
    fun direction(): String {
        val bearing = (Math.toDegrees(atan2(east, north)) + 360) % 360
        return Geodesy.compassPoint(bearing)
    }
}

private fun offsetOf(chip: Geodetic?, s: Solution?): Offset3? {
    if (chip == null || s == null) return null
    val d = Solver.offset(chip, s.ecef)
    return Offset3(d[0], d[1], d[2])
}

@Composable
internal fun OwnFixesCard(state: GnssEngine.State, prefs: DisplayPrefs, raw: RawUi) {
    val r = state.raw
    val report = r.report
    val fix = state.fix
    val chip = fix?.takeIf { state.fixValid }?.let { Geodetic(it.latitude, it.longitude, it.altitudeM ?: 0.0) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle("OWN FIXES")
            Spacer(Modifier.height(4.dp))
            Note("TPS's own position from the raw signals, one per system and one from all of them, around the chip's fix at the centre.")
            Spacer(Modifier.height(8.dp))
            // Android 12+ says outright what the chip can do; earlier, only the callbacks tell.
            val canMeasure = raw.chip.capability(ChipInfo.RAW)
            val canNavigate = raw.chip.capability(ChipInfo.NAVIGATION)
            val canPosition = raw.chip.capability(ChipInfo.SATELLITE_POSITIONS)
            val noNavigation = r.navigation == RawStatus.NOT_SUPPORTED || (canNavigate == false && r.navMessages == 0L)
            val blocker = when {
                state.demo -> "Not in demo sky."
                !state.running -> "Waiting for GNSS."
                r.measurements == RawStatus.NOT_SUPPORTED || (canMeasure == false && r.epochs == 0L) ->
                    "This phone doesn't give apps raw measurements, so TPS can't compute a fix of its own."
                r.measurements == RawStatus.NOT_ALLOWED -> "Android isn't allowing raw measurements right now."
                report == null -> "Waiting for raw measurements."
                // Without navigation messages, the orbits can only be the chip's own satellite positions.
                noNavigation && !r.pvtSeen -> when {
                    r.pvtUnreadable > 0 ->
                        "The chip sends its satellite positions, but in a form TPS can't read on this Android version (${r.pvtWhy}). Without them or navigation messages, TPS has no orbits."
                    canPosition == false || Build.VERSION.SDK_INT < 31 ->
                        "This phone passes on neither the satellites' navigation messages nor the chip's own satellite positions, so TPS has no orbits to work from."
                    else -> "Waiting for the chip's satellite positions. It sends them for satellites it has orbits for; none yet in ${r.epochs} rounds of measurements."
                }
                else -> null
            }
            if (blocker != null) {
                Note(blocker)
                return@Column
            }
            val systems = report!!.systems
            val solved = systems.mapNotNull { s -> offsetOf(chip, s.solution)?.let { s to it } }
            val combined = offsetOf(chip, report.combined)
            if (chip != null && (solved.isNotEmpty() || combined != null)) {
                OffsetPlot(solved.map { it.first.constellation to it.second }, combined, prefs.shapes)
                Spacer(Modifier.height(8.dp))
            }
            for (s in systems) SystemRow(s, offsetOf(chip, s.solution), prefs, chipPositions = r.pvtSeen)
            report.combined?.let { c ->
                CombinedRow(c, combined, prefs)
            }
            orbitSources(report)?.let {
                Spacer(Modifier.height(4.dp))
                Note(it)
            }
            if (report.orbitsDisagreeing > 0) {
                Spacer(Modifier.height(4.dp))
                Note("${report.orbitsDisagreeing} decoded orbit(s) dropped: they put a satellite somewhere the chip doesn't.")
            }
            if (report.chipOrbitsDropped > 0) {
                Spacer(Modifier.height(4.dp))
                Note("${report.chipOrbitsDropped} of the chip's satellite positions left out: impossible values, or not where the chip's own sky view has the satellite.")
            }
            if (chip == null) {
                Spacer(Modifier.height(4.dp))
                Note("Distances appear once the chip has a fix to compare with.")
            }
        }
    }
}

/** Where the chip says its orbits came from ([EphemerisSource] to signals), in words. */
private fun sourceNames(sources: Map<Int, Int>): List<String> = sources.keys.sorted().mapNotNull { source ->
    when (source) {
        EphemerisSource.DEMODULATED -> "the satellites' broadcasts"
        EphemerisSource.SERVER_NORMAL -> "an assistance server"
        EphemerisSource.SERVER_LONG_TERM -> "a long-term prediction"
        else -> null
    }
}

/** Where this epoch's orbits came from, in words; null when none were used. */
private fun orbitSources(report: OwnFixReport): String? {
    val chip = report.chipOrbits
    val decoded = report.systems.sumOf { it.withOrbit - it.fromChip }
    val from = sourceNames(report.chipOrbitSources)
    val chipText = "the chip's own satellite positions" + if (from.isEmpty()) "" else " (its orbits from " + from.joinToString(" and ") + ")"
    return when {
        chip > 0 && decoded > 0 -> "Orbits: $decoded decoded by TPS, $chip from $chipText."
        chip > 0 -> "Orbits: $chipText."
        decoded > 0 -> "Orbits: decoded by TPS from the satellites' broadcasts."
        else -> null
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.onSurface,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = KibernetykMono
    )
}

@Composable
private fun SystemRow(s: SystemReport, offset: Offset3?, prefs: DisplayPrefs, chipPositions: Boolean) {
    val c = s.constellation
    val detail = when {
        s.solution != null && offset != null ->
            "${s.withOrbit} sats  " + Format.distance(offset.horizontal, prefs.units) + " " + offset.direction() +
                "  ±" + Format.distance(s.solution.residualRms, prefs.units)
        s.solution != null -> "${s.withOrbit} sats  solved"
        s.ranged == 0 -> "${s.signals} signals, none timed yet"
        s.withOrbit == 0 && c !in DECODED && !chipPositions -> "${s.ranged} timed · orbit decoding comes in v0.4"
        s.withOrbit < 4 -> "${s.ranged} timed · orbits for ${s.withOrbit} (needs 4)"
        else -> "${s.withOrbit} sats · no solution"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        MarkSwatch(if (prefs.shapes) c.shape else MarkShape.CIRCLE, c.color, 9.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = c.displayName,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontFamily = KibernetykMono,
            modifier = Modifier.width(monoWidthOf(8, 11))
        )
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = KibernetykMono)
    }
}

@Composable
private fun CombinedRow(c: Solution, offset: Offset3?, prefs: DisplayPrefs) {
    val detail = "${c.satellites.size} sats  " + (offset?.let {
        Format.distance(it.horizontal, prefs.units) + " " + it.direction() + "  "
    } ?: "") + "±" + Format.distance(c.residualRms, prefs.units)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "All",
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = KibernetykMono,
            modifier = Modifier.width(monoWidthOf(8, 11))
        )
        Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = KibernetykMono)
    }
}

@Composable
private fun monoWidthOf(chars: Int, sp: Int) = org.soulstone.tps.ui.theme.monoWidth(chars, sp.sp)

/** Each system's own fix around the chip's (the centre), north up, on a scale that fits them all. */
@Composable
private fun OffsetPlot(points: List<Pair<Constellation, Offset3>>, combined: Offset3?, shapes: Boolean) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = SkyColors.GridLabel, fontSize = 9.sp, fontFamily = KibernetykMono)
    val farthest = (points.map { it.second.horizontal } + listOfNotNull(combined?.horizontal)).maxOrNull() ?: 0.0
    val scale = SCALES.firstOrNull { it >= farthest * 1.1 } ?: SCALES.last()
    val onSurface = MaterialTheme.colorScheme.onSurface
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .size(190.dp)
                .clip(CircleShape)
                .background(SkyColors.DiscEdge)
        ) {
            val c = center
            val r = size.minDimension / 2 - 10.dp.toPx()
            val line = 1.dp.toPx()
            drawCircle(SkyColors.Grid, radius = r, center = c, style = Stroke(line))
            drawCircle(SkyColors.Grid, radius = r / 2, center = c, style = Stroke(line))
            drawLine(SkyColors.Grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), line)
            drawLine(SkyColors.Grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), line)
            val ring = measurer.measure(formatScale(scale), labelStyle)
            drawText(ring, topLeft = Offset(c.x + 3.dp.toPx(), c.y - r + 2.dp.toPx()))
            val half = measurer.measure(formatScale(scale / 2), labelStyle)
            drawText(half, topLeft = Offset(c.x + 3.dp.toPx(), c.y - r / 2 + 2.dp.toPx()))
            val n = measurer.measure("N", labelStyle)
            drawText(n, topLeft = Offset(c.x - n.size.width / 2f, 0f))
            fun at(o: Offset3): Offset {
                val k = (r / scale).toFloat()
                return Offset(c.x + (o.east * k).toFloat(), c.y - (o.north * k).toFloat())
            }
            // The chip's fix: a small cross at the centre.
            val arm = 6.dp.toPx()
            drawLine(onSurface, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), 2.dp.toPx())
            drawLine(onSurface, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), 2.dp.toPx())
            combined?.let { drawCircle(onSurface, radius = 7.dp.toPx(), center = at(it), style = Stroke(2.dp.toPx())) }
            for ((system, o) in points) {
                drawMark(if (shapes) system.shape else MarkShape.CIRCLE, at(o), 5.dp.toPx(), system.color, filled = true)
            }
        }
    }
    Text(
        text = "+ the chip's fix   ○ all systems (TPS)   outer ring ${formatScale(scale)}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 10.sp,
        fontFamily = KibernetykMono,
        modifier = Modifier.padding(top = 4.dp)
    )
}

private val SCALES = listOf(2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1_000.0, 5_000.0, 20_000.0)

private fun formatScale(m: Double) = if (m >= 1_000) "${(m / 1000).roundToInt()} km" else "${m.roundToInt()} m"

@Composable
internal fun ChipCard(state: GnssEngine.State, raw: RawUi) {
    val r = state.raw
    val report = r.report
    val chip = raw.chip
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            CardTitle("CHIP")
            Spacer(Modifier.height(6.dp))
            ChipRow("hardware", listOfNotNull(chip.hardware ?: "not reported", chip.year?.let { "($it)" }).joinToString(" "))
            ChipRow("phone", chip.device)
            ChipRow("Android", chip.android.removePrefix("Android "))
            ChipRow(
                "raw data",
                when {
                    state.demo -> "off in demo sky"
                    r.epochs > 0 -> "receiving · ${r.signals} signals" + when (r.fullTracking) {
                        true -> " · full tracking"
                        false -> " · duty-cycled"
                        null -> ""
                    }
                    else -> statusText(r.measurements)
                }
            )
            ChipRow(
                "nav messages",
                when {
                    state.demo -> "off in demo sky"
                    report != null && report.navMessages.isNotEmpty() ->
                        report.navMessages.entries.sortedBy { it.key }.joinToString(" · ") { "${NavType.name(it.key)} ${it.value}" } +
                            if (report.navRejected > 0) " · ${report.navRejected} failed checks" else ""
                    r.navMessages > 0 -> "receiving · ${r.navMessages}"
                    raw.chip.capability(ChipInfo.NAVIGATION) == false -> "not supported by this phone"
                    else -> statusText(r.navigation)
                }
            )
            ChipRow(
                "sat positions",
                when {
                    state.demo -> "off in demo sky"
                    r.pvtSignals > 0 -> "read from the chip · ${r.pvtSignals} of ${r.signals} signals" +
                        sourceNames(r.pvtSources).let { if (it.isEmpty()) "" else " · orbits from " + it.joinToString(" and ") }
                    r.pvtUnreadable > 0 -> "sent, but TPS can't read them on this Android version (${r.pvtWhy})"
                    Build.VERSION.SDK_INT < 31 -> "Android 12+ only"
                    chip.capability(ChipInfo.SATELLITE_POSITIONS) == false -> "not supported by this phone"
                    r.epochs > 0 -> "none sent yet"
                    else -> "waiting"
                }
            )
            if (report != null && report.orbits.isNotEmpty()) {
                ChipRow("orbits decoded", report.orbits.entries.sortedBy { it.key.ordinal }.joinToString(" · ") { "${it.key.displayName} ${it.value}" })
            }
            if (r.agc.isNotEmpty()) {
                ChipRow(
                    "AGC",
                    r.agc.distinctBy { it.constellation to it.carrierHz }.joinToString(" · ") {
                        it.constellation.displayName + " " + org.soulstone.tps.gnss.Bands.label(
                            it.constellation, 0, org.soulstone.tps.gnss.Carrier.of(it.carrierHz.toDouble())
                        ) + " " + String.format(java.util.Locale.ROOT, "%.1f dB", it.levelDb)
                    }
                )
            }
            if (chip.capabilities.isNotEmpty()) {
                ChipRow(
                    "can do",
                    chip.capabilities.joinToString(" · ") { (name, has) ->
                        name.replaceFirstChar { it.lowercase() } + " " + when (has) { true -> "yes"; false -> "no"; null -> "?" }
                    }
                )
            }
            ChipRow(
                "signals",
                when {
                    chip.signals == null -> "Android 14+ lists them"
                    chip.signals.isEmpty() -> "the chip doesn't say"
                    else -> chip.signals.joinToString(", ")
                }
            )

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Full tracking (raw data every second; more battery)",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 12.sp,
                    fontFamily = KibernetykMono,
                    modifier = Modifier.weight(1f).padding(end = 8.dp)
                )
                Switch(checked = raw.fullTracking, onCheckedChange = raw.onFullTracking)
            }

            Spacer(Modifier.height(8.dp))
            val rec = raw.recorder
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (rec.active == null) {
                    SmallButton("Record raw data", enabled = !state.demo && state.running, modifier = Modifier.weight(1f)) { raw.onRecord() }
                } else {
                    val seconds = ((System.currentTimeMillis() - rec.startedAtMs) / 1000).coerceAtLeast(0)
                    SmallButton(
                        "Stop · ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')} · ${rec.lines} lines",
                        modifier = Modifier.weight(1f),
                        color = FixColors.Red
                    ) { raw.onStopRecording() }
                }
                rec.last?.takeIf { rec.active == null }?.let { name ->
                    SmallButton("Share recording", modifier = Modifier.weight(1f)) { raw.onShare(name) }
                }
            }
            Spacer(Modifier.height(4.dp))
            Note(
                "Recordings are in Google's GnssLogger format, for checking TPS against other tools. " +
                    "They contain your position: share them only with people you trust."
            )
        }
    }
}

/** A CHIP card row: its keys run to 14 characters. */
@Composable
private fun ChipRow(key: String, value: String) = KeyValue(key, value, keyChars = 15)

private fun statusText(s: RawStatus) = when (s) {
    RawStatus.UNKNOWN -> "waiting"
    RawStatus.READY -> "ready, nothing yet"
    RawStatus.NOT_SUPPORTED -> "not supported by this phone"
    RawStatus.LOCATION_DISABLED -> "location is off"
    RawStatus.NOT_ALLOWED -> "not allowed right now"
}

@Composable
private fun SmallButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = color ?: MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (color != null) Color.White else MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(text, fontSize = 12.sp, fontFamily = KibernetykMono, maxLines = 1)
    }
}
