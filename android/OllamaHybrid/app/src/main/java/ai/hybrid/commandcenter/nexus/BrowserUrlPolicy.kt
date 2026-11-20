package ai.hybrid.commandcenter.nexus

/**
 * Address policy for the Cyber Browser (handoff 4.4, 14.3): only http(s)
 * navigations, no javascript:/file:/content:/intent: schemes, no hidden
 * search-engine fallback — the user sees exactly where they are going.
 */
object BrowserUrlPolicy {

    private val hostLike = Regex("""^[\p{L}\p{N}.-]+(:\d{1,5})?(/.*)?$""")

    fun normalize(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.length > 2048) return null
        if (trimmed.any { it.code < 0x20 || it == ' ' }) return null
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("https://") || lower.startsWith("http://") -> trimmed
            trimmed.contains("://") -> null
            lower.startsWith("javascript:") || lower.startsWith("file:") ||
                lower.startsWith("content:") || lower.startsWith("intent:") -> null
            hostLike.matches(trimmed) && trimmed.substringBefore('/').contains('.') -> "https://$trimmed"
            hostLike.matches(trimmed) && trimmed.substringBefore('/').substringBefore(':') == "localhost" ->
                "http://$trimmed"
            else -> null
        }
    }

    /** Whether an in-page navigation (link click/redirect) may proceed. */
    fun allowsNavigation(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("https://") || lower.startsWith("http://")
    }

    fun displayHost(url: String): String =
        url.substringAfter("://", url).substringBefore('/').take(80)
}
