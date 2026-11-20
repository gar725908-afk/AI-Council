package ai.hybrid.commandcenter.nexus

/**
 * Turns a provider-neutral conversation into the exact text a local GGUF model
 * was trained on. A wrong format is the main reason small models "talk nonsense",
 * so every catalog model declares its [PromptStyle] and unknown files are
 * detected from the template stored inside the GGUF.
 */
object PromptFormats {
    /** Roughly 2 000–2 500 tokens of Russian text: leaves room for a 1 024-token answer in a 4K context. */
    const val DEFAULT_HISTORY_BUDGET_CHARS = 6_000

    /**
     * Keeps the system prompt and the newest turns that fit into [budgetChars].
     * The last user message is always kept (trimmed from the front if it alone is too long).
     */
    fun fitHistory(messages: List<ProviderMessage>, budgetChars: Int = DEFAULT_HISTORY_BUDGET_CHARS): List<ProviderMessage> {
        val system = messages.filter { it.role == "system" }
        val turns = messages.filter { it.role == "user" || it.role == "assistant" }
        if (turns.isEmpty()) return system
        val systemText = system.joinToString("\n\n") { it.content }
        val systemBudget = (budgetChars / 3).coerceAtLeast(400)
        val keptSystem = if (systemText.isBlank()) emptyList() else listOf(
            ProviderMessage("system", if (systemText.length > systemBudget) systemText.take(systemBudget) else systemText)
        )
        var remaining = budgetChars - (keptSystem.firstOrNull()?.content?.length ?: 0)
        val kept = ArrayDeque<ProviderMessage>()
        for (message in turns.asReversed()) {
            val size = message.content.length
            if (kept.isEmpty()) {
                val text = if (size > remaining) message.content.takeLast(remaining.coerceAtLeast(200)) else message.content
                kept.addFirst(ProviderMessage(message.role, text))
                remaining -= text.length
                continue
            }
            if (size > remaining) break
            kept.addFirst(message)
            remaining -= size
        }
        // A conversation must not start with an orphan assistant turn.
        while (kept.size > 1 && kept.first().role == "assistant") kept.removeFirst()
        return keptSystem + kept
    }

    /** Builds the prompt text, or returns null for [PromptStyle.NATIVE] (llama.cpp formats it itself). */
    fun build(style: PromptStyle, messages: List<ProviderMessage>): String? = when (style) {
        PromptStyle.QWEN_NOTHINK -> chatMl(messages, "<think>\n\n</think>\n\n")
        PromptStyle.CHATML -> chatMl(messages, "")
        PromptStyle.GEMMA4 -> gemma4(messages)
        PromptStyle.NATIVE -> null
    }

    /** Messages for llama.cpp's template engine: templates that ignore "system" still get the persona. */
    fun nativeMessages(messages: List<ProviderMessage>): List<ProviderMessage> {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content.trim() }
        val turns = messages.filter { it.role == "user" || it.role == "assistant" }
        if (system.isBlank()) return turns
        val firstUser = turns.indexOfFirst { it.role == "user" }
        if (firstUser < 0) return listOf(ProviderMessage("user", system)) + turns
        return turns.mapIndexed { index, message ->
            if (index == firstUser) ProviderMessage("user", system + "\n\n" + message.content) else message
        }
    }

    private fun chatMl(messages: List<ProviderMessage>, assistantPrefill: String): String = buildString {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        if (system.isNotBlank()) {
            append("<|im_start|>system\n").append(system.trim()).append("<|im_end|>\n")
        }
        messages.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
            append("<|im_start|>").append(message.role).append('\n')
            append(message.content.trim()).append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n").append(assistantPrefill)
    }

    /** Mirrors the Gemma 4 chat template with thinking disabled; BOS is added by the tokenizer. */
    private fun gemma4(messages: List<ProviderMessage>): String = buildString {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content.trim() }
        if (system.isNotBlank()) append("<|turn>system\n").append(system).append("<turn|>\n")
        messages.filter { it.role == "user" || it.role == "assistant" }.forEach { message ->
            append("<|turn>").append(if (message.role == "assistant") "model" else "user").append('\n')
            append(message.content.trim()).append("<turn|>\n")
        }
        append("<|turn>model\n")
    }

    /**
     * Picks a style for a GGUF that is not in the catalog, from its architecture
     * and the chat template stored in the file.
     */
    fun detect(architecture: String?, chatTemplate: String?): PromptStyle {
        val arch = architecture.orEmpty().lowercase()
        val template = chatTemplate.orEmpty()
        return when {
            "<|turn>" in template || arch == "gemma4" -> PromptStyle.GEMMA4
            "<|im_start|>" in template && ("<think>" in template || arch.startsWith("qwen3") || arch.startsWith("qwen35")) ->
                PromptStyle.QWEN_NOTHINK
            "<|im_start|>" in template -> PromptStyle.CHATML
            template.isBlank() && (arch.startsWith("qwen3") || arch.startsWith("qwen35")) -> PromptStyle.QWEN_NOTHINK
            template.isBlank() && arch.startsWith("qwen") -> PromptStyle.CHATML
            else -> PromptStyle.NATIVE
        }
    }
}

