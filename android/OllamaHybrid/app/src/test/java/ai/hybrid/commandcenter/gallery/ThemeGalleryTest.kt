package ai.hybrid.commandcenter.gallery

import ai.hybrid.commandcenter.AppTab
import ai.hybrid.commandcenter.NexusApp
import ai.hybrid.commandcenter.NexusApplication
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.ui.CameraRing
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.EffectsController
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.EffectsSettings
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusTheme
import ai.hybrid.commandcenter.ui.ThemeState
import ai.hybrid.commandcenter.testing.FakeAndroidKeyStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ai.hybrid.commandcenter.ui.NexusTheme as NexusThemeComposable

/** The three worlds side by side (no-op unless -Pnexus.screenshots=true). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = NexusApplication::class, qualifiers = RobolectricDeviceQualifiers.Pixel7)
class ThemeGalleryTest {
    @get:Rule
    val compose = createComposeRule()

    private val app: NexusApplication get() = ApplicationProvider.getApplicationContext()

    private fun shot(name: String) = "build/gallery/$name.png"

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("roborazzi.test.record") == "true")
        FakeAndroidKeyStore.install()
        app.getSharedPreferences("nexus_profile", 0).edit().putBoolean("setup_prompted", true).putString("name", "Али").commit()
        EffectsSettings.save(app, EffectsMode.STATIC)
        ProviderSettingsStore(app).save(
            ProviderSettings(endpoint = "https://openrouter.ai/api/v1", model = "deepseek/deepseek-chat-v3:free", providerKind = "OPENROUTER")
        )
        AndroidCredentialVault(app).put(AndroidCredentialVault.providerKeyId("OPENROUTER"), "sk-or-test")
    }

    @After
    fun reset() {
        ThemeState.theme = NexusTheme.NEON_CITY
    }

    private fun awaitText(text: String) {
        compose.waitUntil(30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun home(theme: NexusTheme, name: String) {
        ThemeState.theme = theme
        compose.setContent { NexusApp() }
        awaitText("Письмо, пост")
        compose.onRoot().captureRoboImage(shot(name))
    }

    @Test fun t01HomeNeon() = home(NexusTheme.NEON_CITY, "t01_home_neon")
    @Test fun t02HomeCosmos() = home(NexusTheme.COSMOS, "t02_home_cosmos")
    @Test fun t03HomeAnime() = home(NexusTheme.ANIME, "t03_home_anime")

    @Test
    fun t04SettingsAnime() {
        ThemeState.theme = NexusTheme.ANIME
        compose.setContent { NexusApp(startTab = AppTab.SETTINGS) }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("settings-theme", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("t04_settings_anime"))
    }

    private fun seasonedPet() {
        val pet = ai.hybrid.commandcenter.nexus.PetSnapshot(
            xp = 185, answers = 14, voiceAnswers = 3, councilMissions = 2, offlineAnswers = 5, streakDays = 4,
            lastActiveDay = ai.hybrid.commandcenter.nexus.NexPetStore.epochDayNow(), lastInteractionAt = System.currentTimeMillis(),
            recent = "1111011111",
            achievements = listOf("first_answer", "answers_10", "voice_1", "council_1", "offline", "streak_3", "comeback")
                .associateWith { System.currentTimeMillis() }
        )
        app.getSharedPreferences("nexus_pet", 0).edit().putString("pet", ai.hybrid.commandcenter.nexus.PetRules.encode(pet)).commit()
    }

    @Test
    fun t06PetHomeAndProfile() {
        seasonedPet()
        ThemeState.theme = NexusTheme.ANIME
        compose.setContent { NexusApp() }
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("pet-status", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onAllNodesWithTag("pet", useUnmergedTree = true)[0].performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pet-bubble", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("t06_pet_home"))
        compose.onAllNodesWithTag("pet", useUnmergedTree = true)[0].performTouchInput { longClick() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("pet-sheet", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("t07_pet_profile"))
    }

    @Test
    fun t05CameraRings() {
        val static = EffectsController(EffectsMode.STATIC, EffectsMode.STATIC) {}
        compose.setContent {
            NexusThemeComposable(effects = static) {
                Row(Modifier.fillMaxSize().background(Cyber.Void), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf(NexusTheme.NEON_CITY to NexState.THINKING, NexusTheme.COSMOS to NexState.STANDBY, NexusTheme.ANIME to NexState.LISTENING)
                        .forEach { (theme, state) ->
                            Box(Modifier.size(120.dp)) {
                                CameraRing(state, speaking = false, hole = Rect(150f, 150f, 210f, 210f), theme = theme)
                            }
                        }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("t05_camera_rings"))
    }
}
