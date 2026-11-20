package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.R
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * NEXUS · CYBER — the look the owner picked from reference boards
 * («приложение ИИ будущего», «… киберпанк»): a dark HUD with neon cyan and
 * magenta, chamfered panels with lit corner accents, terminal-style mono
 * labels and a hologram at the centre. Every screen builds from these parts.
 */

object Cyber {
    private val p: ThemePalette get() = ThemeState.palette

    val Void: Color get() = p.void
    val Night: Color get() = p.night
    val Panel: Color get() = p.panel
    val PanelHigh: Color get() = p.panelHigh

    /** Primary accent slot (cyan in «Неон-город», ice blue in «Космос», NERV orange in «Аниме»). */
    val Cyan: Color get() = p.primary
    val CyanSoft: Color get() = p.primarySoft
    /** Secondary slot: the owner's voice and messages. */
    val Magenta: Color get() = p.secondary
    val Violet: Color get() = p.tertiary
    val Yellow: Color get() = p.highlight

    // Signals mean the same in every theme.
    val Green = Color(0xFF2BFF9A)
    val Amber = Color(0xFFFFB547)
    val Red = Color(0xFFFF3B5C)

    val Text: Color get() = p.text
    val TextDim: Color get() = p.textDim
    val TextFaint: Color get() = p.textFaint
    val Line: Color get() = p.primary.copy(alpha = 0.16f)

    /** Ink for text that sits on a bright neon fill. */
    val OnNeon: Color get() = p.onNeon

    val Signature: Brush get() = Brush.linearGradient(listOf(Cyan, Violet, Magenta))
    val Wordmark: Brush get() = Brush.horizontalGradient(p.wordmark)
}

val Exo2Family = FontFamily(
    Font(R.font.exo2_semibold, FontWeight.SemiBold),
    Font(R.font.exo2_bold, FontWeight.Bold),
    Font(R.font.exo2_extrabold, FontWeight.ExtraBold)
)

val MonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold)
)

object CyberText {
    val Wordmark: TextStyle get() = TextStyle(
        fontFamily = Exo2Family, fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp, letterSpacing = 6.sp, brush = Cyber.Wordmark
    )
    val Hero: TextStyle get() = TextStyle(
        fontFamily = Exo2Family, fontWeight = FontWeight.Bold,
        fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = 2.sp, brush = Cyber.Wordmark
    )
    val Title = TextStyle(
        fontFamily = Exo2Family, fontWeight = FontWeight.Bold,
        fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 1.5.sp
    )
    val Heading = TextStyle(
        fontFamily = Exo2Family, fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp, lineHeight = 19.sp, letterSpacing = 0.6.sp
    )
    val Button = TextStyle(
        fontFamily = Exo2Family, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, letterSpacing = 1.4.sp
    )
    val Label = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.Medium,
        fontSize = 10.5.sp, lineHeight = 14.sp, letterSpacing = 1.2.sp
    )
    val Mono = TextStyle(
        fontFamily = MonoFamily, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp
    )
}

object HudShapes {
    /** Two opposite chamfers: the signature HUD panel silhouette. */
    fun chamfer(cut: Dp) = CutCornerShape(topStart = cut, topEnd = 0.dp, bottomEnd = cut, bottomStart = 0.dp)

    val Panel = chamfer(14.dp)
    val Card = chamfer(12.dp)
    val Control = chamfer(9.dp)
    val Small = chamfer(6.dp)

    /** Octagon for icon buttons. */
    val Octagon = CutCornerShape(percent = 28)
}

/** Lit accents along the two chamfers, extended a little along the edges. */
fun DrawScope.drawChamferAccents(accent: Color, cut: Float, extension: Float = 14.dp.toPx(), alpha: Float = 1f) {
    val w = size.width
    val h = size.height
    if (w <= cut * 2 || h <= cut * 2) return
    val ext = extension.coerceAtMost(minOf(w, h) / 3f)
    val topLeft = Path().apply {
        moveTo(0f, cut + ext)
        lineTo(0f, cut)
        lineTo(cut, 0f)
        lineTo(cut + ext, 0f)
    }
    val bottomRight = Path().apply {
        moveTo(w, h - cut - ext)
        lineTo(w, h - cut)
        lineTo(w - cut, h)
        lineTo(w - cut - ext, h)
    }
    val glow = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
    val line = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter)
    for (p in listOf(topLeft, bottomRight)) {
        drawPath(p, accent.copy(alpha = 0.16f * alpha), style = glow)
        drawPath(p, accent.copy(alpha = 0.95f * alpha), style = line)
    }
    // three short pips on the open corner: the HUD "signature"
    val pip = 2.dp.toPx()
    val gap = 4.dp.toPx()
    for (i in 0 until 3) {
        drawRect(
            accent.copy(alpha = (0.75f - i * 0.2f) * alpha),
            topLeft = Offset(w - 8.dp.toPx() - i * (pip + gap) - pip, 6.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(pip, pip)
        )
    }
}