/**
 * Cleans a token stream before the owner sees it: hides reasoning blocks,
 * drops control tags and stops at end-of-turn markers that some GGUFs forget
 * to flag as end-of-generation. Works across token boundaries by holding back
 * a possible tag prefix until the next chunk decides it.
 */
class StreamSanitizer {
    private val pending = StringBuilder()
    private var hiddenUntil: String? = null
    private var emittedAnything = false

    /** True once a stop marker arrived: the caller should end generation. */
    var reachedStop: Boolean = false
        private set

    /** Returns the text that can be shown now (possibly empty). */
    fun accept(chunk: String): String {
        if (reachedStop) return ""
        pending.append(chunk)
        val out = StringBuilder()
        while (true) {
            val endTag = hiddenUntil
            if (endTag != null) {
                val end = pending.indexOf(endTag)
                if (end < 0) {
                    val keep = partialTagLength(listOf(endTag))
                    pending.delete(0, pending.length - keep)
                    break
                }
                pending.delete(0, end + endTag.length)
                hiddenUntil = null
                continue
            }
            val hit = firstTag()
            if (hit == null) {
                val keep = partialTagLength(ALL_TAGS)
                out.append(pending, 0, pending.length - keep)
                pending.delete(0, pending.length - keep)
                break
            }
            val (index, tag) = hit
            out.append(pending, 0, index)
            pending.delete(0, index + tag.length)
            if (tag in STOP_TAGS) {
                reachedStop = true
                pending.setLength(0)
                break
            }
            HIDDEN_BLOCKS[tag]?.let { hiddenUntil = it }
        }
        return visible(out.toString())
    }

    /** Flushes what is left when generation ends. */
    fun finish(): String {
        val rest = if (reachedStop || hiddenUntil != null) "" else pending.toString()
        pending.setLength(0)
        return visible(rest)
    }

    private fun visible(text: String): String {
        if (emittedAnything) return text
        val trimmed = text.trimStart()
        if (trimmed.isNotEmpty()) emittedAnything = true
        return trimmed
    }

    private fun firstTag(): Pair<Int, String>? {
        var best: Pair<Int, String>? = null
        for (tag in ALL_TAGS) {
            val index = pending.indexOf(tag)
            if (index < 0) continue
            val current = best
            if (current == null || index < current.first || (index == current.first && tag.length > current.second.length)) {
                best = index to tag
            }
        }
        return best
    }

    /** Length of the longest tail of [pending] that is a proper prefix of one of [tags]. */
    private fun partialTagLength(tags: List<String>): Int {
        val longest = tags.maxOfOrNull { it.length } ?: return 0
        for (len in minOf(pending.length, longest - 1) downTo 1) {
            val start = pending.length - len
            if (tags.any { tag -> tag.length > len && (0 until len).all { pending[start + it] == tag[it] } }) return len
        }
        return 0
    }

    companion object {
        val STOP_TAGS = listOf(
            "<|im_end|>", "<|im_start|>", "<turn|>", "<|turn>", "<end_of_turn>", "<start_of_turn>",
            "<|eot_id|>", "<|start_header_id|>", "<|endoftext|>", "<|end|>"
        )
        val HIDDEN_BLOCKS = mapOf("<think>" to "</think>", "<|channel>" to "<channel|>")
        private val CONTROL_TAGS = listOf("</think>", "<channel|>", "<|think|>", "<bos>", "<|begin_of_text|>", "[SEP]")
        val ALL_TAGS: List<String> = STOP_TAGS + HIDDEN_BLOCKS.keys + CONTROL_TAGS
    }
}
