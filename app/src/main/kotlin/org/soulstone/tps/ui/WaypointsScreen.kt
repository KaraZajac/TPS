package org.soulstone.tps.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.soulstone.tps.R
import org.soulstone.tps.data.Settings
import org.soulstone.tps.gnss.Fix
import org.soulstone.tps.nav.Geodesy
import org.soulstone.tps.nav.Geohash
import org.soulstone.tps.nav.Waypoint
import org.soulstone.tps.nav.WaypointStore
import org.soulstone.tps.nav.WaypointText
import org.soulstone.tps.ui.theme.FixColors
import org.soulstone.tps.ui.theme.KibernetykMono

private const val EXAMPLES = "Trailhead, 44.46044, -110.82814\n" +
    "Car 9xchyjc2v\n" +
    "44°27'37.6\"N 110°49'41.3\"W Camp"

/**
 * The waypoint list: paste or type positions with labels, add where you are, and tap one
 * to navigate to it. The main screen then shows it as a triangle on the ring.
 */
@Composable
fun WaypointsScreen(
    store: WaypointStore,
    fix: Fix?,
    fixValid: Boolean,
    units: Settings.Units,
    geohashPrecision: Int,
    onNavigate: () -> Unit,
    onBack: () -> Unit
) {
    val waypoints by store.waypoints.collectAsState()
    val targetId by store.targetId.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var here by remember { mutableStateOf<Fix?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Closing the name dialog shouldn't hand the keyboard to the paste box.
    fun closeDialog() {
        here = null
        focusManager.clearFocus()
        keyboard?.hide()
    }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_back),
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Waypoints",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = KibernetykMono,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        Spacer(Modifier.height(8.dp))
        Label("Add")
        Note(
            "One per line: a label and coordinates or a geohash, in either order. Decimal " +
                "degrees, degrees-minutes-seconds and geohashes all work. Nothing leaves the phone."
        )
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(EXAMPLES, fontFamily = KibernetykMono, fontSize = 12.sp) },
            minLines = 3,
            textStyle = LocalTextStyle.current.copy(fontFamily = KibernetykMono, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton("Add", enabled = input.isNotBlank(), modifier = Modifier.weight(1f)) {
                val parsed = WaypointText.parse(input)
                val added = store.add(parsed.waypoints)
                result = summary(added, parsed.errors)
                if (parsed.errors.isEmpty()) input = ""
            }
            ActionButton("Add where I am", enabled = fix != null && fixValid, modifier = Modifier.weight(1.4f)) {
                // The position from the moment of the tap, not after the label is typed.
                focusManager.clearFocus()
                here = fix
            }
        }
        result?.let { Note(it) }

        Spacer(Modifier.height(16.dp))
        Label("Saved (${waypoints.size})")
        if (waypoints.isEmpty()) {
            Note("No waypoints yet.")
        }
        for (w in waypoints) {
            val isTarget = w.id == targetId
            WaypointCard(
                waypoint = w,
                isTarget = isTarget,
                fix = fix,
                units = units,
                geohashPrecision = geohashPrecision,
                onClick = {
                    if (isTarget) {
                        store.setTarget(null)
                    } else {
                        store.setTarget(w.id)
                        onNavigate()
                    }
                },
                onDelete = { store.remove(w.id) }
            )
            Spacer(Modifier.height(8.dp))
        }
        if (waypoints.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ActionButton("Copy all", modifier = Modifier.weight(1f)) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("waypoints", WaypointText.export(waypoints)))
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Waypoints copied", Toast.LENGTH_SHORT).show()
                }
                ActionButton("Delete all", modifier = Modifier.weight(1f)) { confirmClear = true }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    here?.let { f ->
        NameDialog(
            fix = f,
            geohashPrecision = geohashPrecision,
            onSave = { label ->
                store.add(listOf(WaypointText.Parsed(label, f.latitude, f.longitude)))
                result = "Added “$label”."
                closeDialog()
            },
            onDismiss = { closeDialog() }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all ${waypoints.size} waypoints?") },
            text = { Text("This can't be undone. Copy all first if you want to keep a copy.") },
            confirmButton = {
                TextButton(onClick = { store.clear(); confirmClear = false; result = null }) { Text("Delete all") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } }
        )
    }
}

