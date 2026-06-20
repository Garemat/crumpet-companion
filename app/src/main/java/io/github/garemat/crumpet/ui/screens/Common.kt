package io.github.garemat.crumpet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.garemat.crumpet.ui.theme.Bg2
import io.github.garemat.crumpet.ui.theme.Bg3
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Line

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        color = Brass,
        fontSize = 9.5.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.4.sp,
        modifier = modifier,
    )
}

@Composable
fun SoftCard(
    modifier: Modifier = Modifier,
    fill: Color = Bg2,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .border(1.dp, Line, RoundedCornerShape(22.dp))
            .padding(18.dp),
        content = content,
    )
}

@Composable
fun Meter(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Bg3),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
    }
}

fun ellipsize(s: String, n: Int): String = if (s.length <= n) s else s.take(n - 1) + "…"

val OverflowEllipsis = TextOverflow.Ellipsis
