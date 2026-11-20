package ai.hybrid.commandcenter.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.launch

/**
 * Core Orbital with NEX at its centre (handoff 5.1 + section 2).
 *
 * - Outer ring: one arc per measured system segment; colour = real status.
 * - Inner ring + constellation points: the NEXUS mark.
 * - Core: NEX — a violet crystal with an eye. Its mood is [state].
 *
 * Motion follows the spec strictly: Standby is still; Thinking rotates a
 * segment of the inner ring only while a mission really runs; Listening
 * pulses only while dictation is active; Success emits one pulse; a tap
 * makes NEX blink (a real user event). In Static mode nothing moves.
 */
@Composable
fun CoreOrbital(
    segments: List<OrbitalSegment>,
    state: NexState,
    mode: EffectsMode,
    modifier: Modifier = Modifier,
    diameter: Dp = 176.dp,
    description: String = "",
    onTap: () -> Unit = {}
) {
    val animates = MotionPolicy.animates(mode)
    val scope = rememberCoroutineScope()
    val blink = remember { Animatable(1f) }
    val pulse = remember { Animatable(0f) }

    // One-shot success pulse when NEX enters SUCCESS — a real event.
    LaunchedEffect(state, animates) {
        if (animates && state == NexState.SUCCESS) {
            pulse.snapTo(0f)
            pulse.animateTo(1f, tween(NexusMotion.SUCCESS_PULSE))
        }
    }

    // Looping motion exists only while the real state needs it; otherwise no
    // transition runs at all, so an idle deck requests no frames.
    val thinking = loopingValue(animates && state == NexState.THINKING, NexusMotion.THINKING_ROTATION, 360f, reverse = false)
    val listening = loopingValue(animates && state == NexState.LISTENING, NexusMotion.LISTENING_PULSE, 1f, reverse = true)

    Canvas(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = "NEX: " + state.title + ". " + description }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (animates) {
                    scope.launch {
                        blink.animateTo(0f, tween(90))
                        blink.animateTo(1f, tween(140))
                    }
                }
                onTap()
            }
    ) {
        val c = center
        val r = min(size.width, size.height) / 2f

        drawHalo(c, r, state, mode)
        drawSegments(c, r * 0.92f, r * 0.075f, segments)
        drawInnerRing(c, r * 0.70f, state, thinking?.value ?: 300f)
        drawConstellation(c, r * 0.70f, state)
        if (pulse.value in 0.001f..0.999f) {
            drawCircle(
                color = NexState.SUCCESS.color.copy(alpha = (1f - pulse.value) * 0.6f),
                radius = r * (0.35f + 0.6f * pulse.value),
                center = c,
                style = Stroke(width = r * 0.025f)
            )
        }
        val listeningPhase = listening?.value ?: 0f
        if (listeningPhase > 0f) {
            drawCircle(
                color = NexState.LISTENING.color.copy(alpha = 0.45f * (1f - listeningPhase)),
                radius = r * (0.42f + 0.16f * listeningPhase),
                center = c,
                style = Stroke(width = r * 0.02f)
            )
        }
        drawNex(c, r * 0.36f, state, blink.value)
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

/** NEX alone, for headers of other spaces. */
@Composable
fun NexAvatar(state: NexState, modifier: Modifier = Modifier, diameter: Dp = 44.dp) {
    Canvas(
        modifier = modifier
            .size(diameter)
            .semantics { contentDescription = "NEX: " + state.title }
    ) {
        val c = center
        val r = min(size.width, size.height) / 2f
        drawCircle(
            brush = Brush.radialGradient(
                listOf(state.color.copy(alpha = 0.28f), Color.Transparent),
                center = c, radius = r
            ),
            radius = r, center = c
        )
        drawCircle(state.color.copy(alpha = 0.55f), radius = r * 0.92f, center = c, style = Stroke(width = r * 0.06f))
        drawNex(c, r * 0.62f, state, 1f)
    }
}

private fun DrawScope.drawHalo(c: Offset, r: Float, state: NexState, mode: EffectsMode) {
    val intensity = when (mode) {
        EffectsMode.STATIC -> 0.14f
        EffectsMode.SUBTLE -> 0.22f
        EffectsMode.EXPERIMENTAL -> 0.34f
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(state.color.copy(alpha = intensity), Color.Transparent),
            center = c,
            radius = r * 1.0f
        ),
        radius = r,
        center = c
    )
}

