package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CouncilOrchestratorTest {

    private class DeterministicGateway : ModelGateway {
        override fun stream(request: CompletionRequest): Flow<String> = flow {
            val isSynthesis = request.messages.firstOrNull()?.content?.contains("Manager и Synthesizer") == true
            emit(if (isSynthesis) "Итоговый проверочный ответ." else "Результат тестового агента.")
        }
    }

    @Test
    fun runsAgentsAndProducesSynthesis() = runBlocking {
        val agents = listOf(
            CouncilAgent("a1", "Аналитик", CouncilAgentRole.ANALYST, "Проверь логику."),
            CouncilAgent("a2", "Критик", CouncilAgentRole.CRITIC, "Ищи ошибки.")
        )

        val result = CouncilOrchestrator(
            gateway = DeterministicGateway(),
            maxParallelAgents = 2
        ).run(
            goal = "Проверить тестовую задачу",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "test-key",
            agents = agents
        )

        assertEquals(2, result.agentResults.size)
        assertTrue(result.agentResults.all { it.status == AgentRunStatus.COMPLETED })
        assertEquals("Итоговый проверочный ответ.", result.synthesis)
        assertTrue(result.completed)
    }

    @Test
    fun reportsFailureWhenAllAgentsFail() = runBlocking {
        val failingGateway = object : ModelGateway {
            override fun stream(request: CompletionRequest): Flow<String> = flow {
                throw IllegalStateException("simulated provider failure")
            }
        }

        val result = CouncilOrchestrator(failingGateway).run(
            goal = "Проверить отказоустойчивость",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "test-key",
            agents = listOf(
                CouncilAgent("a1", "Аналитик", CouncilAgentRole.ANALYST, "Проверь.")
            )
        )

        assertEquals(1, result.agentResults.size)
        assertEquals(AgentRunStatus.FAILED, result.agentResults.single().status)
        assertNotNull(result.error)
        assertTrue(!result.completed)
    }

    @Test
    fun routesSelectedAgentToItsOwnGateway() = runBlocking {
        class TaggedGateway(private val tag: String) : ModelGateway {
            override fun stream(request: CompletionRequest): Flow<String> = flow {
                emit(tag)
            }
        }

        val defaultGateway = TaggedGateway("default")
        val routedGateway = TaggedGateway("routed")
        val agents = listOf(
            CouncilAgent("route-me", "Роль с отдельной моделью", CouncilAgentRole.ANALYST, "Анализируй."),
            CouncilAgent("default-route", "Роль по умолчанию", CouncilAgentRole.CRITIC, "Критикуй.")
        )
        val result = CouncilOrchestrator(
            gateway = defaultGateway,
            routeResolver = { agent ->
                if (agent.id == "route-me") {
                    CouncilModelRoute(
                        gateway = routedGateway,
                        endpoint = "https://example.invalid/v1",
                        model = "routed-model",
                        apiKey = ""
                    )
                } else null
            }
        ).run(
            goal = "Проверить маршрутизацию",
            endpoint = "https://example.invalid/v1",
            model = "default-model",
            apiKey = "",
            agents = agents
        )

        assertEquals("routed", result.agentResults.first { it.agent.id == "route-me" }.output)
        assertEquals("default", result.agentResults.first { it.agent.id == "default-route" }.output)
        assertEquals("default", result.synthesis)
    }

    @Test
    fun oneAgentTimeoutDoesNotCancelOtherAgentsOrSynthesis() = runBlocking {
        val timeoutGateway = object : ModelGateway {
            override fun stream(request: CompletionRequest): Flow<String> = flow {
                val system = request.messages.firstOrNull()?.content.orEmpty()
                when {
                    system.contains("Manager и Synthesizer") -> emit("Сводный итог.")
                    system.contains("медленный исполнитель") -> {
                        delay(2_000)
                        emit("Слишком поздний ответ.")
                    }
                    else -> emit("Быстрый ответ.")
                }
            }
        }
        val agents = listOf(
            CouncilAgent("slow", "Медленный", CouncilAgentRole.ANALYST, "Ты медленный исполнитель."),
            CouncilAgent("fast", "Быстрый", CouncilAgentRole.CRITIC, "Работай быстро.")
        )

        val result = CouncilOrchestrator(
            gateway = timeoutGateway,
            maxParallelAgents = 2,
            agentTimeoutMillis = 500,
            synthesisTimeoutMillis = 2_000
        ).run(
            goal = "Проверить изоляцию тайм-аутов",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "",
            agents = agents
        )

        assertEquals(AgentRunStatus.FAILED, result.agentResults.first { it.agent.id == "slow" }.status)
        assertEquals(AgentRunStatus.COMPLETED, result.agentResults.first { it.agent.id == "fast" }.status)
        assertEquals("Сводный итог.", result.synthesis)
        assertTrue(result.completed)
    }

    @Test
    fun synthesisTimeoutReturnsStructuredFailure() = runBlocking {
        val gateway = object : ModelGateway {
            override fun stream(request: CompletionRequest): Flow<String> = flow {
                val system = request.messages.firstOrNull()?.content.orEmpty()
                if (system.contains("Manager и Synthesizer")) {
                    delay(2_000)
                } else {
                    emit("Результат агента.")
                }
            }
        }

        val result = CouncilOrchestrator(
            gateway = gateway,
            agentTimeoutMillis = 1_000,
            synthesisTimeoutMillis = 500
        ).run(
            goal = "Проверить тайм-аут синтеза",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "",
            agents = listOf(
                CouncilAgent("a1", "Аналитик", CouncilAgentRole.ANALYST, "Анализируй.")
            )
        )

        assertTrue(!result.completed)
        assertTrue(result.synthesis == null)
        assertTrue(result.error?.contains("Синтез превысил лимит времени") == true)
    }

    @Test
    fun defaultCouncilHasEightDistinctRoles() {
        val agents = CouncilOrchestrator.defaultAgents()

        assertEquals(8, agents.size)
        assertEquals(8, agents.map { it.role }.toSet().size)
    }
}
