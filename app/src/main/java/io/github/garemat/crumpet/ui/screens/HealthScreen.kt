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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.garemat.crumpet.data.HealthSnapshot
import io.github.garemat.crumpet.ui.AppViewModel
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Coral
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.Muted
import io.github.garemat.crumpet.ui.theme.Ok

@Composable
fun HealthScreen(vm: AppViewModel) {
    val s by vm.snapshot.collectAsStateWithLifecycle()
    val available = vm.health.available

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
    ) {
        Text("Health", style = MaterialTheme.typography.displaySmall, color = Cream)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(if (available) Ok else Muted))
            Spacer(Modifier.width(7.dp))
            Text(
                if (available) "Reading from Health Connect" else "Health Connect not available",
                color = Muted, fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(18.dp))
        Eyebrow("Nutrition · today")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            BigStat(s.calories?.toString() ?: "—", "kcal")
            Spacer(Modifier.height(12.dp))
            MacroRow("Protein", s.protein, 180, Coral)
            Spacer(Modifier.height(8.dp))
            MacroRow("Carbs", s.carbs, 230, Brass)
            Spacer(Modifier.height(8.dp))
            MacroRow("Fat", s.fat, 65, Jade)
        }

        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Body")
                Spacer(Modifier.height(10.dp))
                SoftCard { BigStat(s.weightKg?.let { "%.1f".format(it) } ?: "—", "kg") }
            }
            Column(Modifier.weight(1f)) {
                Eyebrow("Steps")
                Spacer(Modifier.height(10.dp))
                SoftCard { BigStat(s.steps?.toString() ?: "—", "today") }
            }
        }

        Spacer(Modifier.height(18.dp))
        Eyebrow("Sleep")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            BigStat(
                s.sleepMinutes?.let { "${it / 60}:${(it % 60).toString().padStart(2, '0')}" } ?: "—",
                "last night",
            )
        }

        Spacer(Modifier.height(18.dp))
        Eyebrow("Recent workouts")
        Spacer(Modifier.height(10.dp))
        SoftCard {
            if (s.recentWorkouts.isEmpty()) {
                Text("No workouts synced yet.", color = Muted, fontSize = 13.sp)
            } else {
                s.recentWorkouts.forEachIndexed { i, w ->
                    if (i > 0) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(w.title, color = Cream, style = MaterialTheme.typography.titleMedium)
                            Text(w.detail, color = Faint, fontSize = 11.sp)
                        }
                        Text(w.source, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun BigStat(value: String, unit: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, style = MaterialTheme.typography.displayLarge, color = Cream, fontSize = 30.sp)
        Spacer(Modifier.width(5.dp))
        Text(unit, color = Faint, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
    }
}

@Composable
private fun MacroRow(label: String, value: Int?, target: Int, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Cream, fontSize = 12.sp, modifier = Modifier.width(64.dp))
        Column(Modifier.weight(1f)) {
            Text("${value ?: "—"} / ${target}g", color = Faint, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Meter((value ?: 0) / target.toFloat(), color)
        }
    }
}