/** Outer ring: equal arcs with gaps; colour carries the measured status. */
private fun DrawScope.drawSegments(c: Offset, radius: Float, width: Float, segments: List<OrbitalSegment>) {
    val topLeft = Offset(c.x - radius, c.y - radius)
    val arcSize = Size(radius * 2, radius * 2)
    if (segments.isEmpty()) {
        drawCircle(NexusColors.Divider, radius = radius, center = c, style = Stroke(width = width))
        return
    }
    val gap = 10f
    val sweep = 360f / segments.size - gap
    segments.forEachIndexed { index, segment ->
        val start = -90f + index * (360f / segments.size) + gap / 2
        drawArc(
            color = NexusColors.Divider,
            startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(width = width * 1.6f, cap = StrokeCap.Round)
        )
        drawArc(
            color = segment.status.color,
            startAngle = start, sweepAngle = sweep, useCenter = false,
            topLeft = topLeft, size = arcSize,
            style = Stroke(width = width, cap = StrokeCap.Round)
        )
    }
}

/** Inner ring; a bright segment rotates only while a mission really runs. */
private fun DrawScope.drawInnerRing(c: Offset, radius: Float, state: NexState, thinkingAngle: Float) {
    val broken = state == NexState.ERROR || state == NexState.OFFLINE
    drawCircle(
        color = NexusColors.NeonCyan.copy(alpha = if (broken) 0.25f else 0.35f),
        radius = radius,
        center = c,
        style = Stroke(
            width = radius * 0.03f,
            pathEffect = if (broken) PathEffect.dashPathEffect(floatArrayOf(radius * 0.08f, radius * 0.06f)) else null
        )
    )
    if (state == NexState.THINKING) {
        rotate(thinkingAngle, pivot = c) {
            drawArc(
                // The 90° arc spans the first quarter of the sweep, so the
                // gradient stops are packed into that quarter.
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.10f to NexusColors.NeonViolet,
                    0.25f to NexusColors.NeonCyan,
                    1f to NexusColors.NeonCyan,
                    center = c
                ),
                startAngle = 0f, sweepAngle = 90f, useCenter = false,
                topLeft = Offset(c.x - radius, c.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = radius * 0.07f, cap = StrokeCap.Round)
            )
        }
    }
}

/** Four constellation points joined to the core by signal traces. */
private fun DrawScope.drawConstellation(c: Offset, radius: Float, state: NexState) {
    val accent = if (state == NexState.OFFLINE) NexusColors.TextMuted else NexusColors.NeonCyan
    for (i in 0 until 4) {
        val angle = Math.toRadians((-90.0 + i * 90.0))
        val point = Offset(c.x + radius * cos(angle).toFloat(), c.y + radius * sin(angle).toFloat())
        val inner = Offset(c.x + radius * 0.62f * cos(angle).toFloat(), c.y + radius * 0.62f * sin(angle).toFloat())
        drawLine(accent.copy(alpha = 0.45f), inner, point, strokeWidth = radius * 0.018f)
        drawCircle(accent, radius = radius * 0.045f, center = point)
        drawCircle(accent.copy(alpha = 0.25f), radius = radius * 0.09f, center = point)
    }
}

/**
 * NEX itself: a violet crystal (the NEXUS mark) with an eye whose shape is
 * the mood. [openness] is 1 normally and dips to 0 during a tap blink.
 */
