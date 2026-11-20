package ai.hybrid.commandcenter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.random.Random

/**
 * «Deep Field», the 3055 look: open space with fine star dust and a soft
 * nebula, and the curved horizon of a planet along the bottom edge, as if
 * the chat ran on a ship in orbit. The atmosphere glow takes NEX's colour,
 * so the whole screen quietly reflects the real state (thinking, error…).
 * Everything here is static; only a state change fades the glow colour.
 */
object Space {
    val Deep = Color(0xFF02030A)
    val Night = Color(0xFF070918)
    val Planet = Color(0xFF0A0F2A)
    val Ion = Color(0xFF6FF3FF)
    val Plasma = Color(0xFF9B7BFF)
    val Flare = Color(0xFFFF6AD5)

    /** Ion → plasma → flare, the signature gradient for primary actions. */
    val Signature = Brush.linearGradient(listOf(Ion, Plasma, Flare))

    fun rim(state: NexState?): Color = when (state) {
        null, NexState.STANDBY, NexState.LISTENING -> Ion
        NexState.THINKING -> Plasma
        NexState.SUCCESS -> NexusColors.SignalGreen
        NexState.ATTENTION, NexState.SETUP -> NexusColors.SignalAmber
        NexState.ERROR -> NexusColors.SignalRed
        NexState.OFFLINE -> Color(0xFF6B7386)
    }
}

@Composable
fun SpaceBackground(
    modifier: Modifier = Modifier,
    state: NexState? = null,
    showHorizon: Boolean = true,
    horizonHeight: Dp = 150.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val rim by animateColorAsState(Space.rim(state), tween(700), label = "horizon-rim")
    Box(
        modifier
            .background(Space.Deep)
            .drawWithCache {
                val w = size.width
                val h = size.height
                val random = Random(3055)
                // Three depths of star dust, positions fixed for this size.
                val stars = List(170) {
                    val depth = random.nextFloat()
                    Triple(
                        Offset(random.nextFloat() * w, random.nextFloat() * h),
                        0.5f + depth * 1.3f,
                        0.18f + depth * 0.62f
                    )
                }
                val tints = List(stars.size) {
                    when (random.nextInt(9)) {
                        0 -> Space.Ion
                        1 -> Color(0xFFC9B8FF)
                        else -> Color.White
                    }
                }
                val horizonY = h - horizonHeight.toPx()
                val planetRadius = w * 2.4f
                val planetCenter = Offset(w / 2f, horizonY + planetRadius)
                onDrawBehind {
                    // nebula
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFF2B1B6B).copy(alpha = 0.55f),
                            0.6f to Color(0xFF2B1B6B).copy(alpha = 0.12f),
                            1f to Color.Transparent,
                            center = Offset(w * 0.05f, h * 0.10f),
                            radius = w * 1.05f
                        ),
                        radius = w * 1.05f,
                        center = Offset(w * 0.05f, h * 0.10f)
                    )
                    drawCircle(
                        Brush.radialGradient(
                            0f to Color(0xFF0E4C5C).copy(alpha = 0.45f),
                            1f to Color.Transparent,
                            center = Offset(w * 1.05f, h * 0.38f),
                            radius = w * 0.85f
                        ),
                        radius = w * 0.85f,
                        center = Offset(w * 1.05f, h * 0.38f)
                    )
                    // star dust
                    stars.forEachIndexed { i, (p, r, a) ->
                        if (!showHorizon || p.y < horizonY) drawCircle(tints[i].copy(alpha = a), radius = r, center = p)
                    }
                    if (!showHorizon) return@onDrawBehind
                    // atmosphere glow above the horizon
                    drawCircle(
                        Brush.radialGradient(
                            0f to rim.copy(alpha = 0.30f),
                            0.5f to rim.copy(alpha = 0.08f),
                            1f to Color.Transparent,
                            center = Offset(w / 2f, horizonY + 30f),
                            radius = w * 0.85f
                        ),
                        radius = w * 0.85f,
                        center = Offset(w / 2f, horizonY + 30f)
                    )
                    // the planet
                    drawCircle(
                        Brush.verticalGradient(
                            listOf(Space.Planet, Space.Deep),
                            startY = horizonY,
                            endY = h
                        ),
                        radius = planetRadius,
                        center = planetCenter
                    )
                    // rim light: wide soft band, then a crisp edge
                    drawCircle(rim.copy(alpha = 0.10f), planetRadius, planetCenter, style = Stroke(width = 26.dp.toPx()))
                    drawCircle(rim.copy(alpha = 0.22f), planetRadius, planetCenter, style = Stroke(width = 8.dp.toPx()))
                    drawCircle(rim.copy(alpha = 0.85f), planetRadius, planetCenter, style = Stroke(width = 1.2.dp.toPx()))
                }
            },
        content = content
    )
}
