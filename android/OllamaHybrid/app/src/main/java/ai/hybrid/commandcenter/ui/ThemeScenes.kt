package ai.hybrid.commandcenter.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Backdrop scenes for the «Космос» and «Аниме» themes. Everything is drawn
 * procedurally (no bitmaps) from deterministic seeds, so a scene costs a few
 * hundred primitives and looks the same on every launch. Motion is driven by
 * one clock `t` in 0..1 and appears only in the bright effects mode.
 */

internal data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val warm: Boolean, val speed: Float)

internal fun starfield(seed: Int = 11, count: Int = 240): List<Star> {
    val rnd = Random(seed)
    return List(count) { i ->
        val band = i < count * 0.45f
        val x = rnd.nextFloat()
        // a soft diagonal «milky way» band, the rest scattered evenly
        val y = if (band) (0.06f + 0.55f * x + (rnd.nextFloat() + rnd.nextFloat() - 1f) * 0.07f) else rnd.nextFloat()
        val big = rnd.nextFloat() < 0.06f
        Star(
            x = x, y = y.coerceIn(0f, 1f),
            r = if (big) 1.4f + rnd.nextFloat() * 0.9f else 0.35f + rnd.nextFloat() * 0.8f,
            phase = rnd.nextFloat(), warm = rnd.nextFloat() < 0.22f, speed = 0.5f + rnd.nextFloat()
        )
    }
}

internal data class Petal(val x: Float, val phase: Float, val speed: Float, val size: Float, val sway: Float, val spin: Float)

internal fun petals(seed: Int = 5, count: Int = 16): List<Petal> {
    val rnd = Random(seed)
    return List(count) {
        Petal(rnd.nextFloat(), rnd.nextFloat(), 0.6f + rnd.nextFloat() * 0.8f, 3f + rnd.nextFloat() * 3f,
            8f + rnd.nextFloat() * 22f, rnd.nextFloat() * 360f)
    }
}

private fun DrawScope.glow(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(
            0f to color.copy(alpha = alpha), 0.5f to color.copy(alpha = alpha * 0.35f), 1f to Color.Transparent,
            center = center, radius = radius
        ),
        radius = radius, center = center
    )
}

