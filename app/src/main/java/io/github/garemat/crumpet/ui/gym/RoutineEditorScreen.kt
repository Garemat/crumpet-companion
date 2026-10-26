package io.github.garemat.crumpet.ui.gym

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
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
import io.github.garemat.crumpet.gym.RoutineExercise
import io.github.garemat.crumpet.gym.RoutineSet
import io.github.garemat.crumpet.ui.screens.SoftCard
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Espresso
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Muted

/** A routine template: which exercises, in what order, how many warm-up and working sets,
 *  the rep range the progression engine works in, rest, and superset letters. Weights
 *  are deliberately absent — the engine fills them from history at start. */
@Composable
fun RoutineEditorScreen(vm: GymViewModel, routineId: String, onDone: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val original = remember(routineId) {
        state.routines.firstOrNull { it.id == routineId && !it.deleted }
            ?: Routine(vm.newRoutineId(), "", position = state.routines.size)
    }
    var name by remember { mutableStateOf(original.name) }
    var body by remember { mutableStateOf(original.body) }
    var picking by remember { mutableStateOf(false) }

    fun set(i: Int, f: (RoutineExercise) -> RoutineExercise) {
        body = body.mapIndexed { j, e -> if (j == i) f(e) else e }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (original.name.isBlank()) "New routine" else "Edit routine",
                style = MaterialTheme.typography.headlineMedium, color = Cream, modifier = Modifier.weight(1f))
            TextButton(onClick = onDone) { Text("Cancel", color = Faint) }
            Button(
                enabled = name.isNotBlank() && body.isNotEmpty(),
                onClick = { vm.saveRoutine(original.copy(name = name.trim(), body = body), onDone) },
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Espresso),
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        }
        TextField(name, { name = it.take(60) }, label = { Text("Name", color = Faint) },
            placeholder = { Text("e.g. Push", color = Faint) }, singleLine = true,
            colors = fieldColors(), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(body, key = { i, e -> "$i-${e.exercise}" }) { i, e ->
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Canon.pretty(e.exercise), style = MaterialTheme.typography.titleLarge, color = Cream,
                            modifier = Modifier.weight(1f))
                        if (i > 0) TextButton(onClick = {
                            body = body.toMutableList().apply { add(i - 1, removeAt(i)) }
                        }) { Text("↑", color = Muted) }
                        if (i < body.lastIndex) TextButton(onClick = {
                            body = body.toMutableList().apply { add(i + 1, removeAt(i)) }
                        }) { Text("↓", color = Muted) }
                        TextButton(onClick = { body = body.filterIndexed { j, _ -> j != i } }) { Text("✕", color = Coral) }
                    }
                    val warm = e.sets.count { it.type == "warmup" }
                    val work = e.sets.size - warm
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Warm-up", warm.toString(), Modifier.weight(1f)) { v ->
                            v.toIntOrNull()?.let { n -> set(i) { it.copy(sets = sets(n, work)) } }
                        }
                        Field("Sets", work.toString(), Modifier.weight(1f)) { v ->
                            v.toIntOrNull()?.takeIf { it >= 1 }?.let { n -> set(i) { it.copy(sets = sets(warm, n)) } }
                        }
                        Field("Reps from", e.repMin.toString(), Modifier.weight(1f)) { v ->
                            v.toIntOrNull()?.let { n -> set(i) { it.copy(repMin = n.coerceIn(1, 100), repMax = maxOf(it.repMax, n)) } }
                        }
                        Field("to", e.repMax.toString(), Modifier.weight(1f)) { v ->
                            v.toIntOrNull()?.let { n -> set(i) { it.copy(repMax = n.coerceIn(it.repMin, 100)) } }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Field("Rest s", e.restS.toString(), Modifier.weight(1f)) { v ->
                            v.toIntOrNull()?.let { n -> set(i) { it.copy(restS = n.coerceIn(0, 900)) } }
                        }
                        Field("Superset", e.superset ?: "", Modifier.weight(1f), letters = true) { v ->
                            set(i) { it.copy(superset = v.take(2).uppercase().ifBlank { null }) }
                        }
                        Spacer(Modifier.weight(2f))
                    }
                }
            }
            item {
                Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Bg3, contentColor = Brass)) {
                    Text("+ Add exercise", fontWeight = FontWeight.Bold)
                }
                Text("Weights aren't set here — Crumpet's progression engine fills them from your history " +
                    "when you start. Same superset letter = alternate those exercises.",
                    color = Faint, fontSize = 11.5.sp, modifier = Modifier.padding(vertical = 10.dp))
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    if (picking) ExercisePicker(vm, onDismiss = { picking = false }) { raw ->
        body = body + RoutineExercise(Canon.lift(raw))
        picking = false
    }
}

private fun sets(warm: Int, work: Int): List<RoutineSet> =
    List(warm.coerceIn(0, 5)) { RoutineSet("warmup") } + List(work.coerceIn(1, 12)) { RoutineSet("working") }

@Composable
private fun Field(label: String, value: String, modifier: Modifier, letters: Boolean = false, onValue: (String) -> Unit) {
    // Local text so clearing a field to retype it doesn't snap back; callers only commit
    // values that parse.
    var text by remember { mutableStateOf(value) }
    Column(modifier) {
        Text(label, color = Faint, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        TextField(text, { t ->
            text = if (letters) t.filter(Char::isLetter).take(2) else t.filter(Char::isDigit).take(3)
            onValue(text)
        }, singleLine = true, colors = fieldColors(), modifier = Modifier.fillMaxWidth())
    }
}
