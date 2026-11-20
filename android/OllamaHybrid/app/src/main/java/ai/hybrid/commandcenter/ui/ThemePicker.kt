package ai.hybrid.commandcenter.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random

/** Three tiles, each with a tiny painted preview of its world. */
@Composable
fun ThemePicker(selected: NexusTheme, onSelect: (NexusTheme) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NexusTheme.entries.forEach { theme ->
            ThemeTile(theme, theme == selected, Modifier.weight(1f)) { onSelect(theme) }
        }
    }
}

@Composable
private fun ThemeTile(theme: NexusTheme, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = theme.palette
    val shape = HudShapes.Card
    Column(
        modifier
            .clip(shape)
            .border(if (active) 2.dp else 1.dp, if (active) p.primary else p.primary.copy(alpha = 0.35f), shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .testTag("theme-" + theme.id)
    ) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val w = size.width
            val h = size.height
            drawRect(Brush.verticalGradient(listOf(p.void, p.panelHigh, p.night)))
            when (theme) {
                NexusTheme.NEON_CITY -> {
                    val rnd = Random(3)
                    var x = 0f
                    while (x < w) {
                        val bw = w * (0.08f + rnd.nextFloat() * 0.1f)
                        val bh = h * (0.25f + rnd.nextFloat() * 0.45f)
                        drawRect(Color(0xFF050915), Offset(x, h * 0.78f - bh), Size(bw, bh))
                        drawRect(p.primary.copy(alpha = 0.8f), Offset(x + bw * 0.4f, h * 0.78f - bh * 0.7f), Size(2.dp.toPx(), 3.dp.toPx()))
                        x += bw + 3f
                    }
                    drawLine(p.secondary, Offset(0f, h * 0.78f), Offset(w, h * 0.78f), 2.dp.toPx())
                }
                NexusTheme.COSMOS -> {
                    val rnd = Random(9)
                    repeat(40) {
                        drawCircle(Color.White.copy(alpha = 0.5f + rnd.nextFloat() * 0.5f), 0.6f + rnd.nextFloat() * 1.2f,
                            Offset(rnd.nextFloat() * w, rnd.nextFloat() * h))
                    }
                    val c = Offset(w * 0.75f, h * 1.05f)
                    drawCircle(Brush.radialGradient(listOf(p.tertiary, Color(0xFF110A30)), center = c - Offset(10f, 20f), radius = h),
                        radius = h * 0.62f, center = c)
                    drawOval(p.primarySoft.copy(alpha = 0.7f), Offset(c.x - h, c.y - h * 0.62f), Size(h * 2f, h * 0.3f), style = Stroke(2f))
                }
                NexusTheme.ANIME -> {
                    val sun = Offset(w * 0.5f, h * 0.8f)
                    drawCircle(
                        Brush.verticalGradient(listOf(Color(0xFFFFE0A8), p.primary, p.secondary), startY = sun.y - h * 0.5f, endY = sun.y),
                        radius = h * 0.5f, center = sun
                    )
                    var y = sun.y - h * 0.14f
                    var k = 0
                    while (y < h && k < 5) {
                        drawRect(p.void, Offset(0f, y), Size(w, 1.5f + k))
                        y += 6f
                        k++
                    }
                    drawRect(p.void, Offset(0f, h * 0.8f), Size(w, h * 0.2f))
                    hazardBand(0f, 4.dp.toPx(), 0.85f, p.primary)
                }
            }
        }
        Column(Modifier.fillMaxWidth().background(p.panel).padding(8.dp)) {
            Text(theme.glyph, style = JpLabel, color = p.primary)
            Text(theme.title.uppercase(), style = CyberText.Heading.copy(fontSize = 12.sp, letterSpacing = 0.5.sp), color = p.text, maxLines = 1)
            Text(if (active) "ВКЛЮЧЕНА" else "ВЫБРАТЬ", style = CyberText.Label, color = if (active) p.highlight else p.textFaint)
        }
    }
}
