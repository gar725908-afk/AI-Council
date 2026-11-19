package ai.hybrid.commandcenter.nexus

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native Ollama LAN node API per handoff section 10.3: /api/chat with
 * streaming JSONL and /api/tags for actual model discovery. The native API is
 * preferred over the OpenAI-compatible shim; a /v1 suffix in a stored
 * endpoint is stripped so older configurations keep working.
 */
object OllamaApi {

    data class ChatChunk(val content: String?, val done: Boolean, val error: String?)

    /** Base LAN URL without a trailing /v1 (legacy configs) or trailing slash. */
    fun baseUrl(endpoint: String): String {
        val base = ProviderEndpointPolicy.base(endpoint)
        val trimmedPath = base.encodedPath.trimEnd('/').removeSuffix("/v1")
        val builder = base.newBuilder().encodedPath(if (trimmedPath.isEmpty()) "/" else "$trimmedPath/")
        return builder.build().toString().trimEnd('/')
    }

    fun chatUrl(endpoint: String): String = baseUrl(endpoint) + "/api/chat"

    fun tagsUrl(endpoint: String): String = baseUrl(endpoint) + "/api/tags"

    /**
     * One NDJSON line of a streaming /api/chat response. Unparseable lines
     * return null so a partial buffer never crashes the stream.
     */
    fun parseChatLine(line: String): ChatChunk? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        val json = try {
            JSONObject(trimmed)
        } catch (_: Exception) {
            return null
        }
        val error = json.optString("error").takeIf { it.isNotBlank() }
        val content = json.optJSONObject("message")?.optString("content")?.takeIf { it.isNotEmpty() }
        return ChatChunk(content = content, done = json.optBoolean("done", false), error = error)
    }

    /** Model names from a /api/tags payload, bounded and order-preserving. */
    fun parseTags(jsonText: String, maxModels: Int = 200): List<String> {
        val models = JSONObject(jsonText).optJSONArray("models") ?: return emptyList()
        return buildList {
            for (index in 0 until models.length()) {
                if (size >= maxModels) break
                val name = models.optJSONObject(index)?.optString("name")?.trim()
                if (!name.isNullOrEmpty() && name.length <= 256) add(name)
            }
        }
    }
}

class OllamaNativeGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()
) : ModelGateway {

    override fun stream(request: CompletionRequest): Flow<String> = flow {
        require(request.model.isNotBlank()) { "Не указана модель." }
        val payload = JSONObject()
            .put("model", request.model)
            .put("stream", true)
            .put(
                "messages",
                JSONArray().apply {
                    request.messages.forEach { message ->
                        put(JSONObject().put("role", message.role).put("content", message.content))
                    }
                }
            )
        val httpRequest = Request.Builder()
            .url(OllamaApi.chatUrl(request.endpoint))
            .header("Accept", "application/x-ndjson")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty().take(500)
                throw ProviderHttpException(response.code, body)
            }
            val source = response.body?.source()
                ?: throw IOException("Ollama вернул пустой ответ.")
            while (true) {
                val line = source.readUtf8Line() ?: break
                val chunk = OllamaApi.parseChatLine(line) ?: continue
                chunk.error?.let { throw IOException("Ollama: ${it.take(300)}") }
                chunk.content?.let { emit(it) }
                if (chunk.done) break
            }
        }
    }
}
