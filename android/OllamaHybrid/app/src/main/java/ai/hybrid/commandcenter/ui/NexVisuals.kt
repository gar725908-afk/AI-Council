package ai.hybrid.commandcenter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * NEX — the NEXUS companion. An iridescent sphere with a dark glass visor and
 * two glowing eyes; the eyes carry the mood, the sphere the identity.
 *
 * Motion rules (handoff 5): states that mean work — Thinking, Listening —
 * animate only while that work really happens; Success and Error play once
 * on the real transition; a tap squashes and blinks. The only idle motion is
 * an occasional blink (a character trait, not a status claim), off in Static.
 */

private data class Mood(val bodyLight: Color, val bodyMid: Color, val bodyDeep: Color, val eye: Color)

private fun mood(state: NexState): Mood = when (state) {
    NexState.STANDBY -> Mood(Color(0xFFE7E0FF), Color(0xFF8C6CFF), Color(0xFF3A1F9E), NexusColors.NeonCyan)
    NexState.LISTENING -> Mood(Color(0xFFDDF8FF), Color(0xFF4FB6FF), Color(0xFF2A3FB8), Color(0xFF9DF1FF))
    NexState.THINKING -> Mood(Color(0xFFF3E4FF), Color(0xFFA86CFF), Color(0xFF5B1F9E), Color(0xFFD6C8FF))
    NexState.SUCCESS -> Mood(Color(0xFFE2FFF4), Color(0xFF43D6A0), Color(0xFF0E6E55), Color(0xFFB9FFE4))
    NexState.ATTENTION -> Mood(Color(0xFFFFF1DA), Color(0xFFF2B65D), Color(0xFF8A4B12), Color(0xFFFFE2A8))
    NexState.SETUP -> Mood(Color(0xFFFFF1DA), Color(0xFFB98CFF), Color(0xFF4A2A8E), NexusColors.SignalAmber)
    NexState.ERROR -> Mood(Color(0xFFFFE0E6), Color(0xFFFF647C), Color(0xFF7A1430), Color(0xFFFFB3C0))
    NexState.OFFLINE -> Mood(Color(0xFFC9CED9), Color(0xFF5E6577), Color(0xFF22262F), Color(0xFF8B93A6))
}

/**
 * NEX with the Core Orbital around it (handoff 5.1): a thin ring split into
 * one arc per measured system segment, coloured by the measurement.
 */
@Composable
fun CoreOrbital(
    segments: List<OrbitalSegment>,
    state: NexState,
    mode: EffectsMode,
    modifier: Modifier = Modifier,
    diameter: Dp = 220.dp,
    description: String = "",
    onTap: () -> Unit = {}
) {
    NexOrb(
        state = state,
        mode = mode,
        modifier = modifier,
        diameter = diameter,
        description = description,
        segments = segments,
        onTap = onTap
    )
}

/** NEX alone, for headers. */
@Composable
fun NexAvatar(state: NexState, modifier: Modifier = Modifier, diameter: Dp = 44.dp, mode: EffectsMode = EffectsMode.STATIC) {
    NexOrb(state = state, mode = mode, modifier = modifier, diameter = diameter, segments = null, compact = true)
}

