package ai.hybrid.commandcenter.ui

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Reads the front-camera hole from the display cutout. Only a small, roughly
 * round hole qualifies — notches and wide bars are left alone.
 */
@Composable
fun rememberCameraHole(): Rect? {
    val view = LocalView.current
    val orientation = LocalConfiguration.current.orientation
    var hole by remember { mutableStateOf<Rect?>(null) }
    LaunchedEffect(view, orientation) {
        withFrameNanos { }
        hole = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) null else {
            view.rootWindowInsets?.displayCutout?.boundingRects
                ?.map { Rect(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat()) }
                ?.firstOrNull { r ->
                    r.width > 0f && r.height > 0f && r.width < view.width * 0.18f && r.width / r.height in 0.6f..1.6f
                }
        }
    }
    return hole
}

/**
 * A live ring around the front camera: NEX's state at the very top of the
 * screen. Each theme has its own original HUD design — tick marks in the neon
 * city, an orbiting satellite in space, three rotating blades in the anime HQ.
 * It spins fast while NEX thinks, pulses while listening, flashes on success
 * and errors. Draws nothing on phones without a punch-hole camera.
 */
@Composable
fun CameraRing(
    state: NexState,
    speaking: Boolean,
    modifier: Modifier = Modifier,
    hole: Rect? = rememberCameraHole(),
    theme: NexusTheme = ThemeState.theme
) {
    val target = hole ?: return
    val vivid = LocalEffects.current.effective == EffectsMode.EXPERIMENTAL
    val spin = if (vivid) {
        val period = when {
            state == NexState.THINKING -> 1_400
            state == NexState.LISTENING || speaking -> 3_000
            else -> 9_000
        }
        rememberInfiniteTransition(label = "ring").animateFloat(
            0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "ring-spin"
        )
    } else remember { mutableFloatStateOf(0.08f) }
    val color = when {
        speaking -> Cyber.Magenta
        else -> holoColors(state).first
    }
    Canvas(modifier.fillMaxSize()) {
        val turn = spin.value
        val pulse = if (state == NexState.LISTENING || speaking) 0.5f + 0.5f * sin(turn * 2f * PI.toFloat() * 3f) else 1f
        when (theme) {
            NexusTheme.NEON_CITY -> neonRing(target, turn, color, pulse)
            NexusTheme.COSMOS -> orbitRing(target, turn, color, pulse)
            NexusTheme.ANIME -> bladeRing(target, turn, color, pulse)
        }
    }
}

private fun DrawScope.ringGeometry(hole: Rect): Pair<Offset, Float> =
    hole.center to (maxOf(hole.width, hole.height) / 2f + 3.dp.toPx())

private fun DrawScope.neonRing(hole: Rect, turn: Float, color: Color, pulse: Float) {
    val (c, r) = ringGeometry(hole)
    val d = 1.dp.toPx()
    drawCircle(color.copy(alpha = 0.25f * pulse), radius = r + 4 * d, center = c, style = Stroke(4 * d))
    drawCircle(color.copy(alpha = 0.95f), radius = r, center = c, style = Stroke(1.6f * d))
    rotate(turn * 360f, pivot = c) {
        for (i in 0 until 12) {
            val a = (i / 12f) * 2f * PI.toFloat()
            val len = if (i % 3 == 0) 5f * d else 3f * d
            val from = Offset(c.x + cos(a) * (r + 3 * d), c.y + sin(a) * (r + 3 * d))
            val to = Offset(c.x + cos(a) * (r + 3 * d + len), c.y + sin(a) * (r + 3 * d + len))
            drawLine(color.copy(alpha = 0.8f), from, to, 1.2f * d)
        }
        drawArc(
            Cyber.Magenta.copy(alpha = 0.9f), -90f, 70f, false,
            Offset(c.x - r - 2 * d, c.y - r - 2 * d), Size((r + 2 * d) * 2, (r + 2 * d) * 2),
            style = Stroke(2f * d, cap = StrokeCap.Round)
        )
    }
}

private fun DrawScope.orbitRing(hole: Rect, turn: Float, color: Color, pulse: Float) {
    val (c, r) = ringGeometry(hole)
    val d = 1.dp.toPx()
    drawCircle(color.copy(alpha = 0.9f), radius = r, center = c, style = Stroke(1.4f * d))
    val orbit = r + 6 * d
    drawCircle(color.copy(alpha = 0.22f), radius = orbit, center = c, style = Stroke(1f * d))
    val a = turn * 2f * PI.toFloat() - PI.toFloat() / 2f
    // fading trail behind the satellite
    for (k in 1..14) {
        val ta = a - k * 0.07f
        drawCircle(
            Cyber.Yellow.copy(alpha = 0.5f * (1f - k / 15f) * pulse),
            radius = (2.2f - k * 0.1f).coerceAtLeast(0.6f) * d,
            center = Offset(c.x + cos(ta) * orbit, c.y + sin(ta) * orbit)
        )
    }
    val sat = Offset(c.x + cos(a) * orbit, c.y + sin(a) * orbit)
    drawCircle(Cyber.Yellow, radius = 2.6f * d, center = sat)
    drawCircle(Cyber.Yellow.copy(alpha = 0.25f), radius = 6f * d, center = sat)
}

private fun DrawScope.bladeRing(hole: Rect, turn: Float, color: Color, pulse: Float) {
    val (c, r) = ringGeometry(hole)
    val d = 1.dp.toPx()
    // segmented outer ring
    rotate(-turn * 180f, pivot = c) {
        for (i in 0 until 8) {
            drawArc(
                color.copy(alpha = 0.85f), i * 45f + 4f, 34f, false,
                Offset(c.x - r - 4 * d, c.y - r - 4 * d), Size((r + 4 * d) * 2, (r + 4 * d) * 2),
                style = Stroke(1.6f * d)
            )
        }
    }
    drawCircle(color.copy(alpha = 0.35f * pulse), radius = r, center = c, style = Stroke(3f * d))
    // three swept blades orbiting the lens
    rotate(turn * 360f, pivot = c) {
        for (k in 0 until 3) {
            val a = k * (2f * PI.toFloat() / 3f)
            val tip = Offset(c.x + cos(a) * (r + 9 * d), c.y + sin(a) * (r + 9 * d))
            val left = Offset(c.x + cos(a - 0.42f) * (r + 1 * d), c.y + sin(a - 0.42f) * (r + 1 * d))
            val right = Offset(c.x + cos(a + 0.12f) * (r + 2 * d), c.y + sin(a + 0.12f) * (r + 2 * d))
            val blade = Path().apply {
                moveTo(left.x, left.y)
                quadraticTo(c.x + cos(a - 0.12f) * (r + 7 * d), c.y + sin(a - 0.12f) * (r + 7 * d), tip.x, tip.y)
                lineTo(right.x, right.y)
                close()
            }
            drawPath(blade, Cyber.Magenta.copy(alpha = 0.95f))
        }
    }
}
