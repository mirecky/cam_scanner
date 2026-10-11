package sk.mirecky.skener.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import sk.mirecky.skener.R

/** Farby podľa schváleného návrhu: tmavý grafit a šafranový akcent („svetlo skenera“). */
object SkenerColors {
    val Ink = Color(0xFF0F1216)
    val Panel = Color(0xFF181C22)
    val Raise = Color(0xFF222730)
    val Line = Color(0xFF2E3440)
    val Text = Color(0xFFEEF1F5)
    val Muted = Color(0xFF9AA3B2)
    val Accent = Color(0xFFF2B33D)
    val AccentInk = Color(0xFF1B1406)
    val Ok = Color(0xFF5FCF8F)
    val Info = Color(0xFF6AA8FF)
    val Danger = Color(0xFFFF8A80)
}

val Onest = FontFamily(
    Font(R.font.onest_regular, FontWeight.Normal),
    Font(R.font.onest_medium, FontWeight.Medium),
    Font(R.font.onest_semibold, FontWeight.SemiBold),
    Font(R.font.onest_bold, FontWeight.Bold),
)

private val colors = darkColorScheme(
    primary = SkenerColors.Accent,
    onPrimary = SkenerColors.AccentInk,
    primaryContainer = SkenerColors.Accent,
    onPrimaryContainer = SkenerColors.AccentInk,
    secondary = SkenerColors.Info,
    secondaryContainer = SkenerColors.Raise,
    onSecondaryContainer = SkenerColors.Text,
    background = SkenerColors.Ink,
    onBackground = SkenerColors.Text,
    surface = SkenerColors.Ink,
    onSurface = SkenerColors.Text,
    surfaceVariant = SkenerColors.Raise,
    onSurfaceVariant = SkenerColors.Muted,
    surfaceContainerLowest = SkenerColors.Ink,
    surfaceContainerLow = SkenerColors.Panel,
    surfaceContainer = SkenerColors.Panel,
    surfaceContainerHigh = SkenerColors.Raise,
    surfaceContainerHighest = SkenerColors.Raise,
    outline = SkenerColors.Line,
    outlineVariant = SkenerColors.Line,
    error = SkenerColors.Danger,
    errorContainer = Color(0xFF5C1F1A),
)

private fun Typography.withFont(family: FontFamily): Typography {
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}

@Composable
fun SkenerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = Typography().withFont(Onest), content = content)
}
