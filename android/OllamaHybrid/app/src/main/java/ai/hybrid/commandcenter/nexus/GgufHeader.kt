package ai.hybrid.commandcenter.nexus

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/** What NEXUS learned from the header of a GGUF file, without loading the weights. */
data class GgufInfo(
    val version: Int,
    val tensorCount: Long,
    val architecture: String?,
    val name: String?,
    val basename: String?,
    val sizeLabel: String?,
    val finetune: String?,
    val fileType: Int?,
    val license: String?,
    val contextLength: Long?,
    val blockCount: Long?,
    val splitCount: Int?,
    val splitIndex: Int?,
    val chatTemplate: String?
) {
    val quantization: String? get() = fileType?.let { GgufHeader.fileTypeName(it) }

    /** "Qwen3.5 2B Instruct" style title built from the metadata the author left. */
    val title: String?
        get() {
            val base = (name ?: basename)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val withSize = if (sizeLabel != null && !base.contains(sizeLabel, ignoreCase = true)) "$base $sizeLabel" else base
            return withSize.take(60)
        }

    val kind: GgufKind get() = GgufHeader.classify(architecture)
}

enum class GgufKind(val runnable: Boolean, val note: String) {
    CHAT(true, ""),
    EMBEDDING(false, "модель эмбеддингов, она не отвечает в чате"),
    PROJECTOR(false, "проектор картинок (mmproj), нужен вместе с основной моделью"),
    AUXILIARY(false, "служебная модель (черновик или озвучка), отдельно не запускается"),
    UNSUPPORTED(false, "архитектура не поддерживается встроенным llama.cpp"),
    UNKNOWN(false, "в файле не указана архитектура")
}

/**
 * Minimal, allocation-bounded reader of the GGUF v2/v3 metadata block.
 * It skips the vocabulary arrays instead of storing them, so even a 262K-token
 * Gemma header is read in a few milliseconds and a few kilobytes of memory.
 */
object GgufHeader {
    private const val MAGIC = 0x46554747 // "GGUF" little-endian
    private const val MAX_KV = 100_000L
    private const val MAX_ARRAY = 50_000_000L
    private const val MAX_KEY = 64 * 1024L
    private const val MAX_KEPT_STRING = 256 * 1024L
    private const val MAX_SKIPPED_STRING = 64L * 1024 * 1024

    /** Architectures the pinned llama.cpp (v1.0.0) can run as a chat model. */
    private val EMBEDDING = setOf(
        "bert", "modern-bert", "nomic-bert", "nomic-bert-moe", "neo-bert", "jina-bert-v2", "jina-bert-v3",
        "eurobert", "gemma-embedding", "gemma-embedding2", "llama-embed", "t5encoder"
    )
    private val AUXILIARY = setOf("wavtokenizer-dec", "qwen3tts", "pockettts", "eagle3", "dflash", "gemma4-assistant")
    private val CHAT_ARCHS = setOf(
        "llama", "llama4", "deci", "falcon", "grok", "gpt2", "gptj", "gptneox", "mpt", "baichuan", "starcoder",
        "refact", "bloom", "stablelm", "qwen", "qwen2", "qwen2moe", "qwen2vl", "sensenova_u1", "qwen3", "flint",
        "qwen3moe", "clover_moe", "qwen3next", "qwen3vl", "qwen3vlmoe", "qwen35", "qwen35moe", "clef",
        "vllm-decision2", "qwen4exp", "phi2", "phi3", "phimoe", "plamo", "plamo2", "plamo3", "codeshell", "orion",
        "internlm2", "minicpm", "minicpm3", "gemma", "gemma2", "gemma3", "gemma3n", "gemma4", "starcoder2", "mamba",
        "mamba2", "maple", "jamba", "falcon-h1", "xverse", "command-r", "cohere2", "cohere2moe", "dbrx", "olmo",
        "olmo2", "olmoe", "muse-glimmer", "muse-spark", "openelm", "arctic", "deepseek", "deepseek2",
        "deepseek2-ocr", "deepseek32", "deepseek4", "deepseek41", "xing4_0", "chatglm", "glm4", "glm4moe",
        "glm-dsa", "bitnet", "t5", "jais", "jais2", "nemotron", "nemotron_h", "nemotron_h_moe", "metis16",
        "exaone", "exaone4", "exaone-moe", "rwkv6", "rwkv6qwen2", "rwkv7", "arwkv7", "granite", "granitemoe",
        "granitehybrid", "graniteswitch", "granite_swa", "chameleon", "plm", "bailingmoe", "bailingmoe2",
        "bailingmoe3", "dots1", "dots3note", "arcee", "afmoe", "laguna", "ernie4_5", "ernie4_5-moe",
        "hunyuan-moe", "hunyuan-dense", "hunyuan_vl", "hy_v3", "hy_v4", "smollm3", "gpt-oss", "lfm2", "lfm2moe",
        "lfm3", "dream", "smallthinker", "llada", "llada-moe", "seed_oss", "grovemoe", "apertus", "minimax-01",
        "hrm_text", "limite", "minimax-m2", "minimax-m3", "cogvlm", "rnd1", "pangu-embedded", "mistral3",
        "mistral4", "mistral-large4", "paddleocr", "mimo2", "step35", "spark2_5", "maincoder", "maion-coder",
        "kimi-linear", "kimi-k3", "kimi-k31", "glm5-next", "talkie", "mellum", "nanbeige", "k2-horizon", "beam",
        "kolibri1", "ricursive"
    )

