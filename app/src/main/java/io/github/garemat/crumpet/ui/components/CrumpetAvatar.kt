package io.github.garemat.crumpet.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import io.github.garemat.crumpet.ui.theme.Brass
import io.github.garemat.crumpet.ui.theme.Cream
import io.github.garemat.crumpet.ui.theme.Espresso
import io.github.garemat.crumpet.ui.theme.Jade
import io.github.garemat.crumpet.ui.theme.JadeDeep

/** The dapper turtle — a small reactive avatar. Idle = a gentle breathe;
 * [active] (thinking/listening) lifts the breathe a touch. Drawn, not bitmap. */
@Composable
fun CrumpetAvatar(modifier: Modifier = Modifier, active: Boolean = false) {
    val t = rememberInfiniteTransition(label = "breathe")
    val s by t.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.05f else 1.02f,
        animationSpec = infiniteRepeatable(
            tween(if (active) 1600 else 4200), RepeatMode.Reverse,
        ),
        label = "scale",
    )
    Canvas(modifier = modifier) {
        val w = size.width
        val u = w / 100f                 // unit: design is on a 100-box
        val cx = w / 2f
        val cy = size.height / 2f
        fun p(x: Float, y: Float) = Offset(cx + (x - 50f) * u * s, cy + (y - 52f) * u * s)
        fun r(v: Float) = v * u * s

        // top hat
        drawOval(Espresso, topLeft = p(20f, 24f), size = Size(r(60f), r(13f)))
        drawRect(Espresso, topLeft = p(31f, 9f), size = Size(r(38f), r(22f)))
        drawRect(Brass, topLeft = p(31f, 24f), size = Size(r(38f), r(5.5f)))
        // head
        drawCircle(JadeDeep, radius = r(29f), center = p(50f, 62f))
        drawCircle(Jade, radius = r(24f), center = p(46f, 57f))
        // eyes
        drawCircle(Cream, radius = r(7f), center = p(40f, 58f))
        drawCircle(Cream, radius = r(7f), center = p(60f, 58f))
        drawCircle(Espresso, radius = r(3.2f), center = p(41.5f, 59.5f))
        drawCircle(Espresso, radius = r(3.2f), center = p(61.5f, 59.5f))
        // brass monocle — the dapper
        drawCircle(Brass, radius = r(9f), center = p(60f, 58f), style = Stroke(width = r(1.6f)))
        // smile
        drawArc(
            color = Color(0xFF2C5F47),
            startAngle = 20f, sweepAngle = 100f, useCenter = false,
            topLeft = p(42f, 66f), size = Size(r(16f), r(11f)),
            style = Stroke(width = r(2.4f)),
        )
    }
}
