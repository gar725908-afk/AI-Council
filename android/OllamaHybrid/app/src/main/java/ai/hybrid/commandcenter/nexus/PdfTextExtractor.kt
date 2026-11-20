package ai.hybrid.commandcenter.nexus

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/**
 * Real PDF text-layer extraction via PDFBox-Android (Apache 2.0). Honest
 * about its limits: encrypted documents and scans without a text layer are
 * refused with the reason — no OCR is pretended.
 */
object PdfTextExtractor {

    sealed class Result {
        data class Text(val text: String, val pages: Int) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val MAX_PAGES = 2_000

    fun extract(bytes: ByteArray, maxChars: Int = MaterialTextExtractor.MAX_TEXT_CHARS): Result {
        val document = try {
            PDDocument.load(bytes)
        } catch (error: Exception) {
            return Result.Failed("PDF не открывается: " + (error.message ?: error.javaClass.simpleName).take(120))
        }
        document.use { doc ->
            if (doc.isEncrypted) {
                return Result.Failed("PDF зашифрован — извлечение текста без пароля невозможно.")
            }
            val pages = doc.numberOfPages
            if (pages == 0) return Result.Failed("В PDF нет страниц.")
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(pages, MAX_PAGES)
                sortByPosition = true
            }
            val text = try {
                stripper.getText(doc)
            } catch (error: Exception) {
                return Result.Failed("Текстовый слой не прочитан: " + (error.message ?: error.javaClass.simpleName).take(120))
            }
            if (text.isBlank()) {
                return Result.Failed(
                    "В PDF нет текстового слоя (похоже на скан). Нужно распознавание OCR, которого в NEXUS пока нет."
                )
            }
            return Result.Text(text.take(maxChars), pages)
        }
    }
}
