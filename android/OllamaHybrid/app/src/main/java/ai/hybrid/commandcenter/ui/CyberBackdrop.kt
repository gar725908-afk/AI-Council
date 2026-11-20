package ai.hybrid.commandcenter.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.pow
import kotlin.random.Random

/** How much of the night city shows: all of it on the bridge, a dim hint behind a conversation. */
enum class BackdropScene { CITY, DIM, PLAIN }

private data class Building(
    val x: Float, val w: Float, val h: Float, val far: Boolean,
    val windows: List<Window>, val sign: Color?, val antenna: Boolean
)
private data class Window(val dx: Float, val dy: Float, val color: Color)

/** Deterministic skyline: x and width as fractions of the screen, height in dp. */
private fun skyline(seed: Int = 7): List<Building> {
    val rnd = Random(seed)
    val palette = listOf(Cyber.Cyan, Cyber.Magenta, Cyber.Yellow, Cyber.Violet, Color(0xFF9FD8FF), Cyber.Cyan)
    val out = ArrayList<Building>()
    for (layer in 0..1) {
        val far = layer == 0
        var x = -0.02f
        while (x < 1.02f) {
            val w = if (far) 0.03f + rnd.nextFloat() * 0.05f else 0.045f + rnd.nextFloat() * 0.085f
            val h = if (far) 34f + rnd.nextFloat() * 64f else 22f + rnd.nextFloat() * 96f
            val widthDp = w * 400f
            val cols = (widthDp / 5f).toInt().coerceAtLeast(1)
            val rows = (h / 7f).toInt().coerceAtLeast(1)
            val windows = ArrayList<Window>()
            val litChance = if (far) 0.14f else 0.22f
            val tint = palette[rnd.nextInt(palette.size)]
            for (r in 1 until rows) for (c in 0 until cols) {
                if (rnd.nextFloat() < litChance) {
                    val color = if (rnd.nextFloat() < 0.75f) tint else palette[rnd.nextInt(palette.size)]
                    windows += Window((c + 0.5f) / cols, (r + 0.2f) / rows, color)
                }
            }
            val sign = if (!far && rnd.nextFloat() < 0.22f) palette[rnd.nextInt(2)] else null
            val antenna = h > (if (far) 80f else 95f) && rnd.nextFloat() < 0.7f
            out += Building(x, w, h, far, windows, sign, antenna)
            x += w + (if (far) 0.003f else 0.008f) + rnd.nextFloat() * 0.012f
        }
    }
    return out
}

/**
 * The night city behind NEXUS: deep blue-black, two neon pools, a skyline
 * with lit windows on the horizon and a perspective grid running to it.
 * The grid drifts only in the bright effects mode; Static stays still.
 */
@Composable
fun CyberBackdrop(
    modifier: Modifier = Modifier,
    scene: BackdropScene = BackdropScene.CITY,
    accent: Color = Cyber.Cyan,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val mode = LocalEffects.current.effective
    val cityAlpha by animateFloatAsState(
        when (scene) { BackdropScene.CITY -> 1f; BackdropScene.DIM -> 0.38f; BackdropScene.PLAIN -> 0.0f },
        tween(NexusMotion.PANEL * 2), label = "city"
    )
    val drift = if (mode == EffectsMode.EXPERIMENTAL) {
        rememberInfiniteTransition(label = "grid").animateFloat(
            0f, 1f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "grid-drift"
        )
    } else remember { mutableFloatStateOf(0.35f) }
    val buildings = remember { skyline() }
    Box(
        modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val horizon = h * 0.70f
            val base = Brush.verticalGradient(
                0f to Color(0xFF02040A),
                0.45f to Color(0xFF040A18),
                0.62f to Color(0xFF0A0A22),
                1f to Color(0xFF05030E)
            )
            val scan = Path().apply {
                var y = 0f
                val step = 3.dp.toPx()
                while (y < h) { moveTo(0f, y); lineTo(w, y); y += step }
            }
            onDrawBehind {
                drawRect(base)
                pool(Offset(w * 0.08f, h * 0.06f), w * 0.95f, accent, 0.13f)
                pool(Offset(w * 0.98f, h * 0.30f), w * 0.70f, Cyber.Violet, 0.12f)
                pool(Offset(w * 0.75f, h * 0.98f), w * 0.95f, Cyber.Magenta, 0.12f)
                if (cityAlpha > 0.01f) {
                    drawCity(buildings, horizon, cityAlpha)
                    drawGrid(horizon, cityAlpha, drift.value)
                }
                drawPath(scan, Color.Black.copy(alpha = 0.10f), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
                drawRect(
                    Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                        center = Offset(w / 2f, h * 0.45f),
                        radius = maxOf(w, h) * 0.75f
                    )
                )
            }
        },
        content = content
    )
}

