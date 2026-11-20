package ai.hybrid.commandcenter.nexus

/**
 * Lexical search over imported materials with real citations: every match
 * carries its source item and character position, and the snippet is an
 * exact quote from the stored text — never a paraphrase (handoff 4.7:
 * search with citation of the source).
 */
object LibrarySearch {

    data class Doc(val id: String, val name: String, val text: String)

    data class Match(
        val itemId: String,
        val itemName: String,
        val score: Int,
        val snippet: String,
        val positionChars: Int
    )

    private const val SNIPPET_RADIUS = 90
    private const val MIN_TERM_LENGTH = 2

    fun terms(query: String): List<String> = query
        .lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= MIN_TERM_LENGTH }
        .distinct()
        .take(8)

    fun search(docs: List<Doc>, query: String, limit: Int = 20): List<Match> {
        val queryTerms = terms(query)
        if (queryTerms.isEmpty()) return emptyList()
        return docs.mapNotNull { doc -> scoreDoc(doc, queryTerms) }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.itemName })
            .take(limit.coerceIn(1, 100))
    }

    private fun scoreDoc(doc: Doc, queryTerms: List<String>): Match? {
        val lower = doc.text.lowercase()
        var score = 0
        var firstPosition = -1
        for (term in queryTerms) {
            var index = lower.indexOf(term)
            if (index >= 0 && (firstPosition == -1 || index < firstPosition)) {
                firstPosition = index
            }
            while (index >= 0) {
                score++
                index = lower.indexOf(term, index + term.length)
            }
        }
        if (score == 0 || firstPosition < 0) return null
        return Match(
            itemId = doc.id,
            itemName = doc.name,
            score = score,
            snippet = snippetAt(doc.text, firstPosition),
            positionChars = firstPosition
        )
    }

    /** Exact quote around the first hit, with ellipses only at real cuts. */
    fun snippetAt(text: String, position: Int): String {
        val safePosition = position.coerceIn(0, maxOf(0, text.length - 1))
        val start = maxOf(0, safePosition - SNIPPET_RADIUS)
        val end = minOf(text.length, safePosition + SNIPPET_RADIUS)
        val core = text.substring(start, end).replace(Regex("\\s+"), " ").trim()
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < text.length) "…" else ""
        return prefix + core + suffix
    }
}