/** Deep space: nebulae, a twinkling star field, a ringed gas giant rising from below and a small moon. */
internal fun DrawScope.drawSpaceScene(stars: List<Star>, sceneAlpha: Float, t: Float, vivid: Boolean) {
    val w = size.width
    val h = size.height
    val d = 1.dp.toPx()
    val p = ThemeState.palette
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF010208), 0.45f to Color(0xFF050A20), 0.75f to Color(0xFF0B0A26), 1f to Color(0xFF07041A)
        )
    )
    glow(Offset(w * 0.88f, h * 0.16f), w * 0.85f, p.secondary, 0.15f)
    glow(Offset(w * 0.05f, h * 0.42f), w * 0.95f, p.tertiary, 0.15f)
    glow(Offset(w * 0.55f, h * 0.30f), w * 0.55f, p.primary, 0.07f)

    // stars
    for (s in stars) {
        val twinkle = if (vivid) 0.55f + 0.45f * sin(2f * PI.toFloat() * (t * 24f * s.speed + s.phase)) else 0.8f
        val color = if (s.warm) Color(0xFFFFE3C2) else Color(0xFFDCEBFF)
        val c = Offset(s.x * w, s.y * h)
        drawCircle(color.copy(alpha = (0.35f + 0.6f * twinkle) * sceneAlpha.coerceAtLeast(0.55f)), radius = s.r * d, center = c)
        if (s.r > 1.5f) {
            val len = s.r * 4.5f * d * twinkle
            val glint = color.copy(alpha = 0.45f * twinkle)
            drawLine(glint, Offset(c.x - len, c.y), Offset(c.x + len, c.y), 0.8f * d)
            drawLine(glint, Offset(c.x, c.y - len), Offset(c.x, c.y + len), 0.8f * d)
        }
    }

    // a shooting star every few seconds in the bright mode
    if (vivid) {
        val phase = (t * 7f) % 1f
        if (phase < 0.10f) {
            val k = phase / 0.10f
            val start = Offset(w * 0.15f, h * 0.08f)
            val dir = Offset(w * 0.55f, h * 0.20f)
            val head = start + dir * k
            val tail = start + dir * (k - 0.25f).coerceAtLeast(0f)
            drawLine(
                Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.85f)), start = tail, end = head),
                tail, head, 1.6f * d
            )
        }
    }

    if (sceneAlpha <= 0.01f) return

    // small moon, top-left
    val moon = Offset(w * 0.17f, h * 0.17f)
    val mr = 15f * d
    glow(moon, mr * 3f, p.primarySoft, 0.10f * sceneAlpha)
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFE9EEF8), Color(0xFF8E9BB8), Color(0xFF2B3350)), center = moon - Offset(mr * 0.4f, mr * 0.4f), radius = mr * 1.6f),
        radius = mr, center = moon, alpha = sceneAlpha
    )
    drawCircle(Color(0xFF5B6684).copy(alpha = 0.55f * sceneAlpha), radius = mr * 0.22f, center = moon + Offset(mr * 0.25f, -mr * 0.1f))
    drawCircle(Color(0xFF5B6684).copy(alpha = 0.45f * sceneAlpha), radius = mr * 0.14f, center = moon + Offset(-mr * 0.35f, mr * 0.35f))

    // ringed gas giant rising from the bottom-right corner
    val r = w * 0.60f
    val c = Offset(w * 0.80f, h + r * 0.14f)
    val tilt = -14f
    val ring = Rect(c.x - r * 1.55f, c.y - r * 0.28f, c.x + r * 1.55f, c.y + r * 0.28f)
    val ringBrush = Brush.horizontalGradient(listOf(p.primary.copy(alpha = 0.0f), p.primarySoft.copy(alpha = 0.55f), p.secondary.copy(alpha = 0.35f), p.primary.copy(alpha = 0.0f)), startX = ring.left, endX = ring.right)
    rotate(tilt, pivot = c) {
        drawArc(ringBrush, 180f, 180f, false, ring.topLeft, ring.size, alpha = sceneAlpha, style = Stroke(5f * d))
        drawArc(ringBrush, 180f, 180f, false, Rect(ring.left + 14 * d, ring.top + 3 * d, ring.right - 14 * d, ring.bottom - 3 * d).topLeft,
            Size(ring.width - 28 * d, ring.height - 6 * d), alpha = 0.6f * sceneAlpha, style = Stroke(2f * d))
    }
    glow(c, r * 1.35f, p.primary, 0.12f * sceneAlpha)
    drawCircle(
        Brush.radialGradient(
            listOf(lerp(p.tertiary, Color.White, 0.35f), p.tertiary, Color(0xFF1B1243), Color(0xFF07051A)),
            center = c - Offset(r * 0.35f, r * 0.55f), radius = r * 1.5f
        ),
        radius = r, center = c, alpha = sceneAlpha
    )
    val disc = Path().apply { addOval(Rect(c, r)) }
    clipPath(disc) {
        var y = c.y - r
        var i = 0
        while (y < c.y + r) {
            val bandH = r * (0.05f + 0.04f * ((i * 37) % 5) / 4f)
            drawRect(
                (if (i % 2 == 0) p.primary else p.secondary).copy(alpha = 0.07f * sceneAlpha),
                topLeft = Offset(c.x - r, y), size = Size(2 * r, bandH)
            )
            y += bandH * 2.1f
            i++
        }
        // night side
        drawCircle(
            Brush.radialGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f), center = c - Offset(r * 0.45f, r * 0.6f), radius = r * 1.9f),
            radius = r, center = c
        )
    }
    drawCircle(p.primary.copy(alpha = 0.55f * sceneAlpha), radius = r, center = c, style = Stroke(2f * d))
    drawCircle(p.primary.copy(alpha = 0.10f * sceneAlpha), radius = r + 5 * d, center = c, style = Stroke(8f * d))
    rotate(tilt, pivot = c) {
        drawArc(ringBrush, 0f, 180f, false, ring.topLeft, ring.size, alpha = sceneAlpha, style = Stroke(5f * d))
    }

    // a satellite on a wide dashed orbit
    val orbit = Rect(c.x - r * 1.95f, c.y - r * 0.95f, c.x + r * 1.95f, c.y + r * 0.95f)
    rotate(tilt, pivot = c) {
        drawOval(
            p.primarySoft.copy(alpha = 0.16f * sceneAlpha), orbit.topLeft, orbit.size,
            style = Stroke(1f * d, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * d, 7 * d)))
        )
        val a = (if (vivid) t * 2f * PI.toFloat() * 3f else 3.9f) + PI.toFloat()
        val sat = Offset(c.x + cos(a) * orbit.width / 2f, c.y + sin(a) * orbit.height / 2f)
        drawCircle(p.highlight.copy(alpha = 0.9f * sceneAlpha), radius = 2.2f * d, center = sat)
        drawCircle(p.highlight.copy(alpha = 0.18f * sceneAlpha), radius = 7f * d, center = sat)
    }
}

