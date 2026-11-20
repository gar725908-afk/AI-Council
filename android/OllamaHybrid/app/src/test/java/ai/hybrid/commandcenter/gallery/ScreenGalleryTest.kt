package ai.hybrid.commandcenter.gallery

import ai.hybrid.commandcenter.CommandDeckScreen
import ai.hybrid.commandcenter.CouncilScreen
import ai.hybrid.commandcenter.DeviceCoreScreen
import ai.hybrid.commandcenter.NexusApplication
import ai.hybrid.commandcenter.nexus.AgentRunResult
import ai.hybrid.commandcenter.nexus.AgentRunStatus
import ai.hybrid.commandcenter.nexus.BrowserHistoryStore
import ai.hybrid.commandcenter.nexus.CouncilAgentEvent
import ai.hybrid.commandcenter.nexus.CouncilAgentPhase
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.testing.FakeAndroidKeyStore
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.CoreOrbital
import ai.hybrid.commandcenter.ui.CouncilConstellation
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.OrbitalSegment
import ai.hybrid.commandcenter.ui.SegmentStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual gallery of real screens rendered on the JVM at phone size.
 * Records PNGs only with -Pnexus.screenshots=true; otherwise each capture is a
 * no-op, so this never gates CI. Not a substitute for a physical device.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = NexusApplication::class, qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ScreenGalleryTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: NexusApplication get() = ApplicationProvider.getApplicationContext()

    private fun shot(name: String) = "build/gallery/$name.png"

    @Before
    fun keystore() = FakeAndroidKeyStore.install()

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = "+h1800dp")
    fun deckFirstRun() {
        compose.setContent { CommandDeckScreen(onNavigate = {}, autoRefresh = false) }
        awaitText("COMMAND PORTAL")
        compose.onRoot().captureRoboImage(shot("01_deck_first_run"))
    }

    @Test
    @Config(qualifiers = "+h1800dp")
    fun deckWorking() {
        ProviderSettingsStore(app).save(
            ProviderSettings(endpoint = "https://api.openai.com/v1", model = "gpt-4.1-mini", providerKind = "OPENAI")
        )
        val journal = app.missionJournal
        journal.createMission("Подготовить сравнение трёх поставщиков ноутбуков", "", "").also { id ->
            journal.transition(id, MissionStatus.QUEUED)
            journal.transition(id, MissionStatus.RUNNING)
            journal.transition(id, MissionStatus.VERIFYING)
            journal.transition(id, MissionStatus.COMPLETED, result = "Таблица готова")
        }
        journal.createMission("Разобрать ошибку импорта PDF и предложить исправление", "", "").also { id ->
            journal.transition(id, MissionStatus.QUEUED)
            journal.transition(id, MissionStatus.RUNNING)
        }
        BrowserHistoryStore(app).record("https://developer.android.com/jetpack/compose", "Jetpack Compose")
        compose.setContent { CommandDeckScreen(onNavigate = {}, autoRefresh = false) }
        awaitText("LIVE SYSTEMS")
        compose.onRoot().captureRoboImage(shot("02_deck_working"))
    }

    /** Every NEX mood side by side, in Static mode so the frame is exact. */
    @Test
    @Config(qualifiers = "+h1500dp")
    fun nexStates() {
        val segments = listOf(
            OrbitalSegment("Модель", "", SegmentStatus.OK),
            OrbitalSegment("Сеть", "", SegmentStatus.OK),
            OrbitalSegment("Устройство", "", SegmentStatus.WARN),
            OrbitalSegment("Council", "", SegmentStatus.ACTIVE)
        )
        compose.setContent {
            Column(
                Modifier.fillMaxSize().background(NexusColors.Void).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("NEX — состояния", color = NexusColors.TextPrimary, fontSize = 20.sp)
                NexState.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { state ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                CoreOrbital(
                                    segments = segments,
                                    state = state,
                                    mode = EffectsMode.STATIC,
                                    diameter = 150.dp
                                )
                                Text(state.title, color = state.color, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("03_nex_states"))
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun councilCenter() {
        val journal = app.missionJournal
        val agents = CouncilOrchestrator.defaultAgents()
        val missionId = journal.createMission("Оценить риски перехода склада на новую WMS", "", "")
        journal.transition(missionId, MissionStatus.QUEUED)
        journal.transition(missionId, MissionStatus.RUNNING)
        agents.take(6).forEachIndexed { index, agent ->
            journal.saveAgentResult(
                missionId,
                if (index == 3) AgentRunResult(agent, AgentRunStatus.FAILED, "", "Провайдер не ответил за 120 с")
                else AgentRunResult(agent, AgentRunStatus.COMPLETED, "Результат роли ${agent.name}")
            )
        }
        journal.transition(missionId, MissionStatus.FAILED, error = "Синтез не выполнен: исполнитель упал")
        compose.setContent { CouncilScreen() }
        awaitText("NEURAL CONSTELLATION")
        compose.onRoot().captureRoboImage(shot("04_council_center"))
    }

    /** Live constellation mid-mission: every phase at once. */
    @Test
    @Config(qualifiers = "+h1100dp")
    fun councilLiveConstellation() {
        val agents = CouncilOrchestrator.defaultAgents()
        val phases = listOf(
            CouncilAgentPhase.COMPLETED, CouncilAgentPhase.RUNNING, CouncilAgentPhase.RUNNING,
            CouncilAgentPhase.ASSIGNED, CouncilAgentPhase.FAILED, CouncilAgentPhase.COMPLETED
        )
        var clock = 1_763_640_000_000L
        val events = agents.zip(phases).map { (agent, phase) ->
            clock += 4_000
            CouncilAgentEvent(agent.id, agent.name, phase, if (phase == CouncilAgentPhase.FAILED) "HTTP 429" else null, clock)
        }
        val latest = events.associateBy { it.agentId }
        compose.setContent {
            Column(Modifier.fillMaxSize().background(NexusColors.Void).padding(16.dp)) {
                val nodes = ConstellationModel.fromLive(agents, latest)
                CouncilConstellation(
                    nodes = nodes,
                    manager = ConstellationModel.managerPhase(nodes, running = true, missionStatus = null),
                    mode = EffectsMode.STATIC,
                    events = events
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("06_council_live"))
    }

    @Test
    fun deviceCore() {
        compose.setContent { DeviceCoreScreen() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("05_device_core"))
    }
}
