package ai.hybrid.commandcenter.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * NEXUS design system (handoff section 5), revised after owner review: deep
 * black with soft aurora light, translucent glass surfaces, vivid but sparing
 * neon accents, real typography (Inter, Unbounded) and line icons. One source
 * for colour, shape, spacing and motion so every screen speaks one language.
 */
object NexusColors {
    // Base follows the current theme (see ThemeState)
    val Void: Color get() = Cyber.Void
    val DeepSurface: Color get() = Cyber.Night
    val RaisedSurface: Color get() = Cyber.Panel
    val ElevatedSurface: Color get() = Cyber.PanelHigh

    // HUD layers (accent-tinted, translucent over the backdrop)
    val GlassFillTop: Color get() = Cyber.Cyan.copy(alpha = 0.08f)
    val GlassFillBottom = Color(0x05000000)
    val GlassBorderTop: Color get() = Cyber.Cyan.copy(alpha = 0.40f)
    val GlassBorderBottom: Color get() = Cyber.Cyan.copy(alpha = 0.08f)
    val Hairline: Color get() = Cyber.Cyan.copy(alpha = 0.14f)

    // Text
    val TextPrimary: Color get() = Cyber.Text
    val TextSecondary: Color get() = Cyber.TextDim
    val TextMuted: Color get() = Cyber.TextFaint

    // Neon accents
    val NeonCyan: Color get() = Cyber.Cyan
    val NeonViolet: Color get() = Cyber.Violet
    val NeonMagenta: Color get() = Cyber.Magenta
    val NeonYellow: Color get() = Cyber.Yellow
    val SignalGreen = Color(0xFF2BFF9A)
    val SignalAmber = Color(0xFFFFB547)
    val SignalRed = Color(0xFFFF3B5C)

    /** Kept for older call sites; now a hairline rather than a slab. */
    val Divider: Color get() = Cyber.PanelHigh
}

object NexusBrushes {
    /** Primary action: primary through tertiary into secondary. */
    val Primary: Brush get() = Brush.linearGradient(listOf(Cyber.Cyan, Cyber.Violet, Cyber.Magenta))

    /** Cool accent: primary into tertiary. */
    val Cool: Brush get() = Brush.linearGradient(listOf(Cyber.Cyan, Cyber.Violet))

    /** Wordmark of the current theme. */
    val Wordmark: Brush get() = Cyber.Wordmark

    val GlassFill: Brush get() = Brush.verticalGradient(listOf(NexusColors.GlassFillTop, NexusColors.GlassFillBottom))
    val GlassBorder: Brush get() = Brush.linearGradient(listOf(NexusColors.GlassBorderTop, NexusColors.GlassBorderBottom, Cyber.Cyan.copy(alpha = 0.25f)))

    fun accentBorder(accent: Color) =
        Brush.linearGradient(listOf(accent.copy(alpha = 0.6f), accent.copy(alpha = 0.12f), accent.copy(alpha = 0.4f)))
}

/** Chamfered silhouettes: HUD panels instead of soft rounded cards. */
object NexusShapes {
    val ControlRadius = 9.dp
    val CardRadius = 12.dp
    val PanelRadius = 14.dp
    val SheetRadius = 18.dp

    val Control = CutCornerShape(topStart = ControlRadius, topEnd = 0.dp, bottomEnd = ControlRadius, bottomStart = 0.dp)
    val Card = CutCornerShape(topStart = CardRadius, topEnd = 0.dp, bottomEnd = CardRadius, bottomStart = 0.dp)
    val Panel = CutCornerShape(topStart = PanelRadius, topEnd = 0.dp, bottomEnd = PanelRadius, bottomStart = 0.dp)
    val Sheet = CutCornerShape(topStart = SheetRadius, topEnd = 0.dp, bottomEnd = SheetRadius, bottomStart = 0.dp)
    val Pill = CutCornerShape(7.dp)
}

object NexusSpacing {
    val XS = 4.dp
    val S = 8.dp
    val M = 12.dp
    val L = 16.dp
    val XL = 20.dp
    val XXL = 24.dp
}

/** Motion timings from the spec, in milliseconds. */
object NexusMotion {
    const val TAP = 140
    const val STATE_CHANGE = 200
    const val PANEL = 260
    const val THINKING_ROTATION = 2_400
    const val LISTENING_PULSE = 1_100
    const val SUCCESS_PULSE = 900
}

/** Effects intensity the user picks; the system may still force Static. */
enum class EffectsMode(val id: String, val title: String) {
    STATIC("static", "Статично"),
    SUBTLE("subtle", "Сдержанно"),
    EXPERIMENTAL("experimental", "Ярко");

    companion object {
        fun byId(id: String?): EffectsMode = entries.firstOrNull { it.id == id } ?: EXPERIMENTAL
    }
}

/**
 * Pure resolution of the effective mode: Android's "remove animations"
 * (animator scale 0) and battery saver always win over the user's choice,
 * so the app never drains a phone that asked it not to.
 */
object MotionPolicy {
    fun resolve(userChoice: EffectsMode, animatorScale: Float, powerSave: Boolean): EffectsMode = when {
        animatorScale <= 0f -> EffectsMode.STATIC
        powerSave -> EffectsMode.STATIC
        else -> userChoice
    }

    fun animates(mode: EffectsMode): Boolean = mode != EffectsMode.STATIC

    /** Scales a duration by the system animator scale, with a sane floor. */
    fun scaled(durationMillis: Int, animatorScale: Float): Int =
        if (animatorScale <= 0f) 0 else (durationMillis * animatorScale).toInt().coerceAtLeast(1)
}

object EffectsSettings {
    private const val PREFS = "nexus_effects"
    private const val KEY = "mode"

    fun load(context: Context): EffectsMode = EffectsMode.byId(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
    )

    fun save(context: Context, mode: EffectsMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.id).apply()
    }

    fun animatorScale(context: Context): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)

    fun powerSave(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isPowerSaveMode == true

    fun effective(context: Context): EffectsMode =
        MotionPolicy.resolve(load(context), animatorScale(context), powerSave(context))
}

/**
 * The user's choice plus what is actually in effect after the system's
 * reduced-motion and power-save signals. Settings changes apply live.
 */
data class EffectsController(
    val choice: EffectsMode,
    val effective: EffectsMode,
    val choose: (EffectsMode) -> Unit
)

val LocalEffects = compositionLocalOf {
    EffectsController(EffectsMode.EXPERIMENTAL, EffectsMode.EXPERIMENTAL) {}
}

/** Created once at the app root and provided through [LocalEffects]. */
@Composable
fun rememberEffectsController(): EffectsController {
    val context = LocalContext.current
    var choice by remember { mutableStateOf(EffectsSettings.load(context)) }
    val effective = remember(choice) {
        MotionPolicy.resolve(choice, EffectsSettings.animatorScale(context), EffectsSettings.powerSave(context))
    }
    return EffectsController(choice, effective) { mode ->
        EffectsSettings.save(context, mode)
        choice = mode
    }
}
