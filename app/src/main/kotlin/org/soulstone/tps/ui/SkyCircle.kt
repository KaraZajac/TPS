package org.soulstone.tps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin
import org.soulstone.tps.gnss.FixTier
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.gnss.MarkShape
import org.soulstone.tps.gnss.SatState
import org.soulstone.tps.gnss.Satellite
import org.soulstone.tps.gnss.SkyProjection
import org.soulstone.tps.gnss.SkySnapshot
import org.soulstone.tps.ui.theme.FixColors
import org.soulstone.tps.ui.theme.SkyColors
import org.soulstone.tps.ui.theme.color
import org.soulstone.tps.ui.theme.ringColor

/** A waypoint's direction on the ring: the one being navigated to as a triangle, the rest as dots. */
data class RingMarker(val bearingDeg: Float, val target: Boolean)

/** The band outside the ring that the waypoint markers sit in. */
private val MARKER_BAND = 16.dp

/**
 * The sky circle: horizon at the edge, overhead at the centre, every satellite the chip
 * reports drawn where it is. The ring around it is fix quality, and waypoints sit just
 * outside the ring in the direction you'd walk to reach them.
 *
 * North is up unless [heading] is given (heading-up mode): then the sky, the compass
 * letters and the waypoint markers turn so that up is where the phone points. The
 * heading is read only while drawing, so turning the phone redraws the circle without
 * recomposing anything.
 */
@Composable
fun SkyCircle(
    state: GnssEngine.State,
    shapes: Boolean,
    labels: Boolean,
    heading: State<Float?>?,
    markers: List<RingMarker>,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        FixRing(
            color = state.tier.ringColor(idle),
            pulsing = state.tier == FixTier.SEARCHING,
            modifier = Modifier
                .fillMaxSize()
                .padding(MARKER_BAND)
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(MARKER_BAND + 12.dp)
                .clip(CircleShape)
        ) {
            when {
                !state.wanted -> PulsingDisc(idle, animating = false, label = "IDLE", labelColor = muted)
                state.status != GnssEngine.Status.RUNNING ->
                    PulsingDisc(idle, animating = false, label = statusLabel(state.status), labelColor = muted)
                state.sky.isEmpty -> PulsingDisc(FixColors.Red, animating = true, label = "SEARCHING", labelColor = Color.White)
                else -> SkyPlot(state.sky, shapes, labels, heading)
            }
        }
        if (markers.isNotEmpty()) {
            RingMarkers(
                markers = markers,
                heading = heading,
                targetColor = MaterialTheme.colorScheme.onBackground,
                otherColor = muted,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .padding(MARKER_BAND)
                .clip(CircleShape)
                .semantics { contentDescription = "Sky circle. Tap for constellation details." }
                .clickable(onClick = onTap)
        )
    }
}

private fun statusLabel(status: GnssEngine.Status): String = when (status) {
    GnssEngine.Status.NEEDS_PERMISSION -> "NO ACCESS"
    GnssEngine.Status.APPROXIMATE_ONLY -> "APPROXIMATE"
    GnssEngine.Status.LOCATION_OFF -> "LOCATION OFF"
    GnssEngine.Status.NO_GNSS -> "NO GNSS"
    GnssEngine.Status.IDLE, GnssEngine.Status.RUNNING -> "IDLE"
}

