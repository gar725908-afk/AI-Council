package ai.hybrid.commandcenter.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/*
 * Building blocks of the NEXUS look. Screens compose these instead of raw
 * Material widgets so the whole app stays consistent.
 */

/** Deep black with three soft, static pools of light. */
@Composable
fun AuroraBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier
            .background(NexusColors.Void)
            .drawBehind { drawAurora() },
        content = content
    )
}

private fun DrawScope.drawAurora() {
    fun pool(center: Offset, radius: Float, color: Color, alpha: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = alpha),
                0.45f to color.copy(alpha = alpha * 0.45f),
                1f to Color.Transparent,
                center = center,
                radius = radius
            ),
            radius = radius,
            center = center
        )
    }
    val w = size.width
    val h = size.height
    pool(Offset(w * 0.10f, h * 0.02f), w * 0.95f, NexusColors.NeonViolet, 0.26f)
    pool(Offset(w * 1.00f, h * 0.16f), w * 0.80f, NexusColors.NeonCyan, 0.13f)
    pool(Offset(w * 0.30f, h * 0.70f), w * 0.90f, Color(0xFF3B2A9F), 0.12f)
    pool(Offset(w * 0.85f, h * 1.02f), w * 0.95f, NexusColors.NeonMagenta, 0.10f)
}

/** Translucent glass surface with a light top edge. */
fun Modifier.glass(shape: Shape = NexusShapes.Card, accent: Color? = null): Modifier =
    this
        .clip(shape)
        // a dark base first, so stars behind never speckle the text
        .background(Color(0xB8060816))
        .background(NexusBrushes.GlassFill)
        .border(1.dp, if (accent != null) NexusBrushes.accentBorder(accent) else NexusBrushes.GlassBorder, shape)

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = NexusShapes.Card,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    spacing: Dp = 10.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .glass(shape, accent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content
    )
}

/** Card with an icon badge and a title row, the standard section container. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    accent: Color = NexusColors.NeonViolet,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    GlassCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) IconBadge(icon, accent, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(prettyTitle(title), style = MaterialTheme.typography.titleMedium, color = NexusColors.TextPrimary)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NexusColors.TextSecondary)
                }
            }
            trailing?.invoke()
        }
        content()
    }
}

/** Rounded square with a tinted fill and a line icon. */
@Composable
fun IconBadge(@DrawableRes icon: Int, accent: Color, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.32f))
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f)))
            )
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(size * 0.32f)),
        contentAlignment = Alignment.Center
    ) {
        NexIcon(icon, tint = accent, size = size * 0.5f)
    }
}

private fun RoundedCornerShape(radius: Dp) = androidx.compose.foundation.shape.RoundedCornerShape(radius)

/** Filled gradient pill, the one primary action of a view. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    brush: Brush = NexusBrushes.Primary,
    glow: Color = NexusColors.NeonViolet
) {
    val shape = NexusShapes.Pill
    Row(
        modifier
            .then(if (enabled) Modifier.shadow(14.dp, shape, ambientColor = glow, spotColor = glow) else Modifier)
            .clip(shape)
            .background(if (enabled) brush else SolidColor(NexusColors.ElevatedSurface))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        val content = if (enabled) Color.White else NexusColors.TextMuted
        if (icon != null) NexIcon(icon, tint = content, size = 18.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/** Glass pill for secondary actions. */
@Composable
fun SoftButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    tint: Color = NexusColors.TextPrimary,
    enabled: Boolean = true
) {
    Row(
        modifier
            .glass(NexusShapes.Pill)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        val color = if (enabled) tint else NexusColors.TextMuted
        if (icon != null) NexIcon(icon, tint = color, size = 17.dp)
        Text(text, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1)
    }
}

