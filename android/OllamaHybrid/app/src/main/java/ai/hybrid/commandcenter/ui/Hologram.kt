package ai.hybrid.commandcenter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/*
 * NEX as a hologram: a wireframe head and shoulders made of light, standing
 * on a projector pad — the image from the owner's reference boards. The
 * geometry is a parametric head (pure math, unit-tested); the drawing adds
 * depth shading, glowing eyes, a speaking mouth, scanlines and particles.
 * Colour and behaviour follow the real NexState; Static mode freezes it.
 */

data class V3(val x: Float, val y: Float, val z: Float)

object HoloGeometry {
    const val AX = 0.76f
    const val AY = 0.98f
    const val AZ = 0.86f
    const val EYE_PHI = 0.40f
    const val EYE_Y = 0.07f
    const val MOUTH_Y = -0.50f
    const val NECK_TOP = -0.70f
    const val BUST_BOTTOM = -1.90f
    const val HEAD_THETA_MIN = 0.10f
    const val HEAD_THETA_MAX = 2.70f
    const val HEAD_RINGS = 26

    private fun g(d: Float, w: Float): Float = exp(-(d / w) * (d / w))
    fun smoothstep(e0: Float, e1: Float, v: Float): Float {
        val k = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return k * k * (3f - 2f * k)
    }

    /** Point on the head surface; theta from the crown, phi = 0 faces the viewer. */
    fun head(theta: Float, phi: Float): V3 {
        val st = sin(theta)
        val ct = cos(theta)
        val yy = ct
        var x = AX * st * sin(phi)
        val y = AY * ct
        var z = AZ * st * cos(phi)
        // temples and cranium: a touch wider than an ellipsoid
        x *= 1f + 0.05f * g(yy - 0.42f, 0.32f)
        // the face narrows from the cheekbones to the jaw angle and the chin
        if (yy < -0.22f) {
            val k = smoothstep(-0.22f, -1.0f, yy)
            x *= 1f - 0.44f * k.pow(1.25f)
            if (cos(phi) < 0f) z *= 1f - 0.38f * k
        }
        if (cos(phi) < 0f) z *= 0.95f
        val front = max(0f, cos(phi))
        var dz = 0f
        dz += 0.050f * g(yy - 0.21f, 0.065f) * g(phi, 0.72f)                                   // brow
        dz -= 0.090f * g(yy - EYE_Y, 0.09f) * (g(phi - EYE_PHI, 0.16f) + g(phi + EYE_PHI, 0.16f)) // sockets
        val nose = when {
            yy > 0.13f -> 0f
            yy > -0.27f -> (0.13f - yy) / 0.40f
            yy > -0.35f -> (yy + 0.35f) / 0.08f
            else -> 0f
        }
        dz += 0.23f * nose * g(phi, 0.075f + 0.07f * nose)                                       // nose
        dz += 0.040f * g(yy + 0.10f, 0.12f) * (g(phi - 0.60f, 0.18f) + g(phi + 0.60f, 0.18f))   // cheekbones
        dz += 0.045f * g(yy + 0.46f, 0.040f) * g(phi, 0.22f)                                     // upper lip
        dz += 0.035f * g(yy + 0.56f, 0.040f) * g(phi, 0.20f)                                     // lower lip
        dz += 0.060f * g(yy + 0.84f, 0.08f) * g(phi, 0.32f)                                      // chin
        z += dz * front
        return V3(x, y, z)
    }

    /**
     * Neck and shoulders, sampled along a profile (y, half-width, half-depth)
     * so the slope of the shoulders gets as many rings as the straight parts.
     */
    private val bodyProfile = listOf(
        floatArrayOf(-0.70f, 0.30f, 0.27f),
        floatArrayOf(-1.18f, 0.32f, 0.29f),
        floatArrayOf(-1.32f, 0.52f, 0.35f),
        floatArrayOf(-1.42f, 0.84f, 0.41f),
        floatArrayOf(-1.52f, 1.00f, 0.46f),
        floatArrayOf(-1.66f, 1.06f, 0.48f),
        floatArrayOf(-1.90f, 1.02f, 0.47f)
    )

