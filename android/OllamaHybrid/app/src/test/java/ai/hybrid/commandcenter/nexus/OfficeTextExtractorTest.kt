package ai.hybrid.commandcenter.nexus

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficeTextExtractorTest {

    private fun zip(vararg parts: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            parts.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sampleXlsx(): ByteArray = zip(
        "xl/workbook.xml" to """<workbook><sheets><sheet name="Смета" sheetId="1"/><sheet name="Итоги" sheetId="2"/></sheets></workbook>""",
        "xl/sharedStrings.xml" to """<sst><si><t>Томограф</t></si><si><t>Цена &amp; НДС</t></si></sst>""",
        "xl/worksheets/sheet1.xml" to """<worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>
            <row r="2"><c r="A2"><v>1250000</v></c><c r="B2" t="inlineStr"><is><t>в рублях</t></is></c><c r="C2"/></row>
            </sheetData></worksheet>""",
        "xl/worksheets/sheet2.xml" to """<worksheet><sheetData><row r="1"><c r="A1"><v>42</v></c></row></sheetData></worksheet>"""
    )

    @Test
    fun `xlsx cells become sheet-addressed lines with shared and inline strings`() {
        val result = OfficeTextExtractor.extractXlsx(sampleXlsx()) as OfficeTextExtractor.Result.Text
        assertTrue(result.text.contains("Смета!A1 = Томограф"))
        assertTrue(result.text.contains("Смета!B1 = Цена & НДС"))
        assertTrue(result.text.contains("Смета!A2 = 1250000"))
        assertTrue(result.text.contains("Смета!B2 = в рублях"))
        assertTrue(result.text.contains("Итоги!A1 = 42"))
        assertTrue(result.summary.contains("ячеек с данными: 5"))
    }

    @Test
    fun `empty cells are skipped and sheets keep workbook order`() {
        val text = (OfficeTextExtractor.extractXlsx(sampleXlsx()) as OfficeTextExtractor.Result.Text).text
        assertTrue(!text.contains("C2"))
        assertTrue(text.indexOf("## Смета") < text.indexOf("## Итоги"))
    }

    @Test
    fun `xlsx without sheets or without data is refused`() {
        assertTrue(OfficeTextExtractor.extractXlsx(zip("xl/workbook.xml" to "<workbook/>")) is OfficeTextExtractor.Result.Failed)
        val empty = zip("xl/worksheets/sheet1.xml" to "<worksheet><sheetData/></worksheet>")
        assertTrue(OfficeTextExtractor.extractXlsx(empty) is OfficeTextExtractor.Result.Failed)
    }

    @Test
    fun `non zip bytes are refused not crashed`() {
        assertTrue(OfficeTextExtractor.extractXlsx("plain".toByteArray()) is OfficeTextExtractor.Result.Failed)
        assertTrue(OfficeTextExtractor.extractDocx(ByteArray(3)) is OfficeTextExtractor.Result.Failed)
    }

    @Test
    fun `docx paragraphs are joined from runs`() {
        val docx = zip(
            "word/document.xml" to """<w:document><w:body>
                <w:p><w:r><w:t>Договор </w:t></w:r><w:r><w:t xml:space="preserve">поставки</w:t></w:r></w:p>
                <w:p><w:r><w:t>Срок: 30 дней</w:t></w:r></w:p>
                <w:p/>
                </w:body></w:document>"""
        )
        val result = OfficeTextExtractor.extractDocx(docx) as OfficeTextExtractor.Result.Text
        assertEquals("Договор поставки\nСрок: 30 дней", result.text)
        assertTrue(result.summary.contains("абзацев: 2"))
    }

    @Test
    fun `material extractor routes xlsx and docx`() {
        assertEquals("xlsx", (MaterialTextExtractor.extract("smeta.xlsx", sampleXlsx()) as MaterialTextExtractor.Extraction.Text).format)
        val docx = zip("word/document.xml" to "<w:document><w:body><w:p><w:r><w:t>x</w:t></w:r></w:p></w:body></w:document>")
        assertEquals("docx", (MaterialTextExtractor.extract("d.docx", docx) as MaterialTextExtractor.Extraction.Text).format)
    }
}
