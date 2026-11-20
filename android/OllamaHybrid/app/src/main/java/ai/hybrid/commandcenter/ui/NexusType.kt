package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.R
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Typography: Exo 2 for headings, buttons and the wordmark, Inter for
 * reading, JetBrains Mono for HUD labels — all with full Cyrillic.
 * Fonts ship inside the APK (OFL 1.1, see third_party_licenses/).
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

private fun inter(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = InterFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

private fun display(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = Exo2Family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

private fun mono(size: Double, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = MonoFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

/**
 * Exo 2 for headings and buttons (futuristic, full Cyrillic), Inter for
 * reading, JetBrains Mono for the small HUD labels.
 */
val NexusTypography = Typography(
    displayLarge = display(40, 46, FontWeight.Bold, 0.5),
    displayMedium = display(34, 40, FontWeight.Bold, 0.4),
    displaySmall = display(28, 34, FontWeight.Bold, 0.3),
    headlineLarge = display(26, 32, FontWeight.Bold, 0.3),
    headlineMedium = display(22, 28, FontWeight.Bold, 0.3),
    headlineSmall = display(20, 26, FontWeight.SemiBold, 0.3),
    titleLarge = display(18, 24, FontWeight.SemiBold, 0.3),
    titleMedium = display(16, 22, FontWeight.SemiBold, 0.2),
    titleSmall = inter(14, 20, FontWeight.Medium),
    bodyLarge = inter(16, 24, FontWeight.Normal),
    bodyMedium = inter(14, 20, FontWeight.Normal),
    bodySmall = inter(12, 16, FontWeight.Normal),
    labelLarge = display(14, 20, FontWeight.SemiBold, 0.6),
    labelMedium = mono(11.5, 16, FontWeight.Medium, 0.2),
    labelSmall = mono(10.5, 14, FontWeight.Medium, 0.4)
)

/** Extra styles the Material scale has no slot for. */
object NexusText {
    val Wordmark = TextStyle(
        fontFamily = Exo2Family,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
        letterSpacing = 6.sp,
        brush = NexusBrushes.Wordmark
    )

    /** Big numbers: tabular figures so values do not jump while updating. */
    val Metric = TextStyle(
        fontFamily = Exo2Family,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum"
    )

    /** Small uppercase eyebrow, used sparingly above key numbers. */
    val Eyebrow = TextStyle(
        fontFamily = MonoFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp,
        letterSpacing = 1.2.sp
    )
}

private val NexusColorScheme = darkColorScheme(
    primary = NexusColors.NeonCyan,
    onPrimary = Color(0xFF00141A),
    primaryContainer = Color(0xFF0B2A36),
    onPrimaryContainer = Color(0xFFCFFCFF),
    secondary = NexusColors.NeonCyan,
    onSecondary = Color(0xFF00222B),
    tertiary = NexusColors.NeonMagenta,
    background = NexusColors.Void,
    onBackground = NexusColors.TextPrimary,
    surface = NexusColors.DeepSurface,
    onSurface = NexusColors.TextPrimary,
    surfaceVariant = NexusColors.RaisedSurface,
    onSurfaceVariant = NexusColors.TextSecondary,
    surfaceContainer = NexusColors.RaisedSurface,
    surfaceContainerHigh = NexusColors.ElevatedSurface,
    surfaceContainerHighest = NexusColors.ElevatedSurface,
    surfaceContainerLow = NexusColors.DeepSurface,
    outline = Color(0x6600E5FF),
    outlineVariant = Color(0x2400E5FF),
    error = NexusColors.SignalRed,
    onError = Color(0xFF2B0710)
)

private val NexusMaterialShapes = Shapes(
    extraSmall = CutCornerShape(5.dp),
    small = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
    medium = CutCornerShape(topStart = 12.dp, bottomEnd = 12.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
    extraLarge = CutCornerShape(topStart = 20.dp, bottomEnd = 20.dp)
)

/**
 * The single theme host for the app and for screenshot tests: colours,
 * type, shapes and the effects controller.
 */
@Composable
fun NexusTheme(
    effects: EffectsController = rememberEffectsController(),
    content: @Composable () -> Unit
) {
    MaterialTheme(colorScheme = NexusColorScheme, typography = NexusTypography, shapes = NexusMaterialShapes) {
        CompositionLocalProvider(
            LocalEffects provides effects,
            // Readable defaults for any text that sets no colour or style of its own;
            // line height in em follows whatever font size a call site picks.
            LocalContentColor provides NexusColors.TextPrimary,
            LocalTextStyle provides NexusTypography.bodyMedium.copy(lineHeight = 1.4.em),
            content = content
        )
    }
}
