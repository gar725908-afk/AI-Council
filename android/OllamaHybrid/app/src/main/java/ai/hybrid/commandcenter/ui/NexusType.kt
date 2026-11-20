package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.R
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * Typography: Inter for interface text (full Cyrillic), Inter Display for
 * large headings, Unbounded for the wordmark and brand moments.
 * Fonts ship inside the APK (OFL 1.1, see third_party_licenses/).
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

val InterDisplayFamily = FontFamily(
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold)
)

val UnboundedFamily = FontFamily(
    Font(R.font.unbounded_semibold, FontWeight.SemiBold),
    Font(R.font.unbounded_bold, FontWeight.Bold)
)

private fun inter(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = InterFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

private fun display(size: Int, line: Int, weight: FontWeight, tracking: Double) = TextStyle(
    fontFamily = InterDisplayFamily,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp
)

val NexusTypography = Typography(
    displayLarge = display(40, 46, FontWeight.Bold, -0.8),
    displayMedium = display(34, 40, FontWeight.Bold, -0.6),
    displaySmall = display(28, 34, FontWeight.Bold, -0.4),
    headlineLarge = display(26, 32, FontWeight.SemiBold, -0.3),
    headlineMedium = display(22, 28, FontWeight.SemiBold, -0.2),
    headlineSmall = display(20, 26, FontWeight.SemiBold, -0.2),
    titleLarge = inter(18, 24, FontWeight.SemiBold, -0.1),
    titleMedium = inter(16, 22, FontWeight.SemiBold),
    titleSmall = inter(14, 20, FontWeight.Medium),
    bodyLarge = inter(16, 24, FontWeight.Normal),
    bodyMedium = inter(14, 20, FontWeight.Normal),
    bodySmall = inter(12, 16, FontWeight.Normal),
    labelLarge = inter(14, 20, FontWeight.SemiBold),
    labelMedium = inter(12, 16, FontWeight.Medium, 0.1),
    labelSmall = inter(11, 14, FontWeight.Medium, 0.2)
)

/** Extra styles the Material scale has no slot for. */
object NexusText {
    val Wordmark = TextStyle(
        fontFamily = UnboundedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = 1.sp,
        brush = NexusBrushes.Wordmark
    )

    /** Big numbers: tabular figures so values do not jump while updating. */
    val Metric = TextStyle(
        fontFamily = InterDisplayFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum"
    )

    /** Small uppercase eyebrow, used sparingly above key numbers. */
    val Eyebrow = TextStyle(
        fontFamily = InterFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 0.8.sp
    )
}

private val NexusColorScheme = darkColorScheme(
    primary = Color(0xFFB4A6FF),
    onPrimary = Color(0xFF120B2E),
    primaryContainer = Color(0xFF2A2156),
    onPrimaryContainer = Color(0xFFE6E0FF),
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
    outline = Color(0xFF353B4F),
    outlineVariant = Color(0xFF232838),
    error = NexusColors.SignalRed,
    onError = Color(0xFF2B0710)
)

private val NexusMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp)
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
