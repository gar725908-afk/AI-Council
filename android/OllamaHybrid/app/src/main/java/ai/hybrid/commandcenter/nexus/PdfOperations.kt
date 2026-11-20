package ai.hybrid.commandcenter.nexus

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.ByteArrayOutputStream

/**
 * PDF editing primitives on PDFBox-Android (Apache 2.0): page extraction
 * and merging of stored originals. Pure byte-in/byte-out so they are unit
 * tested with synthesized documents; the UI only wires materials to them.
 */
object PdfOperations {

    sealed class Result {
        data class Document(val bytes: ByteArray, val pages: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val MAX_PAGES = 2_000

    /** 1-based inclusive page range into a new document. */
    fun extractPages(source: ByteArray, fromPage: Int, toPage: Int): Result {
        val document = load(source) ?: return Result.Failed("Исходный PDF не открывается.")
        document.use { doc ->
            if (doc.isEncrypted) return Result.Failed("PDF зашифрован — операции недоступны.")
            val total = doc.numberOfPages
            if (fromPage < 1 || toPage < fromPage || toPage > total) {
                return Result.Failed("Диапазон страниц $fromPage–$toPage вне документа (всего $total).")
            }
            if (toPage - fromPage + 1 > MAX_PAGES) return Result.Failed("Слишком большой диапазон.")
            val target = PDDocument()
            try {
                for (index in (fromPage - 1) until toPage) {
                    target.importPage(doc.getPage(index))
                }
                return Result.Document(save(target), target.numberOfPages)
            } finally {
                target.close()
            }
        }
    }

    /** Concatenates documents in the given order. */
    fun merge(sources: List<ByteArray>): Result {
        if (sources.size < 2) return Result.Failed("Для объединения нужны минимум два PDF.")
        val target = PDDocument()
        val opened = mutableListOf<PDDocument>()
        try {
            var pages = 0
            sources.forEachIndexed { index, bytes ->
                val doc = load(bytes) ?: return Result.Failed("PDF №${index + 1} не открывается.")
                opened += doc
                if (doc.isEncrypted) return Result.Failed("PDF №${index + 1} зашифрован — объединение недоступно.")
                for (page in 0 until doc.numberOfPages) {
                    if (pages >= MAX_PAGES) return Result.Failed("Превышен лимит страниц объединения.")
                    target.importPage(doc.getPage(page))
                    pages++
                }
            }
            if (pages == 0) return Result.Failed("В исходных документах нет страниц.")
            return Result.Document(save(target), pages)
        } finally {
            opened.forEach { runCatching { it.close() } }
            target.close()
        }
    }

    private fun load(bytes: ByteArray): PDDocument? = try {
        PDDocument.load(bytes)
    } catch (_: Exception) {
        null
    }

    private fun save(document: PDDocument): ByteArray {
        val out = ByteArrayOutputStream()
        document.save(out)
        return out.toByteArray()
    }
}