/** Round icon button; [filled] uses the primary gradient. */
@Composable
fun CircleIconButton(
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    filled: Boolean = false,
    tint: Color = NexusColors.TextPrimary,
    brush: Brush = NexusBrushes.Primary,
    contentDescription: String? = null
) {
    Box(
        modifier
            .size(size)
            .then(
                if (filled) {
                    Modifier
                        .shadow(12.dp, CircleShape, ambientColor = NexusColors.NeonViolet, spotColor = NexusColors.NeonViolet)
                        .clip(CircleShape)
                        .background(brush)
                } else {
                    Modifier.glass(CircleShape)
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        NexIcon(
            icon,
            tint = if (filled) Color.White else tint,
            size = size * 0.45f,
            contentDescription = contentDescription
        )
    }
}

/** Compact status capsule: icon, text, colour from a real status. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    label: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .glass(NexusShapes.Pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (icon != null) NexIcon(icon, tint = color, size = 16.dp) else Dot(color)
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = NexusColors.TextSecondary, maxLines = 1)
        }
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = NexusColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Small tinted label such as «Готово» or «Ошибка». */
@Composable
fun Tag(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        modifier = modifier
            .clip(NexusShapes.Pill)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    )
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .shadow(6.dp, CircleShape, ambientColor = color, spotColor = color)
            .background(color, CircleShape)
    )
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {}
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            color = NexusColors.TextPrimary,
            modifier = Modifier.weight(1f)
        )
        if (action != null) {
            Row(
                Modifier.clip(NexusShapes.Pill).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = NexusColors.NeonViolet)
                NexIcon(NexIcons.ChevronRight, tint = NexusColors.NeonViolet, size = 16.dp)
            }
        }
    }
}

/** Screen title block used at the top of every space. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = NexusColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = NexusColors.TextSecondary)
            }
        }
        trailing?.invoke()
    }
}

/** Glass segmented control with a sliding gradient thumb. */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .glass(NexusShapes.Pill)
            .padding(4.dp)
    ) {
        val count = options.size.coerceAtLeast(1)
        val thumbWidth = maxWidth / count
        val offset by animateDpAsState(thumbWidth * selected, tween(NexusMotion.STATE_CHANGE), label = "segment")
        Box(
            Modifier
                .offset(x = offset)
                .width(thumbWidth)
                .fillMaxHeight()
                .clip(NexusShapes.Pill)
                .background(NexusBrushes.Primary)
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEachIndexed { index, option ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(NexusShapes.Pill)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        option,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (index == selected) Color.White else NexusColors.TextSecondary,
                        maxLines = 1,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/** A list row: badge, title, meta, trailing tag. */
@Composable
fun ListRow(
    @DrawableRes icon: Int,
    accent: Color,
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(NexusShapes.Control)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconBadge(icon, accent, size = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = NexusColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = NexusColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        trailing?.invoke()
    }
}

@Composable
fun HairlineDivider(modifier: Modifier = Modifier) {
    Spacer(modifier.fillMaxWidth().height(1.dp).background(NexusColors.Hairline))
}

/** Label / value line used inside cards. */
@Composable
fun InfoLine(name: String, value: String, modifier: Modifier = Modifier, valueColor: Color = NexusColors.TextPrimary) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = NexusColors.TextSecondary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, color = valueColor, textAlign = TextAlign.End)
    }
}

private val KEEP_UPPER = setOf(
    "AI", "API", "MCP", "PDF", "OCR", "NEX", "NEXUS", "ADB", "LAN", "RAM", "CPU", "GPU", "URL", "APK",
    "XLSX", "DOCX", "ID", "SHA", "LINK", "HTTP", "HTTPS", "UI", "PR", "LLM", "CI", "SDK", "OAUTH", "JSON"
)
private val PROPER = mapOf(
    "GITHUB" to "GitHub", "SHIZUKU" to "Shizuku", "ROOT" to "Root", "OLLAMA" to "Ollama",
    "ANDROID" to "Android", "COUNCIL" to "Council", "STUDIO" to "Studio", "ACCESSIBILITY" to "Accessibility",
    "WI-FI" to "Wi‑Fi", "GATEWAY" to "Gateway", "FORGE" to "FORGE"
)

/**
 * Turns legacy SHOUTING titles into sentence case while keeping acronyms and
 * product names intact: «ХРАНИЛИЩЕ И ДАННЫЕ» → «Хранилище и данные»,
 * «MCP-КОННЕКТОРЫ» → «MCP-коннекторы».
 */
