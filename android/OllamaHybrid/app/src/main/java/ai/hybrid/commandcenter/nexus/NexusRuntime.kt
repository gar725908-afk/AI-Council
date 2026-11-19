package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
) {
    init {
        require(model.isNotBlank() && model.length <= MAX_MODEL_ID_CHARS) {
            "Model ID must contain 1 to $MAX_MODEL_ID_CHARS characters."
        }
        require(endpoint.length <= MAX_ENDPOINT_CHARS) {
            "Provider endpoint exceeds $MAX_ENDPOINT_CHARS characters."
        }
        require(apiKey.length <= MAX_API_KEY_CHARS) {
            "Provider credential exceeds the configured size limit."
        }
        require(messages.isNotEmpty() && messages.size <= MAX_MESSAGES) {
            "A completion request must contain 1 to $MAX_MESSAGES messages."
        }
        require(messages.all { it.role in ALLOWED_ROLES && it.content.length <= MAX_MESSAGE_CHARS }) {
            "Completion messages contain an unsupported role or oversized content."
        }
        require(messages.sumOf { it.content.length.toLong() } <= MAX_TOTAL_MESSAGE_CHARS) {
            "Completion request content exceeds the total size limit."
        }
        require(temperature.isFinite() && temperature in 0.0..2.0) {
            "Temperature must be a finite value between 0 and 2."
        }
    }

    private companion object {
        const val MAX_MODEL_ID_CHARS = 200
        const val MAX_ENDPOINT_CHARS = 2_048
        const val MAX_API_KEY_CHARS = 16_384
        const val MAX_MESSAGES = 128
        const val MAX_MESSAGE_CHARS = 65_536
        const val MAX_TOTAL_MESSAGE_CHARS = 262_144L
        val ALLOWED_ROLES = setOf("system", "user", "assistant")
    }
}

interface ModelGateway {
    fun stream(request: CompletionRequest): Flow<String>
}

class ProviderHttpException(
    val statusCode: Int,
    message: String
) : IOException("Provider HTTP $statusCode: $message")

private class OutputLimitReached : RuntimeException()

/** Collects a bounded response and cancels the upstream stream as soon as it exceeds the limit. */
internal suspend fun Flow<String>.collectBounded(maxChars: Int): String {
    require(maxChars > 0)
    val output = StringBuilder()
    var truncated = false
    try {
        collect { chunk ->
            val remaining = maxChars - output.length
            if (chunk.length <= remaining) {
                output.append(chunk)
            } else {
                output.append(chunk, 0, remaining)
                truncated = true
                throw OutputLimitReached()
            }
        }
    } catch (_: OutputLimitReached) {
        // The collector exception cancels the upstream HTTP call; keep the bounded partial text.
    }
    if (truncated) {
        val marker = "\n[output truncated]"
        if (output.length + marker.length > maxChars) {
            output.setLength((maxChars - marker.length).coerceAtLeast(0))
        }
        output.append(marker)
    }
    return output.toString()
}

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

    override fun stream(request: CompletionRequest): Flow<String> {
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

        val requestBuilder = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
        if (request.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${request.apiKey}")
        }
        val httpRequest = requestBuilder
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        return SseTransport.stream(httpRequest, client) { data ->
            val content = JSONObject(data)
                .optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.opt("content")
            (content as? String)?.takeIf { it.isNotEmpty() }
        }
    }

    private fun buildUrl(endpoint: String): String {
        val base = ProviderEndpointPolicy.base(endpoint)
        val target = if (base.encodedPath.endsWith("/chat/completions")) {
            base
        } else {
            base.newBuilder().addPathSegments("chat/completions").build()
        }
        return target.toString()
    }
}

enum class CouncilAgentRole {
    PLANNER, RESEARCHER, ANALYST, CRITIC, CREATIVE, TECHNICAL, RISK_REVIEWER, VERIFIER
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
    val error: String? = null,
    val needsUserInput: Boolean = false,
    val paused: Boolean = false
)

data class CouncilModelRoute(
    val gateway: ModelGateway,
    val endpoint: String,
    val model: String,
    val apiKey: String
)

/**
 * Bounded, cancellable multi-agent execution.
 * Mission state remains owned by the application layer; agents cannot invoke tools directly.
 */
class CouncilOrchestrator(
    private val gateway: ModelGateway,
    maxParallelAgents: Int = 3,
    private val agentTimeoutMillis: Long = 120_000,
    private val synthesisTimeoutMillis: Long = 120_000,
    private val routeResolver: ((CouncilAgent) -> CouncilModelRoute?)? = null
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
        require(goal.length <= MAX_GOAL_CHARS) { "Задача превышает лимит $MAX_GOAL_CHARS символов." }
        require(agents.isNotEmpty()) { "В Council должен быть хотя бы один исполнитель." }
        require(agents.size <= 8) { "В этой версии поддерживается не более восьми агентов." }
        require(agents.map { it.id }.distinct().size == agents.size) {
            "ID участников Council должны быть уникальными."
        }

        val results = coroutineScope {
            agents.map { agent ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            val route = routeResolver?.invoke(agent)
                            val selectedGateway = route?.gateway ?: gateway
                            val selectedEndpoint = route?.endpoint ?: endpoint
                            val selectedModel = route?.model ?: model
                            val selectedApiKey = route?.apiKey ?: apiKey
                            val output = withTimeoutOrNull(agentTimeoutMillis) {
                                val request = CompletionRequest(
                                    endpoint = selectedEndpoint,
                                    model = selectedModel,
                                    apiKey = selectedApiKey,
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
                                selectedGateway.stream(request).collectBounded(MAX_AGENT_OUTPUT_CHARS)
                            }
                            if (output == null) {
                                AgentRunResult(
                                    agent = agent,
                                    status = AgentRunStatus.FAILED,
                                    output = "",
                                    error = "Агент превысил лимит времени ${agentTimeoutMillis} мс."
                                )
                            } else {
                                AgentRunResult(agent, AgentRunStatus.COMPLETED, output)
                            }
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
            }.let { built ->
                if (built.length <= MAX_SYNTHESIS_INPUT_CHARS) built
                else built.take(MAX_SYNTHESIS_INPUT_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
            }
            val synthesis = withTimeoutOrNull(synthesisTimeoutMillis) {
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
                ).collectBounded(MAX_SYNTHESIS_OUTPUT_CHARS)
            }
            if (synthesis == null) {
                CouncilRunResult(
                    agentResults = results,
                    synthesis = null,
                    completed = false,
                    error = "Синтез превысил лимит времени ${synthesisTimeoutMillis} мс."
                )
            } else {
                CouncilRunResult(
                    agentResults = results,
                    synthesis = synthesis,
                    completed = synthesis.isNotBlank(),
                    error = if (results.any { it.status == AgentRunStatus.FAILED }) {
                        "Часть агентов завершилась с ошибкой; итог сформирован по доступным результатам."
                    } else null
                )
            }
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
        private const val MAX_GOAL_CHARS = 32_000
        private const val MAX_AGENT_OUTPUT_CHARS = 12_000
        private const val MAX_SYNTHESIS_INPUT_CHARS = 110_000
        private const val MAX_SYNTHESIS_OUTPUT_CHARS = 16_000
        private const val TRUNCATION_MARKER = "\n[context truncated]"

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
            CouncilAgent("verifier", "Проверяющий", CouncilAgentRole.VERIFIER,
                "Проверь согласованность ответов и явно укажи, какие утверждения не подтверждены.")
        )
    }
}
