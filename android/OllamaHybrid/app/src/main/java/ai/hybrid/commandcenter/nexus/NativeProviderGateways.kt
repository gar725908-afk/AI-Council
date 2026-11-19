package ai.hybrid.commandcenter.nexus

import android.content.Context

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

enum class ProviderKind(val displayName: String) {
    OPENAI_COMPATIBLE("Custom OpenAI-compatible"),
    OPENAI("OpenAI"),
    DEEPSEEK("DeepSeek"),
    OPENROUTER("OpenRouter"),
    GROQ("Groq"),
    MISTRAL("Mistral"),
    TOGETHER("Together AI"),
    FIREWORKS("Fireworks AI"),
    XAI("xAI Grok"),
    CEREBRAS("Cerebras Inference"),
    NVIDIA("NVIDIA API Catalog"),
    OLLAMA("Ollama local"),
    ANTHROPIC("Anthropic Claude"),
    GEMINI("Google Gemini"),
    ON_DEVICE_LITERT("On-device LiteRT-LM"),
    ON_DEVICE_LLAMA_CPP("On-device llama.cpp")
}

fun ProviderKind.requiresApiKey(): Boolean = when (this) {
    ProviderKind.OPENAI_COMPATIBLE, ProviderKind.OLLAMA,
    ProviderKind.ON_DEVICE_LITERT, ProviderKind.ON_DEVICE_LLAMA_CPP -> false
    else -> true
}

fun createModelGateway(kind: ProviderKind, context: Context? = null): ModelGateway = when (kind) {
    ProviderKind.OPENAI_COMPATIBLE,
    ProviderKind.OPENAI,
    ProviderKind.DEEPSEEK,
    ProviderKind.OPENROUTER,
    ProviderKind.GROQ,
    ProviderKind.MISTRAL,
    ProviderKind.TOGETHER,
    ProviderKind.FIREWORKS,
    ProviderKind.XAI,
    ProviderKind.CEREBRAS,
    ProviderKind.NVIDIA -> OpenAiCompatibleGateway()
    // Native Ollama LAN API per handoff section 10.3: /api/chat JSONL streaming.
    ProviderKind.OLLAMA -> OllamaNativeGateway()
    ProviderKind.ON_DEVICE_LITERT -> LiteRtLmGateway(
        context ?: throw IllegalArgumentException("Для локального LiteRT-LM требуется Android Context.")
    )
    ProviderKind.ON_DEVICE_LLAMA_CPP -> LlamaCppGateway(
        context ?: throw IllegalArgumentException("Для локального llama.cpp требуется Android Context.")
    )
    ProviderKind.ANTHROPIC -> AnthropicMessagesGateway()
    ProviderKind.GEMINI -> GeminiGenerateContentGateway()
}

internal object ProviderEndpointPolicy {
    fun base(endpoint: String): HttpUrl {
        require(endpoint.isNotBlank() && endpoint.length <= 2_048) {
            "Endpoint must contain 1 to 2048 characters."
        }
        val parsed = endpoint.trim().trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Некорректный URL провайдера.")
        require(parsed.username.isEmpty() && parsed.password.isEmpty()) {
            "Не указывайте логин или пароль внутри URL endpoint."
        }
        require(parsed.query == null && parsed.fragment == null) {
            "Укажите базовый URL без query-параметров и fragment."
        }
        if (parsed.scheme == "http") {
            require(isPrivateHost(parsed.host)) {
                "Незащищённый HTTP разрешён только для localhost и частной сети. Для внешнего сервера используйте HTTPS."
            }
        }
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "Поддерживаются только HTTP и HTTPS endpoint."
        }
        return parsed
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") ||
            normalized.endsWith(".local")) return true

        if (normalized.contains(':')) {
            val address = runCatching { InetAddress.getByName(normalized) }.getOrNull()
                as? Inet6Address ?: return false
            val bytes = address.address
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            val uniqueLocal = (first and 0xFE) == 0xFC
            val linkLocal = first == 0xFE && (second and 0xC0) == 0x80
            return address.isLoopbackAddress || uniqueLocal || linkLocal
        }

        val octets = normalized.split(".")
        if (octets.size != 4 || octets.any { part ->
                part.isEmpty() || part.any { !it.isDigit() }
            }
        ) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false
        val a = values[0]
        val b = values[1]
        return a == 10 || a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254)
    }
}

internal object SseTransport {
    private const val MAX_SSE_LINE_BYTES = 1_048_576L
    private const val MAX_ERROR_BODY_BYTES = 2_048L

    private val defaultClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stream(
        request: Request,
        client: OkHttpClient = defaultClient,
        parseData: (String) -> String?
    ): Flow<String> = callbackFlow {
        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                close(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { networkResponse ->
                    try {
                        if (!networkResponse.isSuccessful) {
                            val details = if (networkResponse.body != null) {
                                networkResponse.peekBody(MAX_ERROR_BODY_BYTES).string().take(2_000)
                            } else {
                                networkResponse.message
                            }
                            close(ProviderHttpException(networkResponse.code, details))
                            return
                        }
                        val body = networkResponse.body
                            ?: throw IOException("Провайдер вернул пустое тело ответа.")
                        val source = body.source()
                        while (!source.exhausted() && !channel.isClosedForSend) {
                            val newlineOffset = source.indexOf(
                                '\n'.code.toByte(),
                                0L,
                                MAX_SSE_LINE_BYTES.toLong() + 1L
                            )
                            if (newlineOffset > MAX_SSE_LINE_BYTES ||
                                (newlineOffset == -1L && source.request(MAX_SSE_LINE_BYTES.toLong() + 1L))
                            ) {
                                throw IOException("Provider SSE line exceeds the 1 MiB safety limit.")
                            }
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue
                            val fragment = parseData(data)
                            if (!fragment.isNullOrEmpty()) {
                                // Apply bounded backpressure on the OkHttp callback thread instead
                                // of dropping tokens when the downstream collector is briefly slower.
                                val sent = channel.trySendBlocking(fragment)
                                if (sent.isFailure && !channel.isClosedForSend) {
                                    close(IOException("The provider stream could not deliver a response fragment."))
                                    call.cancel()
                                    return
                                }
                            }
                        }
                        close()
                    } catch (error: Throwable) {
                        close(error)
                    }
                }
            }
        })
        awaitClose { call.cancel() }
    }
}

