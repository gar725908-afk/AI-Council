package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
    fun defaultCouncilHasEightDistinctRoles() {
        val agents = CouncilOrchestrator.defaultAgents()

        assertEquals(8, agents.size)
        assertEquals(8, agents.map { it.role }.toSet().size)
    }
}
