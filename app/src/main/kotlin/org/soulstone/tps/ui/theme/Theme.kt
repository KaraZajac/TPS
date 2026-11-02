package org.soulstone.tps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.soulstone.tps.data.Settings
import org.soulstone.tps.gnss.Constellation
import org.soulstone.tps.gnss.FixTier

// OVERWATCH's colours, so the two apps read as one family.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF1FAA59),
    onPrimary = Color.White,
    background = Color(0xFF0B0E12),
    onBackground = Color(0xFFF4F6FA),
    surface = Color(0xFF161A21),
    onSurface = Color(0xFFF4F6FA),
    surfaceVariant = Color(0xFF1E232C),
    onSurfaceVariant = Color(0xFF9AA3B2)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF1FAA59),
    onPrimary = Color.White,
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF0B0E12),
    surface = Color.White,
    onSurface = Color(0xFF0B0E12)
)

/** OVERWATCH's four tier colours. In TPS green means a good fix. */
object FixColors {
    val Green = Color(0xFF1FAA59)
    val Yellow = Color(0xFFF4C20D)
    val Orange = Color(0xFFF26B0F)
    val Red = Color(0xFFD7263D)
}

/** The sky disc stays a night sky in both themes, so the constellation colours read the same. */
object SkyColors {
    val DiscCenter = Color(0xFF1A2230)
    val DiscEdge = Color(0xFF0E131A)
    val Grid = Color(0xFF2C3644)
    val GridLabel = Color(0xFF7D8796)
    val SatelliteLabel = Color(0xFFB8C0CC)
}

val Constellation.color: Color get() = Color(argb)

/** The fix ring's colour; [idle] is the theme's muted grey. */
fun FixTier.ringColor(idle: Color): Color = when (this) {
    FixTier.IDLE -> idle
    FixTier.SEARCHING -> FixColors.Red
    FixTier.POOR -> FixColors.Orange
    FixTier.FAIR -> FixColors.Yellow
    FixTier.GOOD -> FixColors.Green
}

/** Whether [mode] shows the dark colours. Shared with MainActivity, which styles the system bars to match. */
fun isDarkTheme(mode: Settings.ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    Settings.ThemeMode.DARK -> true
    Settings.ThemeMode.LIGHT -> false
    Settings.ThemeMode.SYSTEM -> systemDark
}

@Composable
fun TpsTheme(
    mode: Settings.ThemeMode = Settings.ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val dark = isDarkTheme(mode, isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