private fun DrawScope.drawNex(c: Offset, half: Float, state: NexState, openness: Float) {
    val crystal = Path().apply {
        moveTo(c.x, c.y - half)
        lineTo(c.x + half * 0.78f, c.y)
        lineTo(c.x, c.y + half)
        lineTo(c.x - half * 0.78f, c.y)
        close()
    }
    val coreTop = if (state == NexState.OFFLINE) NexusColors.TextMuted else NexusColors.NeonViolet
    val coreBottom = when (state) {
        NexState.ERROR -> NexusColors.SignalRed
        NexState.ATTENTION, NexState.SETUP -> NexusColors.SignalAmber
        NexState.SUCCESS -> NexusColors.SignalGreen
        NexState.OFFLINE -> NexusColors.ElevatedSurface
        else -> Color(0xFF5B3FD6)
    }
    drawPath(
        path = crystal,
        brush = Brush.verticalGradient(listOf(coreTop, coreBottom), startY = c.y - half, endY = c.y + half)
    )
    drawPath(crystal, color = Color.White.copy(alpha = 0.22f), style = Stroke(width = half * 0.05f))
    // facet highlight
    val facet = Path().apply {
        moveTo(c.x, c.y - half)
        lineTo(c.x + half * 0.78f, c.y)
        lineTo(c.x, c.y - half * 0.15f)
        close()
    }
    drawPath(facet, color = Color.White.copy(alpha = 0.10f))

    drawEye(Offset(c.x, c.y + half * 0.05f), half * 0.36f, state, openness)
}

private fun DrawScope.drawEye(c: Offset, w: Float, state: NexState, openness: Float) {
    val iris = when (state) {
        NexState.ERROR -> NexusColors.SignalRed
        NexState.ATTENTION, NexState.SETUP -> NexusColors.SignalAmber
        NexState.SUCCESS -> NexusColors.SignalGreen
        NexState.OFFLINE -> NexusColors.TextSecondary
        else -> NexusColors.NeonCyan
    }
    val baseHeight = when (state) {
        NexState.LISTENING -> w * 0.62f
        NexState.OFFLINE -> w * 0.16f
        NexState.ERROR -> w * 0.30f
        else -> w * 0.46f
    }
    val h = baseHeight * openness.coerceIn(0f, 1f)

    if (state == NexState.SUCCESS && openness > 0.5f) {
        // content "smiling" eye: an upward arc
        // content "^" eye; a dark under-stroke keeps it readable on the green crystal
        val top = Offset(c.x - w, c.y - w * 0.35f)
        val arcSize = Size(w * 2, w * 1.5f)
        drawArc(
            color = Color(0xFF0B1020),
            startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = top, size = arcSize,
            style = Stroke(width = w * 0.42f, cap = StrokeCap.Round)
        )
        drawArc(
            color = NexusColors.TextPrimary,
            startAngle = 200f, sweepAngle = 140f, useCenter = false,
            topLeft = top, size = arcSize,
            style = Stroke(width = w * 0.24f, cap = StrokeCap.Round)
        )
        return
    }
    if (h < w * 0.06f) {
        drawLine(iris, Offset(c.x - w, c.y), Offset(c.x + w, c.y), strokeWidth = w * 0.16f, cap = StrokeCap.Round)
        return
    }
    drawOval(
        color = Color(0xFF0B1020),
        topLeft = Offset(c.x - w, c.y - h),
        size = Size(w * 2, h * 2)
    )
    drawOval(
        color = iris,
        topLeft = Offset(c.x - w, c.y - h),
        size = Size(w * 2, h * 2),
        style = Stroke(width = w * 0.12f)
    )
    val pupilRadius = min(h * 0.62f, w * 0.34f)
    drawCircle(iris, radius = pupilRadius, center = Offset(c.x + w * 0.12f, c.y - h * 0.05f))
    drawCircle(Color.White.copy(alpha = 0.85f), radius = pupilRadius * 0.32f, center = Offset(c.x + w * 0.24f, c.y - h * 0.28f))
}