    /** Profile at arc-length fraction s in 0..1: (y, rx, rz). */
    fun bodyAt(s: Float): FloatArray {
        val lengths = FloatArray(bodyProfile.size)
        for (i in 1 until bodyProfile.size) {
            val a = bodyProfile[i - 1]
            val b = bodyProfile[i]
            lengths[i] = lengths[i - 1] + kotlin.math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1]))
        }
        val target = s.coerceIn(0f, 1f) * lengths.last()
        for (i in 1 until bodyProfile.size) {
            if (target <= lengths[i] || i == bodyProfile.size - 1) {
                val span = (lengths[i] - lengths[i - 1]).coerceAtLeast(1e-6f)
                val u = ((target - lengths[i - 1]) / span).coerceIn(0f, 1f)
                val a = bodyProfile[i - 1]
                val b = bodyProfile[i]
                return floatArrayOf(a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u)
            }
        }
        return bodyProfile.last()
    }

    fun body(s: Float, phi: Float): V3 {
        val (y, rx, rz) = bodyAt(s).let { Triple(it[0], it[1], it[2]) }
        val sn = sin(phi)
        val cs = cos(phi)
        // superellipse (n = 2.6) gives a torso rather than a cylinder
        val n = 2.6f
        val sx = sign(sn) * abs(sn).pow(2f / n)
        val cz = sign(cs) * abs(cs).pow(2f / n)
        return V3(rx * sx, y, rz * cz)
    }

    private fun sign(v: Float) = if (v < 0f) -1f else 1f

    fun eye(left: Boolean): V3 {
        val theta = kotlin.math.acos(EYE_Y.toDouble()).toFloat()
        val p = head(theta, if (left) -EYE_PHI else EYE_PHI)
        return V3(p.x, p.y, p.z + 0.03f)
    }

    fun mouth(): V3 {
        val theta = kotlin.math.acos(MOUTH_Y.toDouble()).toFloat()
        val p = head(theta, 0f)
        return V3(p.x, p.y, p.z + 0.02f)
    }

    fun surfaceAt(yy: Float, phi: Float): V3 = head(kotlin.math.acos(yy.coerceIn(-1f, 1f).toDouble()).toFloat(), phi)

    /** Horizontal contour rings: head, neck and shoulders, top to bottom. */
    fun rings(headRings: Int = HEAD_RINGS, bodyRings: Int = 14, samples: Int = 72): List<Array<V3>> {
        val out = ArrayList<Array<V3>>(headRings + bodyRings)
        for (i in 0 until headRings) {
            val theta = HEAD_THETA_MIN + (HEAD_THETA_MAX - HEAD_THETA_MIN) * i / (headRings - 1)
            out += Array(samples) { j -> head(theta, (-PI + 2 * PI * j / samples).toFloat()) }
        }
        for (i in 0 until bodyRings) {
            val s = 0.03f + 0.97f * i / (bodyRings - 1)
            out += Array(samples) { j -> body(s, (-PI + 2 * PI * j / samples).toFloat()) }
        }
        return out
    }

    /** Vertical meridians: dense across the face, sparse behind. They draw the profile. */
    fun meridians(samples: Int = 48): List<Array<V3>> {
        val phis = ArrayList<Float>()
        var phi = -1.2f
        while (phi <= 1.2001f) { phis += phi; phi += 0.10f }
        var back = 1.45f
        while (back < PI.toFloat() * 2 - 1.45f) { phis += back; back += 0.40f }
        return phis.map { ph ->
            Array(samples) { j ->
                val theta = HEAD_THETA_MIN + (HEAD_THETA_MAX - 0.10f - HEAD_THETA_MIN) * j / (samples - 1)
                head(theta, ph)
            }
        }
    }

    /** Shoulder meridians: front half only, from the neck to the cut. */
    fun bodyMeridians(count: Int = 13, samples: Int = 28): List<Array<V3>> =
        (0 until count).map { m ->
            val ph = (-PI / 2 + PI * m / (count - 1)).toFloat()
            Array(samples) { j -> body(0.04f + 0.96f * j / (samples - 1), ph) }
        }

    /** Feature accents that make the wireframe read as a face. */
    fun featureLines(): List<Array<V3>> {
        fun arc(yy: Float, from: Float, to: Float, n: Int = 12, lift: Float = 0.012f): Array<V3> =
            Array(n) { i ->
                val ph = from + (to - from) * i / (n - 1)
                val p = surfaceAt(yy, ph)
                V3(p.x, p.y, p.z + lift)
            }
        val noseRidge = Array(12) { i ->
            val yy = 0.16f - 0.48f * i / 11f
            val p = surfaceAt(yy, 0f)
            V3(p.x, p.y, p.z + 0.012f)
        }
        return listOf(
            arc(0.235f, -0.78f, -0.14f), arc(0.235f, 0.14f, 0.78f),   // brows
            noseRidge,
            arc(-0.335f, -0.16f, 0.16f, 8),                            // nostrils base
            arc(-0.465f, -0.27f, 0.27f, 10),                           // upper lip
            arc(-0.565f, -0.22f, 0.22f, 10),                           // lower lip
            arc(-0.08f, -0.95f, -0.50f, 8), arc(-0.08f, 0.50f, 0.95f, 8) // cheekbones
        )
    }

    /** Yaw, then a slight pitch; z points to the viewer. */
    fun rotate(p: V3, yaw: Float, pitch: Float): V3 {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val x1 = p.x * cy + p.z * sy
        val z1 = -p.x * sy + p.z * cy
        val cp = cos(pitch)
        val sp = sin(pitch)
        val y2 = p.y * cp - z1 * sp
        val z2 = p.y * sp + z1 * cp
        return V3(x1, y2, z2)
    }
}