    fun classify(architecture: String?): GgufKind {
        val arch = architecture?.lowercase() ?: return GgufKind.UNKNOWN
        return when {
            arch == "clip" -> GgufKind.PROJECTOR
            arch in EMBEDDING -> GgufKind.EMBEDDING
            arch in AUXILIARY -> GgufKind.AUXILIARY
            arch in CHAT_ARCHS -> GgufKind.CHAT
            else -> GgufKind.UNSUPPORTED
        }
    }

    fun fileTypeName(type: Int): String = when (type) {
        0 -> "F32"; 1 -> "F16"; 2 -> "Q4_0"; 3 -> "Q4_1"; 7 -> "Q8_0"; 8 -> "Q5_0"; 9 -> "Q5_1"
        10 -> "Q2_K"; 11 -> "Q3_K_S"; 12 -> "Q3_K_M"; 13 -> "Q3_K_L"; 14 -> "Q4_K_S"; 15 -> "Q4_K_M"
        16 -> "Q5_K_S"; 17 -> "Q5_K_M"; 18 -> "Q6_K"; 19 -> "IQ2_XXS"; 20 -> "IQ2_XS"; 21 -> "Q2_K_S"
        22 -> "IQ3_XS"; 23 -> "IQ3_XXS"; 24 -> "IQ1_S"; 25 -> "IQ4_NL"; 26 -> "IQ3_S"; 27 -> "IQ3_M"
        28 -> "IQ2_S"; 29 -> "IQ2_M"; 30 -> "IQ4_XS"; 31 -> "IQ1_M"; 32 -> "BF16"; 36 -> "TQ1_0"; 37 -> "TQ2_0"
        38 -> "MXFP4"
        else -> "тип $type"
    }

    /** Reads the header; throws [IOException] for anything that is not a sane GGUF. */
    fun read(input: InputStream): GgufInfo {
        val reader = LeReader(if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024))
        if (reader.u32() != MAGIC) throw IOException("Это не GGUF-файл: неверная сигнатура.")
        val version = reader.u32()
        if (version !in 2..3) throw IOException("Неподдерживаемая версия GGUF: $version.")
        val tensorCount = reader.u64()
        val kvCount = reader.u64()
        if (tensorCount < 0 || kvCount < 0 || kvCount > MAX_KV) throw IOException("Повреждённый заголовок GGUF.")

