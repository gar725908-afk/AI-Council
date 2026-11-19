package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CouncilMissionRunnerTest {

    private fun agent(id: String = "research", name: String = "Исследователь") =
        CouncilAgent(id, name, CouncilAgentRole.RESEARCHER, "instructions")

    private fun completed(output: String = "вывод агента") =
        AgentRunResult(agent(), AgentRunStatus.COMPLETED, output)

    private fun failed(error: String = "таймаут") =
        AgentRunResult(agent("critic", "Критик"), AgentRunStatus.FAILED, "", error)

    @Test
    fun `successful synthesis completes the mission with the synthesis as answer`() {
        val decision = CouncilMissionRunner.decide(
            CouncilRunResult(
                agentResults = listOf(completed(), failed()),
                synthesis = "итоговый вывод",
                completed = true
            )
        )
        assertEquals(MissionStatus.COMPLETED, decision.finalStatus)
        assertEquals("итоговый вывод", decision.answer)
        assertTrue(decision.statusLine.contains("1 агентов успешно"))
    }

    @Test
    fun `needsUserInput maps to awaiting input and keeps the explanation`() {
        val decision = CouncilMissionRunner.decide(
            CouncilRunResult(
                agentResults = listOf(completed()),
                synthesis = null,
                completed = false,
                error = "Уточните бюджет",
                needsUserInput = true
            )
        )
        assertEquals(MissionStatus.AWAITING_INPUT, decision.finalStatus)
        assertEquals("Уточните бюджет", decision.answer)
    }

    @Test
    fun `paused run maps to interrupted`() {
        val decision = CouncilMissionRunner.decide(
            CouncilRunResult(
                agentResults = emptyList(),
                synthesis = null,
                completed = false,
                paused = true
            )
        )
        assertEquals(MissionStatus.INTERRUPTED, decision.finalStatus)
        assertTrue(decision.answer.isNotBlank())
    }

    @Test
    fun `blank synthesis fails the mission and answers with agents own outputs`() {
        val decision = CouncilMissionRunner.decide(
            CouncilRunResult(
                agentResults = listOf(completed("нашёл три варианта"), failed("нет ключа")),
                synthesis = "",
                completed = false,
                error = "Empty synthesis"
            )
        )
        assertEquals(MissionStatus.FAILED, decision.finalStatus)
        assertTrue(decision.answer.contains("Исследователь"))
        assertTrue(decision.answer.contains("нашёл три варианта"))
        assertTrue(decision.answer.contains("нет ключа"))
        assertEquals("Empty synthesis", decision.statusLine)
    }

    @Test
    fun `no agents at all yields empty fallback answer`() {
        assertEquals(
            "",
            CouncilMissionRunner.fallbackAnswer(
                CouncilRunResult(emptyList(), null, completed = false)
            )
        )
    }

    @Test
    fun `status line reports error text verbatim when present`() {
        val decision = CouncilMissionRunner.decide(
            CouncilRunResult(
                agentResults = listOf(failed()),
                synthesis = null,
                completed = false,
                error = "Ни один агент не вернул содержательный результат."
            )
        )
        assertEquals("Ни один агент не вернул содержательный результат.", decision.statusLine)
        assertEquals(MissionStatus.FAILED, decision.finalStatus)
    }
}
