package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ProviderMessage(val role: String, val content: String)

data class CompletionRequest(
    val endpoint: String,
    val model: String,
    val apiKey: String,
    val messages: List<ProviderMessage>,
    val temperature: Double = 0.2
)

interface ModelGateway {
    fun stream(request: CompletionRequest): Flow<String>
}

class ProviderHttpException(
    val statusCode: Int,
    message: String
) : IOException("Provider HTTP $statusCode: $message")

/**
 * OpenAI Chat Completions-compatible streaming transport.
 * HTTP is restricted to loopback/private LAN hosts for local inference endpoints.
 * Public endpoints must use HTTPS.
 */
class OpenAiCompatibleGateway(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(200, TimeUnit.SECONDS)
        .build()
) : ModelGateway {

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        require(request.model.isNotBlank()) { "Не указана модель." }
        val url = buildUrl(request.endpoint)
        val messages = JSONArray().apply {
            request.messages.forEach { message ->
                put(JSONObject().put("role", message.role).put("content", message.content))
            }
        }
        val payload = JSONObject()
            .put("model", request.model)
            .put("messages", messages)
            .put("temperature", request.temperature)
            .put("stream", true)

        val httpRequest = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        val call = client.newCall(httpRequest)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                close(error)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { networkResponse ->
                    try {
                        if (!networkResponse.isSuccessful) {
                            val details = networkResponse.body?.string()?.take(2_000)
                                ?: networkResponse.message
                            close(ProviderHttpException(networkResponse.code, details))
                            return
                        }

                        val body = networkResponse.body
                            ?: throw IOException("Провайдер вернул пустое тело ответа.")
                        val source = body.source()
                        while (!source.exhausted() && !channel.isClosedForSend) {
                            val line = source.readUtf8Line() ?: continue
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            if (data.isEmpty()) continue

                            val delta = JSONObject(data)
                                .optJSONArray("choices")
                                ?.optJSONObject(0)
                                ?.optJSONObject("delta")
                                ?.optString("content")
                            if (!delta.isNullOrEmpty()) {
                                trySend(delta)
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

    private fun buildUrl(endpoint: String): String {
        val trimmed = endpoint.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Не указан адрес API." }
        val candidate = if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            "$trimmed/chat/completions"
        }
        val parsed = candidate.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Некорректный URL провайдера.")
        if (parsed.scheme == "http") {
            require(isPrivateHost(parsed.host)) {
                "Незащищённый HTTP разрешён только для localhost и частной локальной сети. Для внешнего сервера используйте HTTPS."
            }
        }
        require(parsed.scheme == "https" || parsed.scheme == "http") {
            "Поддерживаются только HTTP и HTTPS endpoint."
        }
        return parsed.toString()
    }

    private fun isPrivateHost(host: String): Boolean {
        val normalized = host.lowercase()
        if (normalized == "localhost" || normalized.endsWith(".localhost") || normalized.endsWith(".local")) {
            return true
        }
        if (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd") ||
            normalized.startsWith("fe80:")) return true

        val octets = normalized.split(".").mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 127 ||
            (a == 192 && b == 168) ||
            (a == 172 && b in 16..31) ||
            (a == 169 && b == 254)
    }
}

enum class CouncilAgentRole {
    PLANNER, RESEARCHER, ANALYST, CRITIC, CREATIVE, TECHNICAL, RISK_REVIEWER, SYNTHESIZER
}

data class CouncilAgent(
    val id: String,
    val name: String,
    val role: CouncilAgentRole,
    val instructions: String
)

enum class AgentRunStatus { COMPLETED, FAILED }

data class AgentRunResult(
    val agent: CouncilAgent,
    val status: AgentRunStatus,
    val output: String,
    val error: String? = null
)

data class CouncilRunResult(
    val agentResults: List<AgentRunResult>,
    val synthesis: String?,
    val completed: Boolean,
    val error: String? = null
)

/**
 * Bounded, cancellable multi-agent execution.
 * Mission state remains owned by the application layer; agents cannot invoke tools directly.
 */
class CouncilOrchestrator(
    private val gateway: ModelGateway,
    maxParallelAgents: Int = 3,
    private val agentTimeoutMillis: Long = 120_000,
    private val synthesisTimeoutMillis: Long = 120_000
) {
    private val parallelism = maxParallelAgents.coerceIn(1, 8)
    private val semaphore = Semaphore(parallelism)

    suspend fun run(
        goal: String,
        endpoint: String,
        model: String,
        apiKey: String,
        agents: List<CouncilAgent> = defaultAgents()
    ): CouncilRunResult {
        require(goal.isNotBlank()) { "Задача не может быть пустой." }
        require(agents.isNotEmpty()) { "В Council должен быть хотя бы один исполнитель." }
        require(agents.size <= 8) { "В этой версии поддерживается не более восьми агентов." }

        val results = coroutineScope {
            agents.map { agent ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val output = withTimeout(agentTimeoutMillis) {
                                val request = CompletionRequest(
                                    endpoint = endpoint,
                                    model = model,
                                    apiKey = apiKey,
                                    messages = listOf(
                                        ProviderMessage(
                                            "system",
                                            "Ты участник NEXUS COUNCIL. Роль: ${agent.name} (${agent.role}). " +
                                                "${agent.instructions} Работай строго в рамках поставленной задачи. " +
                                                "Не утверждай, что выполнял действия или проверял источники, если этого не было."
                                        ),
                                        ProviderMessage("user", goal)
                                    )
                                )
                                val chunks = mutableListOf<String>()
                                gateway.stream(request).collect { chunk -> chunks += chunk }
                                chunks.joinToString("")
                            }
                            AgentRunResult(agent, AgentRunStatus.COMPLETED, output)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            AgentRunResult(
                                agent = agent,
                                status = AgentRunStatus.FAILED,
                                output = "",
                                error = error.message ?: error::class.java.simpleName
                            )
                        }
                    }
                }
            }.awaitAll()
        }

        val successful = results.filter { it.status == AgentRunStatus.COMPLETED && it.output.isNotBlank() }
        if (successful.isEmpty()) {
            return CouncilRunResult(
                agentResults = results,
                synthesis = null,
                completed = false,
                error = "Ни один агент не вернул содержательный результат. Проверьте endpoint, модель и API-ключ."
            )
        }

        return try {
            val synthesisInput = buildString {
                appendLine("ЦЕЛЬ МИССИИ:")
                appendLine(goal)
                appendLine()
                appendLine("РЕЗУЛЬТАТЫ АГЕНТОВ:")
                successful.forEach { result ->
                    appendLine("### ${result.agent.name} / ${result.agent.role}")
                    appendLine(result.output)
                    appendLine()
                }
                val failures = results.filter { it.status == AgentRunStatus.FAILED }
                if (failures.isNotEmpty()) {
                    appendLine("АГЕНТЫ С ОШИБКАМИ:")
                    failures.forEach { appendLine("- ${it.agent.name}: ${it.error}") }
                }
            }
            val synthesis = withTimeout(synthesisTimeoutMillis) {
                val chunks = mutableListOf<String>()
                gateway.stream(
                    CompletionRequest(
                        endpoint = endpoint,
                        model = model,
                        apiKey = apiKey,
                        messages = listOf(
                            ProviderMessage(
                                "system",
                                "Ты Manager и Synthesizer NEXUS COUNCIL. Сопоставь результаты, " +
                                    "отдели факты от предположений, явно укажи противоречия и пробелы. " +
                                    "Не выдумывай доказательства и не скрывай ошибки исполнителей. " +
                                    "Сформируй единый практический ответ."
                            ),
                            ProviderMessage("user", synthesisInput)
                        ),
                        temperature = 0.1
                    )
                ).collect { chunk -> chunks += chunk }
                chunks.joinToString("")
            }
            CouncilRunResult(
                agentResults = results,
                synthesis = synthesis,
                completed = synthesis.isNotBlank(),
                error = if (results.any { it.status == AgentRunStatus.FAILED }) {
                    "Часть агентов завершилась с ошибкой; итог сформирован по доступным результатам."
                } else null
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            CouncilRunResult(
                agentResults = results,
                synthesis = null,
                completed = false,
                error = "Синтез не выполнен: ${error.message ?: error::class.java.simpleName}"
            )
        }
    }

    companion object {
        fun defaultAgents(): List<CouncilAgent> = listOf(
            CouncilAgent("research", "Исследователь", CouncilAgentRole.RESEARCHER,
                "Ищи ключевые направления решения и перечисляй, какие сведения требуют внешней проверки."),
            CouncilAgent("analysis", "Аналитик", CouncilAgentRole.ANALYST,
                "Разложи задачу на части, проверь логику, расчёты и зависимости."),
            CouncilAgent("critic", "Критик", CouncilAgentRole.CRITIC,
                "Найди слабые места, скрытые допущения, ошибки и альтернативные объяснения."),
            CouncilAgent("technical", "Технический эксперт", CouncilAgentRole.TECHNICAL,
                "Оцени реализуемость, архитектуру, технические ограничения и этапы исполнения."),
            CouncilAgent("creative", "Генератор решений", CouncilAgentRole.CREATIVE,
                "Предложи нестандартные, но реалистичные решения и варианты улучшения."),
            CouncilAgent("risk", "Аудитор рисков", CouncilAgentRole.RISK_REVIEWER,
                "Оцени безопасность, приватность, правовые и операционные риски."),
            CouncilAgent("planner", "Планировщик", CouncilAgentRole.PLANNER,
                "Сформируй последовательность действий, критерии готовности и контрольные точки."),
            CouncilAgent("verifier", "Проверяющий", CouncilAgentRole.SYNTHESIZER,
                "Проверь согласованность ответов и явно укажи, какие утверждения не подтверждены.")
        )
    }
}