/** Colours of the hologram per state: top-of-head colour and shoulder colour. */
internal fun holoColors(state: NexState): Pair<Color, Color> = when (state) {
    NexState.STANDBY -> Cyber.Cyan to Cyber.Violet
    NexState.LISTENING -> Cyber.Magenta to Cyber.Cyan
    NexState.THINKING -> Cyber.Violet to Cyber.Magenta
    NexState.SUCCESS -> Cyber.Green to Cyber.Cyan
    NexState.ATTENTION -> Cyber.Amber to Cyber.Magenta
    NexState.ERROR -> Cyber.Red to Cyber.Magenta
    NexState.OFFLINE -> Color(0xFF6C7F9E) to Color(0xFF3B4760)
    NexState.SETUP -> Cyber.Yellow to Cyber.Amber
}

/** Rotation + perspective without allocations: the draw loop runs every frame. */
private class Projector(yaw: Float, pitch: Float, val camera: Float, val cx: Float, val cy: Float, val unit: Float) {
    private val cyw = cos(yaw)
    private val syw = sin(yaw)
    private val cpt = cos(pitch)
    private val spt = sin(pitch)

    /** Depth after yaw only: > 0 faces the viewer. */
    fun depth(p: V3): Float = -p.x * syw + p.z * cyw

    fun project(x: Float, y: Float, z: Float): Offset {
        val x1 = x * cyw + z * syw
        val z1 = -x * syw + z * cyw
        val y2 = y * cpt - z1 * spt
        val z2 = y * spt + z1 * cpt
        val k = camera / (camera - z2)
        return Offset(cx + x1 * k * unit, cy - y2 * k * unit)
    }

    fun project(p: V3): Offset = project(p.x, p.y, p.z)
}

private class HoloMesh {
    val rings = HoloGeometry.rings()
    val meridians = HoloGeometry.meridians()
    val bodyMeridians = HoloGeometry.bodyMeridians()
    val features = HoloGeometry.featureLines()
    val ringPaths = Array(rings.size) { Array(3) { Path() } }
    val meridianPaths = Array(meridians.size) { Path() }
    val bodyMeridianPaths = Array(bodyMeridians.size) { Path() }
    val featurePaths = Array(features.size) { Path() }
    val sparkle = Path()
}

