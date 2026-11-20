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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * NEXUS design system — JARVIS BLACK × NEON NEXUS × GHOST PROTOCOL
 * (handoff section 5). One source for colour, shape, spacing and motion so
 * every screen speaks the same visual language.
 */
object NexusColors {
    val Void = Color(0xFF080B12)
    val DeepSurface = Color(0xFF0E1420)
    val RaisedSurface = Color(0xFF151E2D)
    val ElevatedSurface = Color(0xFF1B2738)
    val TextPrimary = Color(0xFFF1F5FA)
    val TextSecondary = Color(0xFFA4B1C2)
    val TextMuted = Color(0xFF6D7B8E)
    val NeonCyan = Color(0xFF42D9F5)
    val NeonViolet = Color(0xFF9B7BFF)
    val NeonMagenta = Color(0xFFF05BCB)
    val SignalGreen = Color(0xFF43D6A0)
    val SignalAmber = Color(0xFFF2B65D)
    val SignalRed = Color(0xFFFF647C)
    val Divider = Color(0xFF263448)
}

object NexusShapes {
    val ControlRadius = 10.dp
    val CardRadius = 14.dp
    val PanelRadius = 20.dp
    val SheetRadius = 28.dp

    val Control = RoundedCornerShape(ControlRadius)
    val Card = RoundedCornerShape(CardRadius)
    val Panel = RoundedCornerShape(PanelRadius)
    val Sheet = RoundedCornerShape(SheetRadius)
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
