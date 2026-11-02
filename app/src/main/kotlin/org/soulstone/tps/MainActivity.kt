package org.soulstone.tps

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.soulstone.tps.data.Settings
import org.soulstone.tps.gnss.GnssEngine
import org.soulstone.tps.nav.Compass
import org.soulstone.tps.nav.WaypointStore
import org.soulstone.tps.ui.CompassView
import org.soulstone.tps.ui.DisplayPrefs
import org.soulstone.tps.ui.MainScreen
import org.soulstone.tps.ui.Notice
import org.soulstone.tps.ui.RawUi
import org.soulstone.tps.ui.SettingsScreen
import org.soulstone.tps.ui.WaypointsScreen
import org.soulstone.tps.ui.theme.TpsTheme
import org.soulstone.tps.ui.theme.isDarkTheme

/**
 * TPS starts listening as soon as it's on screen and stops when it leaves; there is no
 * START or STOP. On first launch it asks for location straight away. If that's refused,
 * the screen says why it's needed and offers to ask again.
 */
class MainActivity : ComponentActivity() {

    private lateinit var settings: Settings
    private lateinit var engine: GnssEngine
    private lateinit var waypoints: WaypointStore
    private lateinit var compass: Compass

    /** What the last permission prompt left us with. The engine reports everything else. */
    private enum class Access { OK, DENIED, DENIED_FOREVER, APPROXIMATE }

    private val access = mutableStateOf(Access.OK)

    /** Ask on its own only once per launch; after that the screen's button asks. */
    private var askedThisLaunch = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        access.value = when {
            result[FINE] == true || granted(FINE) -> Access.OK
            // Android 12+: "Approximate" was picked. It gets no satellite status at all;
            // asking again shows Android's "change to precise" prompt.
            result[COARSE] == true || granted(COARSE) -> Access.APPROXIMATE
            ActivityCompat.shouldShowRequestPermissionRationale(this, FINE) -> Access.DENIED
            else -> Access.DENIED_FOREVER
        }
        startListening()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askedThisLaunch = savedInstanceState?.getBoolean(KEY_ASKED) ?: false
        pendingSave = savedInstanceState?.getString(KEY_PENDING_SAVE)
        settings = Settings.get(this)
        engine = GnssEngine.get(this)
        waypoints = WaypointStore.get(this)
        compass = Compass(this)

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
                val headingUp by settings.headingUp.collectAsState()
                val units by settings.units.collectAsState()
                val geohash by settings.geohashPrecision.collectAsState()
                KeepScreenOn(keepOn)

                val fullTracking by settings.fullTracking.collectAsState()
                LaunchedEffect(fullTracking) { engine.setFullTracking(fullTracking) }

                // The compass runs only in heading-up mode (and onStop stops it).
                LaunchedEffect(headingUp) { if (headingUp) compass.start() else compass.stop() }
                SideEffect { state.fix?.let { compass.setPosition(it.latitude, it.longitude, it.altitudeM) } }

