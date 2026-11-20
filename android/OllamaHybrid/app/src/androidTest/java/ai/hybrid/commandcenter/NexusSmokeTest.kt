package ai.hybrid.commandcenter

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
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
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
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
    fun a_ownerJourney() {
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

    /**
     * The path most owners will pick: a model on the phone itself. Downloads
     * Qwen3 0.6B (≈409 MB) through the wizard, checks SHA-256, activates it and
     * gets an answer from llama.cpp with no server at all.
     */
    @Test
    fun b_onDeviceModelJourney() {
        awaitTag("tab-settings")
        compose.onNodeWithTag("tab-settings").performClick()
        awaitTag("settings-change-core")
        compose.onNodeWithTag("settings-change-core").performClick()
        awaitTag("path-phone")
        compose.onNodeWithTag("path-phone").performClick()
        awaitTag("download-qwen3-0.6b")
        compose.onNodeWithTag("download-qwen3-0.6b").performScrollTo().performClick()
        Thread.sleep(6_000)
        shot("14_phone_downloading", idle = false)
        compose.waitUntil(1_200_000) {
            compose.onAllNodesWithTag("setup-done", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("setup-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("15_phone_model_ready")
        assertTrue("the on-device model must download and activate", count("setup-error") == 0)
        compose.onNodeWithTag("setup-finish").performClick()
        awaitTag("composer")
        compose.onNode(hasContentDescription("Новый чат")).performClick()
        Thread.sleep(1_000)
        compose.onNodeWithTag("composer").performTextInput("Привет! Ответь одним предложением: что такое NEXUS?")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        Thread.sleep(5_000)
        shot("16_phone_thinking", idle = false)
        compose.waitUntil(600_000) {
            compose.onAllNodes(hasTestTag("nex-message")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("error-panel")).fetchSemanticsNodes().isNotEmpty()
        }
        shot("17_phone_answer")
        assertTrue("NEX must answer from the on-device model", count("error-panel") == 0)
    }

    /**
     * The recommended phone model (Gemma 4 E2B on LiteRT-LM, ≈2.4 GB). Heavy, so
     * it runs only when the workflow is started with gemma=true.
     */
    @Test
    fun c_gemmaJourney() {
        org.junit.Assume.assumeTrue(args.getString("gemma") == "true")
        awaitTag("tab-settings")
        compose.onNodeWithTag("tab-settings").performClick()
        awaitTag("settings-change-core")
        compose.onNodeWithTag("settings-change-core").performClick()
        awaitTag("path-phone")
        compose.onNodeWithTag("path-phone").performClick()
        awaitTag("download-google-gemma-4-e2b-it")
        compose.onNodeWithTag("download-google-gemma-4-e2b-it").performScrollTo().performClick()
        compose.waitUntil(1_800_000) {
            compose.onAllNodesWithTag("setup-done", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("setup-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        shot("18_gemma_ready")
        assertTrue("Gemma must download and activate", count("setup-error") == 0)
        compose.onNodeWithTag("setup-finish").performClick()
        awaitTag("composer")
        compose.onNode(hasContentDescription("Новый чат")).performClick()
        Thread.sleep(1_000)
        compose.onNodeWithTag("composer").performTextInput("Привет! Ответь одним предложением: чем ты можешь помочь?")
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(900_000) {
            compose.onAllNodes(hasTestTag("nex-message")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("error-panel")).fetchSemanticsNodes().isNotEmpty()
        }
        shot("19_gemma_answer")
        assertTrue("NEX must answer from Gemma on LiteRT-LM", count("error-panel") == 0)
    }

    private fun openPhoneSetup() {
        awaitTag("tab-settings")
        compose.onNodeWithTag("tab-settings").performClick()
        awaitTag("settings-change-core")
        compose.onNodeWithTag("settings-change-core").performClick()
        awaitTag("path-phone")
        compose.onNodeWithTag("path-phone").performClick()
    }

    private fun awaitSetupResult(timeout: Long) {
        compose.waitUntil(timeout) {
            compose.onAllNodesWithTag("setup-done", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodesWithTag("setup-error", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun askInNewChat(question: String, shotName: String, timeout: Long = 600_000) {
        compose.onNodeWithTag("setup-finish").performClick()
        awaitTag("composer")
        compose.onNode(hasContentDescription("Новый чат")).performClick()
        Thread.sleep(1_000)
        compose.onNodeWithTag("composer").performTextInput(question)
        awaitTag("send")
        compose.onNodeWithTag("send").performClick()
        compose.waitUntil(timeout) {
            compose.onAllNodes(hasTestTag("nex-message")).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasTestTag("error-panel")).fetchSemanticsNodes().isNotEmpty()
        }
        shot(shotName)
        assertTrue("NEX must answer ($shotName)", count("error-panel") == 0)
    }

    /**
     * «Скачивать не надо»: the CI script puts two GGUF files into the phone's
     * Download folder. Qwen3.5 0.8B must be recognised as a catalog model
     * (size + SHA-256) and run in place; Gemma 3 270M is unknown to the catalog
     * and must run with the chat template stored inside the file.
     */
    @Test
    fun d_foundModelsJourney() {
        org.junit.Assume.assumeTrue(args.getString("found") == "true")
        openPhoneSetup()
        awaitTag("run-found-qwen3.5-0.8b", timeout = 180_000)
        Thread.sleep(1_000)
        shot("22_found_on_phone")
        compose.onNodeWithTag("run-found-qwen3.5-0.8b").performScrollTo().performClick()
        awaitSetupResult(900_000)
        shot("23_found_ready")
        assertTrue("the found catalog model must verify and activate", count("setup-error") == 0)
        askInNewChat("Привет! Ответь одним предложением: что ты умеешь?", "24_found_answer")

        openPhoneSetup()
        val gemma3 = "found-run-gemma-3-270m-it-Q8_0.gguf"
        awaitTag(gemma3, timeout = 180_000)
        compose.onNodeWithTag(gemma3).performScrollTo().performClick()
        awaitSetupResult(300_000)
        shot("25_unknown_ready")
        assertTrue("an unknown GGUF must activate in place", count("setup-error") == 0)
        askInNewChat("Hi! Answer in one short sentence: what is the capital of France?", "26_unknown_answer")
    }
}
