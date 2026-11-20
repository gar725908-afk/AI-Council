package ai.hybrid.commandcenter.nexus

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfTextExtractorTest {

    @Before
    fun init() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun pdfWithText(vararg lines: String): ByteArray {
        val document = PDDocument()
        val page = PDPage()
        document.addPage(page)
        PDPageContentStream(document, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(50f, 700f)
            lines.forEachIndexed { index, line ->
                if (index > 0) stream.newLineAtOffset(0f, -16f)
                stream.showText(line)
            }
            stream.endText()
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun blankPdf(): ByteArray {
        val document = PDDocument()
        document.addPage(PDPage())
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    @Test
    fun `text layer is extracted from a real pdf`() {
        val result = PdfTextExtractor.extract(pdfWithText("NEXUS PDF TEST", "second line"))
        val text = result as PdfTextExtractor.Result.Text
        assertTrue(text.text.contains("NEXUS PDF TEST"))
        assertTrue(text.text.contains("second line"))
        assertEquals(1, text.pages)
    }

    @Test
    fun `scan-like pdf without text layer is refused honestly`() {
        val result = PdfTextExtractor.extract(blankPdf())
        assertTrue(result is PdfTextExtractor.Result.Failed)
        assertTrue((result as PdfTextExtractor.Result.Failed).reason.contains("OCR"))
    }

    @Test
    fun `garbage bytes with pdf extension are refused not crashed`() {
        val result = PdfTextExtractor.extract("not a pdf".toByteArray())
        assertTrue(result is PdfTextExtractor.Result.Failed)
    }

    @Test
    fun `material extractor routes pdf through the real parser`() {
        val extraction = MaterialTextExtractor.extract("doc.pdf", pdfWithText("Archive via PDFBox"))
        val text = extraction as MaterialTextExtractor.Extraction.Text
        assertEquals("pdf", text.format)
        assertTrue(text.text.contains("Archive via PDFBox"))
    }
}
