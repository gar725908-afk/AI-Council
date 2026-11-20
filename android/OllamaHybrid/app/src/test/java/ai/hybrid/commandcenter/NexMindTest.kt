package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.NexFacts
import ai.hybrid.commandcenter.ui.NexMind
import ai.hybrid.commandcenter.ui.NexState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NexMindTest {

    private val now = 10_000_000L
    private fun ready(f: NexFacts.() -> NexFacts = { this }) =
        NexFacts(nowMillis = now, localModelName = "qwen3-0.6b").f()

    @Test
    fun `nothing happening with a model means standby`() {
        assertEquals(NexState.STANDBY, NexMind.derive(ready()))
    }

    @Test
    fun `no model at all asks for setup instead of pretending`() {
        val state = NexMind.derive(NexFacts(nowMillis = now))
        assertEquals(NexState.SETUP, state)
        assertTrue(NexMind.line(state, NexFacts(nowMillis = now)).contains("Подключите модель"))
    }

    @Test
    fun `listening wins over everything`() {
        val facts = ready { copy(listening = true, runningMissionGoal = "x", lastErrorSummary = "e", lastErrorAtMillis = now) }
        assertEquals(NexState.LISTENING, NexMind.derive(facts))
    }

    @Test
    fun `a running mission shows thinking with its real goal`() {
        val facts = ready { copy(runningMissionGoal = "Сравнить три томографа") }
        val state = NexMind.derive(facts)
        assertEquals(NexState.THINKING, state)
        assertTrue(NexMind.line(state, facts).contains("Сравнить три томографа"))
    }

    @Test
    fun `a recent error is shown and points to repairs`() {
        val facts = ready { copy(lastErrorSummary = "IllegalStateException: boom", lastErrorAtMillis = now - 60_000) }
        val state = NexMind.derive(facts)
        assertEquals(NexState.ERROR, state)
        assertTrue(NexMind.line(state, facts).contains("Исправления"))
    }

    @Test
    fun `an old error does not keep NEX red forever`() {
        val facts = ready { copy(lastErrorSummary = "boom", lastErrorAtMillis = now - NexMind.RECENT_ERROR_WINDOW_MILLIS - 1) }
        assertEquals(NexState.STANDBY, NexMind.derive(facts))
    }

    @Test
    fun `a success after the error clears the error mood`() {
        val facts = ready {
            copy(
                lastErrorSummary = "boom", lastErrorAtMillis = now - 120_000,
                lastCompletedGoal = "Отчёт", lastCompletedAtMillis = now - 30_000
            )
        }
        assertEquals(NexState.SUCCESS, NexMind.derive(facts))
    }

    @Test
    fun `awaiting input asks the user`() {
        val facts = ready { copy(awaitingInputGoal = "Бюджет закупки") }
        assertEquals(NexState.ATTENTION, NexMind.derive(facts))
    }

    @Test
    fun `cloud only without network is offline, local model keeps working`() {
        val cloudOnly = NexFacts(nowMillis = now, cloudProviderName = "OpenAI", networkAvailable = false)
        assertEquals(NexState.OFFLINE, NexMind.derive(cloudOnly))

        val withLocal = ready { copy(networkAvailable = false) }
        val state = NexMind.derive(withLocal)
        assertEquals(NexState.STANDBY, state)
        assertTrue(NexMind.line(state, withLocal).contains("Сети нет"))
    }

    @Test
    fun `success fades back to standby after the window`() {
        val facts = ready { copy(lastCompletedGoal = "x", lastCompletedAtMillis = now - NexMind.RECENT_SUCCESS_WINDOW_MILLIS - 1) }
        assertEquals(NexState.STANDBY, NexMind.derive(facts))
    }

    @Test
    fun `long goals are shortened with an ellipsis, never rewritten`() {
        val goal = "Подготовить сравнение предложений поставщиков по сорока позициям медицинского оборудования"
        val line = NexMind.line(NexState.THINKING, ready { copy(runningMissionGoal = goal) })
        assertTrue(line.contains("Подготовить сравнение предложений"))
        assertTrue(line.contains("…"))
    }

    @Test
    fun `every state has a non-empty line`() {
        for (state in NexState.entries) {
            assertTrue(state.name, NexMind.line(state, ready()).length > 10)
        }
    }
}
