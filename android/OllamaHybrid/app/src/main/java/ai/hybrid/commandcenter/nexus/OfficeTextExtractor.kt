package ai.hybrid.commandcenter.nexus

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Dependency-free text extraction for Office Open XML (handoff 4.7, 8):
 * XLSX and DOCX are ZIP containers with XML parts. Spreadsheets become
 * cell-addressed text (Sheet!A1 = value) so a reviewer or Council can cite
 * exact cells; documents become paragraphs. Formulas, styles and images
 * are not interpreted — only what is actually stored as text/values.
 */
object OfficeTextExtractor {

    sealed class Result {
        data class Text(val text: String, val summary: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private const val MAX_ENTRY_BYTES = 60L * 1024 * 1024
    private const val MAX_CELLS = 200_000

    fun extractXlsx(bytes: ByteArray, maxChars: Int = MaterialTextExtractor.MAX_TEXT_CHARS): Result {
        val parts = readParts(bytes) { name ->
            name == "xl/sharedStrings.xml" || name == "xl/workbook.xml" ||
                (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
        } ?: return Result.Failed("Файл не является корректным XLSX (ZIP не читается).")
        if (parts.keys.none { it.startsWith("xl/worksheets/sheet") }) {
            return Result.Failed("В XLSX не найдено ни одного листа.")
        }
        val shared = parseSharedStrings(parts["xl/sharedStrings.xml"].orEmpty())
        val sheetNames = parseSheetNames(parts["xl/workbook.xml"].orEmpty())
        val builder = StringBuilder()
        var cells = 0
        parts.keys.filter { it.startsWith("xl/worksheets/sheet") }.sortedBy { sheetIndex(it) }.forEach { part ->
            val index = sheetIndex(part)
            val sheetName = sheetNames.getOrNull(index - 1) ?: "Лист$index"
            builder.append("## ").append(sheetName).append('\n')
            cellRegex.findAll(parts.getValue(part)).forEach { match ->
                if (cells >= MAX_CELLS || builder.length >= maxChars) return@forEach
                val attributes = match.groupValues[1]
                val ref = refAttr.find(attributes)?.groupValues?.get(1) ?: return@forEach
                val type = typeAttr.find(attributes)?.groupValues?.get(1).orEmpty()
                val inner = match.groupValues[2]
                val value = when {
                    type == "s" -> valueTag.find(inner)?.groupValues?.get(1)?.toIntOrNull()?.let { shared.getOrNull(it) }
                    type == "inlineStr" -> tTag.find(inner)?.groupValues?.get(1)?.let(::unescape)
                    else -> valueTag.find(inner)?.groupValues?.get(1)?.let(::unescape)
                }
                if (!value.isNullOrBlank()) {
                    builder.append(sheetName).append('!').append(ref).append(" = ").append(value.trim()).append('\n')
                    cells++
                }
            }
            builder.append('\n')
        }
        if (cells == 0) return Result.Failed("В таблице нет заполненных ячеек с текстом или числами.")
        return Result.Text(
            builder.toString().take(maxChars),
            "листов: ${sheetNames.size.coerceAtLeast(1)}, ячеек с данными: $cells"
        )
    }

    fun extractDocx(bytes: ByteArray, maxChars: Int = MaterialTextExtractor.MAX_TEXT_CHARS): Result {
        val parts = readParts(bytes) { it == "word/document.xml" }
            ?: return Result.Failed("Файл не является корректным DOCX (ZIP не читается).")
        val document = parts["word/document.xml"]
            ?: return Result.Failed("В DOCX нет основной части word/document.xml.")
        val paragraphs = paragraphRegex.findAll(document).map { paragraph ->
            runRegex.findAll(paragraph.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }.trim()
        }.filter { it.isNotEmpty() }.toList()
        if (paragraphs.isEmpty()) return Result.Failed("В документе не найден текст (возможно, только изображения).")
        return Result.Text(paragraphs.joinToString("\n").take(maxChars), "абзацев: ${paragraphs.size}")
    }

    private fun readParts(bytes: ByteArray, wanted: (String) -> Boolean): Map<String, String>? = try {
        val result = HashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            var sawAny = false
            while (entry != null) {
                sawAny = true
                val name = entry.name
                if (!entry.isDirectory && wanted(name) && entry.size <= MAX_ENTRY_BYTES) {
                    result[name] = zip.readBytes().toString(Charsets.UTF_8)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            if (!sawAny) return null
        }
        result
    } catch (_: Exception) {
        null
    }

    private val cellRegex = Regex("""<c\b([^>]*)>(.*?)</c>""", RegexOption.DOT_MATCHES_ALL)
    private val refAttr = Regex("""\br="([A-Z]+\d+)"""")
    private val typeAttr = Regex("""\bt="([^"]*)"""")
    private val valueTag = Regex("""<v>(.*?)</v>""", RegexOption.DOT_MATCHES_ALL)
    private val tTag = Regex("""<t[^>]*>(.*?)</t>""", RegexOption.DOT_MATCHES_ALL)
    private val siRegex = Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL)
    private val sheetRegex = Regex("""<sheet\b[^>]*\bname="([^"]*)"""")
    private val paragraphRegex = Regex("""<w:p\b[^>]*>(.*?)</w:p>""", RegexOption.DOT_MATCHES_ALL)
    private val runRegex = Regex("""<w:t\b[^>]*>(.*?)</w:t>""", RegexOption.DOT_MATCHES_ALL)

    private fun parseSharedStrings(xml: String): List<String> =
        siRegex.findAll(xml).map { si ->
            tTag.findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
        }.toList()

    private fun parseSheetNames(xml: String): List<String> =
        sheetRegex.findAll(xml).map { unescape(it.groupValues[1]) }.toList()

    private fun sheetIndex(partName: String): Int =
        partName.removePrefix("xl/worksheets/sheet").removeSuffix(".xml").toIntOrNull() ?: Int.MAX_VALUE

    private fun unescape(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&#10;", "\n").replace("&amp;", "&")
}
