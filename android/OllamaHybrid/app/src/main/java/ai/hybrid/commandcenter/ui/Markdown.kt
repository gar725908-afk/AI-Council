package ai.hybrid.commandcenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * A small, safe Markdown subset for model answers: headings, paragraphs,
 * bullet and numbered lists, quotes, code blocks, rules, and inline bold,
 * italic, code and links (shown as text, never opened automatically).
 */
sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Bullet(val text: String, val depth: Int) : MdBlock()
    data class Numbered(val number: String, val text: String) : MdBlock()
    data class Quote(val text: String) : MdBlock()
    data class Code(val language: String, val code: String) : MdBlock()
    object Rule : MdBlock()
}

object MarkdownParser {
    private val heading = Regex("^(#{1,6})\\s+(.*)$")
    private val bullet = Regex("^(\\s*)([-*•+])\\s+(.*)$")
    private val numbered = Regex("^\\s*(\\d{1,3})[.)]\\s+(.*)$")
    private val rule = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")

    fun parse(source: String): List<MdBlock> {
        val blocks = mutableListOf<MdBlock>()
        val lines = source.replace("\r\n", "\n").split("\n")
        val paragraph = StringBuilder()
        fun flush() {
            if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
            paragraph.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.startsWith("```") -> {
                    flush()
                    val language = trimmed.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    blocks += MdBlock.Code(language, code.toString())
                }
                trimmed.isEmpty() -> flush()
                rule.matches(trimmed) -> { flush(); blocks += MdBlock.Rule }
                heading.matches(trimmed) -> {
                    flush()
                    val m = heading.find(trimmed)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim().trimEnd('#').trim())
                }
                bullet.matches(line) -> {
                    flush()
                    val m = bullet.find(line)!!
                    blocks += MdBlock.Bullet(m.groupValues[3], (m.groupValues[1].length / 2).coerceAtMost(3))
                }
                numbered.matches(line) -> {
                    flush()
                    val m = numbered.find(line)!!
                    blocks += MdBlock.Numbered(m.groupValues[1], m.groupValues[2])
                }
                trimmed.startsWith(">") -> {
                    flush()
                    blocks += MdBlock.Quote(trimmed.removePrefix(">").trim())
                }
                else -> {
                    if (paragraph.isNotEmpty()) paragraph.append(' ')
                    paragraph.append(trimmed)
                }
            }
            i++
        }
        flush()
        return blocks
    }

    /** Inline spans: **bold**, *italic* / _italic_, `code`, [text](url). */
    fun inline(text: String, codeColor: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            val rest = text.substring(i)
            when {
                rest.startsWith("**") && rest.indexOf("**", 2) > 2 -> {
                    val end = rest.indexOf("**", 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(rest.substring(2, end)) }
                    i += end + 2
                }
                rest.startsWith("`") && rest.indexOf('`', 1) > 1 -> {
                    val end = rest.indexOf('`', 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = Color(0x1FFFFFFF))) {
                        append(rest.substring(1, end))
                    }
                    i += end + 1
                }
                (rest.startsWith("*") || rest.startsWith("_")) && rest.length > 2 && !rest[1].isWhitespace() &&
                    rest.indexOf(rest[0], 1) > 1 -> {
                    val end = rest.indexOf(rest[0], 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(rest.substring(1, end)) }
                    i += end + 1
                }
                rest.startsWith("[") && rest.indexOf("](") > 0 && rest.indexOf(')', rest.indexOf("](")) > 0 -> {
                    val close = rest.indexOf("](")
                    val end = rest.indexOf(')', close)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(rest.substring(1, close))
                    }
                    i += end + 1
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}

@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = NexusColors.TextPrimary,
    trailing: AnnotatedString? = null
) {
    val blocks = remember(text) { MarkdownParser.parse(text) }
    val body = MaterialTheme.typography.bodyLarge.copy(lineHeight = 1.55.em, color = color)
    val code = Space.Ion
    val link = Space.Ion
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            val last = index == blocks.lastIndex
            fun withTrailing(s: AnnotatedString): AnnotatedString =
                if (last && trailing != null) s + trailing else s
            when (block) {
                is MdBlock.Heading -> Text(
                    withTrailing(MarkdownParser.inline(block.text, code, link)),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        else -> MaterialTheme.typography.titleMedium
                    },
                    color = color
                )
                is MdBlock.Paragraph -> Text(withTrailing(MarkdownParser.inline(block.text, code, link)), style = body)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (block.depth * 16).dp)) {
                    Text("•", style = body.copy(color = Space.Ion), modifier = Modifier.width(18.dp))
                    Text(withTrailing(MarkdownParser.inline(block.text, code, link)), style = body)
                }
                is MdBlock.Numbered -> Row {
                    Text(block.number + ".", style = body.copy(color = Space.Ion), modifier = Modifier.width(26.dp))
                    Text(withTrailing(MarkdownParser.inline(block.text, code, link)), style = body)
                }
                is MdBlock.Quote -> Row {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(22.dp)
                            .clip(NexusShapes.Pill)
                            .background(Space.Plasma)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        withTrailing(MarkdownParser.inline(block.text, code, link)),
                        style = body.copy(color = NexusColors.TextSecondary, fontStyle = FontStyle.Italic)
                    )
                }
                is MdBlock.Code -> Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(NexusShapes.Control)
                        .background(Color(0xCC05060F))
                        .padding(12.dp)
                ) {
                    if (block.language.isNotBlank()) {
                        Text(block.language, style = MaterialTheme.typography.labelSmall, color = NexusColors.TextMuted)
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        block.code,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                        color = Color(0xFFD6E2FF),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    )
                }
                MdBlock.Rule -> Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(NexusColors.Hairline)
                )
            }
        }
        if (blocks.isEmpty() && trailing != null) Text(trailing, style = body)
    }
}
