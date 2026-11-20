package ai.hybrid.commandcenter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Static decorative motifs from the design system: Neural Constellation,
 * Telemetry Rulers and Holo Markers. None of them move — decoration never
 * pretends to be activity (handoff 5: no fake neural animation).
 */

/** A fixed, seeded star field with faint links between near neighbours. */
@Composable
fun ConstellationBackdrop(
    modifier: Modifier = Modifier,
    accent: Color = NexusColors.NeonCyan,
    points: Int = 34,
    seed: Int = 7
) {
    Canvas(modifier) {
        val random = Random(seed)
        val stars = List(points) {
            Offset(random.nextFloat() * size.width, random.nextFloat() * size.height) to random.nextFloat()
        }
        val linkDistance = size.minDimension * 0.28f
        for (i in stars.indices) {
            for (j in i + 1 until stars.size) {
                val (a, _) = stars[i]
                val (b, _) = stars[j]
                val d = hypot(a.x - b.x, a.y - b.y)
                if (d < linkDistance) {
                    drawLine(
                        color = accent.copy(alpha = 0.10f * (1f - d / linkDistance)),
                        start = a, end = b, strokeWidth = 1.2f
                    )
                }
            }
        }
        stars.forEachIndexed { index, (p, brightness) ->
            val tint = if (index % 5 == 0) NexusColors.NeonViolet else accent
            drawCircle(tint.copy(alpha = 0.20f + 0.35f * brightness), radius = 1.6f + 2.2f * brightness, center = p)
        }
    }
}

/** Section label followed by a ruler with ticks: "Telemetry Rulers". */
@Composable
fun TelemetryRuler(label: String, modifier: Modifier = Modifier, accent: Color = NexusColors.NeonCyan) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            label,
            color = accent,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.5.sp
        )
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val y = size.height / 2
            drawLine(NexusColors.Divider, Offset(0f, y), Offset(size.width, y), strokeWidth = 2f)
            val step = 18.dp.toPx()
            var x = 0f
            var i = 0
            while (x <= size.width) {
                val tall = i % 4 == 0
                val h = if (tall) size.height * 0.5f else size.height * 0.25f
                drawLine(
                    color = if (tall) accent.copy(alpha = 0.55f) else NexusColors.Divider,
                    start = Offset(x, y - h), end = Offset(x, y + h), strokeWidth = 2f
                )
                x += step
                i++
            }
        }
    }
}

/** Corner brackets around a panel: "Holo Markers". */
fun Modifier.holoCorners(color: Color, length: Float = 34f, inset: Float = 2f): Modifier = drawBehind {
    val w = size.width
    val h = size.height
    val stroke = 3f
    val c = color.copy(alpha = 0.75f)
    // top-left
    drawLine(c, Offset(inset, inset), Offset(inset + length, inset), stroke, StrokeCap.Round)
    drawLine(c, Offset(inset, inset), Offset(inset, inset + length), stroke, StrokeCap.Round)
    // top-right
    drawLine(c, Offset(w - inset, inset), Offset(w - inset - length, inset), stroke, StrokeCap.Round)
    drawLine(c, Offset(w - inset, inset), Offset(w - inset, inset + length), stroke, StrokeCap.Round)
    // bottom-left
    drawLine(c, Offset(inset, h - inset), Offset(inset + length, h - inset), stroke, StrokeCap.Round)
    drawLine(c, Offset(inset, h - inset), Offset(inset, h - inset - length), stroke, StrokeCap.Round)
    // bottom-right
    drawLine(c, Offset(w - inset, h - inset), Offset(w - inset - length, h - inset), stroke, StrokeCap.Round)
    drawLine(c, Offset(w - inset, h - inset), Offset(w - inset, h - inset - length), stroke, StrokeCap.Round)
}
