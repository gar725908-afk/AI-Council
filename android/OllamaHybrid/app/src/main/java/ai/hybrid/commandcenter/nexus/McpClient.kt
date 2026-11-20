package ai.hybrid.commandcenter.nexus

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Model Context Protocol client over Streamable HTTP (JSON-RPC 2.0), the
 * open standard that lets any connector expose typed tools (handoff 6.5, 8).
 * Remote tools never run directly: they are wrapped as bridge tools and go
 * through the Gateway with approvals and audit like everything else.
 */
data class McpServer(val name: String, val url: String, val bearerToken: String? = null)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: String,
    val readOnlyHint: Boolean
)

data class McpCallResult(val text: String, val isError: Boolean)

object McpProtocol {
    const val PROTOCOL_VERSION = "2025-03-26"

    fun request(id: Int, method: String, params: JSONObject? = null): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .apply { if (params != null) put("params", params) }
        .toString()

    fun notification(method: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("method", method)
        .toString()

    fun initializeParams(): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject())
        .put("clientInfo", JSONObject().put("name", "NEXUS").put("version", "1"))

    /** Streamable HTTP may answer with JSON or an SSE stream; return the message matching [id]. */
    fun parseResponse(body: String, contentType: String?, id: Int): JSONObject? {
        val candidates: List<String> = if (contentType?.contains("text/event-stream") == true) {
            sseDataPayloads(body)
        } else {
            listOf(body)
        }
        for (candidate in candidates) {
            val json = runCatching { JSONObject(candidate) }.getOrNull() ?: continue
            if (json.optInt("id", Int.MIN_VALUE) == id) return json
        }
        return null
    }

    fun sseDataPayloads(stream: String): List<String> = buildList {
        val current = StringBuilder()
        stream.lineSequence().forEach { line ->
            when {
                line.startsWith("data:") -> current.append(line.removePrefix("data:").trim())
                line.isBlank() && current.isNotEmpty() -> { add(current.toString()); current.clear() }
            }
        }
        if (current.isNotEmpty()) add(current.toString())
    }

    fun parseTools(response: JSONObject): List<McpTool> {
        val tools = response.optJSONObject("result")?.optJSONArray("tools") ?: return emptyList()
        return buildList {
            for (index in 0 until tools.length()) {
                val tool = tools.optJSONObject(index) ?: continue
                val name = tool.optString("name").trim()
                if (name.isEmpty() || name.length > 128) continue
                add(
                    McpTool(
                        name = name,
                        description = tool.optString("description").take(500),
                        inputSchema = (tool.optJSONObject("inputSchema") ?: JSONObject()).toString(),
                        readOnlyHint = tool.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true
                    )
                )
            }
        }
    }

    fun parseCallResult(response: JSONObject): McpCallResult {
        response.optJSONObject("error")?.let { error ->
            return McpCallResult("Ошибка сервера MCP: " + error.optString("message", "unknown").take(300), true)
        }
        val result = response.optJSONObject("result") ?: return McpCallResult("Пустой результат.", true)
        val content: JSONArray = result.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                when (item.optString("type")) {
                    "text" -> appendLine(item.optString("text"))
                    else -> appendLine("[" + item.optString("type", "content") + "]")
                }
            }
        }.trim()
        return McpCallResult(text.ifBlank { "(без текстового содержимого)" }, result.optBoolean("isError", false))
    }

    fun errorMessage(response: JSONObject): String? =
        response.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
}

class McpClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun listTools(server: McpServer): Result<List<McpTool>> = withContext(Dispatchers.IO) {
        runCatching {
            val session = Session(server)
            session.initialize()
            val response = session.call(3, "tools/list", JSONObject())
            McpProtocol.errorMessage(response)?.let { throw IOException("tools/list: $it") }
            McpProtocol.parseTools(response)
        }
    }

    suspend fun callTool(server: McpServer, name: String, arguments: JSONObject): Result<McpCallResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = Session(server)
                session.initialize()
                val response = session.call(
                    4, "tools/call", JSONObject().put("name", name).put("arguments", arguments)
                )
                McpProtocol.parseCallResult(response)
            }
        }

    private inner class Session(private val server: McpServer) {
        private var sessionId: String? = null

        fun initialize() {
            val response = call(1, "initialize", McpProtocol.initializeParams())
            McpProtocol.errorMessage(response)?.let { throw IOException("initialize: $it") }
            post(McpProtocol.notification("notifications/initialized"), expectBody = false)
        }

        fun call(id: Int, method: String, params: JSONObject): JSONObject {
            val (body, contentType) = post(McpProtocol.request(id, method, params), expectBody = true)
            return McpProtocol.parseResponse(body, contentType, id)
                ?: throw IOException("Сервер MCP не вернул ответ на $method.")
        }

        private fun post(payload: String, expectBody: Boolean): Pair<String, String?> {
            val builder = Request.Builder()
                .url(server.url)
                .header("Accept", "application/json, text/event-stream")
                .post(payload.toRequestBody(json))
            sessionId?.let { builder.header("Mcp-Session-Id", it) }
            server.bearerToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            client.newCall(builder.build()).execute().use { response ->
                response.header("Mcp-Session-Id")?.let { sessionId = it }
                if (!response.isSuccessful && !(response.code == 202 && !expectBody)) {
                    throw IOException("Сервер MCP вернул HTTP ${response.code}.")
                }
                if (!expectBody) return "" to null
                val text = response.body?.string().orEmpty()
                if (text.length > MAX_RESPONSE_CHARS) throw IOException("Ответ сервера MCP слишком велик.")
                return text to response.header("Content-Type")
            }
        }
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