class AnthropicMessagesGateway : ModelGateway {
    override fun stream(request: CompletionRequest): Flow<String> {
        require(request.apiKey.isNotBlank()) { "Для Anthropic требуется API-ключ." }
        require(request.model.isNotBlank()) { "Не указана модель Claude." }

        val base = ProviderEndpointPolicy.base(
            request.endpoint.ifBlank { "https://api.anthropic.com" }
        )
        val url = when {
            base.encodedPath.endsWith("/v1/messages") -> base
            base.encodedPath.endsWith("/v1") -> base.newBuilder().addPathSegment("messages").build()
            else -> base.newBuilder().addPathSegments("v1/messages").build()
        }

        val systemPrompt = request.messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }

        val messages = JSONArray()
        request.messages.filter { it.role != "system" }.forEach { message ->
            val role = if (message.role == "assistant") "assistant" else "user"
            messages.put(JSONObject().put("role", role).put("content", message.content))
        }
        require(messages.length() > 0) { "Для Anthropic необходим хотя бы один user/assistant message." }

        val payload = JSONObject()
            .put("model", request.model)
            .put("max_tokens", 4096)
            .put("stream", true)
            .put("messages", messages)
        if (systemPrompt.isNotBlank()) payload.put("system", systemPrompt)

        val httpRequest = Request.Builder()
            .url(url)
            .header("x-api-key", request.apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return SseTransport.stream(httpRequest) { data ->
            val json = JSONObject(data)
            when (json.optString("type")) {
                "error" -> {
                    val error = json.optJSONObject("error")
                    throw IllegalStateException(error?.optString("message") ?: "Anthropic stream error.")
                }
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta")
                    if (delta?.optString("type") == "text_delta") delta.optString("text") else null
                }
                else -> null
            }
        }
    }
}

class GeminiGenerateContentGateway : ModelGateway {
    override fun stream(request: CompletionRequest): Flow<String> {
        require(request.apiKey.isNotBlank()) { "Для Google Gemini требуется API-ключ." }
        require(request.model.matches(Regex("[A-Za-z0-9._-]{1,120}"))) {
            "Некорректное имя модели Gemini."
        }

        val base = ProviderEndpointPolicy.base(
            request.endpoint.ifBlank { "https://generativelanguage.googleapis.com/v1beta" }
        )
        val url = base.newBuilder()
            .addPathSegments("models/${request.model}:streamGenerateContent")
            .addQueryParameter("alt", "sse")
            .build()

        val systemPrompt = request.messages
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }

        val contents = JSONArray()
        request.messages.filter { it.role != "system" }.forEach { message ->
            val role = if (message.role == "assistant") "model" else "user"
            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", message.content)))
            )
        }
        require(contents.length() > 0) { "Для Gemini необходим хотя бы один user/model message." }

        val payload = JSONObject().put("contents", contents)
        if (systemPrompt.isNotBlank()) {
            payload.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }
        payload.put(
            "generationConfig",
            JSONObject().put("temperature", request.temperature)
        )

        val httpRequest = Request.Builder()
            .url(url)
            .header("x-goog-api-key", request.apiKey)
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return SseTransport.stream(httpRequest) { data ->
            val json = JSONObject(data)
            val candidates = json.optJSONArray("candidates")
            if (candidates == null || candidates.length() == 0) {
                val feedback = json.optJSONObject("promptFeedback")
                if (feedback != null) {
                    throw IllegalStateException("Gemini не принял запрос: ${feedback}")
                }
                null
            } else {
                val parts = candidates.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                if (parts == null) {
                    null
                } else {
                    buildString {
                        for (index in 0 until parts.length()) {
                            val text = parts.optJSONObject(index)?.optString("text")
                            if (!text.isNullOrEmpty()) append(text)
                        }
                    }.ifEmpty { null }
                }
            }
        }
    }
}

/** Canonical default endpoint per provider; the runtime and UI share this one source. */
fun defaultProviderEndpoint(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI_COMPATIBLE -> ""
    ProviderKind.OPENAI -> "https://api.openai.com/v1"
    ProviderKind.DEEPSEEK -> "https://api.deepseek.com"
    ProviderKind.OPENROUTER -> "https://openrouter.ai/api/v1"
    ProviderKind.GROQ -> "https://api.groq.com/openai/v1"
    ProviderKind.MISTRAL -> "https://api.mistral.ai/v1"
    ProviderKind.TOGETHER -> "https://api.together.ai/v1"
    ProviderKind.FIREWORKS -> "https://api.gofireworks.org/v1"
    ProviderKind.XAI -> "https://api.x.ai/v1"
    ProviderKind.CEREBRAS -> "https://api.cerebras.ai/v1"
    ProviderKind.NVIDIA -> "https://integrate.api.nvidia.com/v1"
    ProviderKind.OLLAMA -> ""
    ProviderKind.ANTHROPIC -> "https://api.anthropic.com/v1"
    ProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    ProviderKind.ON_DEVICE_LITERT, ProviderKind.ON_DEVICE_LLAMA_CPP -> ""
}
