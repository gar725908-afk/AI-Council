package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.MarkdownParser
import ai.hybrid.commandcenter.ui.MdBlock
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun blocksAreRecognised() {
        val blocks = MarkdownParser.parse(
            """
            # Итог
            Первый абзац
            продолжается здесь.

            - пункт один
            - пункт **два**
            1. шаг
            > цитата
            ---
            ```kotlin
            val x = 1
            ```
            """.trimIndent()
        )
        assertEquals(MdBlock.Heading(1, "Итог"), blocks[0])
        assertEquals(MdBlock.Paragraph("Первый абзац продолжается здесь."), blocks[1])
        assertEquals(MdBlock.Bullet("пункт один", 0), blocks[2])
        assertEquals(MdBlock.Bullet("пункт **два**", 0), blocks[3])
        assertEquals(MdBlock.Numbered("1", "шаг"), blocks[4])
        assertEquals(MdBlock.Quote("цитата"), blocks[5])
        assertEquals(MdBlock.Rule, blocks[6])
        assertEquals(MdBlock.Code("kotlin", "val x = 1"), blocks[7])
    }

    @Test
    fun unclosedCodeFenceKeepsTheRest() {
        val blocks = MarkdownParser.parse("```\nline 1\nline 2")
        assertEquals(listOf(MdBlock.Code("", "line 1\nline 2")), blocks)
    }

    @Test
    fun inlineBoldCodeAndLinks() {
        val s = MarkdownParser.inline("a **b** `c` [d](https://x.y) e", Color.Cyan, Color.Cyan)
        assertEquals("a b c d e", s.text)
        assertTrue(s.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && s.text.substring(it.start, it.end) == "b" })
    }

    @Test
    fun lonelyAsterisksStayText() {
        assertEquals("2 * 3 = 6", MarkdownParser.inline("2 * 3 = 6", Color.Cyan, Color.Cyan).text)
        assertEquals("**", MarkdownParser.inline("**", Color.Cyan, Color.Cyan).text)
    }
}