/**
 * The hologram. [speaking] animates the mouth (a reply is streaming or being
 * read aloud); [level] is the live microphone level 0..1 while listening.
 */
@Composable
fun NexHologram(
    state: NexState,
    mode: EffectsMode,
    modifier: Modifier = Modifier,
    size: Dp = 280.dp,
    speaking: Boolean = false,
    level: Float = 0f,
    description: String = "NEX: " + state.title,
    showPad: Boolean = true,
    headOnly: Boolean = false
) {
    val (top, bottom) = holoColors(state)
    val topColor by animateColorAsState(top, tween(NexusMotion.STATE_CHANGE * 2), label = "holo-top")
    val bottomColor by animateColorAsState(bottom, tween(NexusMotion.STATE_CHANGE * 2), label = "holo-bottom")
    val clock = holoClock(mode)
    val mesh = remember { HoloMesh() }
    val particles = remember { List(34) { i -> Particle.seeded(i) } }
    Box(
        modifier
            .size(width = if (headOnly) size else size * 0.92f, height = size)
            .semantics { contentDescription = description }
            .drawWithCache {
                onDrawBehind {
                    val t = clock.value
                    drawHologram(
                        mesh, particles, t, mode, state, topColor, bottomColor,
                        speaking, level.coerceIn(0f, 1f), showPad && !headOnly, headOnly
                    )
                }
            }
    )
}

/** Seconds since start for animated modes; a fixed pleasant pose for Static. */
@Composable
private fun holoClock(mode: EffectsMode): State<Float> {
    if (!MotionPolicy.animates(mode)) return remember { mutableStateOf(1.35f) }
    val transition = rememberInfiniteTransition(label = "holo-clock")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 3600f,
        animationSpec = infiniteRepeatable(tween(3_600_000, easing = LinearEasing)),
        label = "holo-seconds"
    )
}

private data class Particle(val x: Float, val speed: Float, val phase: Float, val size: Float) {
    companion object {
        fun seeded(i: Int): Particle {
            fun r(k: Int): Float {
                val v = sin((i * 12.9898 + k * 78.233)) * 43758.5453
                return (v - floor(v)).toFloat()
            }
            return Particle(r(1) * 2f - 1f, 0.10f + 0.16f * r(2), r(3), 0.6f + 1.4f * r(4))
        }
    }
}