                when (screen) {
                    Screen.MAIN -> {
                        val coordinates by settings.coordinates.collectAsState()
                        val shapes by settings.shapes.collectAsState()
                        val labels by settings.labels.collectAsState()
                        val list by waypoints.waypoints.collectAsState()
                        val targetId by waypoints.targetId.collectAsState()
                        val headingState = compass.heading.collectAsState()
                        val accuracy by compass.accuracyDeg.collectAsState()
                        val accessNow by access
                        val recorder by engine.recorderState.collectAsState()
                        val chip = remember { engine.chipInfo() }
                        val raw = RawUi(
                            chip = chip,
                            recorder = recorder,
                            fullTracking = fullTracking,
                            onFullTracking = { settings.setFullTracking(it) },
                            onRecord = { engine.startRecording(BuildConfig.VERSION_NAME) },
                            onStopRecording = { engine.stopRecording() },
                            onSave = ::saveRecording,
                        )
                        MainScreen(
                            state = state,
                            prefs = DisplayPrefs(units, coordinates, shapes, labels, geohash),
                            waypoints = list,
                            target = list.firstOrNull { it.id == targetId },
                            compass = if (headingUp) CompassView(headingState, accuracy, compass.available) else null,
                            raw = raw,
                            notice = notice(state, accessNow),
                            onOpenWaypoints = { screen = Screen.WAYPOINTS },
                            onOpenSettings = { screen = Screen.SETTINGS }
                        )
                    }
                    Screen.SETTINGS -> {
                        // System back returns to MAIN instead of finishing the activity.
                        BackHandler { screen = Screen.MAIN }
                        SettingsScreen(
                            settings = settings,
                            onDemoChange = { on ->
                                settings.setDemoSky(on)
                                startListening()
                            },
                            onBack = { screen = Screen.MAIN }
                        )
                    }
                    Screen.WAYPOINTS -> {
                        BackHandler { screen = Screen.MAIN }
                        WaypointsScreen(
                            store = waypoints,
                            fix = state.fix,
                            fixValid = state.fixValid,
                            units = units,
                            geohashPrecision = geohash,
                            onNavigate = { screen = Screen.MAIN },
                            onBack = { screen = Screen.MAIN }
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ASKED, askedThisLaunch)
        outState.putString(KEY_PENDING_SAVE, pendingSave)
    }

    override fun onStart() {
        super.onStart()
        if (granted(FINE)) access.value = Access.OK
        startListening()
        if (settings.headingUp.value) compass.start()
    }

    override fun onStop() {
        // GNSS and the compass run only while TPS is on screen. The compass belongs to
        // this activity, so it always stops (a rotation starts the new one's); GNSS
        // keeps going through a rotation, which isn't leaving.
        compass.stop()
        if (!isChangingConfigurations) engine.stop()
        super.onStop()
    }

    /** Listen in the current mode; on first launch, ask for location first. */
    private fun startListening() {
        val demo = settings.demoSky.value
        engine.setFullTracking(settings.fullTracking.value)
        engine.start(demo)
        if (!demo && !granted(FINE) && !askedThisLaunch) requestLocation()
    }

    private fun requestLocation() {
        askedThisLaunch = true
        permissionLauncher.launch(arrayOf(FINE, COARSE))
    }

    private fun notice(state: GnssEngine.State, access: Access): Notice? {
        if (state.demo) return null
        return when (state.status) {
            GnssEngine.Status.NEEDS_PERMISSION -> if (access == Access.DENIED_FOREVER) {
                Notice("Location permission is off for TPS. Allow it in app settings.", "Open app settings") {
                    openAppSettings()
                }
            } else {
                Notice("TPS needs precise location to hear the satellites.", "Allow location") { requestLocation() }
            }
            GnssEngine.Status.APPROXIMATE_ONLY -> Notice(
                "Approximate location gets no satellite data. Choose Precise, or allow it in app settings.",
                "Allow precise location"
            ) { requestLocation() }
            GnssEngine.Status.LOCATION_OFF -> Notice(
                "Location is off, so Android sends no satellite data.",
                "Turn on location"
            ) { openLocationSettings() }
            GnssEngine.Status.NO_GNSS -> Notice("This device has no GNSS receiver. Demo sky still works (Settings).")
            GnssEngine.Status.IDLE, GnssEngine.Status.RUNNING -> null
        }
    }

    /**
     * Saves a raw-data recording to Download/TPS. Android 10+ lets an app add to Downloads
     * without any permission; on 8–9 that needs storage access, so there the system's own
     * "Save as" picker is used instead (also permission-free).
     */
    private fun saveRecording(name: String) {
        val file = engine.recording(name)
        if (!file.exists()) return
        if (Build.VERSION.SDK_INT >= 29) {
            lifecycleScope.launch(Dispatchers.IO) {
                val where = runCatching { Downloads.save(this@MainActivity, file) }.getOrNull()
                withContext(Dispatchers.Main) { toast(if (where != null) "Saved to $where" else "Couldn't save the recording") }
            }
        } else {
            pendingSave = name
            runCatching { saveAs.launch(name) }
        }
    }

    /** The recording waiting on the "Save as" picker (Android 8–9). */
    private var pendingSave: String? = null

    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val name = pendingSave
        pendingSave = null
        if (uri == null || name == null) return@registerForActivityResult
        val file = engine.recording(name)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.isSuccess
            withContext(Dispatchers.Main) { toast(if (ok) "Saved $name" else "Couldn't save the recording") }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

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

    private enum class Screen { MAIN, SETTINGS, WAYPOINTS }

    private companion object {
        const val FINE = Manifest.permission.ACCESS_FINE_LOCATION
        const val COARSE = Manifest.permission.ACCESS_COARSE_LOCATION
        const val KEY_ASKED = "asked_for_location"
        const val KEY_PENDING_SAVE = "pending_save"

        // androidx's own navigation-bar scrims, which only Android 8–9 draw.
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}

/** Android 10+: adds files to the shared Download folder, no permission needed. */
@RequiresApi(29)
private object Downloads {
    /** Copies [file] into Download/TPS and returns where it ended up. */
    fun save(context: Context, file: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TPS")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Downloads refused the file")
        try {
            resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        // Android adds " (1)" when a file of that name is already there.
        val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: file.name
        return "Download/TPS/$name"
    }
}

/** Holds the screen on while [on]. TPS only listens while it's on screen anyway. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}