@Composable
private fun SkyPlot(sky: SkySnapshot, shapes: Boolean, labels: Boolean, heading: State<Float?>?) {
    val measurer = rememberTextMeasurer()
    val compassStyle = TextStyle(color = SkyColors.GridLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val northStyle = compassStyle.copy(color = SkyColors.SatelliteLabel)
    val ringStyle = TextStyle(color = SkyColors.GridLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    val labelStyle = TextStyle(color = SkyColors.SatelliteLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    // Predicted first and used last, so the satellites in the fix draw on top.
    val marks = sky.satellites.filter { it.plottable }.sortedByDescending { it.state.ordinal }

    Canvas(Modifier.fillMaxSize()) {
        val h = heading?.value ?: 0f
        val c = center
        val outer = size.minDimension / 2
        drawCircle(
            brush = Brush.radialGradient(listOf(SkyColors.DiscCenter, SkyColors.DiscEdge), center = c, radius = outer),
            radius = outer,
            center = c
        )
        // The horizon sits inside the disc's edge, leaving a band for N/E/S/W.
        val plot = outer - 16.dp.toPx()
        val line = 1.dp.toPx()
        for (elevation in listOf(60f, 30f, 0f)) {
            drawCircle(SkyColors.Grid, radius = plot * (90f - elevation) / 90f, center = c, style = Stroke(line))
        }
        fun at(azimuth: Float, radius: Float): Offset {
            val p = SkyProjection.project(azimuth, 0f, h)
            return Offset(c.x + p.x * radius, c.y + p.y * radius)
        }
        drawLine(SkyColors.Grid, at(0f, plot), at(180f, plot), line)
        drawLine(SkyColors.Grid, at(90f, plot), at(270f, plot), line)

        fun label(text: String, style: TextStyle, position: Offset, centred: Boolean = true) {
            val layout = measurer.measure(text, style)
            val topLeft = if (centred) {
                Offset(position.x - layout.size.width / 2f, position.y - layout.size.height / 2f)
            } else {
                position
            }
            drawText(layout, topLeft = topLeft)
        }
        val band = (outer + plot) / 2
        // North is brighter, so it stands out when heading-up turns the letters around.
        label("N", if (heading != null) northStyle else compassStyle, at(0f, band))
        label("E", compassStyle, at(90f, band))
        label("S", compassStyle, at(180f, band))
        label("W", compassStyle, at(270f, band))
        if (heading == null) {
            label("30°", ringStyle, Offset(c.x + 3.dp.toPx(), c.y - plot * 60f / 90f + 2.dp.toPx()), centred = false)
            label("60°", ringStyle, Offset(c.x + 3.dp.toPx(), c.y - plot * 30f / 90f + 2.dp.toPx()), centred = false)
        }

        for (s in marks) {
            val p = SkyProjection.project(s.azimuthDeg, s.elevationDeg, h)
            val position = Offset(c.x + p.x * plot, c.y + p.y * plot)
            val r = markRadius(s).toPx()
            val color = s.constellation.color
            val shape = if (shapes) s.constellation.shape else MarkShape.CIRCLE
            when (s.state) {
                SatState.USED -> drawMark(shape, position, r, color, filled = true)
                SatState.TRACKED -> {
                    drawMark(shape, position, r, color.copy(alpha = 0.18f), filled = true)
                    drawMark(shape, position, r, color, filled = false, strokeWidth = 1.6.dp.toPx())
                }
                SatState.PREDICTED ->
                    drawMark(shape, position, r, color.copy(alpha = 0.45f), filled = false, strokeWidth = 1.dp.toPx())
            }
            if (s.dualFrequency) {
                drawCircle(
                    color.copy(alpha = 0.75f),
                    radius = r * extent(shape) + 3.dp.toPx(),
                    center = position,
                    style = Stroke(1.dp.toPx())
                )
            }
            if (labels) {
                val layout = measurer.measure(s.label, labelStyle)
                drawText(layout, topLeft = Offset(position.x + r * extent(shape) + 3.dp.toPx(), position.y - layout.size.height / 2f))
            }
        }
    }
}

/** Waypoints just outside the ring, at the bearing you'd walk. */
@Composable
private fun RingMarkers(
    markers: List<RingMarker>,
    heading: State<Float?>?,
    targetColor: Color,
    otherColor: Color,
    modifier: Modifier
) {
    // The target last, so it's on top.
    val ordered = markers.sortedBy { it.target }
    Canvas(modifier) {
        val h = heading?.value ?: 0f
        val c = center
        val ring = size.minDimension / 2 - MARKER_BAND.toPx()
        for (m in ordered) {
            val a = Math.toRadians((m.bearingDeg - h).toDouble())
            val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
            if (m.target) {
                // A triangle standing on the ring, pointing outwards: the way to go.
                val across = Offset(-dir.y, dir.x)
                val base = c + dir * (ring + 1.dp.toPx())
                val apex = c + dir * (ring + 15.dp.toPx())
                val half = 7.dp.toPx()
                val path = Path().apply {
                    moveTo(apex.x, apex.y)
                    lineTo(base.x + across.x * half, base.y + across.y * half)
                    lineTo(base.x - across.x * half, base.y - across.y * half)
                    close()
                }
                drawPath(path, targetColor)
            } else {
                drawCircle(otherColor, radius = 3.dp.toPx(), center = c + dir * (ring + 7.dp.toPx()))
            }
        }
    }
}

/** Mark size follows signal strength: 15 dB-Hz or less is smallest, 50 dB-Hz or more largest. */
private fun markRadius(s: Satellite): Dp {
    if (s.state == SatState.PREDICTED) return 3.dp
    val t = ((s.cn0DbHz - 15f) / 35f).coerceIn(0f, 1f)
    return (3.5f + 5f * t).dp
}

/** How far a shape reaches from its centre, in units of the mark radius. */
private fun extent(shape: MarkShape): Float = when (shape) {
    MarkShape.CIRCLE -> 1f
    MarkShape.SQUARE -> 1.22f
    MarkShape.TRIANGLE, MarkShape.TRIANGLE_DOWN -> 1.3f
    MarkShape.DIAMOND -> 1.18f
    MarkShape.STAR -> 1.32f
    MarkShape.CROSS -> 1.1f
}

/** Draws a constellation mark, filled (used in the fix) or outlined. */
internal fun DrawScope.drawMark(
    shape: MarkShape,
    at: Offset,
    r: Float,
    color: Color,
    filled: Boolean,
    strokeWidth: Float = 0f
) {
    val style = if (filled) Fill else Stroke(width = strokeWidth, join = StrokeJoin.Round)
    if (shape == MarkShape.CIRCLE) {
        drawCircle(color, radius = r, center = at, style = style)
    } else {
        drawPath(markPath(shape, at, r), color, style = style)
    }
}

/** The outline of each shape, sized so the shapes look about as big as a circle of radius [r]. */
internal fun markPath(shape: MarkShape, at: Offset, r: Float): Path = Path().apply {
    fun polygon(points: List<Offset>) {
        moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
        close()
    }
    fun regular(sides: Int, radius: Float, startDeg: Double): List<Offset> = List(sides) { k ->
        val a = Math.toRadians(startDeg + 360.0 * k / sides)
        Offset(at.x + radius * cos(a).toFloat(), at.y + radius * sin(a).toFloat())
    }
    when (shape) {
        MarkShape.CIRCLE -> addOval(Rect(at.x - r, at.y - r, at.x + r, at.y + r))
        MarkShape.SQUARE -> {
            val h = 0.86f * r
            addRect(Rect(at.x - h, at.y - h, at.x + h, at.y + h))
        }
        MarkShape.TRIANGLE -> polygon(regular(3, 1.3f * r, -90.0))
        MarkShape.TRIANGLE_DOWN -> polygon(regular(3, 1.3f * r, 90.0))
        MarkShape.DIAMOND -> polygon(regular(4, 1.18f * r, -90.0))
        MarkShape.STAR -> polygon(List(10) { k ->
            val radius = if (k % 2 == 0) 1.32f * r else 0.56f * r
            val a = Math.toRadians(-90.0 + 36.0 * k)
            Offset(at.x + radius * cos(a).toFloat(), at.y + radius * sin(a).toFloat())
        })
        MarkShape.CROSS -> {
            val w = 0.4f * r
            val l = 1.1f * r
            polygon(
                listOf(
                    Offset(-w, -l), Offset(w, -l), Offset(w, -w), Offset(l, -w), Offset(l, w), Offset(w, w),
                    Offset(w, l), Offset(-w, l), Offset(-w, w), Offset(-l, w), Offset(-l, -w), Offset(-w, -w),
                ).map { Offset(at.x + it.x, at.y + it.y) }
            )
        }
    }
}

/** A legend swatch: the constellation's mark, filled. */
@Composable
internal fun MarkSwatch(shape: MarkShape, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        drawMark(shape, center, this.size.minDimension / 2 / extent(shape), color, filled = true)
    }
}
