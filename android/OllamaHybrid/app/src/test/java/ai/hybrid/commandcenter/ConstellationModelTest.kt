package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.AgentJournalRecord
import ai.hybrid.commandcenter.nexus.CouncilAgentEvent
import ai.hybrid.commandcenter.nexus.CouncilAgentPhase
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.ManagerPhase
import ai.hybrid.commandcenter.ui.NodePhase
import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ConstellationModelTest {

    private val agents = CouncilOrchestrator.defaultAgents()

    private fun event(id: String, phase: CouncilAgentPhase) =
        CouncilAgentEvent(id, id, phase, detail = null, atEpochMillis = 1)

    private fun record(agentId: String, status: String, createdAt: Long, error: String? = null) =
        AgentJournalRecord("r-$agentId-$createdAt", "m", agentId, agentId, "ROLE", status, "вывод", error, createdAt)

    @Test
    fun liveNodesWithoutEventsAreIdle() {
        val nodes = ConstellationModel.fromLive(agents, emptyMap())
        assertEquals(agents.size, nodes.size)
        assertTrue(nodes.all { it.phase == NodePhase.IDLE })
    }

    @Test
    fun liveEventsMapOneToOne() {
        val first = agents[0].id
        val second = agents[1].id
        val nodes = ConstellationModel.fromLive(
            agents,
            mapOf(first to event(first, CouncilAgentPhase.RUNNING), second to event(second, CouncilAgentPhase.FAILED))
        )
        assertEquals(NodePhase.RUNNING, nodes[0].phase)
        assertEquals(NodePhase.FAILED, nodes[1].phase)
        assertEquals(NodePhase.IDLE, nodes[2].phase)
    }

    @Test
    fun journalUsesLatestRecordPerAgentAndPrefersError() {
        val id = agents[0].id
        val nodes = ConstellationModel.fromJournal(
            agents,
            listOf(record(id, "FAILED", 1, error = "таймаут"), record(id, "COMPLETED", 2))
        )
        assertEquals(NodePhase.COMPLETED, nodes[0].phase)
        val failed = ConstellationModel.fromJournal(agents, listOf(record(id, "FAILED", 3, error = "таймаут")))
        assertEquals(NodePhase.FAILED, failed[0].phase)
        assertEquals("таймаут", failed[0].detail)
    }

    @Test
    fun managerPhases() {
        val idle = ConstellationModel.fromLive(agents, emptyMap())
        assertEquals(ManagerPhase.IDLE, ConstellationModel.managerPhase(idle, running = false, missionStatus = null))
        assertEquals(ManagerPhase.DISPATCHING, ConstellationModel.managerPhase(idle, running = true, missionStatus = null))

        val id = agents[0].id
        val running = ConstellationModel.fromLive(agents, mapOf(id to event(id, CouncilAgentPhase.RUNNING)))
        assertEquals(ManagerPhase.DISPATCHING, ConstellationModel.managerPhase(running, true, null))

        val done = ConstellationModel.fromLive(agents, mapOf(id to event(id, CouncilAgentPhase.COMPLETED)))
        assertEquals(ManagerPhase.SYNTHESIS, ConstellationModel.managerPhase(done, true, null))
        assertEquals(ManagerPhase.DONE, ConstellationModel.managerPhase(done, false, MissionStatus.COMPLETED))
        assertEquals(ManagerPhase.FAILED, ConstellationModel.managerPhase(done, false, MissionStatus.INTERRUPTED))
    }

    @Test
    fun orbitStartsAtTopAndGoesClockwise() {
        val c = Offset(100f, 100f)
        val top = ConstellationModel.orbitPosition(0, 4, c, 50f)
        val right = ConstellationModel.orbitPosition(1, 4, c, 50f)
        assertTrue(abs(top.x - 100f) < 0.01f && abs(top.y - 50f) < 0.01f)
        assertTrue(abs(right.x - 150f) < 0.01f && abs(right.y - 100f) < 0.01f)
    }
}