@Composable
fun NexOrb(
    state: NexState,
    mode: EffectsMode,
    modifier: Modifier = Modifier,
    diameter: Dp = 200.dp,
    description: String = "",
    segments: List<OrbitalSegment>? = null,
    compact: Boolean = false,
    onTap: (() -> Unit)? = null
) {
    val animates = MotionPolicy.animates(mode)
    val scope = rememberCoroutineScope()
    val blink = remember { Animatable(1f) }
    val squash = remember { Animatable(1f) }
    val pulse = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }

    // Occasional blink while idle-capable; frames are requested only during it.
    LaunchedEffect(animates, state) {
        if (!animates || state == NexState.OFFLINE || state == NexState.SUCCESS || state == NexState.ERROR) return@LaunchedEffect
        val random = Random(System.nanoTime())
        while (true) {
            delay(3_200L + random.nextLong(3_600L))
            blink.animateTo(0.08f, tween(90))
            blink.animateTo(1f, tween(150))
        }
    }

    // One-shot reactions to real transitions.
    LaunchedEffect(state, animates) {
        if (!animates) return@LaunchedEffect
        when (state) {
            NexState.SUCCESS -> {
                launch {
                    pulse.snapTo(0f)
                    pulse.animateTo(1f, tween(NexusMotion.SUCCESS_PULSE))
                }
                squash.animateTo(1.07f, tween(140))
                squash.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            }
            NexState.ERROR -> {
                for (x in listOf(1f, -1f, 0.6f, -0.6f, 0f)) shake.animateTo(x, tween(60))
            }
            else -> Unit
        }
    }

    val thinking = loopingValue(animates && state == NexState.THINKING, NexusMotion.THINKING_ROTATION, 360f, reverse = false)
    val listening = loopingValue(animates && state == NexState.LISTENING, NexusMotion.LISTENING_PULSE * 2, 1f, reverse = false)
    val float = loopingValue(mode == EffectsMode.EXPERIMENTAL && !compact, 3_600, 1f, reverse = true)

    val tapModifier = if (onTap != null) {
        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
            if (animates) {
                scope.launch {
                    squash.animateTo(0.92f, tween(90))
                    squash.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
                }
                scope.launch {
                    blink.animateTo(0.08f, tween(80))
                    blink.animateTo(1f, tween(140))
                }
            }
            onTap()
        }
    } else {
        Modifier
    }

    Canvas(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = "NEX: " + state.title + if (description.isNotBlank()) ". $description" else "" }
            .then(tapModifier)
            .graphicsLayer {
                val s = squash.value
                scaleX = s
                scaleY = s
                translationX = shake.value * 6.dp.toPx()
                translationY = (float?.value ?: 0.5f).let { (it - 0.5f) * 6.dp.toPx() }
            }
    ) {
        val c = center
        val half = min(size.width, size.height) / 2f
        val body = if (segments != null) half * 0.60f else half * 0.86f
        val m = mood(state)

        drawHalo(c, body, m, mode, compact)

        if (segments != null) drawOrbitRing(c, half * 0.93f, segments)

        listening?.value?.let { phase -> drawListeningRipples(c, body, phase, m.eye) }

        if (pulse.value in 0.001f..0.999f) {
            drawCircle(
                color = m.bodyMid.copy(alpha = (1f - pulse.value) * 0.55f),
                radius = body * (1.05f + 0.55f * pulse.value),
                center = c,
                style = Stroke(width = body * 0.04f)
            )
        }

        drawBody(c, body, m, state)
        drawVisor(c, body)
        drawEyes(c, body, state, m.eye, blink.value)

        thinking?.value?.let { angle -> drawThinkingSatellites(c, body, angle) }
        if (state == NexState.THINKING && thinking == null) drawThinkingSatellites(c, body, 300f)
        if (state == NexState.OFFLINE) drawSleepMarks(c, body)
    }
}

@Composable
private fun loopingValue(active: Boolean, durationMillis: Int, target: Float, reverse: Boolean): State<Float>? {
    if (!active) return null
    val transition = rememberInfiniteTransition(label = "nex-loop")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = target,
        animationSpec = infiniteRepeatable(
            tween(durationMillis, easing = LinearEasing),
            if (reverse) RepeatMode.Reverse else RepeatMode.Restart
        ),
        label = "nex-loop-value"
    )
}

private fun DrawScope.drawHalo(c: Offset, body: Float, m: Mood, mode: EffectsMode, compact: Boolean) {
    val strength = when (mode) {
        EffectsMode.STATIC -> 0.30f
        EffectsMode.SUBTLE -> 0.42f
        EffectsMode.EXPERIMENTAL -> 0.58f
    } * if (compact) 0.6f else 1f
    val radius = body * if (compact) 1.18f else 1.75f
    drawCircle(
        brush = Brush.radialGradient(
            0f to m.bodyMid.copy(alpha = strength),
            0.5f to m.bodyMid.copy(alpha = strength * 0.35f),
            1f to Color.Transparent,
            center = c,
            radius = radius
        ),
        radius = radius,
        center = c
    )
}

