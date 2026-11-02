package org.soulstone.tps

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.soulstone.tps.data.Settings
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.ui.DisplayPrefs
import org.soulstone.tps.ui.MainScreen
import org.soulstone.tps.ui.Notice
import org.soulstone.tps.ui.SettingsScreen
import org.soulstone.tps.ui.theme.TpsTheme
import org.soulstone.tps.ui.theme.isDarkTheme

class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var engine: GnssEngine

    /** What the last permission prompt left us with. The engine reports everything else. */
    private enum class Access { OK, DENIED, DENIED_FOREVER, APPROXIMATE }

    private val access = mutableStateOf(Access.OK)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        access.value = when {
            result[FINE] == true || granted(FINE) -> Access.OK
            // Android 12+: the user picked "Approximate". Approximate location gets no
            // satellite status at all, so TPS can't start. START asks again, which
            // shows Android's "change to precise" prompt.
            result[COARSE] == true || granted(COARSE) -> Access.APPROXIMATE
            ActivityCompat.shouldShowRequestPermissionRationale(this, FINE) -> Access.DENIED
            else -> Access.DENIED_FOREVER
        }
        // First run: start straight away, so START needn't be pressed twice.
        if (access.value == Access.OK) engine.start(demo = false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings.get(this)
        engine = GnssEngine.get(this)

        // Edge-to-edge on every release (Android 15+ enforces it anyway), with the
        // bars' icons following the theme TPS shows rather than the system's. The
        // check reads the setting each time androidx re-runs it.
        val appIsDark = { res: Resources ->
            val systemDark = (res.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            isDarkTheme(settings.themeMode.value, systemDark)
        }
        val statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT, appIsDark)
        val navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM, appIsDark)
        enableEdgeToEdge(statusBarStyle, navigationBarStyle)

        setContent {
            val themeMode by settings.themeMode.collectAsState()
            DisposableEffect(themeMode) {
                enableEdgeToEdge(statusBarStyle, navigationBarStyle)
                onDispose { }
            }
            TpsTheme(mode = themeMode) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                val state by engine.state.collectAsState()
                val keepOn by settings.keepScreenOn.collectAsState()
                KeepScreenOn(state.wanted && keepOn)

                when (screen) {
                    Screen.MAIN -> {
                        val units by settings.units.collectAsState()
                        val coordinates by settings.coordinates.collectAsState()
                        val shapes by settings.shapes.collectAsState()
                        val labels by settings.labels.collectAsState()
                        val accessNow by access
                        MainScreen(
                            state = state,
                            prefs = DisplayPrefs(units, coordinates, shapes, labels),
                            notice = notice(state, accessNow),
                            onStartStop = ::onStartStop,
                            onOpenSettings = { screen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS -> {
                        // System back returns to MAIN instead of finishing the activity.
                        BackHandler { screen = Screen.MAIN }
                        SettingsScreen(
                            settings = settings,
                            onDemoChange = ::onDemoChange,
                            onBack = { screen = Screen.MAIN }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        engine.onForeground()
    }

    override fun onStop() {
        // GNSS runs only while TPS is on screen. A rotation isn't leaving.
        if (!isChangingConfigurations) engine.onBackground()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        // The user may have changed the permission in app settings meanwhile.
        if (granted(FINE)) access.value = Access.OK
    }

    private fun onStartStop() {
        if (engine.state.value.wanted) {
            engine.stop()
            return
        }
        if (settings.demoSky.value) {
            engine.start(demo = true)
            return
        }
        when {
            granted(FINE) -> {
                access.value = Access.OK
                engine.start(demo = false)
            }
            access.value == Access.DENIED_FOREVER -> openAppSettings()
            else -> permissionLauncher.launch(arrayOf(FINE, COARSE))
        }
    }

    private fun onDemoChange(on: Boolean) {
        settings.setDemoSky(on)
        val s = engine.state.value
        if (s.wanted && s.demo != on) {
            // Into demo: switch over now. Out of demo: stop, and let START ask for
            // permission if it's needed.
            if (on) engine.start(demo = true) else engine.stop()
        }
    }

    private fun notice(state: GnssEngine.State, access: Access): Notice? {
        val openSettings = "Open app settings"
        if (state.wanted && !state.demo) {
            when (state.status) {
                GnssEngine.Status.LOCATION_OFF -> return Notice(
                    "Location is off, so Android sends no satellite data.",
                    "Turn on location"
                ) { openLocationSettings() }
                GnssEngine.Status.NO_GNSS -> return Notice(
                    "This device has no GNSS receiver. Demo sky still works (Settings)."
                )
                GnssEngine.Status.NEEDS_PERMISSION -> return Notice(
                    "TPS no longer has location permission.", openSettings
                ) { openAppSettings() }
                GnssEngine.Status.APPROXIMATE_ONLY -> return Notice(
                    "Approximate location gets no satellite data. Allow precise location for TPS.",
                    openSettings
                ) { openAppSettings() }
                GnssEngine.Status.IDLE, GnssEngine.Status.RUNNING -> Unit
            }
        }
        if (state.wanted) return null
        return when (access) {
            Access.OK -> null
            Access.DENIED -> Notice("TPS needs location permission to hear satellites. Tap START to allow it.")
            Access.DENIED_FOREVER -> Notice("Location permission is denied. Allow it in app settings.", openSettings) {
                openAppSettings()
            }
            Access.APPROXIMATE -> Notice(
                "Approximate location gets no satellite data. Tap START and choose Precise, or allow it in app settings.",
                openSettings
            ) { openAppSettings() }
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        val intent = Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(intent) }
    }

    private fun openLocationSettings() {
        runCatching { startActivity(Intent(AndroidSettings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }

    private enum class Screen { MAIN, SETTINGS }

    private companion object {
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION

        // androidx's own navigation-bar scrims, which only Android 8–9 draw.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** Holds the screen on while [on]; TPS only listens while it's on screen. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