fun prettyTitle(raw: String): String {
    val letters = raw.filter { it.isLetter() }
    if (letters.isEmpty() || letters.any { it.isLowerCase() }) return raw
    var first = true
    return raw.split(" ").joinToString(" ") { word ->
        word.split("-").joinToString("-") { part ->
            val core = part.trim { !it.isLetterOrDigit() }
            val key = core.uppercase(Locale.ROOT)
            val result = when {
                core.isEmpty() -> part
                key in KEEP_UPPER -> part
                key in PROPER -> part.replace(core, PROPER.getValue(key))
                first -> part.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
                else -> part.lowercase(Locale.ROOT)
            }
            if (core.any { it.isLetter() }) first = false
            result
        }
    }
}

/** Picks a fitting line icon for a section from words in its title. */
@DrawableRes
fun iconForTitle(title: String): Int {
    val t = title.lowercase(Locale.ROOT)
    val table = listOf(
        listOf("диагност") to NexIcons.Activity,
        listOf("репозитор") to NexIcons.GitPullRequest,
        listOf("исходн", "код") to NexIcons.CodeXml,
        listOf("импорт") to NexIcons.FolderOpen,
        listOf("активн") to NexIcons.Zap,
        listOf("привилег", "доступ") to NexIcons.ShieldCheck,
        listOf("root", "shizuku", "команд") to NexIcons.Terminal,
        listOf("шлюз", "gateway", "инструмент", "аудит") to NexIcons.Waypoints,
        listOf("снимок", "захват", "экран") to NexIcons.Eye,
        listOf("accessibility", "специальн") to NexIcons.Eye,
        listOf("разрешен") to NexIcons.Lock,
        listOf("системн", "настройк") to NexIcons.Settings2,
        listOf("сборк", "о приложении", "версия", "build") to NexIcons.BadgeCheck,
        listOf("хранил", "данн", "накопит") to NexIcons.HardDrive,
        listOf("приватн", "безопасн") to NexIcons.ShieldCheck,
        listOf("экспорт", "загрузк", "скачив") to NexIcons.Download,
        listOf("очист", "сброс") to NexIcons.RotateCcw,
        listOf("эффект", "оформлен") to NexIcons.Sparkles,
        listOf("ошиб", "журнал", "сбо") to NexIcons.Bug,
        listOf("github", "исправлен", "pull") to NexIcons.GitPullRequest,
        listOf("ключ", "токен") to NexIcons.KeyRound,
        listOf("коннектор", "mcp", "подключ") to NexIcons.Plug,
        listOf("сервер") to NexIcons.Server,
        listOf("модел") to NexIcons.Boxes,
        listOf("pdf", "документ", "просмотр") to NexIcons.FileText,
        listOf("архив", "материал", "библиот") to NexIcons.Archive,
        listOf("поиск") to NexIcons.Search,
        listOf("ocr", "распозн") to NexIcons.ScanText,
        listOf("браузер", "страниц", "вкладк") to NexIcons.Globe,
        listOf("истори") to NexIcons.History,
        listOf("голос") to NexIcons.Mic,
        listOf("сеть") to NexIcons.Wifi,
        listOf("памят") to NexIcons.MemoryStick,
        listOf("обновлен") to NexIcons.RefreshCw,
        listOf("расширен", "платформ") to NexIcons.Layers,
        listOf("evolution", "эволюц") to NexIcons.Brain,
        listOf("link", "компьютер", "пк") to NexIcons.Smartphone,
        listOf("совет", "council", "миссии") to NexIcons.Orbit
    )
    return table.firstOrNull { (keys, _) -> keys.any { it in t } }?.second ?: NexIcons.Layers
}

/** Horizontally scrolling tabs as pills; the active one carries the gradient. */
@Composable
fun ChipTabs(
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { index, (label, icon) ->
            val active = index == selected
            Row(
                Modifier
                    .clip(NexusShapes.Pill)
                    .then(if (active) Modifier.background(NexusBrushes.Primary) else Modifier.glass(NexusShapes.Pill))
                    .clickable { onSelect(index) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                NexIcon(icon, tint = if (active) Color.White else NexusColors.TextSecondary, size = 16.dp)
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (active) Color.White else NexusColors.TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * Drop-in glass replacement for Material's Card: same call shape, so older
 * screens keep their structure while taking the new look. The colors
 * argument is accepted for compatibility and ignored on purpose.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = NexusShapes.Card,
    @Suppress("UNUSED_PARAMETER") colors: androidx.compose.material3.CardColors? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides NexusColors.TextPrimary
    ) {
        Column(modifier.glass(shape), content = content)
    }
}