private fun DrawScope.drawBody(c: Offset, r: Float, m: Mood, state: NexState) {
    // Main sphere: light from the upper left.
    drawCircle(
        brush = Brush.radialGradient(
            0f to m.bodyLight,
            0.35f to m.bodyMid,
            0.85f to m.bodyDeep,
            1f to m.bodyDeep.copy(alpha = 0.95f),
            center = Offset(c.x - r * 0.38f, c.y - r * 0.45f),
            radius = r * 1.75f
        ),
        radius = r,
        center = c
    )
    // Iridescent sheen.
    if (state != NexState.OFFLINE) {
        drawCircle(
            brush = Brush.sweepGradient(
                listOf(
                    NexusColors.NeonCyan.copy(alpha = 0.22f),
                    Color.Transparent,
                    NexusColors.NeonMagenta.copy(alpha = 0.26f),
                    Color.Transparent,
                    NexusColors.NeonCyan.copy(alpha = 0.22f)
                ),
                center = c
            ),
            radius = r,
            center = c
        )
    }
    // Specular highlight.
    val spot = Offset(c.x - r * 0.42f, c.y - r * 0.55f)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.55f), Color.Transparent),
            center = spot,
            radius = r * 0.42f
        ),
        radius = r * 0.42f,
        center = spot
    )
    // Rim light.
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.02f)),
            startY = c.y - r,
            endY = c.y + r
        ),
        radius = r,
        center = c,
        style = Stroke(width = r * 0.025f)
    )
}

/** Dark glass visor that holds the eyes. */
private fun DrawScope.drawVisor(c: Offset, r: Float) {
    val w = r * 1.28f
    val h = r * 0.74f
    val topLeft = Offset(c.x - w / 2f, c.y - h / 2f + r * 0.06f)
    val radius = CornerRadius(h / 2f, h / 2f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color(0xF2080A14), Color(0xE6141832)),
            startY = topLeft.y,
            endY = topLeft.y + h
        ),
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = radius
    )
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.03f)),
            startY = topLeft.y,
            endY = topLeft.y + h
        ),
        topLeft = topLeft,
        size = Size(w, h),
        cornerRadius = radius,
        style = Stroke(width = r * 0.02f)
    )
}

