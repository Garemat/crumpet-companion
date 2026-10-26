package io.github.garemat.crumpet.ui.gym

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.gym.Canon
import io.github.garemat.crumpet.gym.Routine
import io.github.garemat.crumpet.ui.screens.Eyebrow
import io.github.garemat.crumpet.ui.screens.SoftCard
import io.github.garemat.crumpet.ui.screens.ellipsize
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Espresso
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Muted

/** Gym home: resume a running session, start Crumpet's day card or a saved routine, or
 *  free-log. Renders from the offline cache; the line under the title says how fresh it is
 *  and how much is still queued. */
@Composable
fun GymHomeScreen(
    vm: GymViewModel,
    onSession: () -> Unit,
    onEditRoutine: (String) -> Unit,
    onHistory: (() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val outbox by vm.outbox.collectAsStateWithLifecycle()
    val meta by vm.meta.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<Routine?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    val routines = state.routines.filterNot { it.deleted }
    val card = state.card?.takeIf { it.status == "planned" && it.body.isNotEmpty() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Gym", style = MaterialTheme.typography.headlineMedium, color = Cream)
                    Text(syncLine(meta.lastSyncMillis, outbox.size, now), color = Muted, fontSize = 12.sp)
                }
                onHistory?.let { TextButton(onClick = it) { Text("History", color = Brass, fontWeight = FontWeight.Bold) } }
            }
        }
        if (meta.note.isNotBlank()) item {
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(meta.note, color = if (meta.note.startsWith("Couldn't")) Coral else Jade,
                        fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearNote() }) { Text("Dismiss", color = Faint) }
                }
            }
        }
        active?.let { s ->
            item {
                SoftCard(fill = Bg3) {
                    Eyebrow("In progress")
                    Text(s.title, style = MaterialTheme.typography.titleLarge, color = Cream)
                    Text("${s.exercises.sumOf { e -> e.sets.count { it.done } }} sets ticked", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onSession, colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Espresso)) {
                        Text("Resume", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (active == null) {
            card?.let { c ->
                item {
                    SoftCard {
                        Eyebrow("Today from Crumpet")
                        Text(c.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge, color = Cream)
                        Text(summary(c), color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.start(c, onSession) },
                            colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Espresso)) {
                            Text("Start", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.startEmpty(onSession) }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Bg3, contentColor = Cream)) {
                        Text("Empty workout")
                    }
                    Button(onClick = { onEditRoutine("new") }, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Bg3, contentColor = Brass)) {
                        Text("+ New routine", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Eyebrow("Routines", Modifier.padding(top = 8.dp)) }
        if (routines.isEmpty()) item {
            Text("No routines yet. Make one here, or ask Crumpet to draft one from your last few sessions.",
                color = Faint, fontSize = 12.5.sp)
        }
        items(routines, key = { it.id }) { r ->
            RoutineRow(r, startEnabled = active == null,
                onStart = { vm.start(r, onSession) }, onEdit = { onEditRoutine(r.id) }, onDelete = { deleting = r })
        }
        item { Spacer(Modifier.height(30.dp)) }
    }

    deleting?.let { r ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = Bg3,
            title = { Text("Delete ${r.name}?", color = Cream) },
            text = { Text("Your logged sessions stay — only the template goes.", color = Muted) },
            confirmButton = { TextButton(onClick = { vm.deleteRoutine(r.id); deleting = null }) { Text("Delete", color = Coral) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = Faint) } },
        )
    }
}

private fun summary(r: Routine): String =
    ellipsize("${r.body.size} exercises · " + r.body.joinToString(", ") { Canon.pretty(it.exercise) }, 90)

private fun syncLine(last: Long, queued: Int, now: Long): String {
    val ago = if (last == 0L) "not synced yet" else {
        val m = (now - last) / 60000
        "synced " + when { m < 1 -> "just now"; m < 60 -> "${m}m ago"; m < 1440 -> "${m / 60}h ago"; else -> "${m / 1440}d ago" }
    }
    return if (queued > 0) "$ago · $queued waiting to send" else ago
}

@Composable
private fun RoutineRow(r: Routine, startEnabled: Boolean, onStart: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(r.name, style = MaterialTheme.typography.titleLarge, color = Cream)
                Text(summary(r), color = Muted, fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = Faint) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Bg3) {
                    DropdownMenuItem(text = { Text("Edit", color = Cream) }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete", color = Coral) }, onClick = { menu = false; onDelete() })
                }
            }
        }
        if (startEnabled) {
            Spacer(Modifier.height(6.dp))
            Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Espresso)) {
                Text("Start", fontWeight = FontWeight.Bold)
            }
        }
    }
}