        val kept = HashMap<String, Any>()
        var architecture: String? = null
        for (i in 0 until kvCount) {
            val key = reader.string(MAX_KEY) ?: throw IOException("Слишком длинный ключ GGUF.")
            val type = reader.u32()
            val wanted = key in WANTED_KEYS || key.endsWith(".context_length") || key.endsWith(".block_count")
            if (wanted && type != TYPE_ARRAY) {
                val value = reader.value(type, keep = true)
                if (value != null) kept[key] = value
                if (key == "general.architecture") architecture = value as? String
            } else {
                reader.value(type, keep = false)
            }
        }
        fun str(key: String) = (kept[key] as? String)?.takeIf { it.isNotBlank() }
        fun num(key: String) = (kept[key] as? Number)?.toLong()
        return GgufInfo(
            version = version,
            tensorCount = tensorCount,
            architecture = architecture,
            name = str("general.name"),
            basename = str("general.basename"),
            sizeLabel = str("general.size_label"),
            finetune = str("general.finetune"),
            fileType = num("general.file_type")?.toInt(),
            license = str("general.license"),
            contextLength = architecture?.let { num("$it.context_length") },
            blockCount = architecture?.let { num("$it.block_count") },
            splitCount = num("split.count")?.toInt(),
            splitIndex = num("split.no")?.toInt(),
            chatTemplate = str("tokenizer.chat_template")
        )
    }

    private val WANTED_KEYS = setOf(
        "general.architecture", "general.name", "general.basename", "general.size_label", "general.finetune",
        "general.file_type", "general.license", "split.count", "split.no", "tokenizer.chat_template"
    )

    private const val TYPE_UINT8 = 0
    private const val TYPE_INT8 = 1
    private const val TYPE_UINT16 = 2
    private const val TYPE_INT16 = 3
    private const val TYPE_UINT32 = 4
    private const val TYPE_INT32 = 5
    private const val TYPE_FLOAT32 = 6
    private const val TYPE_BOOL = 7
    private const val TYPE_STRING = 8
    private const val TYPE_ARRAY = 9
    private const val TYPE_UINT64 = 10
    private const val TYPE_INT64 = 11
    private const val TYPE_FLOAT64 = 12

    private class LeReader(private val input: InputStream) {
        private val scratch = ByteArray(8)

        private fun fill(count: Int) {
            var read = 0
            while (read < count) {
                val n = input.read(scratch, read, count - read)
                if (n < 0) throw EOFException("Файл GGUF обрывается в заголовке.")
                read += n
            }
        }

        fun u8(): Int { fill(1); return scratch[0].toInt() and 0xFF }
        fun u16(): Int { fill(2); return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8) }
        fun u32(): Int {
            fill(4)
            return (scratch[0].toInt() and 0xFF) or ((scratch[1].toInt() and 0xFF) shl 8) or
                ((scratch[2].toInt() and 0xFF) shl 16) or ((scratch[3].toInt() and 0xFF) shl 24)
        }
        fun u64(): Long {
            fill(8)
            var value = 0L
            for (i in 7 downTo 0) value = (value shl 8) or (scratch[i].toLong() and 0xFF)
            return value
        }

        fun skip(count: Long) {
            var left = count
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) { left -= skipped; continue }
                if (input.read() < 0) throw EOFException("Файл GGUF обрывается в заголовке.")
                left--
            }
        }

        /** Reads a GGUF string, or skips it and returns null when it is longer than [maxKept]. */
        fun string(maxKept: Long): String? {
            val length = u64()
            if (length < 0 || length > MAX_SKIPPED_STRING) throw IOException("Повреждённая строка в заголовке GGUF.")
            if (length > maxKept) { skip(length); return null }
            val bytes = ByteArray(length.toInt())
            var read = 0
            while (read < bytes.size) {
                val n = input.read(bytes, read, bytes.size - read)
                if (n < 0) throw EOFException("Файл GGUF обрывается в заголовке.")
                read += n
            }
            return String(bytes, Charsets.UTF_8)
        }

        fun value(type: Int, keep: Boolean): Any? = when (type) {
            TYPE_UINT8 -> u8().toLong()
            TYPE_INT8 -> u8().toByte().toLong()
            TYPE_UINT16 -> u16().toLong()
            TYPE_INT16 -> u16().toShort().toLong()
            TYPE_UINT32 -> u32().toLong() and 0xFFFFFFFFL
            TYPE_INT32 -> u32().toLong()
            TYPE_FLOAT32 -> { u32(); null }
            TYPE_BOOL -> u8() != 0
            TYPE_STRING -> if (keep) string(MAX_KEPT_STRING) else { string(0); null }
            TYPE_UINT64, TYPE_INT64 -> u64()
            TYPE_FLOAT64 -> { u64(); null }
            TYPE_ARRAY -> { skipArray(); null }
            else -> throw IOException("Неизвестный тип значения GGUF: $type.")
        }

        private fun skipArray() {
            val elementType = u32()
            val count = u64()
            if (count < 0 || count > MAX_ARRAY) throw IOException("Повреждённый массив в заголовке GGUF.")
            val fixed = when (elementType) {
                TYPE_UINT8, TYPE_INT8, TYPE_BOOL -> 1L
                TYPE_UINT16, TYPE_INT16 -> 2L
                TYPE_UINT32, TYPE_INT32, TYPE_FLOAT32 -> 4L
                TYPE_UINT64, TYPE_INT64, TYPE_FLOAT64 -> 8L
                else -> 0L
            }
            when {
                fixed > 0 -> skip(fixed * count)
                elementType == TYPE_STRING -> for (i in 0 until count) string(0)
                elementType == TYPE_ARRAY -> for (i in 0 until count) skipArray()
                else -> throw IOException("Неизвестный тип массива GGUF: $elementType.")
            }
        }
    }
}
