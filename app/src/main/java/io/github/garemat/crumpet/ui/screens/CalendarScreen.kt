package io.github.garemat.crumpet.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.data.CalEvent
import io.github.garemat.crumpet.data.day
import io.github.garemat.crumpet.data.timeLabel
import io.github.garemat.crumpet.ui.AppViewModel
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Muted
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The calendar body: agenda from the offline-first cache (Radicale via the brain is the
 *  source of truth; DAVx5 is gone), quick-add without going through chat, tap-to-delete.
 *  Everything renders from the local cache — offline just means "queued" rows and an older
 *  "synced X ago" line. Design: crumpet docs/backlog/calendar.md phase 2. */
@Composable
fun CalendarScreen(vm: AppViewModel) {
    val events by vm.calEvents.collectAsStateWithLifecycle()
    val lastSync by vm.calLastSync.collectAsStateWithLifecycle()
    val note by vm.calNote.collectAsStateWithLifecycle()
    val connected by vm.connected.collectAsStateWithLifecycle()

    var showAdd by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<CalEvent?>(null) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Calendar", style = MaterialTheme.typography.headlineMedium, color = Cream)
                Text(
                    if (connected) syncedAgo(lastSync) else "Offline — showing what's cached",
                    color = Muted, fontSize = 12.sp,
                )
            }
            TextButton(onClick = { showAdd = true }) {
                Text("+ Add", color = Brass, fontWeight = FontWeight.Bold)
            }
        }

        if (note.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(note, color = Coral, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::clearCalNote) { Text("Dismiss", color = Faint) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (events.isEmpty()) {
            SoftCard {
                Text("Nothing coming up.", color = Muted)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Add something here, or just ask Crumpet in chat — both land on the same calendar.",
                    color = Faint, fontSize = 12.sp,
                )
            }
        } else {
            val grouped = events.groupBy { it.day() }.toList().sortedBy { it.first }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                grouped.forEach { (day, dayEvents) ->
                    item(key = "day-${day}") {
                        Spacer(Modifier.height(10.dp))
                        Eyebrow(dayLabel(day))
                        Spacer(Modifier.height(2.dp))
                    }
                    items(dayEvents, key = { "${it.uid}/${it.recurrenceId ?: it.start}" }) { e ->
                        EventRow(e, onClick = { selected = e })
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }

    if (showAdd) AddEventSheet(onDismiss = { showAdd = false }, vm = vm)

    selected?.let { e ->
        AlertDialog(
            onDismissRequest = { selected = null },
            containerColor = Bg3,
            title = { Text(e.summary, color = Cream) },
            text = {
                Column {
                    Text(
                        (e.day()?.let { dayLabel(it) } ?: "") +
                            (e.timeLabel().takeIf { it.isNotBlank() }?.let { " · $it" } ?: " · all day"),
                        color = Muted, fontSize = 13.sp,
                    )
                    if (e.notes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(e.notes, color = Cream, fontSize = 14.sp)
                    }
                    if (e.pending) {
                        Spacer(Modifier.height(8.dp))
                        Text("Queued — lands on the calendar when Crumpet's reachable.", color = Jade, fontSize = 12.sp)
                    }
                    if (e.recurring && !e.pending) {
                        Spacer(Modifier.height(8.dp))
                        Text("Repeats — deleting removes every occurrence.", color = Coral, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.deleteCalendarEvent(e.uid); selected = null }) {
                    Text(if (e.pending) "Remove" else "Delete", color = Coral, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("Close", color = Faint) }
            },
        )
    }
}

@Composable
private fun EventRow(e: CalEvent, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            e.timeLabel().ifBlank { "•" },
            color = Brass, fontSize = 12.sp, modifier = Modifier.width(56.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(e.summary, color = Cream, fontSize = 14.sp)
            if (e.notes.isNotBlank()) {
                Text(ellipsize(e.notes.replace('\n', ' '), 64), color = Faint, fontSize = 11.sp)
            }
        }
        if (e.recurring) Text("↻", color = Muted, fontSize = 13.sp)
        if (e.pending) {
            Spacer(Modifier.width(6.dp))
            Text("queued", color = Jade, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddEventSheet(onDismiss: () -> Unit, vm: AppViewModel) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var allDay by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now()) }
    var time by remember { mutableStateOf("") }       // "HH:MM", "" = unset
    var endTime by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }      // "", daily, weekly, monthly, weekdays
    var repeatDays by remember { mutableStateOf(setOf<String>()) }
    var until by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf("") }     // date | time | end | until

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Bg3) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 28.dp),
        ) {
            Eyebrow("Add to calendar")
            Spacer(Modifier.height(12.dp))
            SheetField("What is it?", title) { title = it }
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                PickerPill(date.format(DateTimeFormatter.ofPattern("EEE d MMM"))) { picking = "date" }
                Spacer(Modifier.width(8.dp))
                if (!allDay) {
                    PickerPill(time.ifBlank { "Time" }) { picking = "time" }
                    Spacer(Modifier.width(8.dp))
                    if (time.isNotBlank()) {
                        PickerPill(endTime.ifBlank { "End?" }) { picking = "end" }
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("All day", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                Switch(checked = allDay, onCheckedChange = { allDay = it })
            }

            Spacer(Modifier.height(8.dp))
            SheetField("Notes (optional)", notes, singleLine = false) { notes = it }

            Spacer(Modifier.height(14.dp))
            Eyebrow("Repeats")
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf("" to "Once", "daily" to "Daily", "weekly" to "Weekly",
                       "monthly" to "Monthly", "weekdays" to "Mon–Fri").forEach { (value, label) ->
                    RepeatChip(label, repeat == value) {
                        repeat = value
                        if (value != "weekly") repeatDays = emptySet()
                    }
                }
            }
            if (repeat == "weekly") {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun").forEach { d ->
                        RepeatChip(d.replaceFirstChar { it.uppercase() }.take(2), d in repeatDays) {
                            repeatDays = if (d in repeatDays) repeatDays - d else repeatDays + d
                        }
                    }
                }
            }
            if (repeat.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PickerPill(
                        until?.format(DateTimeFormatter.ofPattern("'until' d MMM")) ?: "Until (optional)",
                    ) { picking = "until" }
                    if (until != null) {
                        TextButton(onClick = { until = null }) { Text("clear", color = Faint, fontSize = 11.sp) }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            val ready = title.isNotBlank() && (allDay || time.isNotBlank())
            Button(
                onClick = {
                    vm.addCalendarEvent(
                        summary = title,
                        start = if (allDay) "$date" else "$date $time",
                        end = if (!allDay && endTime.isNotBlank()) "$date $endTime" else "",
                        allDay = allDay,
                        notes = notes,
                        repeat = repeat,
                        repeatDays = repeatDays.toList(),
                        repeatUntil = until?.toString() ?: "",
                    )
                    onDismiss()
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Brass, contentColor = Color.Black),
            ) { Text(if (allDay) "Add" else "Add" + (time.takeIf { it.isNotBlank() }?.let { " at $it" } ?: ""), fontWeight = FontWeight.Bold) }
            Text(
                "Goes straight to your real calendar — offline it queues and sends itself later.",
                color = Faint, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
    }

    when (picking) {
        "date", "until" -> {
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (if (picking == "until") until ?: date else date)
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            )
            DatePickerDialog(
                onDismissRequest = { picking = "" },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let {
                            val picked = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                            if (picking == "until") until = picked else date = picked
                        }
                        picking = ""
                    }) { Text("OK", color = Brass) }
                },
            ) { DatePicker(state = state) }
        }
        "time", "end" -> {
            val state = rememberTimePickerState(is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = "" },
                containerColor = Bg3,
                text = { TimePicker(state = state) },
                confirmButton = {
                    TextButton(onClick = {
                        val hhmm = "%02d:%02d".format(state.hour, state.minute)
                        if (picking == "end") endTime = hhmm else time = hhmm
                        picking = ""
                    }) { Text("OK", color = Brass) }
                },
                dismissButton = {
                    TextButton(onClick = { picking = "" }) { Text("Cancel", color = Faint) }
                },
            )
        }
    }
}

@Composable
private fun SheetField(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 12.sp) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Bg3.copy(alpha = 0.5f),
            unfocusedContainerColor = Bg3.copy(alpha = 0.5f),
            focusedTextColor = Cream,
            unfocusedTextColor = Cream,
            focusedLabelColor = Brass,
            unfocusedLabelColor = Faint,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun PickerPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Brass, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun RepeatChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 11.sp) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Brass,
            selectedLabelColor = Color.Black,
            labelColor = Muted,
        ),
    )
}

private fun dayLabel(day: LocalDate?): String {
    day ?: return "Sometime"
    val today = LocalDate.now()
    val prefix = when (day) {
        today -> "Today — "
        today.plusDays(1) -> "Tomorrow — "
        else -> ""
    }
    return prefix + day.format(DateTimeFormatter.ofPattern("EEE d MMM"))
}

private fun syncedAgo(at: Long): String {
    if (at == 0L) return "Not synced yet"
    val mins = (System.currentTimeMillis() - at) / 60_000
    return when {
        mins < 1 -> "Synced just now"
        mins < 60 -> "Synced $mins min ago"
        mins < 48 * 60 -> "Synced ${mins / 60}h ago"
        else -> "Synced " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM"))
    }
}