/** Asks for a label for the current position; Save alone keeps the suggested one. */
@Composable
private fun NameDialog(fix: Fix, geohashPrecision: Int, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    val suggestion = remember(fix) {
        "Here " + DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
            .format(Instant.ofEpochMilli(fix.gnssTimeMs).atZone(ZoneId.systemDefault()))
    }
    // Pre-filled and selected, so typing replaces it.
    var name by remember { mutableStateOf(TextFieldValue(suggestion, selection = TextRange(0, suggestion.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    fun save() = onSave(name.text.trim().ifEmpty { suggestion }.take(60))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this place") },
        text = {
            Column {
                Text(
                    text = Format.latLonDecimal(fix.latitude, fix.longitude) +
                        if (geohashPrecision > 0) "\n" + Geohash.encode(fix.latitude, fix.longitude, geohashPrecision) else "",
                    fontSize = 12.sp,
                    fontFamily = KibernetykMono,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("Label") },
                    textStyle = LocalTextStyle.current.copy(fontFamily = KibernetykMono, fontSize = 15.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                )
                LaunchedEffect(Unit) {
                    focus.requestFocus()
                    keyboard?.show()
                }
            }
        },
        confirmButton = { TextButton(onClick = { save() }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun summary(added: List<Waypoint>, errors: List<String>): String {
    val parts = mutableListOf<String>()
    if (added.isNotEmpty()) parts += "Added ${added.size}."
    if (errors.isNotEmpty()) parts += "Couldn't read " + errors.joinToString("; ") + "."
    return parts.joinToString(" ").ifEmpty { "Nothing to add." }
}

@Composable
private fun WaypointCard(
    waypoint: Waypoint,
    isTarget: Boolean,
    fix: Fix?,
    units: Settings.Units,
    geohashPrecision: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .then(if (isTarget) Modifier.border(1.5.dp, FixColors.Green, RoundedCornerShape(8.dp)) else Modifier),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 12.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = waypoint.label,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = KibernetykMono,
                    modifier = Modifier.weight(1f)
                )
                if (isTarget) {
                    Text(
                        text = "NAVIGATING",
                        color = FixColors.Green,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = KibernetykMono
                    )
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = "Delete ${waypoint.label}",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            val position = Format.latLonDecimal(waypoint.latitude, waypoint.longitude) +
                if (geohashPrecision > 0) " · " + Geohash.encode(waypoint.latitude, waypoint.longitude, geohashPrecision) else ""
            Small(position, MaterialTheme.colorScheme.onSurface)
            if (fix != null) {
                val d = Geodesy.distanceMeters(fix.latitude, fix.longitude, waypoint.latitude, waypoint.longitude)
                val b = Geodesy.bearingDeg(fix.latitude, fix.longitude, waypoint.latitude, waypoint.longitude)
                val where = if (arrived(fix, waypoint)) {
                    "you're here (${Format.longDistance(d, units)})"
                } else {
                    Format.longDistance(d, units) + " · " + Format.bearing(b) + " " + Geodesy.compassPoint(b)
                }
                Small(where, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Small(if (isTarget) "tap to stop navigating" else "tap to navigate here", MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Small(text: String, color: Color) {
    Text(text = text, color = color, fontSize = 11.sp, fontFamily = KibernetykMono)
}

@Composable
private fun Label(text: String) {
    Text(
        text = text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = KibernetykMono,
        modifier = Modifier.padding(vertical = 8.dp)
    )
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = KibernetykMono,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun ActionButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
    ) {
        Text(text, fontSize = 13.sp, fontFamily = KibernetykMono)
    }
}