private fun DrawScope.pool(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha),
            0.5f to color.copy(alpha = alpha * 0.35f),
            1f to Color.Transparent,
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )
}

private fun DrawScope.drawCity(buildings: List<Building>, horizon: Float, alpha: Float) {
    val w = size.width
    val d = 1.dp.toPx()
    // glow behind the skyline
    drawRect(
        Brush.verticalGradient(
            0f to Color.Transparent,
            0.65f to Cyber.Magenta.copy(alpha = 0.14f * alpha),
            1f to Cyber.Cyan.copy(alpha = 0.18f * alpha),
            startY = horizon - 170 * d, endY = horizon
        ),
        topLeft = Offset(0f, horizon - 170 * d),
        size = Size(w, 170 * d)
    )
    for (b in buildings) {
        val left = b.x * w
        val bw = b.w * w
        val bh = b.h * d
        val top = horizon - bh
        val body = if (b.far) Color(0xFF0C1433) else Color(0xFF050915)
        drawRect(body.copy(alpha = alpha * (if (b.far) 0.9f else 1f)), topLeft = Offset(left, top), size = Size(bw, bh))
        if (b.far) {
            // haze: far towers fade into the glow
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Cyber.Violet.copy(alpha = 0.10f * alpha)), startY = top, endY = horizon),
                topLeft = Offset(left, top), size = Size(bw, bh)
            )
        }
        drawLine(
            (if (b.far) Cyber.Violet else Cyber.Cyan).copy(alpha = (if (b.far) 0.25f else 0.40f) * alpha),
            Offset(left, top), Offset(left + bw, top), 1f
        )
        val ww = if (b.far) 1.4f * d else 1.8f * d
        val wh = if (b.far) 1.6f * d else 2.4f * d
        for (win in b.windows) {
            drawRect(
                win.color.copy(alpha = (if (b.far) 0.30f else 0.62f) * alpha),
                topLeft = Offset(left + win.dx * bw - ww / 2f, top + win.dy * bh),
                size = Size(ww, wh)
            )
        }
        if (b.antenna) {
            val ax = left + bw * 0.5f
            drawLine(body.copy(alpha = alpha), Offset(ax, top), Offset(ax, top - 16 * d), 1.5f * d)
            drawCircle(Cyber.Red.copy(alpha = 0.85f * alpha), radius = 1.6f * d, center = Offset(ax, top - 16 * d))
            drawCircle(Cyber.Red.copy(alpha = 0.18f * alpha), radius = 5f * d, center = Offset(ax, top - 16 * d))
        }
        if (b.sign != null) {
            val sx = left + bw * 0.5f - 2 * d
            val sh = bh * 0.38f
            drawRect(b.sign.copy(alpha = 0.16f * alpha), topLeft = Offset(sx - 5 * d, top + 6 * d), size = Size(14 * d, sh + 8 * d))
            drawRect(b.sign.copy(alpha = 0.92f * alpha), topLeft = Offset(sx, top + 10 * d), size = Size(4 * d, sh))
        }
    }
    drawRect(
        Brush.horizontalGradient(listOf(Cyber.Magenta.copy(alpha = 0.0f), Cyber.Magenta.copy(alpha = 0.8f * alpha), Cyber.Cyan.copy(alpha = 0.8f * alpha), Cyber.Cyan.copy(alpha = 0f))),
        topLeft = Offset(0f, horizon - 1 * d), size = Size(w, 2 * d)
    )
}

private fun DrawScope.drawGrid(horizon: Float, alpha: Float, drift: Float) {
    val w = size.width
    val h = size.height
    val vp = Offset(w / 2f, horizon)
    val depth = h - horizon
    // receding horizontals
    val rows = 14
    for (i in 0 until rows) {
        val f = ((i + drift) / rows).coerceIn(0f, 1f)
        val y = horizon + depth * f.pow(2.2f)
        drawLine(Cyber.Magenta.copy(alpha = (0.06f + 0.32f * f) * alpha), Offset(0f, y), Offset(w, y), 1.dp.toPx())
    }
    // converging verticals
    val cols = 22
    for (i in -cols..cols) {
        val xb = w / 2f + i * (w / cols) * 1.6f
        drawLine(
            Cyber.Cyan.copy(alpha = 0.16f * alpha),
            vp, Offset(xb, h), 1.dp.toPx()
        )
    }
    // fade the grid into the horizon haze
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF0A0A22).copy(alpha = 0.95f * alpha),
            0.35f to Color(0xFF0A0A22).copy(alpha = 0.0f),
            startY = horizon, endY = h
        ),
        topLeft = Offset(0f, horizon), size = Size(w, depth)
    )
}
