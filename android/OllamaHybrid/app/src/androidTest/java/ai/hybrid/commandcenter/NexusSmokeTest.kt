package ai.hybrid.commandcenter

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * NEXUS on a real Android system (an emulator in CI): first launch, the core
 * setup against a real Ollama server on the host (10.0.2.2), a real answer
 * in the chat, the council, every tab and the voice overlay. Screenshots of
 * each step land in the app's files/smoke folder for the workflow to collect.
 *
 * Arguments: ollamaUrl (default http://10.0.2.2:11434), ollamaModel.
 */
@RunWith(AndroidJUnit4::class)
class NexusSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val args = InstrumentationRegistry.getArguments()
    private val ollamaUrl = args.getString("ollamaUrl") ?: "http://10.0.2.2:11434"
    private val ollamaModel = args.getString("ollamaModel") ?: "qwen2.5:0.5b"

    private fun shot(name: String, idle: Boolean = true) {
        if (idle) compose.waitForIdle()
        // the emulator renders in software: give the display a moment to catch up
        Thread.sleep(1_500)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val dir = File(instrumentation.targetContext.filesDir, "smoke").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Log.i("NexusSmoke", "screenshot $name")
    }

    private fun awaitTag(tag: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitText(text: String, timeout: Long = 30_000) {
        compose.waitUntil(timeout) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun count(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun ownerJourney() {
        // 1. First launch: the core setup opens by itself.
        awaitTag("core-setup")
        shot("01_first_launch_setup")

        // 2. Ollama on the "PC" (the CI host), with a real check of /api/tags.
        compose.onNodeWithTag("path-pc").performClick()
        awaitTag("ollama-address")
        compose.onNodeWithTag("ollama-address").performTextInput(ollamaUrl.removePrefix("http://"))
        compose.onNodeWithTag("ollama-check").performClick()
        awaitTag("ollama-connect", timeout = 60_000)
        awaitText(ollamaModel)
        shot("02_setup_pc_models")
        compose.onNodeWithTag("ollama-connect").performScrollTo().performClick()
        awaitTag("setup-done")
        shot("03_setup_done")
        compose.onNodeWithTag("setup-finish").performClick()

        // 3. Home with a connected core.
        awaitTag("composer")
        shot("04_home_ready")

        // 4. A real question, a real streamed answer.
        compose.onNodeWithTag("composer").performTextInput("Привет! Ответь одним коротким предложением: кто ты?")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(300_000) {
            compose.onAllNodes(hasTestTag("nex-message")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("error-panel")).fetchSemanticsNodes().isNotEmpty()
        }
        shot("05_chat_answer")
        assertTrue("NEX must answer through Ollama, got an error panel instead", count("error-panel") == 0)

        // 5. The council (best effort: a 0.5B model may not follow every format).
        compose.onNodeWithTag("mode-council").performClick()
        compose.onNodeWithTag("composer").performTextInput("Составь план из трёх шагов: как выучить 100 английских слов за неделю.")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        Thread.sleep(4_000)
        shot("06_council_running")
        runCatching {
            compose.waitUntil(540_000) {
                count("nex-streaming") == 0 && (count("nex-message") >= 2 || count("error-panel") > 0)
            }
        }
        shot("07_council_result")
        compose.onNodeWithTag("mode-nex").performClick()

        // 6. Every tab.
        compose.onNodeWithTag("tab-council").performClick()
        awaitTag("council-ask")
        shot("08_tab_council")
        compose.onNodeWithTag("tab-modules").performClick()
        awaitTag("module-browser")
        shot("09_tab_modules")
        compose.onNodeWithTag("module-browser").performClick()
        awaitText("Адрес или поиск")
        shot("10_module_browser")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("tab-settings").performClick()
        awaitTag("settings-core")
        shot("11_tab_settings")
        compose.onNodeWithTag("tab-chat").performClick()
        awaitTag("composer")

        // 7. Voice overlay (the emulator may have no speech recogniser — the overlay says so honestly).
        compose.onNodeWithTag("voice-core").performClick()
        awaitTag("voice-overlay")
        // the live microphone level keeps Compose busy on purpose — no idle wait here
        shot("12_voice", idle = false)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("composer")
        shot("13_back_to_chat")
    }
}
