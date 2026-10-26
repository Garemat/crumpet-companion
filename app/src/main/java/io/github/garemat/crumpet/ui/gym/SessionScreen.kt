package io.github.garemat.crumpet.ui.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.gym.ActiveExercise
import io.github.garemat.crumpet.gym.ActiveSet
import io.github.garemat.crumpet.gym.Canon
import io.github.garemat.crumpet.gym.ExerciseInfo
import io.github.garemat.crumpet.gym.ExercisePref
import io.github.garemat.crumpet.gym.LastSet
import io.github.garemat.crumpet.gym.SessionOps
import io.github.garemat.crumpet.ui.theme.Bg2
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Espresso
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Line
import io.github.garemat.crumpet.ui.theme.Muted

/** The in-gym screen: one card per exercise, a row per set — type, last time, kg, reps,
 *  tick. Ticking judges PRs on the spot (offline), every edit is persisted immediately,
 *  and Finish hands the ticked sets to the outbox. */
@Composable
fun SessionScreen(vm: GymViewModel, onExit: () -> Unit) {
    val session by vm.active.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val now by vm.now.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var confirmFinish by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val s = session
    LaunchedEffect(s == null) { if (s == null) onExit() }
    if (s == null) return

    val ticked = s.exercises.sumOf { ex -> ex.sets.count { it.done } }
    val prs = s.exercises.sumOf { ex -> ex.sets.count { it.prs.isNotEmpty() } }

    Column(Modifier.fillMaxSize().background(Espresso)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.headlineMedium, color = Cream)
                Text(
                    "${elapsed(now - s.startedMillis)} · $ticked sets" + if (prs > 0) " · $prs PR" else "",
                    color = if (prs > 0) Brass else Muted, fontSize = 12.sp,
                )
            }
            Button(onClick = { confirmFinish = true },
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Espresso)) {
                Text("Finish", fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.exercises, key = { it.key }) { ex ->
                ExerciseCard(vm, ex, state.exercises[ex.exercise], first = ex.key == s.exercises.first().key,
                    last = ex.key == s.exercises.last().key)
            }
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Bg3, contentColor = Brass)) {
                        Text("+ Add exercise", fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { confirmDiscard = true }) { Text("Discard workout", color = Coral) }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }

    if (picking) ExercisePicker(vm, onDismiss = { picking = false }) { vm.addExercise(it); picking = false }

    if (confirmFinish) {
        AlertDialog(
            onDismissRequest = { confirmFinish = false }, containerColor = Bg3,
            title = { Text(if (ticked > 0) "Finish workout?" else "Nothing ticked", color = Cream) },
            text = {
                Text(if (ticked > 0) "$ticked sets go to Crumpet — now, or as soon as there's signal."
                     else "Only ticked sets are saved. Tick what you did, or discard this workout.",
                     color = Muted)
            },
            confirmButton = {
                if (ticked > 0) TextButton(onClick = { confirmFinish = false; vm.finish { } }) {
                    Text("Finish", color = Brass, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmFinish = false }) { Text("Keep going", color = Faint) } },
        )
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false }, containerColor = Bg3,
            title = { Text("Discard this workout?", color = Cream) },
            text = { Text("Nothing from it will be saved.", color = Muted) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; vm.discard { } }) {
                    Text("Discard", color = Coral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Cancel", color = Faint) } },
        )
    }
}