/**
 * The HUD panel surface: dark translucent fill (the city never speckles the
 * text), a faint tint of the accent at the top, a gradient hairline border
 * and lit chamfer accents drawn outside the clip.
 */
fun Modifier.hud(
    accent: Color = Cyber.Cyan,
    cut: Dp = 12.dp,
    fill: Color = Color(0xE0060C19),
    borderAlpha: Float = 0.38f,
    accents: Boolean = true
): Modifier {
    val shape = HudShapes.chamfer(cut)
    return this
        .drawWithContent {
            drawContent()
            if (accents) drawChamferAccents(accent, cut.toPx())
        }
        .clip(shape)
        .background(fill)
        .background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.08f), Color.Transparent)))
        .border(
            1.dp,
            Brush.linearGradient(
                listOf(
                    accent.copy(alpha = borderAlpha),
                    accent.copy(alpha = borderAlpha * 0.22f),
                    accent.copy(alpha = borderAlpha * 0.65f)
                )
            ),
            shape
        )
}

@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = Cyber.Cyan,
    title: String? = null,
    index: String? = null,
    cut: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .hud(accent, cut)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing)
    ) {
        if (title != null || index != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (title != null) {
                    Text("// " + title.uppercase(), style = CyberText.Label, color = accent, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (index != null) Text(cjkAware(index), style = CyberText.Label, color = accent.copy(alpha = 0.6f))
            }
        }
        content()
    }
}

@Composable
fun HudLabel(text: String, modifier: Modifier = Modifier, color: Color = Cyber.TextDim, maxLines: Int = 1) {
    Text(
        cjkAware(text.uppercase()), style = CyberText.Label, color = color, modifier = modifier,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis
    )
}

/** Section title: a lit tab, the title in Exo 2, then a ticked hairline. */
@Composable
fun HudSectionTitle(text: String, modifier: Modifier = Modifier, accent: Color = Cyber.Cyan, trailing: String? = null) {
    Row(modifier.fillMaxWidth().height(22.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 3.dp, height = 14.dp).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = CyberText.Heading.copy(letterSpacing = 2.sp), color = Cyber.Text, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.weight(1f).height(8.dp).drawBehind {
                val y = size.height / 2f
                drawLine(accent.copy(alpha = 0.28f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                var x = size.width
                var k = 0
                while (x > 0f && k < 6) {
                    drawLine(accent.copy(alpha = 0.5f), Offset(x, y - 3.dp.toPx()), Offset(x, y + 3.dp.toPx()), 1.dp.toPx())
                    x -= 6.dp.toPx(); k++
                }
            }
        )
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                trailing.uppercase(),
                style = if (trailing.hasCjk()) JpLabel.copy(fontSize = 11.sp) else CyberText.Label,
                color = accent.copy(alpha = 0.75f)
            )
        }
    }
}

enum class HudButtonStyle { Primary, Secondary, Ghost, Danger }

