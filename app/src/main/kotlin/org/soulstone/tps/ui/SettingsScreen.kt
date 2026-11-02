package org.soulstone.tps.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.soulstone.tps.BuildConfig
import org.soulstone.tps.R
import org.soulstone.tps.data.Settings

private const val SOURCE_URL = "https://github.com/KaraZajac/TPS"

/** Geohash lengths offered, with each cell's size near the equator. */
private val GEOHASH_CHOICES = listOf(
    0 to "None",
    7 to "7 characters · about 150 m",
    8 to "8 characters · about 38 × 19 m",
    9 to "9 characters · about 5 m",
    10 to "10 characters · about 1 m",
)

@Composable
fun SettingsScreen(
    settings: Settings,
    onDemoChange: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val theme by settings.themeMode.collectAsState()
    val units by settings.units.collectAsState()
    val coordinates by settings.coordinates.collectAsState()
    val shapes by settings.shapes.collectAsState()
    val labels by settings.labels.collectAsState()
    val keepScreenOn by settings.keepScreenOn.collectAsState()
    val demo by settings.demoSky.collectAsState()
    val headingUp by settings.headingUp.collectAsState()
    val geohash by settings.geohashPrecision.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Inset before the scroll container so content scrolls inside the safe area.
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Settings",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(8.dp))
        SectionLabel("Sky circle")
        Toggle("Shapes per constellation", shapes) { settings.setShapes(it) }
        HelpText(
            "Each system gets its own mark as well as its colour: circle GPS, triangle " +
                "Galileo, diamond BeiDou, square GLONASS, star QZSS, inverted triangle " +
                "NavIC, cross SBAS. With colour alone, GPS blue and BeiDou violet look " +
                "the same to people with red-green colour blindness."
        )
        Toggle("Satellite labels", labels) { settings.setLabels(it) }
        Toggle("Turn with the phone", headingUp) { settings.setHeadingUp(it) }
        HelpText(
            "North stays up unless this is on. On, the circle turns as you turn, so up is " +
                "where the phone points and a waypoint's triangle shows the way to walk. Uses " +
                "the compass, corrected to true north for where you are."
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("Position")
        RadioRow("Decimal degrees", coordinates == Settings.CoordinateFormat.DECIMAL) {
            settings.setCoordinates(Settings.CoordinateFormat.DECIMAL)
        }
        RadioRow("Degrees, minutes, seconds", coordinates == Settings.CoordinateFormat.DMS) {
            settings.setCoordinates(Settings.CoordinateFormat.DMS)
        }
        Spacer(Modifier.height(4.dp))
        HelpText("Geohash shown with the position (tap it to copy):")
        for ((precision, text) in GEOHASH_CHOICES) {
            RadioRow(text, geohash == precision) { settings.setGeohashPrecision(precision) }
        }
        Spacer(Modifier.height(4.dp))
        RadioRow("Metric", units == Settings.Units.METRIC) { settings.setUnits(Settings.Units.METRIC) }
        RadioRow("Imperial", units == Settings.Units.IMPERIAL) { settings.setUnits(Settings.Units.IMPERIAL) }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Screen")
        Toggle("Keep the screen on", keepScreenOn) { settings.setKeepScreenOn(it) }
        HelpText("TPS listens to the satellites only while it's on screen.")

        Spacer(Modifier.height(16.dp))
        SectionLabel("Appearance")
        RadioRow("System default", theme == Settings.ThemeMode.SYSTEM) {
            settings.setThemeMode(Settings.ThemeMode.SYSTEM)
        }
        RadioRow("Dark", theme == Settings.ThemeMode.DARK) { settings.setThemeMode(Settings.ThemeMode.DARK) }
        RadioRow("Light", theme == Settings.ThemeMode.LIGHT) { settings.setThemeMode(Settings.ThemeMode.LIGHT) }

        Spacer(Modifier.height(16.dp))
        SectionLabel("Demo sky")
        Toggle("Show a simulated sky", demo, onDemoChange)
        HelpText(
            "Simulated satellites over Singapore, where all seven systems are overhead, " +
                "for screenshots and the Android emulator (whose GNSS can't show a sky). " +
                "The phone's GNSS isn't used while this is on, and the main screen says DEMO."
        )

        Spacer(Modifier.height(16.dp))
        SectionLabel("About")
        HelpText(
            "TPS ${BuildConfig.VERSION_NAME} · Terran Positioning System · BSD-3-Clause\n\n" +
                "TPS has no network permission: it cannot send anything anywhere. It reads " +
                "your phone's GNSS chip directly, without Google Play services."
        )
        Text(
            text = SOURCE_URL.removePrefix("https://"),
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .clickable {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE_URL))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                }
                .padding(vertical = 8.dp)
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row toggles, not just the switch, with switch semantics.
            .toggleable(value = value, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // weight(1f) lets the label wrap on narrow screens instead of running under the Switch.
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .weight(1f, fill = true)
                .padding(end = 12.dp)
        )
        // Null: the row above owns the click, so the switch must not handle it too.
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // The whole row is the target, with radio-group semantics.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}
