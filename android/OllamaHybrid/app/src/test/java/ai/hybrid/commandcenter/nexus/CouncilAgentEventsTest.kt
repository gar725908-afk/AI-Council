package ai.hybrid.commandcenter.nexus

import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CouncilAgentEventsTest {

    private class DeterministicGateway : ModelGateway {
        override fun stream(request: CompletionRequest): Flow<String> = flow {
            val isSynthesis =
                request.messages.firstOrNull()?.content?.contains("Manager и Synthesizer") == true
            emit(if (isSynthesis) "Итоговый проверочный ответ." else "Результат тестового агента.")
        }
    }

    private val agents = listOf(
        CouncilAgent("a1", "Аналитик", CouncilAgentRole.ANALYST, "Проверь логику."),
        CouncilAgent("a2", "Критик", CouncilAgentRole.CRITIC, "Ищи ошибки.")
    )

    @Test
    fun `successful run emits assigned running completed per agent in order`() = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<CouncilAgentEvent>())
        CouncilOrchestrator(DeterministicGateway(), maxParallelAgents = 2).run(
            goal = "Проверить события",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "test-key",
            agents = agents,
            onEvent = { events.add(it) }
        )
        for (agent in agents) {
            val phases = events.filter { it.agentId == agent.id }.map { it.phase }
            assertEquals(
                listOf(
                    CouncilAgentPhase.ASSIGNED,
                    CouncilAgentPhase.RUNNING,
                    CouncilAgentPhase.COMPLETED
                ),
                phases
            )
        }
        assertTrue(events.all { it.atEpochMillis > 0 })
    }

    @Test
    fun `failing agent emits failed with a detail`() = runBlocking {
        val failingGateway = object : ModelGateway {
            override fun stream(request: CompletionRequest): Flow<String> = flow {
                throw IllegalStateException("simulated provider failure")
            }
        }
        val events = Collections.synchronizedList(mutableListOf<CouncilAgentEvent>())
        CouncilOrchestrator(failingGateway).run(
            goal = "Проверить события при отказе",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "test-key",
            agents = listOf(agents.first()),
            onEvent = { events.add(it) }
        )
        val failure = events.single { it.phase == CouncilAgentPhase.FAILED }
        assertEquals("a1", failure.agentId)
        assertEquals("simulated provider failure", failure.detail)
    }

    @Test
    fun `throwing listener never breaks the run`() = runBlocking {
        val result = CouncilOrchestrator(DeterministicGateway(), maxParallelAgents = 2).run(
            goal = "Слушатель кидает исключение",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "test-key",
            agents = agents,
            onEvent = { throw RuntimeException("listener bug") }
        )
        assertTrue(result.completed)
        assertEquals(2, result.agentResults.size)
    }

    @Test
    fun `no listener means no behavior change`() = runBlocking {
        val result = CouncilOrchestrator(DeterministicGateway(), maxParallelAgents = 2).run(
            goal = "Без слушателя",
            endpoint = "https://example.invalid/v1",
            model = "test-model",
            apiKey = "test-key",
            agents = agents
        )
        assertTrue(result.completed)
        assertEquals("Итоговый проверочный ответ.", result.synthesis)
    }
}
