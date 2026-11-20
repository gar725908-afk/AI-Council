package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A model NEXUS recommends for the owner's PC, pulled by Ollama on that PC. */
data class OllamaOffer(
    val tag: String,
    val title: String,
    val tier: ModelTier,
    /** Total download from the Ollama registry (weights + vision projector + draft), bytes. */
    val downloadBytes: Long,
    val note: String,
    val russianTuned: Boolean = false,
    val isNew: Boolean = false
)

/**
 * Curated Ollama models up to 9B (the owner's rule: nothing heavier), with
 * sizes taken from registry.ollama.ai manifests. Hugging Face GGUFs are pulled
 * through Ollama's hf.co/ syntax, so Russian models need no manual setup.
 */
object OllamaModelHub {
    val offers = listOf(
        OllamaOffer("qwen3.5:0.8b", "Qwen3.5 0.8B", ModelTier.LIGHT, 1_322_069_043L, "мгновенные ответы даже на слабом ПК"),
        OllamaOffer("qwen3.5:2b", "Qwen3.5 2B", ModelTier.LIGHT, 2_683_396_637L, "быстрая, понимает картинки"),
        OllamaOffer("gemma4:e2b", "Gemma 4 E2B", ModelTier.LIGHT, 4_587_156_264L, "Google, хороший русский"),
        OllamaOffer("qwen3.5:4b", "Qwen3.5 4B", ModelTier.MEDIUM, 3_324_173_757L, "умная и всё ещё быстрая"),
        OllamaOffer("gemma4:e4b", "Gemma 4 E4B", ModelTier.MEDIUM, 6_583_656_264L, "старшая Gemma 4 для ПК"),
        OllamaOffer(
            "hf.co/Vikhrmodels/QVikhr-3-4B-Instruction-GGUF:Q4_K_M", "QVikhr-3 4B", ModelTier.MEDIUM,
            2_497_281_088L, "Qwen3, дообученный на русском", russianTuned = true
        ),
        OllamaOffer(
            "nemotron-3.5-bolt:e4b", "Nemotron 3.5 Bolt E4B", ModelTier.MAX, 7_908_812_809L,
            "новинка NVIDIA: рассуждения и код", isNew = true
        ),
        OllamaOffer("qwen3.5:9b", "Qwen3.5 9B", ModelTier.MAX, 6_550_825_373L, "самая умная до 9B"),
        OllamaOffer("qwen3:8b", "Qwen3 8B", ModelTier.MAX, 5_225_387_677L, "проверенная универсальная"),
        OllamaOffer(
            "hf.co/yandex/YandexGPT-5-Lite-8B-instruct-GGUF:Q4_K_M", "YandexGPT 5 Lite 8B", ModelTier.MAX,
            4_920_741_184L, "Яндекс: родной русский язык", russianTuned = true
        ),
        OllamaOffer(
            "hf.co/t-tech/T-lite-it-2.1-GGUF:Q4_K_M", "T-lite 2.1 8B", ModelTier.MAX,
            5_027_750_240L, "Т-Банк: сильный русский", russianTuned = true
        )
    )

    /** Ollama reports tags as "qwen3.5:2b" or "qwen3.5:2b-…"; "latest" is implicit. */
    fun isInstalled(offer: OllamaOffer, installed: List<String>): Boolean =
        installedName(offer, installed) != null

    fun installedName(offer: OllamaOffer, installed: List<String>): String? {
        val wanted = normalize(offer.tag)
        return installed.firstOrNull { normalize(it) == wanted }
    }

    private fun normalize(tag: String): String {
        val lower = tag.trim().lowercase()
        return if (':' in lower.substringAfterLast('/')) lower else "$lower:latest"
    }

    /** Progress of one /api/pull, aggregated over all layers Ollama downloads. */
    data class PullProgress(val status: String, val completed: Long, val total: Long) {
        val fraction: Float get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else 0f
    }

    /** Turns NDJSON lines of /api/pull into overall progress; throws on an "error" line. */
    class PullTracker {
        private val totals = LinkedHashMap<String, Long>()
        private val done = LinkedHashMap<String, Long>()
        var status: String = "Подключаюсь к Ollama…"
            private set
        var success = false
            private set

        fun accept(line: String): PullProgress? {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return null
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
            json.optString("error").takeIf { it.isNotBlank() }?.let { throw IOException(humanError(it)) }
            val raw = json.optString("status")
            val digest = json.optString("digest")
            if (digest.isNotBlank()) {
                val total = json.optLong("total", 0L)
                if (total > 0) totals[digest] = total
                done[digest] = json.optLong("completed", done[digest] ?: 0L)
            }
            status = when {
                raw == "success" -> { success = true; "Готово" }
                raw.startsWith("pulling manifest") -> "Получаю описание модели…"
                raw.startsWith("pulling") -> "Скачиваю на ПК…"
                raw.startsWith("verifying") -> "Проверяю SHA-256…"
                raw.startsWith("writing") -> "Сохраняю…"
                raw.isNotBlank() -> raw
                else -> status
            }
            return PullProgress(status, done.values.sum(), totals.values.sum())
        }

        private fun humanError(error: String): String = when {
            "file does not exist" in error || "not found" in error -> "Такой модели нет в библиотеке Ollama: $error"
            "no space" in error -> "На компьютере не хватает места для модели."
            else -> "Ollama: $error"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // a slow registry can stall between progress lines
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Asks Ollama on the PC to download [tag]. The phone only relays progress;
     * the gigabytes travel straight from the registry to the computer.
     */
    suspend fun pull(
        endpoint: String,
        tag: String,
        onProgress: (PullProgress) -> Unit,
        onCall: (okhttp3.Call) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        val body = JSONObject().put("model", tag).put("stream", true).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(OllamaApi.baseUrl(endpoint) + "/api/pull").post(body).build()
        val call = client.newCall(request)
        onCall(call)
        call.execute().use { response ->
            if (!response.isSuccessful) throw IOException("Ollama ответил HTTP " + response.code + " на загрузку модели.")
            val source = response.body?.source() ?: throw IOException("Ollama вернул пустой ответ.")
            val tracker = PullTracker()
            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                tracker.accept(line)?.let(onProgress)
            }
            if (!tracker.success) throw IOException("Загрузка на ПК оборвалась. Нажми ещё раз — Ollama продолжит.")
        }
    }
}
