package io.github.garemat.crumpet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.ui.AppViewModel
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Muted
import io.github.garemat.crumpet.ui.theme.Ok

@Composable
fun SetupScreen(
    vm: AppViewModel,
    onOpenPlaces: () -> Unit,
    onRequestHealth: () -> Unit,
    onRequestOther: () -> Unit,
    onConnect: (String, String) -> Unit,
) {
    val savedUrl by vm.serverUrl.collectAsStateWithLifecycle()
    val savedToken by vm.token.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val ghSaved by vm.ghToken.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val syncMsg by vm.syncMsg.collectAsStateWithLifecycle()
    val spotifyMsg by vm.spotifyMsg.collectAsStateWithLifecycle()

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember(savedToken) { mutableStateOf(savedToken) }
    var gh by remember(ghSaved) { mutableStateOf(ghSaved) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Setup", style = MaterialTheme.typography.displaySmall, color = Cream)

        Spacer(Modifier.height(16.dp))
        Eyebrow("Connect to Crumpet")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(11.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (connected) Ok else Muted),
                )
                Spacer(Modifier.width(10.dp))
                Text(status, color = if (connected) Ok else Muted)
            }
            Spacer(Modifier.height(14.dp))
            Field("Brain URL  (e.g. https://crumpet)", url) { url = it }
            Spacer(Modifier.height(10.dp))
            Field("Device token", token, password = true) { token = it }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { onConnect(url.trim(), token.trim()) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Color(0xFF22170C)),
            ) { Text("Save & connect") }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Permissions")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Text("Health Connect", color = Cream, style = MaterialTheme.typography.titleMedium)
            Text("Nutrition · weight · activity · sleep (read-only)", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRequestHealth, modifier = Modifier.fillMaxWidth()) {
                Text("Grant Health Connect", color = Jade)
            }
            Spacer(Modifier.height(12.dp))
            Text("Notifications", color = Cream, style = MaterialTheme.typography.titleMedium)
            Text("Receive Crumpet's nudges", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onRequestOther, modifier = Modifier.fillMaxWidth()) {
                Text("Grant notifications", color = Jade)
            }
            Spacer(Modifier.height(12.dp))
            Text("Spotify", color = Cream, style = MaterialTheme.typography.titleMedium)
            Text(
                "Lets Crumpet wake Spotify here so this phone shows up as a playback device",
                color = Faint, fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { vm.connectSpotify() }, modifier = Modifier.fillMaxWidth()) {
                Text("Connect Spotify (one-time)", color = Jade)
            }
            spotifyMsg?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = if (it.startsWith("Connected")) Ok else Muted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Presence")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Text("Places & presence", color = Cream, style = MaterialTheme.typography.titleMedium)
            Text(
                "Geofence your own places (Home/Office/Gym) so Crumpet knows where you are — " +
                    "labels only, coordinates never leave the phone",
                color = Faint, fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onOpenPlaces, modifier = Modifier.fillMaxWidth()) {
                Text("Manage places", color = Jade)
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Sync")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Text("Health data syncs every 3 hours, on open, and when you tap below.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.syncNow() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Color(0xFF22170C)),
            ) { Text("Sync now") }
            syncMsg?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, color = if (it.startsWith("Synced")) Ok else Muted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("App updates")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Text(
                "You're on v${vm.currentVersion}." +
                    (update?.let { "  Update to v${it.version} is available — see Home." } ?: "  Up to date."),
                color = Muted, fontSize = 13.sp,
            )
            Spacer(Modifier.height(10.dp))
            Field("GitHub token (only needed while the repo is private)", gh, password = true) { gh = it }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = { vm.setGhToken(gh); vm.checkForUpdate() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Check for updates", color = Jade) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Field(label: String, value: String, password: Boolean = false, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        colors = TextFieldDefaults.colors(
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