private fun DrawScope.drawEyes(c: Offset, r: Float, state: NexState, color: Color, openness: Float) {
    val eyeY = c.y + r * 0.06f
    val dx = r * 0.30f
    val left = Offset(c.x - dx, eyeY)
    val right = Offset(c.x + dx, eyeY)
    val stroke = r * 0.075f

    fun glow(at: Offset, radius: Float) {
        drawCircle(
            brush = Brush.radialGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent), center = at, radius = radius),
            radius = radius,
            center = at
        )
    }

    fun capsule(at: Offset, w: Float, h: Float) {
        val height = (h * openness.coerceIn(0.06f, 1f)).coerceAtLeast(w * 0.5f)
        glow(at, h * 0.9f)
        drawRoundRect(
            color = color,
            topLeft = Offset(at.x - w / 2f, at.y - height / 2f),
            size = Size(w, height),
            cornerRadius = CornerRadius(w / 2f, w / 2f)
        )
    }

    when (state) {
        NexState.SUCCESS -> listOf(left, right).forEach { at ->
            glow(at, r * 0.22f)
            drawArc(
                color = color,
                startAngle = 200f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(at.x - r * 0.11f, at.y - r * 0.05f),
                size = Size(r * 0.22f, r * 0.18f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        NexState.ERROR -> listOf(left, right).forEach { at ->
            glow(at, r * 0.2f)
            val k = r * 0.075f
            drawLine(color, Offset(at.x - k, at.y - k), Offset(at.x + k, at.y + k), stroke, StrokeCap.Round)
            drawLine(color, Offset(at.x - k, at.y + k), Offset(at.x + k, at.y - k), stroke, StrokeCap.Round)
        }
        NexState.OFFLINE -> listOf(left, right).forEach { at ->
            drawArc(
                color = color,
                startAngle = 20f,
                sweepAngle = 140f,
                useCenter = false,
                topLeft = Offset(at.x - r * 0.1f, at.y - r * 0.09f),
                size = Size(r * 0.2f, r * 0.14f),
                style = Stroke(width = stroke * 0.8f, cap = StrokeCap.Round)
            )
        }
        NexState.LISTENING -> {
            capsule(left, r * 0.15f, r * 0.30f)
            capsule(right, r * 0.15f, r * 0.30f)
        }
        NexState.THINKING -> {
            // gaze up and to the side, a little narrowed
            val lift = Offset(r * 0.06f, -r * 0.06f)
            capsule(left + lift, r * 0.12f, r * 0.20f)
            capsule(right + lift, r * 0.12f, r * 0.20f)
        }
        NexState.ATTENTION -> {
            capsule(left, r * 0.13f, r * 0.32f)
            capsule(right, r * 0.13f, r * 0.32f)
        }
        NexState.SETUP -> {
            val look = Offset(-r * 0.05f, 0f)
            capsule(left + look, r * 0.12f, r * 0.14f)
            capsule(right + look, r * 0.12f, r * 0.14f)
        }
        NexState.STANDBY -> {
            capsule(left, r * 0.12f, r * 0.26f)
            capsule(right, r * 0.12f, r * 0.26f)
        }
    }
}

private fun DrawScope.drawThinkingSatellites(c: Offset, r: Float, angle: Float) {
    val orbit = r * 1.28f
    rotate(angle, pivot = c) {
        for (i in 0 until 3) {
            val a = Math.toRadians(i * 120.0 - 90.0)
            val p = Offset(c.x + orbit * cos(a).toFloat(), c.y + orbit * sin(a).toFloat())
            val tint = if (i == 0) NexusColors.NeonCyan else NexusColors.NeonViolet
            drawCircle(tint.copy(alpha = 0.30f), radius = r * 0.10f, center = p)
            drawCircle(tint, radius = r * 0.045f, center = p)
        }
    }
}

private fun DrawScope.drawListeningRipples(c: Offset, r: Float, phase: Float, color: Color) {
    for (i in 0 until 2) {
        val t = (phase + i * 0.5f) % 1f
        drawCircle(
            color = color.copy(alpha = 0.40f * (1f - t)),
            radius = r * (1.08f + 0.55f * t),
            center = c,
            style = Stroke(width = r * 0.03f)
        )
    }
}

private fun DrawScope.drawSleepMarks(c: Offset, r: Float) {
    fun z(x: Float, y: Float, s: Float, alpha: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + s, y)
            lineTo(x, y + s)
            lineTo(x + s, y + s)
        }
        drawPath(path, NexusColors.TextSecondary.copy(alpha = alpha), style = Stroke(width = s * 0.18f, cap = StrokeCap.Round))
    }
    z(c.x + r * 0.62f, c.y - r * 0.95f, r * 0.18f, 0.9f)
    z(c.x + r * 0.88f, c.y - r * 1.22f, r * 0.12f, 0.6f)
}

/** Thin status ring: an arc per measured segment, a faint full track behind. */
private fun DrawScope.drawOrbitRing(c: Offset, radius: Float, segments: List<OrbitalSegment>) {
    val topLeft = Offset(c.x - radius, c.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    val width = radius * 0.028f
    drawCircle(Color.White.copy(alpha = 0.06f), radius = radius, center = c, style = Stroke(width = width))
    if (segments.isEmpty()) return
    val gap = 16f
    val sweep = 360f / segments.size - gap
    segments.forEachIndexed { index, segment ->
        val start = -90f + index * (360f / segments.size) + gap / 2
        val color = segment.status.color
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = width * 4f, cap = StrokeCap.Round)
        )
        drawArc(
            color = color,
            startAngle = start,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = width * 1.4f, cap = StrokeCap.Round)
        )
        // a bright node at the arc's end
        val end = Math.toRadians((start + sweep).toDouble())
        val p = Offset(c.x + radius * cos(end).toFloat(), c.y + radius * sin(end).toFloat())
        drawCircle(color.copy(alpha = 0.35f), radius = width * 3.2f, center = p)
        drawCircle(Color.White, radius = width * 1.2f, center = p)
    }
}
