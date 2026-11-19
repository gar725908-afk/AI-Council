package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ProviderModelCatalog(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    suspend fun listModels(
        kind: ProviderKind,
        endpoint: String,
        apiKey: String
    ): List<String> {
        if (kind.requiresApiKey()) {
            require(apiKey.isNotBlank()) { "Для этого провайдера требуется API-ключ." }
        }
        if (kind == ProviderKind.OLLAMA) {
            require(endpoint.isNotBlank()) {
                "Укажите LAN IP компьютера, на котором запущен Ollama, например http://192.168.1.10:11434/v1."
            }
        }
        val base = parseBase(endpoint.ifBlank { defaultEndpoint(kind) })
        val url = modelsUrl(kind, base)
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        when (kind) {
            ProviderKind.OPENAI_COMPATIBLE,
            ProviderKind.OPENAI,
            ProviderKind.DEEPSEEK,
            ProviderKind.OPENROUTER,
            ProviderKind.GROQ,
            ProviderKind.MISTRAL,
            ProviderKind.TOGETHER,
            ProviderKind.FIREWORKS,
            ProviderKind.OLLAMA -> if (apiKey.isNotBlank()) {
                builder.header("Authorization", "Bearer $apiKey")
            }
            ProviderKind.ANTHROPIC -> {
                builder.header("x-api-key", apiKey)
                builder.header("anthropic-version", "2023-06-01")
            }
            ProviderKind.GEMINI -> builder.header("x-goog-api-key", apiKey)
        }
        val request = builder.build()

        val jsonText = execute(request)
        val json = JSONObject(jsonText)
        val models = when (kind) {
            ProviderKind.OPENAI_COMPATIBLE,
            ProviderKind.OPENAI,
            ProviderKind.DEEPSEEK,
            ProviderKind.OPENROUTER,
            ProviderKind.GROQ,
            ProviderKind.MISTRAL,
            ProviderKind.TOGETHER,
            ProviderKind.FIREWORKS,
            ProviderKind.OLLAMA,
            ProviderKind.ANTHROPIC -> {
                val data = json.optJSONArray("data")
                    ?: throw IOException("В ответе каталога отсутствует массив data.")
                buildList {
                    for (index in 0 until data.length()) {
                        val id = data.optJSONObject(index)?.optString("id")
                        if (!id.isNullOrBlank()) add(id)
                    }
                }
            }
            ProviderKind.GEMINI -> {
                val data = json.optJSONArray("models")
                    ?: throw IOException("В ответе Gemini отсутствует массив models.")
                buildList {
                    for (index in 0 until data.length()) {
                        val item = data.optJSONObject(index) ?: continue
                        val methods = item.optJSONArray("supportedGenerationMethods")
                        val supportsTextGeneration = methods == null ||
                            (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                        val name = item.optString("name").removePrefix("models/")
                        if (supportsTextGeneration && name.isNotBlank()) add(name)
                    }
                }
            }
        }
        return models.distinct().sorted().also {
            if (it.isEmpty()) throw IOException("Провайдер ответил, но доступных моделей для генерации не найдено.")
        }
    }

    private suspend fun execute(request: Request): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    response.use {
                        try {
                            val body = it.body?.string()
                                ?: throw IOException("Провайдер вернул пустой ответ.")
                            if (!it.isSuccessful) {
                                throw ProviderHttpException(it.code, body.take(2_000))
                            }
                            if (continuation.isActive) continuation.resume(body)
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                }
            })
        }

    private fun modelsUrl(kind: ProviderKind, base: HttpUrl): HttpUrl = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE,
        ProviderKind.OPENAI,
        ProviderKind.DEEPSEEK,
        ProviderKind.OPENROUTER,
        ProviderKind.GROQ,
        ProviderKind.MISTRAL,
        ProviderKind.TOGETHER,
        ProviderKind.FIREWORKS,
        ProviderKind.OLLAMA -> base.newBuilder()
            .addPathSegment("models")
            .build()
        ProviderKind.ANTHROPIC -> when {
            base.encodedPath.endsWith("/v1/models") -> base
            base.encodedPath.endsWith("/v1") -> base.newBuilder().addPathSegment("models").build()
            else -> base.newBuilder().addPathSegments("v1/models").build()
        }
        ProviderKind.GEMINI -> base.newBuilder()
            .addPathSegment("models")
            .build()
    }

    private fun parseBase(endpoint: String): HttpUrl {
        val url = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Некорректный URL endpoint.")
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Не указывайте логин или пароль внутри URL."
        }
        require(url.query == null && url.fragment == null) {
            "Endpoint каталога должен быть базовым URL без query и fragment."
        }
        require(url.scheme == "https" || (url.scheme == "http" && isPrivateHost(url.host))) {
            "Используйте HTTPS; HTTP разрешён только для localhost или частной сети."
        }
        return url
    }

    private fun isPrivateHost(host: String): Boolean {
        val value = host.lowercase()
        if (value == "localhost" || value.endsWith(".localhost") || value.endsWith(".local") ||
            value == "::1" || value.startsWith("fc") || value.startsWith("fd") ||
            value.startsWith("fe80:")) return true
        val octets = value.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 127 || (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }

    private fun defaultEndpoint(kind: ProviderKind): String = when (kind) {
        ProviderKind.OPENAI_COMPATIBLE, ProviderKind.OPENAI -> "https://api.openai.com/v1"
        ProviderKind.DEEPSEEK -> "https://api.deepseek.com"
        ProviderKind.OPENROUTER -> "https://openrouter.ai/api/v1"
        ProviderKind.GROQ -> "https://api.groq.com/openai/v1"
        ProviderKind.MISTRAL -> "https://api.mistral.ai/v1"
        ProviderKind.TOGETHER -> "https://api.together.ai/v1"
        ProviderKind.FIREWORKS -> "https://api.gofireworks.org/v1"
        ProviderKind.OLLAMA -> ""
        ProviderKind.ANTHROPIC -> "https://api.anthropic.com/v1"
        ProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    }
}
