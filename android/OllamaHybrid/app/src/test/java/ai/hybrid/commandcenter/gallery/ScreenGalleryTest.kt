package ai.hybrid.commandcenter.gallery

import ai.hybrid.commandcenter.AppTab
import ai.hybrid.commandcenter.ModulePage
import ai.hybrid.commandcenter.NexusApp
import ai.hybrid.commandcenter.NexusApplication
import ai.hybrid.commandcenter.SetupPath
import ai.hybrid.commandcenter.CoreSetupScreen
import ai.hybrid.commandcenter.rememberChatController
import ai.hybrid.commandcenter.nexus.AgentRunResult
import ai.hybrid.commandcenter.nexus.AgentRunStatus
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.CouncilAgentEvent
import ai.hybrid.commandcenter.nexus.CouncilAgentPhase
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.testing.FakeAndroidKeyStore
import ai.hybrid.commandcenter.ui.BackdropScene
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.CouncilConstellation
import ai.hybrid.commandcenter.ui.CyberBackdrop
import ai.hybrid.commandcenter.ui.EffectsController
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.EffectsSettings
import ai.hybrid.commandcenter.ui.NexusTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Before
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Visual gallery of the real app rendered on the JVM at phone size.
 * Records PNGs only with -Pnexus.screenshots=true; otherwise each capture is a
 * no-op, so this never gates CI. Not a substitute for a real device — the
 * emulator workflow captures that.
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
    fun setUp() {
        // Visual review only: runs with -Pnexus.screenshots=true, never gates CI
        // (the flows themselves are covered by ShellFlowTest).
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        FakeAndroidKeyStore.install()
        // the wizard auto-opens only on a real first launch; scenes open it explicitly
        app.getSharedPreferences("nexus_profile", 0).edit().putBoolean("setup_prompted", true).commit()
        // Static pose: exact, comparable frames (the hologram turned three-quarters).
        EffectsSettings.save(app, EffectsMode.STATIC)
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun cloudCore() {
        ProviderSettingsStore(app).save(
            ProviderSettings(endpoint = "https://openrouter.ai/api/v1", model = "deepseek/deepseek-chat-v3:free", providerKind = "OPENROUTER")
        )
        AndroidCredentialVault(app).put(AndroidCredentialVault.providerKeyId("OPENROUTER"), "sk-or-test")
        app.getSharedPreferences("nexus_profile", 0).edit().putString("name", "Али").commit()
    }

    @Test
    fun homeFirstLaunch() {
        compose.setContent { NexusApp() }
        awaitTag("connect-core")
        compose.onRoot().captureRoboImage(shot("01_home_first_launch"))
    }

    @Test
    fun homeReady() {
        cloudCore()
        compose.setContent { NexusApp() }
        awaitText("Письмо, пост")
        compose.onRoot().captureRoboImage(shot("02_home_ready"))
    }

    @Test
    fun homeReadyBright() {
        cloudCore()
        EffectsSettings.save(app, EffectsMode.EXPERIMENTAL)
        compose.setContent { NexusApp() }
        awaitText("Письмо, пост")
        compose.onRoot().captureRoboImage(shot("02b_home_bright"))
    }

    @Test
    fun conversation() {
        cloudCore()
        val journal = app.missionJournal
        val chat = journal.createConversation()
        journal.appendChatMessage(chat, "user", "Сравни два варианта навеса для двора: металл или дерево?")
        journal.appendChatMessage(
            chat, "assistant",
            """
            **Коротко:** для двора в Ингушетии чаще выигрывает **металлический каркас** с поликарбонатом.

            - металл: служит 20+ лет, не боится влаги и жуков;
            - дерево: теплее на вид, но нужна пропитка каждые 2–3 года;
            - по цене каркасы близки, крыша решает больше.

            > Совет: если хочется уюта — металлический каркас и деревянная обшивка.
            """.trimIndent()
        )
        journal.appendChatMessage(chat, "user", "А сколько материала нужно на 4×6 метров?")
        compose.setContent { NexusApp() }
        awaitText("сколько материала")
        compose.onRoot().captureRoboImage(shot("03_conversation"))
    }

    @Test
    fun conversationError() {
        val server = MockWebServer()
        server.start()
        try {
            ProviderSettingsStore(app).save(
                ProviderSettings(endpoint = server.url("/v1").toString().trimEnd('/'), model = "nexus-test", providerKind = "OPENAI_COMPATIBLE")
            )
            server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":\"invalid api key\"}"))
            compose.setContent { NexusApp() }
            awaitTag("composer")
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("connect-core").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("composer").performTextInput("Привет, ты здесь?")
            awaitTag("send")
            compose.onNodeWithTag("send").performClick()
            awaitTag("error-panel")
            compose.onRoot().captureRoboImage(shot("04_conversation_error"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun history() {
        cloudCore()
        val journal = app.missionJournal
        listOf("Навес у дома", "Сверка Главснаб", "Письмо поставщику").forEach { title ->
            val id = journal.createConversation(title)
            journal.appendChatMessage(id, "user", title)
        }
        compose.setContent { NexusApp() }
        awaitTag("chat-list")
        // a click can land while the first frames settle — retry like a person would
        repeat(3) {
            compose.onNode(hasContentDescription("История чатов")).performClick()
            val opened = runCatching {
                compose.waitUntil(8_000) { compose.onAllNodesWithText("Навес у дома", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }.isSuccess
            if (opened) return@repeat
        }
        awaitText("Навес у дома")
        compose.onRoot().captureRoboImage(shot("05_history"))
    }

    @Test
    fun voice() {
        cloudCore()
        compose.setContent { NexusApp(startVoice = true) }
        awaitTag("voice-overlay")
        compose.onRoot().captureRoboImage(shot("06_voice"))
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun setupChoose() {
        compose.setContent { NexusApp(startSetup = true) }
        awaitTag("path-cloud")
        compose.onRoot().captureRoboImage(shot("07_setup_choose"))
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun setupPhone() {
        compose.setContent { NexusTheme { CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.PLAIN) {
            CoreSetupScreen(rememberChatController(), onClose = {}, startPath = SetupPath.PHONE)
        } } }
        awaitText("Скачать")
        compose.onRoot().captureRoboImage(shot("08_setup_phone"))
    }

    @Test
    @Config(qualifiers = "+h1300dp")
    fun setupPc() {
        compose.setContent { NexusTheme { CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.PLAIN) {
            CoreSetupScreen(rememberChatController(), onClose = {}, startPath = SetupPath.PC)
        } } }
        awaitTag("ollama-address")
        compose.onRoot().captureRoboImage(shot("09_setup_pc"))
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun setupCloud() {
        compose.setContent { NexusTheme { CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.PLAIN) {
            CoreSetupScreen(rememberChatController(), onClose = {}, startPath = SetupPath.CLOUD)
        } } }
        awaitTag("cloud-key")
        compose.onRoot().captureRoboImage(shot("10_setup_cloud"))
    }

    @Test
    @Config(qualifiers = "+h1500dp")
    fun councilTab() {
        cloudCore()
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
        compose.setContent { NexusApp(startTab = AppTab.COUNCIL) }
        awaitText("Созвездие совета")
        compose.onRoot().captureRoboImage(shot("11_council_tab"))
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun councilLive() {
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
                CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.DIM) {
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
        compose.onRoot().captureRoboImage(shot("12_council_live"))
    }

    @Test
    @Config(qualifiers = "+h1100dp")
    fun modulesTab() {
        compose.setContent { NexusApp(startTab = AppTab.MODULES) }
        awaitTag("module-system")
        compose.onRoot().captureRoboImage(shot("13_modules_tab"))
    }

    @Test
    @Config(qualifiers = "+h1700dp")
    fun settingsTab() {
        cloudCore()
        compose.setContent { NexusApp(startTab = AppTab.SETTINGS) }
        awaitText("Модели на телефоне")
        compose.onRoot().captureRoboImage(shot("14_settings_tab"))
    }

    @Test
    fun browserModule() {
        compose.setContent { NexusApp(startModule = ModulePage.BROWSER) }
        awaitText("Адрес или поиск")
        compose.onRoot().captureRoboImage(shot("15_module_browser"))
    }

    @Test
    @Config(qualifiers = "+h2000dp")
    fun statusModule() {
        cloudCore()
        compose.setContent { NexusApp(startModule = ModulePage.STATUS) }
        awaitText("Недавнее")
        compose.onRoot().captureRoboImage(shot("16_module_status"))
    }

    @Test
    @Config(qualifiers = "+h1400dp")
    fun modelsModule() {
        compose.setContent { NexusApp(startModule = ModulePage.MODELS) }
        awaitText("Gemma")
        compose.onRoot().captureRoboImage(shot("17_module_models"))
    }
}
