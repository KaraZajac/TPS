package org.soulstone.tps.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.tps.ui.theme.KibernetykMono

/*
 * The pulsing visuals, kept in their own leaf composables (OVERWATCH's lesson).
 * The pulse is an infinite animation, so anything that reads it in composition
 * recomposes every frame. Reading it only here, and in the ring's draw phase,
 * means the sky plot redraws when the satellites change and not 60 times a second.
 */

@Composable
private fun rememberPulse(animating: Boolean): State<Float> {
    val transition = rememberInfiniteTransition(label = "fix-pulse")
    return transition.animateFloat(
        initialValue = if (animating) 0.5f else 1.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fix-pulse"
    )
}

/** Solid pulsing disc: the circle's face while idle, or before any satellite is heard. */
@Composable
internal fun PulsingDisc(
    color: Color,
    animating: Boolean,
    label: String?,
    labelColor: Color,
    modifier: Modifier = Modifier
) {
    val pulse by rememberPulse(animating)
    val alpha = if (animating) pulse else 1.0f
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.6f))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(
                text = label,
                color = labelColor,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                fontFamily = KibernetykMono
            )
        }
    }
}

/** The fix-quality ring. The pulse is read in the draw phase, so only this ring redraws. */
@Composable
internal fun FixRing(color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val pulse = rememberPulse(pulsing)
    Canvas(modifier) {
        val alpha = if (pulsing) pulse.value else 1f
        val stroke = 6.dp.toPx()
        drawCircle(
            color = color.copy(alpha = color.alpha * alpha),
            radius = size.minDimension / 2 - stroke / 2,
            style = Stroke(width = stroke)
        )
    }
}
