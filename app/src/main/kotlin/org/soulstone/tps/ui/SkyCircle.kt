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

/**
 * The sky circle: north up, horizon at the edge, overhead at the centre, every
 * satellite the chip reports drawn where it is. The ring around it is fix quality.
 */
@Composable
fun SkyCircle(
    state: GnssEngine.State,
    shapes: Boolean,
    labels: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        FixRing(
            color = state.tier.ringColor(idle),
            pulsing = state.tier == FixTier.SEARCHING,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(12.dp)
                .clip(CircleShape)
        ) {
            when {
                !state.wanted -> PulsingDisc(idle, animating = false, label = "IDLE", labelColor = muted)
                state.status != GnssEngine.Status.RUNNING ->
                    PulsingDisc(idle, animating = false, label = statusLabel(state.status), labelColor = muted)
                state.sky.isEmpty -> PulsingDisc(FixColors.Red, animating = true, label = "SEARCHING", labelColor = Color.White)
                else -> SkyPlot(state.sky, shapes, labels)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
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
private fun SkyPlot(sky: SkySnapshot, shapes: Boolean, labels: Boolean) {
    val measurer = rememberTextMeasurer()
    val compassStyle = TextStyle(color = SkyColors.GridLabel, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val ringStyle = TextStyle(color = SkyColors.GridLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    val labelStyle = TextStyle(color = SkyColors.SatelliteLabel, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    // Predicted first and used last, so the satellites in the fix draw on top.
    val marks = sky.satellites.filter { it.plottable }.sortedByDescending { it.state.ordinal }

    Canvas(Modifier.fillMaxSize()) {
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
        drawLine(SkyColors.Grid, Offset(c.x, c.y - plot), Offset(c.x, c.y + plot), line)
        drawLine(SkyColors.Grid, Offset(c.x - plot, c.y), Offset(c.x + plot, c.y), line)

        fun label(text: String, style: TextStyle, at: Offset, centred: Boolean = true) {
            val layout = measurer.measure(text, style)
            val topLeft = if (centred) {
                Offset(at.x - layout.size.width / 2f, at.y - layout.size.height / 2f)
            } else {
                at
            }
            drawText(layout, topLeft = topLeft)
        }
        val band = (outer + plot) / 2
        label("N", compassStyle, Offset(c.x, c.y - band))
        label("S", compassStyle, Offset(c.x, c.y + band))
        label("E", compassStyle, Offset(c.x + band, c.y))
        label("W", compassStyle, Offset(c.x - band, c.y))
        label("30°", ringStyle, Offset(c.x + 3.dp.toPx(), c.y - plot * 60f / 90f + 2.dp.toPx()), centred = false)
        label("60°", ringStyle, Offset(c.x + 3.dp.toPx(), c.y - plot * 30f / 90f + 2.dp.toPx()), centred = false)

        for (s in marks) {
            val p = SkyProjection.project(s.azimuthDeg, s.elevationDeg)
            val at = Offset(c.x + p.x * plot, c.y + p.y * plot)
            val r = markRadius(s).toPx()
            val color = s.constellation.color
            val shape = if (shapes) s.constellation.shape else MarkShape.CIRCLE
            when (s.state) {
                SatState.USED -> drawMark(shape, at, r, color, filled = true)
                SatState.TRACKED -> {
                    drawMark(shape, at, r, color.copy(alpha = 0.18f), filled = true)
                    drawMark(shape, at, r, color, filled = false, strokeWidth = 1.6.dp.toPx())
                }
                SatState.PREDICTED ->
                    drawMark(shape, at, r, color.copy(alpha = 0.45f), filled = false, strokeWidth = 1.dp.toPx())
            }
            if (s.dualFrequency) {
                drawCircle(
                    color.copy(alpha = 0.75f),
                    radius = r * extent(shape) + 3.dp.toPx(),
                    center = at,
                    style = Stroke(1.dp.toPx())
                )
            }
            if (labels) {
                val layout = measurer.measure(s.label, labelStyle)
                drawText(layout, topLeft = Offset(at.x + r * extent(shape) + 3.dp.toPx(), at.y - layout.size.height / 2f))
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
