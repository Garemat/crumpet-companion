@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package io.github.garemat.crumpet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.garemat.crumpet.R

// Variable-font instances (minSdk 26). Fraunces carries a soft optical-size axis for warmth.
private fun fraunces(w: Int) = Font(
    R.font.fraunces,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(
        FontVariation.weight(w),
        FontVariation.Setting("opsz", 40f),
        FontVariation.Setting("SOFT", 50f),
    ),
)

private fun hanken(w: Int) = Font(
    R.font.hanken_grotesk,
    weight = FontWeight(w),
    variationSettings = FontVariation.Settings(FontVariation.weight(w)),
)

val Fraunces = FontFamily(fraunces(400), fraunces(500), fraunces(600), fraunces(700))
val Hanken = FontFamily(hanken(400), hanken(500), hanken(600), hanken(700))

// Display = Fraunces (characterful serif); everything else = Hanken (clean grotesque).
val CrumpetTypography = Typography(
    displayLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 38.sp),
    displaySmall = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 24.sp),
    headlineMedium = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    titleLarge = TextStyle(fontFamily = Fraunces, fontWeight = FontWeight.SemiBold, fontSize = 19.sp),
    titleMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
    bodyLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
    labelMedium = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.SemiBold, fontSize = 11.sp),
    labelSmall = TextStyle(fontFamily = Hanken, fontWeight = FontWeight.Bold, fontSize = 9.5.sp),
)