private fun DrawScope.drawHologram(
    mesh: HoloMesh,
    particles: List<Particle>,
    t: Float,
    mode: EffectsMode,
    state: NexState,
    top: Color,
    bottom: Color,
    speaking: Boolean,
    level: Float,
    showPad: Boolean,
    headOnly: Boolean
) {
    val animated = MotionPolicy.animates(mode)
    val vivid = mode == EffectsMode.EXPERIMENTAL && !headOnly
    val w = size.width
    val h = size.height
    // model space: y from +1.0 (crown) to -2.1 (pad); scale to fit the box
    val unit = if (headOnly) h / 2.2f else h / 3.30f
    val cx = w / 2f
    val cy = if (headOnly) h * 0.46f else h * 0.31f
    val camera = 5.2f

    val yaw = when {
        !animated -> 0.32f
        state == NexState.LISTENING -> 0.10f * sin(t * 0.9f)
        state == NexState.ERROR -> 0.22f + 0.05f * sin(t * 9f)
        else -> 0.34f * sin(t * 0.42f) + 0.05f * sin(t * 1.1f)
    }
    val pitch = if (animated) 0.06f + 0.025f * sin(t * 0.33f) else 0.07f
    val flicker = if (vivid) 0.90f + 0.10f * (0.5f + 0.5f * sin(t * 23f) * sin(t * 7.3f)) else 1f
    val dim = (if (state == NexState.OFFLINE) 0.6f else 1f) * flicker
    val pr = Projector(yaw, pitch, camera, cx, cy, unit)
    val px = 1.dp.toPx()

    if (headOnly) {
        drawContext.canvas.save()
        drawContext.canvas.clipRect(0f, 0f, w, h)
    }

    // ---- light cone ----
    val padY = cy + 2.04f * unit
    val padRx = 1.40f * unit
    val padRy = padRx * 0.17f
    if (showPad) {
        val cone = Path().apply {
            moveTo(cx - padRx * 0.92f, padY)
            lineTo(cx - 0.66f * unit, cy - 0.95f * unit)
            lineTo(cx + 0.66f * unit, cy - 0.95f * unit)
            lineTo(cx + padRx * 0.92f, padY)
            close()
        }
        drawPath(
            cone,
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to bottom.copy(alpha = 0.05f * dim),
                1f to bottom.copy(alpha = 0.22f * dim),
                startY = cy - 0.95f * unit,
                endY = padY
            )
        )
    }

    // ---- contour rings: back, rim and front buckets ----
    val headRings = HoloGeometry.HEAD_RINGS
    val ringCount = mesh.rings.size
    val glitchBand = if (vivid && (state == NexState.ERROR || (t % 7.3f) in 3.0f..3.14f)) (t * 0.7f) % 1f else -1f
    for ((index, ring) in mesh.rings.withIndex()) {
        if (headOnly && index >= headRings + 3) continue
        val paths = mesh.ringPaths[index]
        paths[0].reset(); paths[1].reset(); paths[2].reset()
        var prev = Offset.Zero
        var prevDepth = 0f
        for (j in 0..ring.size) {
            val p = ring[j % ring.size]
            val depth = pr.depth(p)
            val o = pr.project(p)
            if (j > 0) {
                val d = (depth + prevDepth) / 2f
                val bucket = when {
                    d < -0.05f -> 0
                    d < 0.32f -> 1
                    else -> 2
                }
                paths[bucket].moveTo(prev.x, prev.y)
                paths[bucket].lineTo(o.x, o.y)
            }
            prev = o
            prevDepth = depth
        }
        val f = index.toFloat() / (ringCount - 1)
        val y = ring[0].y
        val fade = if (index >= headRings) 1f - HoloGeometry.smoothstep(-1.55f, -1.92f, y) * 0.92f else 1f
        val color = lerp(top, bottom, f.pow(0.75f))
        if (glitchBand >= 0f && abs(f - glitchBand) < 0.07f) {
            for (path in paths) path.translate(Offset(6 * px, 0f))
        }
        drawPath(paths[0], color.copy(alpha = 0.10f * dim * fade), style = Stroke(width = 0.9f * px))
        drawPath(paths[1], color.copy(alpha = 0.16f * dim * fade), style = Stroke(width = 3.5f * px))
        drawPath(paths[1], color.copy(alpha = 0.55f * dim * fade), style = Stroke(width = 1.0f * px))
        drawPath(paths[2], color.copy(alpha = 0.20f * dim * fade), style = Stroke(width = 4.0f * px))
        drawPath(paths[2], color.copy(alpha = 0.92f * dim * fade), style = Stroke(width = 1.2f * px))
    }

    // ---- meridians on the visible side: they draw the face profile ----
    val meridianColor = lerp(top, bottom, 0.2f)
    for ((index, meridian) in mesh.meridians.withIndex()) {
        val path = mesh.meridianPaths[index]
        path.reset()
        var has = false
        var prev = Offset.Zero
        for (p in meridian) {
            val o = pr.project(p)
            if (pr.depth(p) > 0.04f) {
                if (has) { path.moveTo(prev.x, prev.y); path.lineTo(o.x, o.y) }
                prev = o; has = true
            } else has = false
        }
        drawPath(path, meridianColor.copy(alpha = 0.30f * dim), style = Stroke(width = 0.8f * px))
    }
    if (!headOnly) {
        for ((index, meridian) in mesh.bodyMeridians.withIndex()) {
            val path = mesh.bodyMeridianPaths[index]
            path.reset()
            var has = false
            var prev = Offset.Zero
            for (p in meridian) {
                val o = pr.project(p)
                if (pr.depth(p) > 0.02f && p.y > -1.80f) {
                    if (has) { path.moveTo(prev.x, prev.y); path.lineTo(o.x, o.y) }
                    prev = o; has = true
                } else has = false
            }
            drawPath(path, bottom.copy(alpha = 0.26f * dim), style = Stroke(width = 0.8f * px))
        }
    }

    // ---- face accents (brows, nose, lips, cheekbones) ----
    val accent = lerp(top, Color.White, 0.30f)
    for ((index, line) in mesh.features.withIndex()) {
        val isLip = index == 4 || index == 5
        if (isLip && speaking && animated) continue
        val path = mesh.featurePaths[index]
        path.reset()
        var has = false
        var prev = Offset.Zero
        for (p in line) {
            val o = pr.project(p)
            if (pr.depth(p) > 0.06f) {
                if (has) { path.moveTo(prev.x, prev.y); path.lineTo(o.x, o.y) }
                prev = o; has = true
            } else has = false
        }
        drawPath(path, top.copy(alpha = 0.30f * dim), style = Stroke(width = 4f * px, cap = StrokeCap.Round))
        drawPath(path, accent.copy(alpha = 0.95f * dim), style = Stroke(width = 1.5f * px, cap = StrokeCap.Round))
    }

    // ---- sparkle: points on the front of the head ----
    mesh.sparkle.reset()
    for (index in 1 until headRings step 3) {
        val ring = mesh.rings[index]
        for (j in ring.indices step 6) {
            val p = ring[j]
            if (pr.depth(p) > 0.25f) {
                val o = pr.project(p)
                mesh.sparkle.addOval(androidx.compose.ui.geometry.Rect(o.x - 1.1f * px, o.y - 1.1f * px, o.x + 1.1f * px, o.y + 1.1f * px))
            }
        }
    }
    drawPath(mesh.sparkle, lerp(top, Color.White, 0.5f).copy(alpha = 0.85f * dim))

    // ---- eyes ----
    val blink = if (animated) {
        val cycle = t % 4.6f
        if (cycle < 0.14f) abs(cycle - 0.07f) / 0.07f else 1f
    } else 1f
    val eyeColor = lerp(top, Color.White, 0.45f)
    for (left in listOf(true, false)) {
        val e = HoloGeometry.eye(left)
        if (pr.depth(e) < 0f) continue
        val o = pr.project(e)
        val rx = 0.088f * unit
        val ry = rx * 0.40f * blink.coerceAtLeast(0.08f)
        drawCircle(
            Brush.radialGradient(listOf(top.copy(alpha = 0.60f * dim), Color.Transparent), center = o, radius = rx * 2.7f),
            radius = rx * 2.7f, center = o
        )
        drawOval(eyeColor.copy(alpha = 0.97f * dim), topLeft = Offset(o.x - rx, o.y - ry), size = Size(rx * 2, ry * 2))
    }

    // ---- mouth: a waveform while NEX speaks or hears ----
    val m = HoloGeometry.mouth()
    val mouthActive = (speaking && animated) || (state == NexState.LISTENING && level > 0.02f)
    if (mouthActive && pr.depth(m) > 0f) {
        val mouth = Path()
        val steps = 16
        for (i in 0..steps) {
            val u = i / steps.toFloat()
            val phi = -0.27f + 0.54f * u
            val base = HoloGeometry.surfaceAt(HoloGeometry.MOUTH_Y - 0.01f, phi)
            val envelope = sin(PI.toFloat() * u)
            val amp = if (speaking) 0.055f * envelope * (0.55f + 0.45f * sin(t * 17f + u * 9f)) * sin(t * 11f + u * 13f)
            else 0.04f * envelope * level * sin(t * 14f + u * 10f)
            val o = pr.project(base.x, base.y + amp, base.z + 0.025f)
            if (i == 0) mouth.moveTo(o.x, o.y) else mouth.lineTo(o.x, o.y)
        }
        drawPath(mouth, top.copy(alpha = 0.35f * dim), style = Stroke(width = 4.5f * px, cap = StrokeCap.Round))
        drawPath(mouth, accent.copy(alpha = 0.98f * dim), style = Stroke(width = 1.8f * px, cap = StrokeCap.Round))
    }

    // ---- halo: a tilted data ring around the head ----
    if (!headOnly && (animated || state == NexState.THINKING)) {
        val halo = Path()
        val speed = if (state == NexState.THINKING) 1.6f else 0.35f
        for (i in 0..72) {
            val a = (2 * PI * i / 72).toFloat()
            val o = pr.project(1.04f * cos(a), 0.40f + 0.10f * sin(a), 1.04f * sin(a))
            if (i == 0) halo.moveTo(o.x, o.y) else halo.lineTo(o.x, o.y)
        }
        drawPath(halo, top.copy(alpha = 0.22f * dim), style = Stroke(width = 1f * px))
        for (k in 0 until 3) {
            val a = t * speed + k * 2.094f
            val o = pr.project(1.04f * cos(a), 0.40f + 0.10f * sin(a), 1.04f * sin(a))
            drawCircle(top.copy(alpha = 0.95f * dim), radius = 2.2f * px, center = o)
            drawCircle(top.copy(alpha = 0.25f * dim), radius = 6f * px, center = o)
        }
    }

    // ---- projector pad ----
    if (showPad) {
        val pulse = when {
            state == NexState.LISTENING -> 0.35f + 0.65f * level
            animated -> 0.5f + 0.5f * sin(t * 1.4f)
            else -> 0.6f
        }
        drawOval(
            Brush.radialGradient(
                listOf(bottom.copy(alpha = 0.55f * dim), bottom.copy(alpha = 0.12f * dim), Color.Transparent),
                center = Offset(cx, padY), radius = padRx
            ),
            topLeft = Offset(cx - padRx, padY - padRy), size = Size(padRx * 2, padRy * 2)
        )
        for (i in 0 until 3) {
            val sc = 0.55f + 0.24f * i
            val ripple = if (state == NexState.LISTENING) 1f + 0.10f * level * (i + 1) else 1f
            drawOval(
                lerp(bottom, top, 0.3f * i).copy(alpha = (0.75f - 0.2f * i) * (0.6f + 0.4f * pulse) * dim),
                topLeft = Offset(cx - padRx * sc * ripple, padY - padRy * sc * ripple),
                size = Size(padRx * 2 * sc * ripple, padRy * 2 * sc * ripple),
                style = Stroke(width = (1.6f - 0.4f * i) * px)
            )
        }
        val ticks = 48
        for (i in 0 until ticks) {
            val a = (2 * PI * i / ticks).toFloat() + if (animated) t * 0.15f else 0f
            val ox = cx + cos(a) * padRx
            val oy = padY + sin(a) * padRy
            val inner = if (i % 4 == 0) 0.92f else 0.96f
            val alpha = if (sin(a) > 0f) 0.55f else 0.22f
            drawLine(top.copy(alpha = alpha * dim), Offset(cx + cos(a) * padRx * inner, padY + sin(a) * padRy * inner), Offset(ox, oy), px)
        }
    }

    // ---- particles rising through the cone ----
    if (vivid && showPad) {
        val topY = cy - 1.0f * unit
        for (p in particles) {
            val life = ((t * p.speed + p.phase) % 1f)
            val y = padY - (padY - topY) * life
            val spread = (padRx * 0.85f) * (1f - life * 0.45f)
            val a = sin(PI.toFloat() * life) * 0.7f
            drawCircle(lerp(bottom, top, life).copy(alpha = a * dim), radius = p.size * px, center = Offset(cx + p.x * spread, y))
        }
    }

    // ---- scanlines over the figure, and a sweeping band ----
    val figureTop = if (headOnly) 0f else cy - 1.12f * unit
    val figureBottom = if (headOnly) h else padY - padRy
    val step = 3f * px
    var yLine = figureTop
    val scan = Color(0xFF02040A).copy(alpha = 0.26f)
    while (yLine < figureBottom) {
        drawLine(scan, Offset(cx - 1.6f * unit, yLine), Offset(cx + 1.6f * unit, yLine), 1f)
        yLine += step
    }
    if (vivid) {
        val band = figureTop + ((t * 0.28f) % 1f) * (figureBottom - figureTop)
        drawRect(
            Brush.verticalGradient(
                listOf(Color.Transparent, top.copy(alpha = 0.16f), Color.Transparent),
                startY = band - 14f * px, endY = band + 14f * px
            ),
            topLeft = Offset(cx - 1.4f * unit, band - 14f * px),
            size = Size(2.8f * unit, 28f * px)
        )
    }
    if (headOnly) drawContext.canvas.restore()
}