@Composable
fun HudButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    style: HudButtonStyle = HudButtonStyle.Primary,
    accent: Color = Cyber.Cyan,
    enabled: Boolean = true,
    height: Dp = 48.dp
) {
    val tint = if (style == HudButtonStyle.Danger) Cyber.Red else accent
    val shape = HudShapes.Control
    val base = modifier
        .heightIn(min = height)
        .alpha(if (enabled) 1f else 0.42f)
        .clip(shape)
    val surface = when (style) {
        HudButtonStyle.Primary -> base.background(
            Brush.horizontalGradient(listOf(tint, lerp(tint, Color.White, 0.25f)))
        )
        HudButtonStyle.Secondary, HudButtonStyle.Danger -> base
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.6f), shape)
        HudButtonStyle.Ghost -> base.border(1.dp, Cyber.Line, shape)
    }
    val content = when (style) {
        HudButtonStyle.Primary -> Cyber.OnNeon
        HudButtonStyle.Ghost -> Cyber.TextDim
        else -> tint
    }
    Row(
        surface
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) NexIcon(icon, tint = content, size = 18.dp)
        Text(text.uppercase(), style = CyberText.Button, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Octagonal icon button with a lit rim. */
@Composable
fun HudIconButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    tint: Color = Cyber.Cyan,
    filled: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null
) {
    val shape = HudShapes.Octagon
    Box(
        modifier
            .size(size)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .then(
                if (filled) Modifier.background(Brush.linearGradient(listOf(tint, lerp(tint, Color.White, 0.3f))))
                else Modifier.background(Color(0xCC060C19)).border(1.dp, tint.copy(alpha = 0.45f), shape)
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        NexIcon(
            icon,
            tint = if (filled) Cyber.OnNeon else tint,
            size = size * 0.46f,
            contentDescription = contentDescription
        )
    }
}

/** A launch tile: icon in a lit box, title, subtitle and a mono index. */
@Composable
fun HudTile(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    index: String? = null,
    accent: Color = Cyber.Cyan,
    onClick: () -> Unit
) {
    Column(
        modifier
            .hud(accent, cut = 10.dp, borderAlpha = 0.32f)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(HudShapes.Small)
                    .background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.5f), HudShapes.Small),
                contentAlignment = Alignment.Center
            ) { NexIcon(icon, tint = accent, size = 18.dp) }
            Spacer(Modifier.weight(1f))
            if (index != null) Text(index, style = CyberText.Label, color = accent.copy(alpha = 0.55f))
        }
        Text(title.uppercase(), style = CyberText.Heading.copy(letterSpacing = 1.2.sp), color = Cyber.Text, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (subtitle != null) {
            Text(subtitle, style = NexusTypography.bodySmall, color = Cyber.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small status chip: lit dot and a mono line. Only real states go here. */
@Composable
fun HudChip(text: String, color: Color, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    Row(
        modifier
            .clip(HudShapes.Small)
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.45f), HudShapes.Small)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(6.dp).background(color))
        Text(cjkAware(text.uppercase()), style = CyberText.Label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Segmented progress, like a charging cell. */
@Composable
fun HudProgress(fraction: Float, modifier: Modifier = Modifier, accent: Color = Cyber.Cyan, segments: Int = 28) {
    val f = fraction.coerceIn(0f, 1f)
    Row(modifier.fillMaxWidth().height(10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (i in 0 until segments) {
            val lit = (i + 0.5f) / segments <= f
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .background(if (lit) accent else accent.copy(alpha = 0.12f))
            )
        }
    }
}

/** Single-line input in a HUD frame with a mono caption above. */
@Composable
fun HudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    secret: Boolean = false,
    enabled: Boolean = true,
    accent: Color = Cyber.Cyan,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: (() -> Unit)? = null,
    testTag: String? = null
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) HudLabel(label, color = accent.copy(alpha = 0.85f))
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(HudShapes.Control)
                .background(Color(0xE6040913))
                .border(1.dp, accent.copy(alpha = if (enabled) 0.45f else 0.15f), HudShapes.Control)
                .padding(horizontal = 14.dp, vertical = 13.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = CyberText.Mono.copy(fontSize = 14.sp), color = Cyber.TextFaint, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                textStyle = CyberText.Mono.copy(fontSize = 14.sp, color = Cyber.Text),
                cursorBrush = SolidColor(accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
                modifier = Modifier.fillMaxWidth().then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            )
        }
    }
}

/** Row with a title, an explanation and a trailing control (switch, chip, value). */
@Composable
fun HudRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    accent: Color = Cyber.Cyan,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (icon != null) NexIcon(icon, tint = accent, size = 20.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = NexusTypography.titleSmall, color = Cyber.Text)
            if (subtitle != null) Text(subtitle, style = NexusTypography.bodySmall, color = Cyber.TextDim)
        }
        trailing()
    }
}

/** Hairline with a lit centre tick: separates blocks inside a panel. */
@Composable
fun HudDivider(modifier: Modifier = Modifier, accent: Color = Cyber.Cyan) {
    Box(
        modifier.fillMaxWidth().height(1.dp).background(
            Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.35f), Color.Transparent))
        )
    )
}
