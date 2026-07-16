package io.github.garemat.crumpet.ui.screens

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.geo.Locator
import io.github.garemat.crumpet.geo.Place
import io.github.garemat.crumpet.geo.PlacesStore
import io.github.garemat.crumpet.geo.PresenceWorker
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Muted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Manage the geofenced places (Home/Office/Gym/…) presence is computed from. The
 *  coordinates entered here stay on this phone (encrypted at rest); only the LABELS
 *  ever reach the brain. Reached from Settings — deliberately not a bottom-bar tab. */
@Composable
fun PlacesScreen() {
    val context = LocalContext.current
    val store = remember { PlacesStore(context) }
    val scope = rememberCoroutineScope()

    val places by store.placesFlow.collectAsStateWithLifecycle(emptyList())
    val paused by store.paused.collectAsStateWithLifecycle(false)

    var label by remember { mutableStateOf("") }
    var coords by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var busy by remember { mutableStateOf(false) }
    val fineGranted = remember { MutableStateFlow(PresenceWorker.hasLocationPermission(context)) }
    val bgGranted = remember { MutableStateFlow(PresenceWorker.hasBackgroundPermission(context)) }
    val fine by fineGranted.collectAsStateWithLifecycle()
    val bg by bgGranted.collectAsStateWithLifecycle()

    val fineLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { fineGranted.value = PresenceWorker.hasLocationPermission(context) }
    val bgLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { bgGranted.value = PresenceWorker.hasBackgroundPermission(context) }

    fun save(place: Place) {
        scope.launch {
            store.setPlaces(places.filterNot { it.label == place.label } + place)
            label = ""; coords = ""
            PresenceWorker.kick(context)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Places", style = MaterialTheme.typography.displaySmall, color = Cream)
        Text(
            "Presence is computed on this phone — Crumpet only ever learns the label " +
                "(“Home”, “Gym”, or “away”), never a coordinate.",
            color = Faint, fontSize = 12.sp,
        )

        Spacer(Modifier.height(16.dp))
        Eyebrow("Presence")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Share presence with Crumpet", color = Cream,
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (paused) "Paused — nothing is being checked or sent"
                        else "Checking roughly every 15 minutes",
                        color = Faint, fontSize = 11.sp,
                    )
                }
                Switch(checked = !paused, onCheckedChange = { on ->
                    scope.launch { store.setPaused(!on); if (on) PresenceWorker.kick(context) }
                })
            }
            if (!fine || !bg) {
                Spacer(Modifier.height(12.dp))
                if (!fine) {
                    OutlinedButton(
                        onClick = {
                            fineLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Grant location", color = Jade) }
                } else {
                    // Android requires the background grant as its own deliberate step
                    // ("Allow all the time" in settings) — correct friction for location.
                    OutlinedButton(
                        onClick = { bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow all the time (background)", color = Jade) }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Your places")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            if (places.isEmpty()) {
                Text("None yet — add Home first, from home.", color = Muted, fontSize = 13.sp)
            }
            places.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.label, color = Cream, style = MaterialTheme.typography.titleMedium)
                        Text("within ${p.radiusM} m", color = Faint, fontSize = 11.sp)
                    }
                    IconButton(onClick = {
                        scope.launch { store.setPlaces(places.filterNot { it.label == p.label }) }
                    }) { Icon(Icons.Outlined.Delete, "Remove ${p.label}", tint = Muted) }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Add a place")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            PlacesField("Label (Home, Office, Gym…)", label) { label = it }
            Spacer(Modifier.height(10.dp))
            Text("Radius: ${radius.toInt()} m", color = Muted, fontSize = 12.sp)
            Slider(value = radius, onValueChange = { radius = it }, valueRange = 50f..500f)
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = {
                    val name = label.trim()
                    if (name.isBlank()) {
                        Toast.makeText(context, "Give it a label first", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        val fix = Locator.currentFix(context, timeoutMs = 30_000)
                        busy = false
                        if (fix == null) {
                            Toast.makeText(context, "No location fix — try outdoors or paste coordinates",
                                Toast.LENGTH_LONG).show()
                        } else {
                            save(Place(name, fix.latitude, fix.longitude, radius.toInt()))
                        }
                    }
                },
                enabled = fine && !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Color(0xFF22170C)),
            ) { Text(if (busy) "Getting a fix…" else "Use current location") }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    PlacesField("or paste “lat, lng” from a maps app", coords) { coords = it }
                }
                OutlinedButton(onClick = {
                    val name = label.trim()
                    val parts = coords.split(",").map { it.trim().toDoubleOrNull() }
                    if (name.isBlank() || parts.size != 2 || parts.any { it == null }) {
                        Toast.makeText(context, "Need a label and “lat, lng”", Toast.LENGTH_SHORT).show()
                    } else {
                        save(Place(name, parts[0]!!, parts[1]!!, radius.toInt()))
                    }
                }) { Text("Add", color = Jade) }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PlacesField(label: String, value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp)),
        colors = androidx.compose.material3.TextFieldDefaults.colors(
            focusedContainerColor = Bg3,
            unfocusedContainerColor = Bg3,
            focusedTextColor = Cream,
            unfocusedTextColor = Cream,
            focusedLabelColor = Brass,
            unfocusedLabelColor = Faint,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            cursorColor = Brass,
        ),
    )
}
