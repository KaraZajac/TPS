package org.soulstone.tps.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import org.soulstone.tps.R

/**
 * Kibernetyk Mono by Pavlo Kilko, used for all of TPS's text. SIL Open Font License
 * 1.1; the license ships in the APK as assets/licenses/KibernetykMono-OFL.txt.
 * It has no ± or ▲, which Android draws from the system font.
 */
val KibernetykMono = FontFamily(
    Font(R.font.kibernetyk_mono_regular, FontWeight.Normal),
    Font(R.font.kibernetyk_mono_medium, FontWeight.Medium),
    Font(R.font.kibernetyk_mono_semibold, FontWeight.SemiBold),
    Font(R.font.kibernetyk_mono_bold, FontWeight.Bold),
)

/** Every Kibernetyk Mono glyph is this wide, in em (wider than most monospace fonts' 0.6). */
const val MONO_ADVANCE_EM = 0.72f

/** The width of [chars] characters of Kibernetyk Mono at [size], following the font-scale setting. */
@Composable
fun monoWidth(chars: Int, size: TextUnit): Dp = with(LocalDensity.current) { (size * (MONO_ADVANCE_EM * chars)).toDp() }

private val base = Typography()

/** Material's type scale, all in Kibernetyk Mono, so text without its own style matches too. */
val TpsTypography = base.copy(
    displayLarge = base.displayLarge.copy(fontFamily = KibernetykMono),
    displayMedium = base.displayMedium.copy(fontFamily = KibernetykMono),
    displaySmall = base.displaySmall.copy(fontFamily = KibernetykMono),
    headlineLarge = base.headlineLarge.copy(fontFamily = KibernetykMono),
    headlineMedium = base.headlineMedium.copy(fontFamily = KibernetykMono),
    headlineSmall = base.headlineSmall.copy(fontFamily = KibernetykMono),
    titleLarge = base.titleLarge.copy(fontFamily = KibernetykMono),
    titleMedium = base.titleMedium.copy(fontFamily = KibernetykMono),
    titleSmall = base.titleSmall.copy(fontFamily = KibernetykMono),
    bodyLarge = base.bodyLarge.copy(fontFamily = KibernetykMono),
    bodyMedium = base.bodyMedium.copy(fontFamily = KibernetykMono),
    bodySmall = base.bodySmall.copy(fontFamily = KibernetykMono),
    labelLarge = base.labelLarge.copy(fontFamily = KibernetykMono),
    labelMedium = base.labelMedium.copy(fontFamily = KibernetykMono),
    labelSmall = base.labelSmall.copy(fontFamily = KibernetykMono),
)
