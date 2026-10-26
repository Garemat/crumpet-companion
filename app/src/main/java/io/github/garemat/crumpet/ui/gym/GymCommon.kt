package io.github.garemat.crumpet.ui.gym

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.garemat.crumpet.gym.GymTarget
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Faint
import io.github.garemat.crumpet.ui.theme.Hanken

/** 60.0 → "60", 62.5 → "62.5", 20.41 → "20.41". */
fun fmtW(w: Double?): String = when {
    w == null -> ""
    w == Math.floor(w) -> w.toLong().toString()
    else -> "%.2f".format(java.util.Locale.ROOT, w).trimEnd('0').trimEnd('.')
}

fun fmtSet(weight: Double?, reps: Int?): String = when {
    reps == null -> ""
    weight == null -> "$reps reps"
    else -> "${fmtW(weight)} × $reps"
}

/** What the engine's reason means, in a few words for the exercise header. */
fun targetLine(t: GymTarget?): String? {
    t ?: return null
    val what = fmtSet(t.weight, t.reps)
    val why = when {
        t.carriedBy != null -> "holding — ${io.github.garemat.crumpet.gym.Canon.pretty(t.carriedBy)} is climbing"
        t.reason == "crumpet" -> "set by Crumpet"
        t.reason == "progress" -> "top of the range — step up"
        t.reason == "jump" -> "well over the range — jump"
        t.reason == "deload" -> "stalled — lighter week"
        t.reason == "reps" -> if (t.stalled) "stalled — try a variation?" else "add reps"
        else -> ""
    }
    return if (why.isBlank()) "Target $what" else "Target $what · $why"
}

/** A compact numeric cell for the set table. Commits on every valid keystroke (so the
 *  session is saved continuously) but keeps the raw text while focused, so typing "62."
 *  on the way to "62.5" isn't rewritten under the thumb. */
@Composable
fun NumberCell(
    value: String,
    placeholder: String,
    decimal: Boolean,
    done: Boolean,
    modifier: Modifier = Modifier,
    onValue: (String) -> Unit,
) {
    var text by remember { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) { if (!focused) text = value }
    Box(
        modifier.clip(RoundedCornerShape(8.dp))
            .background(if (done) Color.Transparent else Bg3)
            .padding(vertical = 7.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (text.isEmpty()) Text(placeholder, color = Faint, fontSize = 15.sp, textAlign = TextAlign.Center)
        BasicTextField(
            value = text,
            onValueChange = { raw ->
                val clean = raw.replace(',', '.').filter { it.isDigit() || (decimal && it == '.') }
                if (clean.count { it == '.' } <= 1 && clean.length <= 6) { text = clean; onValue(clean) }
            },
            singleLine = true,
            textStyle = TextStyle(color = Cream, fontSize = 15.sp, fontFamily = Hanken,
                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
            cursorBrush = SolidColor(Brass),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Next),
            modifier = Modifier.onFocusChanged { focused = it.isFocused },
        )
    }
}
