package ai.hybrid.commandcenter.nexus

/**
 * Honest text extraction for the Material Library (handoff section 4.7):
 * only formats with a real parser are imported; everything else is refused
 * with a reason instead of being silently mangled. PDF/DOCX parsing is a
 * separate future step and is not pretended here.
 */
object MaterialTextExtractor {

    sealed class Extraction {
        data class Text(val text: String, val format: String) : Extraction()
        data class Unsupported(val reason: String) : Extraction()
    }

    const val MAX_TEXT_CHARS = 2_000_000
    private val supported = setOf("txt", "md", "markdown", "json", "log", "csv")

    fun supportedFormatsLabel(): String = "TXT, MD, JSON, LOG, CSV"

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
