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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.data.HealthSnapshot
import io.github.garemat.crumpet.ui.AppViewModel
import io.github.garemat.crumpet.ui.components.CrumpetAvatar
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Lavender
import io.github.garemat.crumpet.ui.theme.Muted
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun HomeScreen(vm: AppViewModel, onChat: () -> Unit) {
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val agenda by vm.agenda.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()

    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Morning"
        in 12..17 -> "Afternoon"
        else -> "Evening"
    }
    val brief = chat.lastOrNull { it.fromCrumpet }?.text
        ?: "Ready when you are. Sync your health data and I'll fold today's training and prep into your brief."

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CrumpetAvatar(Modifier.size(46.dp))
            Spacer(Modifier.width(13.dp))
            Column {
                Text("$greeting.", style = MaterialTheme.typography.headlineMedium, color = Cream)
                Text(LocalDate.now().toString(), color = Muted, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        SoftCard {
            Text(
                brief,
                style = MaterialTheme.typography.titleLarge,
                color = Cream,
                fontWeight = FontWeight.Medium,
            )
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Up next")
        Spacer(Modifier.height(10.dp))
        val next = agenda.firstOrNull()
        SoftCard {
            if (next == null) {
                Text("Nothing on the calendar yet.", color = Muted)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(next.title, style = MaterialTheme.typography.titleMedium, color = Cream)
                        Text(
                            if (next.allDay) "All day" else timeOf(next.startMillis),
                            color = Muted, fontSize = 12.sp,
                        )
                    }
                    Text("→", color = Brass, fontSize = 20.sp)
                }
            }
        }

        if (agenda.size > 1) {
            Spacer(Modifier.height(18.dp))
            Eyebrow("This week")
            Spacer(Modifier.height(10.dp))
            Column {
                agenda.take(4).drop(1).forEach { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            if (item.allDay) "•" else timeOf(item.startMillis),
                            color = Brass, fontSize = 12.sp, modifier = Modifier.width(64.dp),
                        )
                        Text(item.title, color = Cream, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(Modifier.height(22.dp))
        Eyebrow("Today")
        Spacer(Modifier.height(10.dp))
        StatTiles(snapshot)

        Spacer(Modifier.height(22.dp))
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CrumpetAvatar(Modifier.size(26.dp))
                Spacer(Modifier.width(10.dp))
                Text("Ask Crumpet anything…", color = Faint, modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .size(38.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("→", color = Brass, fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "tap to open chat",
            color = Faint, fontSize = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)),
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun StatTiles(s: HealthSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(11.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Tile("Calories", s.calories?.toString() ?: "—", "kcal", Brass, (s.calories ?: 0) / 2100f, Modifier.weight(1f))
            Tile("Protein", s.protein?.toString() ?: "—", "g", Coral, (s.protein ?: 0) / 180f, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Tile("Steps", s.steps?.toString() ?: "—", "", Jade, (s.steps ?: 0) / 9000f, Modifier.weight(1f))
            Tile("Sleep", s.sleepMinutes?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "—", "", Lavender, (s.sleepMinutes ?: 0) / 480f, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, color: androidx.compose.ui.graphics.Color, frac: Float, modifier: Modifier) {
    SoftCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
            Spacer(Modifier.width(7.dp))
            Text(label, color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall, color = Cream)
            if (unit.isNotEmpty()) {
                Spacer(Modifier.width(4.dp))
                Text(unit, color = Faint, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Meter(frac, color)
    }
}

private fun timeOf(millis: Long): String {
    val t = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}