private fun elapsed(ms: Long): String {
    val m = (ms / 60000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

@Composable
private fun ExerciseCard(vm: GymViewModel, ex: ActiveExercise, info: ExerciseInfo?, first: Boolean, last: Boolean) {
    var menu by remember { mutableStateOf(false) }
    var editNote by remember { mutableStateOf(false) }
    var editPref by remember { mutableStateOf(false) }
    val sticky = info?.pref?.note.orEmpty()

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Bg2)
            .border(1.dp, if (ex.superset != null) Jade.copy(alpha = 0.5f) else Line, RoundedCornerShape(18.dp))
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Canon.pretty(ex.exercise), style = MaterialTheme.typography.titleLarge, color = Cream,
                        modifier = Modifier.weight(1f, fill = false))
                    ex.superset?.let {
                        Spacer(Modifier.width(8.dp))
                        Text("Superset $it", color = Jade, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
                targetLine(ex.target)?.let {
                    val warn = ex.target?.stalled == true || ex.target?.reason == "deload"
                    Text(it, color = if (warn) Coral else Brass, fontSize = 11.5.sp)
                }
                if (sticky.isNotBlank()) Text(sticky, color = Muted, fontSize = 12.sp, fontStyle = FontStyle.Italic)
                if (ex.note.isNotBlank()) Text(ex.note, color = Cream, fontSize = 12.sp)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More", tint = Faint) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Bg3) {
                    DropdownMenuItem(text = { Text("Note for today", color = Cream) },
                        onClick = { menu = false; editNote = true })
                    DropdownMenuItem(text = { Text("Exercise settings", color = Cream) },
                        onClick = { menu = false; editPref = true })
                    if (!first) DropdownMenuItem(text = { Text("Move up", color = Cream) },
                        onClick = { menu = false; vm.edit { SessionOps.moveExercise(it, ex.key, -1) } })
                    if (!last) DropdownMenuItem(text = { Text("Move down", color = Cream) },
                        onClick = { menu = false; vm.edit { SessionOps.moveExercise(it, ex.key, 1) } })
                    DropdownMenuItem(text = { Text("Remove exercise", color = Coral) },
                        onClick = { menu = false; vm.edit { SessionOps.removeExercise(it, ex.key) } })
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Header("SET", Modifier.width(34.dp))
            Header("PREVIOUS", Modifier.weight(1.3f))
            Header("KG", Modifier.weight(1f))
            Header("REPS", Modifier.weight(0.9f))
            Spacer(Modifier.width(44.dp))
        }
        val previous = matchPrevious(ex.sets, info?.last?.sets.orEmpty())
        var workingNo = 0
        ex.sets.forEachIndexed { i, set ->
            val label = when (set.type) {
                "warmup" -> "W"; "drop" -> "D"; "failure" -> "F"
                else -> (++workingNo).toString()
            }
            SetRow(vm, ex, set, label, previous[i])
        }

        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            TextButton(onClick = { vm.edit { SessionOps.addSet(it, ex.key) } }) {
                Text("+ Set", color = Brass, fontWeight = FontWeight.Bold)
            }
            if (vm.dropOk(ex.exercise) && ex.sets.isNotEmpty()) {
                TextButton(onClick = { vm.edit { SessionOps.addDrop(it, ex.key, vm.increment(ex.exercise)) } }) {
                    Text("+ Drop set", color = Jade, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    if (editNote) TextDialog("Note for today", ex.note, onDismiss = { editNote = false }) { text ->
        vm.edit { SessionOps.editExercise(it, ex.key, note = text) }
    }
    if (editPref) PrefDialog(ex.exercise, info?.pref ?: ExercisePref(), vm.dropOk(ex.exercise), ex.restS,
        onDismiss = { editPref = false }) { pref, rest ->
        vm.savePref(ex.exercise, pref)
        vm.edit { SessionOps.editExercise(it, ex.key, restS = rest) }
    }
}

/** Last session's set for each row: the k-th warm-up matches the k-th warm-up, the k-th
 *  drop the k-th drop, the k-th working set the k-th working/failure set. */
private fun matchPrevious(sets: List<ActiveSet>, last: List<LastSet>): List<LastSet?> {
    fun group(t: String) = when (t) { "warmup" -> "w"; "drop" -> "d"; else -> "x" }
    val pools = last.groupBy { group(it.type) }
    val seen = HashMap<String, Int>()
    return sets.map { s ->
        val g = group(s.type)
        val n = seen.getOrDefault(g, 0); seen[g] = n + 1
        pools[g]?.getOrNull(n)
    }
}

@Composable
private fun Header(text: String, modifier: Modifier) =
    Text(text, color = Faint, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp,
        textAlign = TextAlign.Center, modifier = modifier)

@Composable
private fun SetRow(vm: GymViewModel, ex: ActiveExercise, set: ActiveSet, label: String, prev: LastSet?) {
    var menu by remember { mutableStateOf(false) }
    val done = set.done
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 0.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (done) Jade.copy(alpha = 0.16f) else Color.Transparent)
            .padding(end = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(34.dp)) {
            Text(label, color = when (set.type) { "warmup" -> Brass; "drop" -> Jade; "failure" -> Coral; else -> Cream },
                fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 8.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Bg3) {
                listOf("warmup" to "Warm-up", "working" to "Working", "failure" to "To failure").forEach { (t, l) ->
                    DropdownMenuItem(text = { Text(l, color = if (t == set.type) Brass else Cream) },
                        onClick = { menu = false; vm.setType(ex.key, set.key, ex.exercise, t) })
                }
                DropdownMenuItem(text = { Text("Delete set", color = Coral) },
                    onClick = { menu = false; vm.edit { SessionOps.removeSet(it, ex.key, set.key) } })
            }
        }
        Column(Modifier.weight(1.3f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(prev?.let { fmtSet(it.weight, it.reps) } ?: "—", color = Faint, fontSize = 12.5.sp)
            if (set.prs.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.EmojiEvents, "PR", tint = Brass, modifier = Modifier.size(12.dp))
                    Text(" PR", color = Brass, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        NumberCell(fmtW(set.weight), "kg", decimal = true, done = done, modifier = Modifier.weight(1f).padding(horizontal = 3.dp)) { t ->
            vm.setNumbers(ex.key, set.key, ex.exercise, t.toDoubleOrNull(), null, clearWeight = t.isEmpty(), clearReps = false)
        }
        NumberCell(set.reps?.toString() ?: "", "reps", decimal = false, done = done, modifier = Modifier.weight(0.9f).padding(horizontal = 3.dp)) { t ->
            vm.setNumbers(ex.key, set.key, ex.exercise, null, t.toIntOrNull(), clearWeight = false, clearReps = t.isEmpty())
        }
        Spacer(Modifier.width(6.dp))
        val canTick = done || (set.reps ?: 0) > 0
        Box(
            Modifier.size(38.dp).clip(CircleShape)
                .background(if (done) Jade else Bg3)
                .clickable(enabled = canTick) { vm.toggle(ex.key, set.key, ex.exercise) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Check, if (done) "Done" else "Tick",
                tint = if (done) Espresso else if (canTick) Muted else Faint.copy(alpha = 0.4f))
        }
    }
}

@Composable
fun TextDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Bg3,
        title = { Text(title, color = Cream) },
        text = {
            TextField(text, { text = it.take(200) }, modifier = Modifier.fillMaxWidth(),
                colors = fieldColors())
        },
        confirmButton = { TextButton(onClick = { onSave(text.trim()); onDismiss() }) { Text("Save", color = Brass) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) } },
    )
}

/** Per-exercise settings that stick across sessions (they sync to the brain): the sticky
 *  note ("seat on 4"), the weight step this machine moves in, whether drop sets are offered,
 *  and default rest. */
@Composable
private fun PrefDialog(exercise: String, pref: ExercisePref, dropOk: Boolean, rest: Int,
                       onDismiss: () -> Unit, onSave: (ExercisePref, Int) -> Unit) {
    var note by remember { mutableStateOf(pref.note) }
    var step by remember { mutableStateOf(fmtW(pref.increment)) }
    var drops by remember { mutableStateOf(dropOk) }
    var restText by remember { mutableStateOf((pref.restS ?: rest).toString()) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Bg3,
        title = { Text(Canon.pretty(exercise), color = Cream) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TextField(note, { note = it.take(200) }, label = { Text("Always-shown note", color = Faint) },
                    placeholder = { Text("e.g. seat on 4", color = Faint) }, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextField(step, { step = it.filter { c -> c.isDigit() || c == '.' }.take(5) },
                        label = { Text("Weight step kg", color = Faint) }, colors = fieldColors(),
                        modifier = Modifier.weight(1f), singleLine = true)
                    TextField(restText, { restText = it.filter(Char::isDigit).take(3) },
                        label = { Text("Rest s", color = Faint) }, colors = fieldColors(),
                        modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Offer drop sets", color = Cream, modifier = Modifier.weight(1f))
                    Switch(drops, { drops = it }, colors = SwitchDefaults.colors(checkedTrackColor = Jade))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val restS = restText.toIntOrNull()?.coerceIn(0, 900) ?: rest
                onSave(pref.copy(note = note.trim(), increment = step.toDoubleOrNull()?.takeIf { it >= 0.25 },
                    dropOk = drops, restS = restS), restS)
                onDismiss()
            }) { Text("Save", color = Brass) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Faint) } },
    )
}

@Composable
fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Bg2, unfocusedContainerColor = Bg2,
    focusedTextColor = Cream, unfocusedTextColor = Cream,
    focusedIndicatorColor = Brass, unfocusedIndicatorColor = Line, cursorColor = Brass,
)