/** Sunset HQ: a striped anime sun over a dark skyline, a hex lattice, vertical katakana and drifting petals. */
internal fun DrawScope.drawAnimeScene(
    skylineDraw: DrawScope.(horizon: Float, alpha: Float) -> Unit,
    gridDraw: DrawScope.(horizon: Float, alpha: Float) -> Unit,
    petalList: List<Petal>,
    watermark: TextLayoutResult?,
    hexLattice: Path?,
    sceneAlpha: Float,
    t: Float,
    vivid: Boolean
) {
    val w = size.width
    val h = size.height
    val d = 1.dp.toPx()
    val p = ThemeState.palette
    val horizon = h * 0.66f
    drawRect(
        Brush.verticalGradient(
            0f to p.void, 0.40f to Color(0xFF15070A), 0.62f to Color(0xFF3A0E16), 0.70f to Color(0xFF1C0709), 1f to Color(0xFF0A0405)
        )
    )
    glow(Offset(w * 0.1f, h * 0.12f), w * 0.8f, p.tertiary, 0.10f)

    // hex lattice fading towards the horizon (built once per size by the caller)
    hexLattice?.let { lattice ->
        drawPath(
            lattice,
            Brush.verticalGradient(0f to p.primary.copy(alpha = 0.09f), 1f to Color.Transparent, startY = 0f, endY = horizon),
            style = Stroke(1f)
        )
    }

    if (sceneAlpha > 0.01f) {
        // the striped sun
        val sun = Offset(w * 0.5f, horizon - 6 * d)
        val sr = w * 0.34f
        glow(sun, sr * 2.2f, p.primary, 0.20f * sceneAlpha)
        clipRect(bottom = horizon) {
            drawCircle(
                Brush.verticalGradient(
                    listOf(Color(0xFFFFE0A8), p.primary, p.secondary), startY = sun.y - sr, endY = sun.y + sr * 0.2f
                ),
                radius = sr, center = sun, alpha = sceneAlpha
            )
            // horizontal cuts across the lower half, thicker towards the horizon
            var k = 0
            var cut = sun.y - sr * 0.18f
            while (cut < horizon && k < 9) {
                val thickness = (1.5f + k * 1.4f) * d
                drawRect(Color(0xFF2A0A10), topLeft = Offset(sun.x - sr, cut), size = Size(2 * sr, thickness))
                cut += thickness + (9f - k * 0.6f) * d
                k++
            }
        }
        skylineDraw(horizon, sceneAlpha)
        gridDraw(horizon, sceneAlpha)
    }

    // vertical katakana watermark
    watermark?.let { layout ->
        drawText(layout, topLeft = Offset(w - layout.size.width - 10 * d, h * 0.12f), alpha = 0.10f)
    }

    // petals drifting down
    if (vivid) {
        for (petal in petalList) {
            val py = ((t * 6f * petal.speed + petal.phase) % 1f) * (h + 40 * d) - 20 * d
            val px = petal.x * w + sin((t * 18f + petal.phase) * 2f * PI.toFloat()) * petal.sway * d
            translate(px, py) {
                rotate(petal.spin + t * 720f * petal.speed, pivot = Offset.Zero) {
                    drawOval(
                        Color(0xFFFF9EC8).copy(alpha = 0.55f),
                        topLeft = Offset(-petal.size * d, -petal.size * 0.55f * d),
                        size = Size(petal.size * 2f * d, petal.size * 1.1f * d)
                    )
                }
            }
        }
    }

    // hazard stripes along the top and bottom edges
    hazardBand(0f, 5f * d, 0.55f)
    hazardBand(h - 4f * d, 4f * d, 0.45f)
}

/** One path with every hexagon of the lattice above the horizon; cheap to draw each frame. */
internal fun buildHexLattice(width: Float, horizon: Float, hex: Float): Path {
    val path = Path()
    val hexH = hex * sqrt(3f)
    var row = 0
    var y = 0f
    while (y < horizon) {
        var x = -hex + (if (row % 2 == 0) 0f else hex * 1.5f)
        while (x < width + hex) {
            for (k in 0..6) {
                val a = (PI / 3.0 * k).toFloat()
                val px = x + hex * cos(a)
                val py = y + hex * sin(a)
                if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            x += hex * 3f
        }
        y += hexH / 2f
        row++
    }
    return path
}

/** Diagonal black-and-accent stripes, the classic «danger zone» band. */
internal fun DrawScope.hazardBand(top: Float, height: Float, alpha: Float, accent: Color = ThemeState.palette.primary) {
    val w = size.width
    clipRect(top = top, bottom = top + height) {
        drawRect(Color.Black.copy(alpha = alpha), topLeft = Offset(0f, top), size = Size(w, height))
        val step = 14f * 1.dp.toPx()
        var x = -height
        while (x < w + height) {
            val path = Path().apply {
                moveTo(x, top + height)
                lineTo(x + step / 2f, top + height)
                lineTo(x + step / 2f + height, top)
                lineTo(x + height, top)
                close()
            }
            drawPath(path, accent.copy(alpha = alpha))
            x += step
        }
    }
}

/** Public entry for composables that want a hazard band behind their content. */
object HazardPainter {
    fun DrawScope.band(accent: Color, alpha: Float = 0.9f) = hazardBand(0f, size.height, alpha, accent)
}
