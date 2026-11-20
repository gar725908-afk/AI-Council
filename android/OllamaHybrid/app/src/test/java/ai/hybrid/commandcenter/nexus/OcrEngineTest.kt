package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrEngineTest {

    @Test
    fun `scope note states the Cyrillic limitation plainly`() {
        assertTrue(OcrEngine.SCOPE_NOTE.contains("Кириллица"))
        assertTrue(OcrEngine.SCOPE_NOTE.contains("не читается"))
    }

    @Test
    fun `assembled pages keep order and carry page markers`() {
        val assembled = OcrEngine.assemblePages(
            listOf(3 to "INVOICE 2026-11", 1 to "CONTRACT No 44-FZ", 2 to "TOTAL 1250000")
        )
        assertTrue(assembled.indexOf("## Страница 1") < assembled.indexOf("## Страница 2"))
        assertTrue(assembled.indexOf("## Страница 2") < assembled.indexOf("## Страница 3"))
        assertTrue(assembled.contains("CONTRACT No 44-FZ"))
    }

    @Test
    fun `assembled text declares that it came from recognition`() {
        val assembled = OcrEngine.assemblePages(listOf(1 to "TEXT"))
        assertTrue(assembled.startsWith("[Распознано на устройстве."))
    }

    @Test
    fun `empty page list still produces an honest header`() {
        val assembled = OcrEngine.assemblePages(emptyList())
        assertTrue(assembled.contains("Распознано на устройстве"))
        assertTrue(!assembled.contains("## Страница"))
    }

    @Test
    fun `result types distinguish empty from failed`() {
        val empty: OcrEngine.Result = OcrEngine.Result.Empty("нет текста")
        val failed: OcrEngine.Result = OcrEngine.Result.Failed("движок упал")
        assertTrue(empty is OcrEngine.Result.Empty)
        assertTrue(failed is OcrEngine.Result.Failed)
        assertEquals("движок упал", (failed as OcrEngine.Result.Failed).reason)
    }
}
