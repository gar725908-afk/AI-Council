package ai.hybrid.commandcenter.nexus

/**
 * Honest text extraction for the Material Library (handoff section 4.7):
 * only formats with a real parser are imported; everything else is refused
 * with a reason instead of being silently mangled. PDF goes through
 * PDFBox-Android (text layer only, no OCR); DOCX is not pretended yet.
 */
object MaterialTextExtractor {

    sealed class Extraction {
        data class Text(val text: String, val format: String) : Extraction()
        data class Unsupported(val reason: String) : Extraction()
    }

    const val MAX_TEXT_CHARS = 2_000_000
    private val supported = setOf("txt", "md", "markdown", "json", "log", "csv", "pdf", "xlsx", "docx")

    fun supportedFormatsLabel(): String = "PDF (текстовый слой), XLSX, DOCX, TXT, MD, JSON, LOG, CSV"

    fun extract(fileName: String, bytes: ByteArray): Extraction {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension !in supported) {
            return Extraction.Unsupported(
                "Формат «${extension.ifBlank { "без расширения" }}» пока не поддерживается. " +
                    "Реальные парсеры есть для: ${supportedFormatsLabel()}."
            )
        }
        if (bytes.isEmpty()) {
            return Extraction.Unsupported("Файл пуст.")
        }
        if (extension == "xlsx") {
            return when (val result = OfficeTextExtractor.extractXlsx(bytes)) {
                is OfficeTextExtractor.Result.Text -> Extraction.Text(result.text, "xlsx")
                is OfficeTextExtractor.Result.Failed -> Extraction.Unsupported(result.reason)
            }
        }
        if (extension == "docx") {
            return when (val result = OfficeTextExtractor.extractDocx(bytes)) {
                is OfficeTextExtractor.Result.Text -> Extraction.Text(result.text, "docx")
                is OfficeTextExtractor.Result.Failed -> Extraction.Unsupported(result.reason)
            }
        }
        if (extension == "pdf") {
            return when (val result = PdfTextExtractor.extract(bytes)) {
                is PdfTextExtractor.Result.Text -> Extraction.Text(result.text, "pdf")
                is PdfTextExtractor.Result.Failed -> Extraction.Unsupported(result.reason)
            }
        }
        if (looksBinary(bytes)) {
            return Extraction.Unsupported("Файл выглядит бинарным, а не текстовым.")
        }
        val text = bytes.toString(Charsets.UTF_8)
        if (text.isBlank()) {
            return Extraction.Unsupported("После чтения не осталось текста.")
        }
        return Extraction.Text(text.take(MAX_TEXT_CHARS), extension)
    }

    /** NUL bytes in the first kilobytes are a reliable binary signal. */
    private fun looksBinary(bytes: ByteArray): Boolean {
        val window = minOf(bytes.size, 8_192)
        for (index in 0 until window) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }
}
