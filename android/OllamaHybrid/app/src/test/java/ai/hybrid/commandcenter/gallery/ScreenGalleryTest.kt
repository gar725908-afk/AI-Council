package ai.hybrid.commandcenter.gallery

import ai.hybrid.commandcenter.AiStudioScreen
import ai.hybrid.commandcenter.ArchiveScreen
import ai.hybrid.commandcenter.BrowserScreen
import ai.hybrid.commandcenter.CommandDeckScreen
import ai.hybrid.commandcenter.SystemScreen
import ai.hybrid.commandcenter.SystemTab
import ai.hybrid.commandcenter.CouncilScreen
import ai.hybrid.commandcenter.DeviceCoreScreen
import ai.hybrid.commandcenter.FloatingNavBar
import ai.hybrid.commandcenter.NexusDestination
import ai.hybrid.commandcenter.ui.AuroraBackground
import ai.hybrid.commandcenter.ui.EffectsController
import ai.hybrid.commandcenter.ui.NexusTheme
import ai.hybrid.commandcenter.ui.NexusText
import ai.hybrid.commandcenter.ui.Tag
import androidx.compose.runtime.Composable
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

    /** Real theme, aurora background and the floating navigation bar, as in the app. */
    private fun frame(selected: NexusDestination, withNav: Boolean = true, screen: @Composable (Modifier) -> Unit) {
        compose.setContent {
            NexusTheme(effects = EffectsController(EffectsMode.SUBTLE, EffectsMode.SUBTLE) {}) {
                AuroraBackground(Modifier.fillMaxSize()) {
                    screen(Modifier.fillMaxSize().padding(top = 24.dp, bottom = if (withNav) 92.dp else 0.dp))
                    if (withNav) {
                        FloatingNavBar(
                            selected = selected,
                            onSelect = {},
                            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 16.dp)
                        )
                    }
                }
            }
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    @Test
    fun deckFirstScreen() {
        frame(NexusDestination.DECK) { m -> CommandDeckScreen(onNavigate = {}, autoRefresh = false, modifier = m) }
        awaitText("Спросите NEX")
        compose.onRoot().captureRoboImage(shot("00_deck_first_screen"))
    }

    @Test
    @Config(qualifiers = "+h2000dp")
    fun deckFirstRun() {
        frame(NexusDestination.DECK, withNav = false) { m -> CommandDeckScreen(onNavigate = {}, autoRefresh = false, modifier = m) }
        awaitText("Недавнее")
        compose.onRoot().captureRoboImage(shot("01_deck_first_run"))
    }

    @Test
    @Config(qualifiers = "+h2000dp")
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
        frame(NexusDestination.DECK, withNav = false) { m -> CommandDeckScreen(onNavigate = {}, autoRefresh = false, modifier = m) }
        awaitText("Недавнее")
        compose.onRoot().captureRoboImage(shot("02_deck_working"))
    }

    /** Every NEX mood side by side, in Static mode so the frame is exact. */
    @Test
    @Config(qualifiers = "+h1250dp")
    fun nexStates() {
        val segments = listOf(
            OrbitalSegment("Модель", "", SegmentStatus.OK),
            OrbitalSegment("Сеть", "", SegmentStatus.OK),
            OrbitalSegment("Устройство", "", SegmentStatus.WARN),
            OrbitalSegment("Council", "", SegmentStatus.ACTIVE)
        )
        compose.setContent {
            NexusTheme(effects = EffectsController(EffectsMode.STATIC, EffectsMode.STATIC) {}) {
                AuroraBackground(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("NEX", style = NexusText.Wordmark)
                        Text("Настроение по реальному состоянию", color = NexusColors.TextSecondary, fontSize = 14.sp)
                        NexState.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { state ->
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        CoreOrbital(
                                            segments = segments,
                                            state = state,
                                            mode = EffectsMode.STATIC,
                                            diameter = 170.dp
                                        )
                                        Tag(state.title, state.color)
                                    }
                                }
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
        frame(NexusDestination.COUNCIL, withNav = false) { m -> CouncilScreen(modifier = m) }
        awaitText("Созвездие совета")
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
            NexusTheme(effects = EffectsController(EffectsMode.STATIC, EffectsMode.STATIC) {}) {
                AuroraBackground(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        val nodes = ConstellationModel.fromLive(agents, latest)
                        CouncilConstellation(
                            nodes = nodes,
                            manager = ConstellationModel.managerPhase(nodes, running = true, missionStatus = null),
                            mode = EffectsMode.STATIC,
                            events = events
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("06_council_live"))
    }

    @Test
    fun studioChat() {
        val journal = app.missionJournal
        val chat = journal.createConversation()
        journal.appendChatMessage(chat, "user", "Сравни два тарифа облачных моделей для команды из пяти человек")
        journal.appendChatMessage(
            chat,
            "assistant",
            "Для пяти человек выгоднее второй тариф: общий лимит запросов выше, а цена за место ниже на 18%. " +
                "Если важна приватность, подключите локальную модель — тогда часть задач не уйдёт в облако."
        )
        journal.appendChatMessage(chat, "user", "А если половина задач — длинные документы?")
        ProviderSettingsStore(app).save(
            ProviderSettings(endpoint = "https://api.openai.com/v1", model = "gpt-4.1-mini", providerKind = "OPENAI")
        )
        frame(NexusDestination.STUDIO) { m -> AiStudioScreen(councilMode = false, modifier = m) }
        awaitText("длинные документы")
        compose.onRoot().captureRoboImage(shot("07_studio_chat"))
    }

    @Test
    fun browser() {
        frame(NexusDestination.BROWSER) { m -> BrowserScreen(modifier = m) }
        awaitText("Адрес или поиск")
        compose.onRoot().captureRoboImage(shot("08_browser"))
    }

    @Test
    fun archive() {
        frame(NexusDestination.ARCHIVE) { m -> ArchiveScreen(modifier = m) }
        awaitText("Архив")
        compose.onRoot().captureRoboImage(shot("09_archive"))
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun settings() {
        frame(NexusDestination.SYSTEM, withNav = false) { m -> SystemScreen(modifier = m, initialTab = SystemTab.SETTINGS.ordinal) }
        awaitText("Оформление")
        compose.onRoot().captureRoboImage(shot("10_settings"))
    }

    @Test
    fun models() {
        frame(NexusDestination.SYSTEM) { m -> SystemScreen(modifier = m, initialTab = SystemTab.MODELS.ordinal) }
        awaitText("Модели")
        compose.onRoot().captureRoboImage(shot("11_models"))
    }

    @Test
    fun repairs() {
        frame(NexusDestination.SYSTEM) { m -> SystemScreen(modifier = m, initialTab = SystemTab.REPAIRS.ordinal) }
        awaitText("Исправления")
        compose.onRoot().captureRoboImage(shot("12_repairs"))
    }

    @Test
    fun deviceCore() {
        frame(NexusDestination.SYSTEM) { m -> DeviceCoreScreen(modifier = m) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("05_device_core"))
    }
}
