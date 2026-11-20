package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.testing.FakeAndroidKeyStore
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's real paths through the new shell on the JVM: first launch asks
 * to connect a core, the setup wizard connects Ollama after a real check, and
 * a message streams back from an OpenAI-compatible server end to end.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = NexusApplication::class, qualifiers = "w411dp-h914dp-xxhdpi")
class ShellFlowTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: NexusApplication get() = ApplicationProvider.getApplicationContext()
    private val server = MockWebServer()

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun awaitText(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitTag(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun firstLaunchOpensTheSetupByItselfOnce() {
        compose.setContent { NexusApp() }
        awaitTag("core-setup")
        compose.onNodeWithTag("path-phone").assertIsDisplayed()
        assertTrue(NexusProfile.setupPrompted(app))
    }

    @Test
    fun afterTheFirstLaunchHomeShowsTheConnectButton() {
        NexusProfile.markSetupPrompted(app)
        compose.setContent { NexusApp() }
        awaitTag("connect-core")
        compose.onNodeWithTag("connect-core").assertIsDisplayed()
        compose.onNodeWithTag("core-status").assertIsDisplayed()
    }

    @Test
    fun connectOpensWizardWithThreePaths() {
        NexusProfile.markSetupPrompted(app)
        compose.setContent { NexusApp() }
        awaitTag("connect-core")
        compose.onNodeWithTag("connect-core").performScrollTo().performClick()
        awaitTag("core-setup")
        compose.onNodeWithTag("path-phone").assertIsDisplayed()
        compose.onNodeWithTag("path-pc").assertIsDisplayed()
        compose.onNodeWithTag("path-cloud").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun ollamaOnPcConnectsAfterRealCheck() {
        server.enqueue(
            MockResponse().setBody("""{"models":[{"name":"qwen3:8b"},{"name":"llama3.2:3b"}]}""")
                .setHeader("Content-Type", "application/json")
        )
        compose.setContent { NexusApp(startSetup = true) }
        awaitTag("path-pc")
        compose.onNodeWithTag("path-pc").performClick()
        awaitTag("ollama-address")
        // the owner types a bare host:port — the wizard adds the scheme
        compose.onNodeWithTag("ollama-address").performTextInput("127.0.0.1:" + server.port)
        compose.onNodeWithTag("ollama-check").performClick()
        // the instructions mention qwen3:8b too — wait for the real result: the connect button
        awaitTag("ollama-connect")
        awaitText("llama3.2:3b")
        compose.onNodeWithTag("ollama-connect").performScrollTo().performClick()
        awaitTag("setup-done")
        val saved = ProviderSettingsStore(app).load()
        assertEquals("OLLAMA", saved.providerKind)
        assertEquals("qwen3:8b", saved.model)
        assertEquals("http://127.0.0.1:" + server.port, saved.endpoint)
        assertTrue(server.takeRequest().path!!.endsWith("/api/tags"))
    }

    @Test
    fun messageStreamsBackFromCompatibleServer() {
        ProviderSettingsStore(app).save(
            ProviderSettings(endpoint = server.url("/v1").toString().trimEnd('/'), model = "nexus-test", providerKind = "OPENAI_COMPATIBLE")
        )
        val sse = buildString {
            append("data: {\"choices\":[{\"delta\":{\"content\":\"Связь \"}}]}\n\n")
            append("data: {\"choices\":[{\"delta\":{\"content\":\"установлена.\"}}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setBody(sse).setHeader("Content-Type", "text/event-stream"))
        compose.setContent { NexusApp() }
        awaitTag("composer")
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("connect-core").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("composer").performTextInput("Проверка связи")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        awaitText("Связь установлена.", timeout = 40_000)
        val request = server.takeRequest()
        assertTrue(request.path!!.endsWith("/v1/chat/completions"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("Проверка связи"))
        assertTrue(body.contains("\"stream\":true"))
    }

    @Test
    fun modulesTabOpensTheBrowser() {
        NexusProfile.markSetupPrompted(app)
        compose.setContent { NexusApp() }
        awaitTag("tab-modules")
        compose.onNodeWithTag("tab-modules").performClick()
        awaitTag("module-browser")
        compose.onNodeWithTag("module-browser").performClick()
        awaitText("Адрес или поиск")
    }

    @Test
    fun finishingSetupFromSettingsReturnsToChat() {
        NexusProfile.markSetupPrompted(app)
        server.enqueue(
            MockResponse().setBody("""{"models":[{"name":"qwen3:8b"}]}""").setHeader("Content-Type", "application/json")
        )
        compose.setContent { NexusApp(startTab = AppTab.SETTINGS) }
        awaitTag("settings-change-core")
        compose.onNodeWithTag("settings-change-core").performClick()
        awaitTag("path-pc")
        compose.onNodeWithTag("path-pc").performClick()
        awaitTag("ollama-address")
        compose.onNodeWithTag("ollama-address").performTextInput("127.0.0.1:" + server.port)
        compose.onNodeWithTag("ollama-check").performClick()
        awaitTag("ollama-connect")
        compose.onNodeWithTag("ollama-connect").performScrollTo().performClick()
        awaitTag("setup-finish")
        compose.onNodeWithTag("setup-finish").performClick()
        awaitTag("composer")
    }

    @Test
    fun settingsShowCoreAndProfile() {
        NexusProfile.markSetupPrompted(app)
        compose.setContent { NexusApp(startTab = AppTab.SETTINGS) }
        awaitTag("settings-core")
        awaitText("КАК К ТЕБЕ ОБРАЩАТЬСЯ")
    }

    @Test
    fun phoneAnswersExactMathAndAlarmsWithoutAnyCore() {
        NexusProfile.markSetupPrompted(app)
        compose.setContent { NexusApp() }
        awaitTag("composer")
        compose.onNodeWithTag("composer").performTextInput("сколько будет 0,1 + 0,2")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        awaitText("= 0,3")
        compose.onNodeWithTag("composer").performTextInput("поставь будильник на 7:30")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        awaitText("Передано Android: будильник на 07:30")
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedActivity
        assertTrue(started.action == android.provider.AlarmClock.ACTION_SET_ALARM)
        // no model was configured, and the setup did not pop up
        assertTrue(compose.onAllNodesWithTag("core-setup").fetchSemanticsNodes().isEmpty())
    }
}
