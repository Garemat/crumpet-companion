package io.github.garemat.crumpet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CrumpetColors = darkColorScheme(
    primary = Brass,
    onPrimary = Espresso,
    secondary = Jade,
    onSecondary = Espresso,
    tertiary = Coral,
    background = Espresso,
    onBackground = Cream,
    surface = Bg2,
    onSurface = Cream,
    surfaceVariant = Bg3,
    onSurfaceVariant = Muted,
    outline = Line,
)

@Composable
fun CrumpetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(), // always warm-dark; param kept for previews
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = CrumpetColors,
        typography = CrumpetTypography,
        content = content,
    )
}
