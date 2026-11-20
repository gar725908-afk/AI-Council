package ai.hybrid.commandcenter.gallery

import ai.hybrid.commandcenter.AppPage
import ai.hybrid.commandcenter.NexusApp
import ai.hybrid.commandcenter.NexusApplication
import ai.hybrid.commandcenter.nexus.AgentRunResult
import ai.hybrid.commandcenter.nexus.AgentRunStatus
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
import ai.hybrid.commandcenter.ui.EffectsController
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusText
import ai.hybrid.commandcenter.ui.NexusTheme
import ai.hybrid.commandcenter.ui.OrbitalSegment
import ai.hybrid.commandcenter.ui.SegmentStatus
import ai.hybrid.commandcenter.ui.SpaceBackground
import ai.hybrid.commandcenter.ui.Tag
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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
 * Visual gallery of the real app rendered on the JVM at phone size.
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

    private fun cloudProvider() = ProviderSettingsStore(app).save(
        ProviderSettings(endpoint = "https://api.openai.com/v1", model = "gpt-4.1-mini", providerKind = "OPENAI")
    )

    @Test
    fun chatEmpty() {
        cloudProvider()
        compose.setContent { NexusApp() }
        awaitText("Куда летим")
        compose.onRoot().captureRoboImage(shot("01_chat_empty"))
    }

    @Test
    fun chatFirstLaunchNeedsModel() {
        compose.setContent { NexusApp() }
        awaitText("Куда летим")
        compose.onRoot().captureRoboImage(shot("02_chat_setup"))
    }

    @Test
    fun chatConversation() {
        cloudProvider()
        val journal = app.missionJournal
        val chat = journal.createConversation()
        journal.appendChatMessage(chat, "user", "Как быстрее всего долететь до Марса в 3055 году?")
        journal.appendChatMessage(
            chat,
            "assistant",
            """
            Короткий ответ: **через орбитальный разгон и ионный двигатель**.

            Что важно:
            - окно запуска открывается раз в 26 месяцев;
            - с ионной тягой путь занимает около **40 дней** вместо полугода;
            - топливо выгоднее брать на лунной станции.

            > Совет: выбирайте рейс с остановкой на Фобосе — там пересадка на посадочный модуль.
            """.trimIndent()
        )
        journal.appendChatMessage(chat, "user", "А сколько это стоит?")
        compose.setContent { NexusApp() }
        awaitText("сколько это стоит")
        compose.onRoot().captureRoboImage(shot("03_chat_conversation"))
    }

    @Test
    fun chatDrawer() {
        cloudProvider()
        val journal = app.missionJournal
        listOf("Маршрут до Марса", "Разбор договора поставки", "Идеи для навеса у дома").forEach { title ->
            val id = journal.createConversation()
            journal.appendChatMessage(id, "user", title)
        }
        compose.setContent { NexusApp() }
        awaitText("Идеи для навеса")
        compose.onNodeWithContentDescription("Меню").performClick()
        compose.mainClock.advanceTimeBy(600)
        awaitText("Состояние системы")
        compose.onRoot().captureRoboImage(shot("04_chat_drawer"))
    }

    @Test
    fun chatRadialMenu() {
        cloudProvider()
        compose.setContent { NexusApp() }
        awaitText("Куда летим")
        compose.onNodeWithContentDescription("Действия").performClick()
        compose.mainClock.advanceTimeBy(800)
        awaitText("Файл")
        compose.onRoot().captureRoboImage(shot("05_chat_radial"))
    }

    @Test
    fun chatVoice() {
        cloudProvider()
        compose.setContent { NexusApp() }
        awaitText("Куда летим")
        compose.onNodeWithContentDescription("Голос").performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("06_chat_voice"))
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
                SpaceBackground(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("NEX", style = NexusText.Wordmark)
                        Text("Настроение по реальному состоянию", color = NexusColors.TextSecondary, fontSize = 14.sp)
                        NexState.entries.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { state ->
                                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                        CoreOrbital(segments = segments, state = state, mode = EffectsMode.STATIC, diameter = 170.dp)
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
        compose.onRoot().captureRoboImage(shot("07_nex_states"))
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun councilPage() {
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
        compose.setContent { NexusApp(startPage = AppPage.COUNCIL) }
        awaitText("Созвездие совета")
        compose.onRoot().captureRoboImage(shot("08_council_page"))
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
                SpaceBackground(Modifier.fillMaxSize()) {
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
        compose.onRoot().captureRoboImage(shot("09_council_live"))
    }

    @Test
    fun browserPage() {
        compose.setContent { NexusApp(startPage = AppPage.BROWSER) }
        awaitText("Адрес или поиск")
        compose.onRoot().captureRoboImage(shot("10_browser"))
    }

    @Test
    fun archivePage() {
        compose.setContent { NexusApp(startPage = AppPage.ARCHIVE) }
        awaitText("Архив файлов")
        compose.onRoot().captureRoboImage(shot("11_archive"))
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun settingsPage() {
        compose.setContent { NexusApp(startPage = AppPage.SETTINGS) }
        awaitText("Оформление")
        compose.onRoot().captureRoboImage(shot("12_settings"))
    }

    @Test
    @Config(qualifiers = "+h2000dp")
    fun statusPage() {
        cloudProvider()
        compose.setContent { NexusApp(startPage = AppPage.STATUS) }
        awaitText("Недавнее")
        compose.onRoot().captureRoboImage(shot("13_status"))
    }
}
