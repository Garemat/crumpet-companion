package io.github.garemat.crumpet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Lavender
import io.github.garemat.crumpet.ui.theme.Muted

/** One entry in the launcher. Native apps are companion screens; web apps are Crumpet's
 *  self-built PWAs (one tile → the WebView launcher, which lists however many he's
 *  published — new ones appear there with zero companion changes). */
data class AppTile(val title: String, val subtitle: String, val icon: ImageVector, val tint: Color, val onOpen: () -> Unit)

/** The Apps tab: a launcher, so new integrations get a tile instead of a bottom-bar slot. */
@Composable
fun AppsHubScreen(gymSubtitle: String, onGym: () -> Unit, onWebApps: () -> Unit) {
    val native = listOf(
        AppTile("Gym", gymSubtitle, Icons.Outlined.FitnessCenter, Brass, onGym),
    )
    val web = listOf(
        AppTile("Crumpet's web apps", "Apps Crumpet has built and published", Icons.Outlined.Language, Lavender, onWebApps),
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Apps", style = MaterialTheme.typography.headlineMedium, color = Cream)
        Eyebrow("Apps", Modifier.padding(top = 6.dp))
        native.forEach { Tile(it) }
        Eyebrow("Web apps", Modifier.padding(top = 10.dp))
        web.forEach { Tile(it) }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Tile(t: AppTile) {
    SoftCard(Modifier.clickable(onClick = t.onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(t.icon, null, tint = t.tint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.titleLarge, color = Cream)
                Text(t.subtitle, color = if (t.subtitle.startsWith("In progress")) Jade else Muted, fontSize = 12.sp)
            }
        }
    }
}
