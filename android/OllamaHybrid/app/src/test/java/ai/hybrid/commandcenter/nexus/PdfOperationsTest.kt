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
class PdfOperationsTest {

    @Before
    fun init() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun pdf(vararg pageTexts: String): ByteArray {
        val document = PDDocument()
        pageTexts.forEach { pageText ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { stream ->
                stream.beginText()
                stream.setFont(PDType1Font.HELVETICA, 12f)
                stream.newLineAtOffset(50f, 700f)
                stream.showText(pageText)
                stream.endText()
            }
        }
        val out = ByteArrayOutputStream()
        document.save(out)
        document.close()
        return out.toByteArray()
    }

    private fun textOf(bytes: ByteArray): String =
        (PdfTextExtractor.extract(bytes) as PdfTextExtractor.Result.Text).text

    @Test
    fun `extractPages keeps only the requested range`() {
        val result = PdfOperations.extractPages(pdf("PAGE ONE", "PAGE TWO", "PAGE THREE"), 2, 3)
        val doc = result as PdfOperations.Result.Document
        assertEquals(2, doc.pages)
        val text = textOf(doc.bytes)
        assertTrue(text.contains("PAGE TWO") && text.contains("PAGE THREE"))
        assertTrue(!text.contains("PAGE ONE"))
    }

    @Test
    fun `invalid ranges are refused with the total`() {
        val result = PdfOperations.extractPages(pdf("A", "B"), 2, 5)
        assertTrue(result is PdfOperations.Result.Failed)
        assertTrue((result as PdfOperations.Result.Failed).reason.contains("всего 2"))
        assertTrue(PdfOperations.extractPages(pdf("A"), 0, 1) is PdfOperations.Result.Failed)
    }

    @Test
    fun `merge concatenates documents in order`() {
        val result = PdfOperations.merge(listOf(pdf("FIRST"), pdf("SECOND A", "SECOND B")))
        val doc = result as PdfOperations.Result.Document
        assertEquals(3, doc.pages)
        val text = textOf(doc.bytes)
        assertTrue(text.indexOf("FIRST") < text.indexOf("SECOND A"))
    }

    @Test
    fun `merge needs two readable documents`() {
        assertTrue(PdfOperations.merge(listOf(pdf("only"))) is PdfOperations.Result.Failed)
        assertTrue(PdfOperations.merge(listOf(pdf("ok"), "garbage".toByteArray())) is PdfOperations.Result.Failed)
    }
}
