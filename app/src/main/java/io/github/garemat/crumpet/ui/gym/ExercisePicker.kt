package io.github.garemat.crumpet.ui.gym

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.gym.Canon
import io.github.garemat.crumpet.ui.screens.Eyebrow
import io.github.garemat.crumpet.ui.theme.Bg2
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Muted

private data class Pick(val name: String, val detail: String)

/** Search your own exercises first (most recently done on top — they're what you use),
 *  then the library (~800, public domain), then "add <what you typed>" for anything else.
 *  Returns the RAW name; callers canonicalise ([Canon.lift]) so keys match the brain. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExercisePicker(vm: GymViewModel, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadLibrary() }

    val q = query.trim().lowercase()
    val mine = state.exercises.entries
        .sortedByDescending { it.value.last?.day ?: "" }
        .filter { q.isEmpty() || q in it.key }
        .map { (name, info) ->
            Pick(name, info.last?.let { l ->
                val top = l.sets.filter { it.type != "warmup" }.maxByOrNull { it.weight ?: 0.0 }
                "last ${l.day.drop(5)} · ${fmtSet(top?.weight, top?.reps)}"
            } ?: "")
        }
    val known = mine.map { it.name }.toSet()
    val lib = if (q.length < 2) emptyList() else library
        .filter { q in it.name.lowercase() && Canon.lift(it.name) !in known }
        .take(40)
        .map { Pick(it.name, listOf(it.equipment, it.primaryMuscles).filter(String::isNotBlank).joinToString(" · ")) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg2,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.9f).padding(horizontal = 18.dp)) {
            TextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text("Search exercises", color = Faint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Bg3, unfocusedContainerColor = Bg3,
                    focusedTextColor = Cream, unfocusedTextColor = Cream,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Brass),
            )
            Spacer(Modifier.height(10.dp))
            LazyColumn(Modifier.fillMaxWidth()) {
                if (q.isNotEmpty() && Canon.lift(query) !in known) {
                    item {
                        Row(Pick(query.trim(), "add as a new exercise"), accent = true) { onPick(query.trim()) }
                    }
                }
                if (mine.isNotEmpty()) {
                    item { Eyebrow("Yours", Modifier.padding(top = 8.dp, bottom = 4.dp)) }
                    items(mine, key = { "m-" + it.name }) { p -> Row(p) { onPick(p.name) } }
                }
                if (lib.isNotEmpty()) {
                    item { Eyebrow("Library", Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    items(lib, key = { "l-" + it.name }) { p -> Row(p) { onPick(p.name) } }
                } else if (q.length < 2 && mine.isEmpty()) {
                    item { Text("Type to search the library.", color = Faint, fontSize = 12.sp) }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable
private fun Row(p: Pick, accent: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 9.dp)) {
        Text(if (accent) "+ ${p.name}" else Canon.pretty(p.name),
            color = if (accent) Brass else Cream, fontSize = 15.sp,
            fontWeight = if (accent) FontWeight.Bold else FontWeight.Normal)
        if (p.detail.isNotBlank()) Text(p.detail, color = Muted, fontSize = 11.5.sp)
    }
}