/**
 * HUD reticle drawn around the hologram: a ticked ring, two lit arcs that
 * turn slowly (only when animations are on) and a sweep while thinking.
 */
@Composable
fun HudReticle(state: NexState, mode: EffectsMode, modifier: Modifier = Modifier) {
    val color by animateColorAsState(holoColors(state).first, tween(NexusMotion.STATE_CHANGE * 2), label = "reticle")
    val clock = holoClock(mode)
    Canvas(modifier) {
        val t = clock.value
        val r = min(size.width, size.height) / 2f * 0.96f
        val c = center
        drawCircle(color.copy(alpha = 0.18f), radius = r, center = c, style = Stroke(1.dp.toPx()))
        val ticks = 90
        for (i in 0 until ticks) {
            val a = (2 * PI * i / ticks).toFloat()
            val long = i % 15 == 0
            val inner = r - (if (long) 9.dp.toPx() else 4.dp.toPx())
            drawLine(
                color.copy(alpha = if (long) 0.6f else 0.22f),
                Offset(c.x + cos(a) * inner, c.y + sin(a) * inner),
                Offset(c.x + cos(a) * r, c.y + sin(a) * r),
                1.dp.toPx()
            )
        }
        val spin = if (MotionPolicy.animates(mode)) t * (if (state == NexState.THINKING) 40f else 9f) else 18f
        val r2 = r * 0.90f
        drawArc(
            color.copy(alpha = 0.8f), startAngle = -120f + spin, sweepAngle = 70f, useCenter = false,
            topLeft = Offset(c.x - r2, c.y - r2), size = Size(r2 * 2, r2 * 2),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt)
        )
        drawArc(
            color.copy(alpha = 0.55f), startAngle = 60f + spin, sweepAngle = 40f, useCenter = false,
            topLeft = Offset(c.x - r2, c.y - r2), size = Size(r2 * 2, r2 * 2),
            style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt)
        )
        val r3 = r * 0.82f
        drawArc(
            color.copy(alpha = 0.35f), startAngle = 200f - spin * 1.4f, sweepAngle = 110f, useCenter = false,
            topLeft = Offset(c.x - r3, c.y - r3), size = Size(r3 * 2, r3 * 2),
            style = Stroke(1.dp.toPx())
        )
        if (state == NexState.THINKING && MotionPolicy.animates(mode)) {
            drawArc(
                Brush.sweepGradient(listOf(Color.Transparent, color.copy(alpha = 0.25f), Color.Transparent), center = c),
                startAngle = spin * 3f % 360f, sweepAngle = 60f, useCenter = true,
                topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2)
            )
        }
    }
}

/** Hologram inside its reticle, with optional corner readouts on top. */
@Composable
fun NexHoloStage(
    state: NexState,
    mode: EffectsMode,
    modifier: Modifier = Modifier,
    size: Dp = 300.dp,
    speaking: Boolean = false,
    level: Float = 0f,
    overlay: @Composable BoxScope.() -> Unit = {}
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        HudReticle(state, mode, Modifier.size(size))
        NexHologram(state, mode, Modifier.align(Alignment.Center), size = size * 0.86f, speaking = speaking, level = level)
        overlay()
    }
}

/** Small hologram head for headers and message avatars (no pad, no reticle). */
@Composable
fun NexHoloAvatar(state: NexState, mode: EffectsMode, modifier: Modifier = Modifier, size: Dp = 40.dp, speaking: Boolean = false) {
    NexHologram(state, mode, modifier, size = size, speaking = speaking, showPad = false, headOnly = true)
}
fun holoColorsFor(state: NexState): Pair<androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color> = holoColors(state)
