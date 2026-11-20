package ai.hybrid.commandcenter.ui

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
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
    // Base
    val Void = Color(0xFF06070B)
    val DeepSurface = Color(0xFF0D0F16)
    val RaisedSurface = Color(0xFF141722)
    val ElevatedSurface = Color(0xFF1C2030)

    // Glass layers (translucent white over the aurora background)
    val GlassFillTop = Color(0x17FFFFFF)
    val GlassFillBottom = Color(0x0AFFFFFF)
    val GlassBorderTop = Color(0x33FFFFFF)
    val GlassBorderBottom = Color(0x0DFFFFFF)
    val Hairline = Color(0x1AFFFFFF)

    // Text
    val TextPrimary = Color(0xFFF4F6FB)
    val TextSecondary = Color(0xFFA3ABBD)
    val TextMuted = Color(0xFF6B7386)

    // Accents (handoff palette)
    val NeonCyan = Color(0xFF42D9F5)
    val NeonViolet = Color(0xFF9B7BFF)
    val NeonMagenta = Color(0xFFF05BCB)
    val SignalGreen = Color(0xFF43D6A0)
    val SignalAmber = Color(0xFFF2B65D)
    val SignalRed = Color(0xFFFF647C)

    /** Kept for older call sites; now a hairline rather than a slab. */
    val Divider = Color(0xFF232838)
}

object NexusBrushes {
    /** Primary action: violet into magenta. */
    val Primary = Brush.linearGradient(listOf(Color(0xFF7D6BFF), Color(0xFFB36CFF), Color(0xFFF05BCB)))

    /** Cool accent: cyan into violet. */
    val Cool = Brush.linearGradient(listOf(Color(0xFF42D9F5), Color(0xFF7D6BFF)))

    /** Wordmark: white into lavender. */
    val Wordmark = Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFC9BFFF), Color(0xFF8FE6FA)))

    val GlassFill = Brush.verticalGradient(listOf(NexusColors.GlassFillTop, NexusColors.GlassFillBottom))
    val GlassBorder = Brush.verticalGradient(listOf(NexusColors.GlassBorderTop, NexusColors.GlassBorderBottom))

    fun accentBorder(accent: Color) =
        Brush.verticalGradient(listOf(accent.copy(alpha = 0.55f), accent.copy(alpha = 0.08f)))
}

object NexusShapes {
    val ControlRadius = 14.dp
    val CardRadius = 22.dp
    val PanelRadius = 28.dp
    val SheetRadius = 32.dp

    val Control = RoundedCornerShape(ControlRadius)
    val Card = RoundedCornerShape(CardRadius)
    val Panel = RoundedCornerShape(PanelRadius)
    val Sheet = RoundedCornerShape(SheetRadius)
    val Pill = RoundedCornerShape(percent = 50)
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
    STATIC("static", "Статичный"),
    SUBTLE("subtle", "Сдержанный"),
    EXPERIMENTAL("experimental", "Экспериментальный");

    companion object {
        fun byId(id: String?): EffectsMode = entries.firstOrNull { it.id == id } ?: SUBTLE
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
    EffectsController(EffectsMode.SUBTLE, EffectsMode.SUBTLE) {}
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
