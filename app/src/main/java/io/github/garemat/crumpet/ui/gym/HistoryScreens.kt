package io.github.garemat.crumpet.ui.gym

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.gym.Canon
import io.github.garemat.crumpet.gym.HistoryOps
import io.github.garemat.crumpet.gym.HistorySession
import io.github.garemat.crumpet.gym.HistorySet
import io.github.garemat.crumpet.ui.screens.Eyebrow
import io.github.garemat.crumpet.ui.screens.SoftCard
import io.github.garemat.crumpet.ui.screens.ellipsize
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Muted
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val IN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun parse(at: String) = runCatching { LocalDateTime.parse(at.take(16), IN) }.getOrNull()
private fun dayLine(s: HistorySession) = (parse(s.at)?.format(DAY) ?: s.at) +
    (s.durationMin?.let { " · ${it} min" } ?: "")

/** Every past session, newest first, by month — the Hevy import, app sessions, and anything
 *  still queued (marked). The newest page is cached, so this opens offline. */
@Composable
fun HistoryScreen(vm: GymViewModel, onOpen: (String) -> Unit) {
    val sessions by vm.history.collectAsStateWithLifecycle()
    val more by vm.historyMore.collectAsStateWithLifecycle()
    val note by vm.historyNote.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Text("History", style = MaterialTheme.typography.headlineMedium, color = Cream)
            if (sessions.isEmpty()) Text("Nothing logged yet — finish a workout and it shows here.",
                color = Faint, fontSize = 12.5.sp)
        }
        var month = ""
        sessions.forEach { s ->
            val m = parse(s.at)?.format(MONTH) ?: ""
            if (m != month) {
                month = m
                item(key = "m-$m") { Eyebrow(m, Modifier.padding(top = 10.dp)) }
            }
            item(key = s.key) { SessionRow(s) { onOpen(s.key) } }
        }
        item {
            if (note.isNotBlank()) Text(note, color = Coral, fontSize = 12.sp)
            if (more) Button(onClick = { vm.loadOlder() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Bg3, contentColor = Brass)) {
                Text("Load older", fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun SessionRow(s: HistorySession, onClick: () -> Unit) {
    val prs = HistoryOps.prCount(s)
    SoftCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, style = MaterialTheme.typography.titleLarge, color = Cream, modifier = Modifier.weight(1f))
            if (s.pending) Text("waiting to send", color = Jade, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(dayLine(s), color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${HistoryOps.setCount(s)} sets", color = Cream, fontSize = 12.5.sp)
            if (prs > 0) {
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Outlined.EmojiEvents, null, tint = Brass, modifier = Modifier.size(13.dp))
                Text(" $prs PR", color = Brass, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(ellipsize(s.exercises.joinToString(", ") { Canon.pretty(it.exercise) }, 110),
            color = Faint, fontSize = 11.5.sp)
    }
}

/** One past session: every exercise and set, PRs marked as they were set. Tap an
 *  exercise for its whole history. */
@Composable
fun SessionDetailScreen(vm: GymViewModel, key: String, onExercise: (String) -> Unit) {
    val sessions by vm.history.collectAsStateWithLifecycle()
    val s = sessions.firstOrNull { it.key == key }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            if (s == null) { Text("That session isn't loaded.", color = Muted); return@item }
            Text(s.title, style = MaterialTheme.typography.headlineMedium, color = Cream)
            Text(dayLine(s) + if (s.pending) " · waiting to send" else "", color = Muted, fontSize = 12.sp)
            if (s.note.isNotBlank()) Text(s.note, color = Cream, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (s != null) items(s.exercises.size) { i ->
            val e = s.exercises[i]
            SoftCard(Modifier.clickable { onExercise(e.exercise) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Canon.pretty(e.exercise), style = MaterialTheme.typography.titleLarge, color = Cream,
                        modifier = Modifier.weight(1f))
                    e.superset?.let { Text("Superset $it", color = Jade, fontSize = 10.5.sp, fontWeight = FontWeight.Bold) }
                }
                if (e.note.isNotBlank()) Text(e.note, color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
                SetLines(e.sets)
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}

@Composable
private fun SetLines(sets: List<HistorySet>) {
    var n = 0
    sets.forEach { set ->
        val label = when (set.type) { "warmup" -> "W"; "drop" -> "D"; "failure" -> "F"; else -> (++n).toString() }
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = when (set.type) { "warmup" -> Brass; "drop" -> Jade; "failure" -> Coral; else -> Muted },
                fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(28.dp))
            Text(fmtSet(set.weight, set.reps), color = Cream, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (set.prs.isNotEmpty()) {
                Icon(Icons.Outlined.EmojiEvents, "PR", tint = Brass, modifier = Modifier.size(13.dp))
                Text(" " + set.prs.joinToString(" · ") { prLabel(it) }, color = Brass, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun prLabel(kind: String) = when (kind) {
    "weight" -> "heaviest"; "e1rm" -> "est. 1RM"; "volume" -> "volume"; "reps" -> "reps"; else -> kind
}

/** One lift over time: its bests up top, then every session it was in. */
@Composable
fun ExerciseHistoryScreen(vm: GymViewModel, name: String, onOpen: (String) -> Unit) {
    LaunchedEffect(name) { vm.loadExercise(name) }
    val loaded by vm.exerciseHistory.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val sessions = if (loaded.first == name) loaded.second else emptyList()
    val info = state.exercises[name]
    val full by vm.history.collectAsStateWithLifecycle()
    val openable = full.map { it.key }.toSet()  // older sessions come back as this lift only
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Text(Canon.pretty(name), style = MaterialTheme.typography.headlineMedium, color = Cream)
            info?.let { i ->
                val b = i.bests
                val parts = listOfNotNull(
                    b.maxWeight?.let { "heaviest ${fmtW(it)} kg" },
                    b.bestE1rm?.let { "est. 1RM ${fmtW(it)} kg" },
                    b.bestVolume?.let { "best set ${fmtW(it)} kg" })
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), color = Brass, fontSize = 12.5.sp)
                targetLine(i.target)?.let { Text(it, color = Muted, fontSize = 12.sp) }
                if (i.pref.note.isNotBlank()) Text(i.pref.note, color = Muted, fontSize = 12.sp)
            }
            if (sessions.isEmpty()) Text("No sessions with this yet.", color = Faint, fontSize = 12.5.sp)
        }
        items(sessions, key = { it.key }) { s ->
            SoftCard(if (s.key in openable) Modifier.clickable { onOpen(s.key) } else Modifier) {
                Text(s.title + " · " + dayLine(s), color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(2.dp))
                SetLines(s.exercises.flatMap { it.sets })
            }
        }
        item { Spacer(Modifier.height(30.dp)) }
    }
}
