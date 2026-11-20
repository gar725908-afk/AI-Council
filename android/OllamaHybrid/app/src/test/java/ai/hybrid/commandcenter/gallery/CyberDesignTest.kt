package ai.hybrid.commandcenter.gallery

import ai.hybrid.commandcenter.NexusApplication
import ai.hybrid.commandcenter.ui.BackdropScene
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberBackdrop
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.EffectsController
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudIconButton
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudProgress
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.HudTextField
import ai.hybrid.commandcenter.ui.HudTile
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.NexHoloAvatar
import ai.hybrid.commandcenter.ui.NexHoloStage
import ai.hybrid.commandcenter.ui.NexHologram
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Design sheets of the cyber parts in isolation (no-op unless -Pnexus.screenshots=true). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = NexusApplication::class, qualifiers = RobolectricDeviceQualifiers.Pixel7)
class CyberDesignTest {

    @get:Rule
    val compose = createComposeRule()

    private fun shot(name: String) = "build/gallery/$name.png"

    private val static = EffectsController(EffectsMode.STATIC, EffectsMode.STATIC) {}

    @org.junit.Before
    fun onlyWhenRecording() = assumeTrue(System.getProperty("roborazzi.test.record") == "true")

    @Test
    fun holoStageOnCity() {
        compose.setContent {
            NexusTheme(effects = static) {
                CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.CITY) {
                    Column(Modifier.fillMaxSize().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        NexHoloStage(NexState.STANDBY, EffectsMode.STATIC, size = 320.dp)
                        Text("ПРИВЕТ, АЛИ", style = CyberText.Hero)
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("d01_holo_city"))
    }

    @Test
    fun holoStates() {
        compose.setContent {
            NexusTheme(effects = static) {
                CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.PLAIN) {
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val states = NexState.entries.toList()
                        for (row in states.chunked(3)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (s in row) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        NexHologram(s, EffectsMode.STATIC, size = 200.dp, speaking = s == NexState.THINKING)
                                        Text(s.name, style = CyberText.Label, color = Cyber.TextDim)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("d02_holo_states"))
    }

    @Test
    fun hudParts() {
        compose.setContent {
            NexusTheme(effects = static) {
                CompositionLocalProvider(LocalEffects provides static) {
                    CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.DIM) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                NexHoloAvatar(NexState.STANDBY, EffectsMode.STATIC, size = 44.dp)
                                Text("NEXUS", style = CyberText.Wordmark)
                                Spacer(Modifier.weight(1f))
                                HudIconButton(NexIcons.History, {})
                                HudIconButton(NexIcons.SquarePen, {}, filled = true)
                            }
                            HudChip("Ядро online · qwen3 0.6b · на телефоне", Cyber.Green)
                            HudSectionTitle("Инструменты", trailing = "06")
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                HudTile(NexIcons.PenLine, "Написать", Modifier.weight(1f), subtitle = "Письмо, пост, текст", index = "01") {}
                                HudTile(NexIcons.Languages, "Перевести", Modifier.weight(1f), subtitle = "Любой язык", index = "02", accent = Cyber.Magenta) {}
                            }
                            HudPanel(title = "Подключение ядра", index = "SYS-01") {
                                Text("Выбери, где будет думать ИИ.", style = CyberText.Heading, color = Cyber.Text)
                                HudTextField("http://192.168.1.10:11434", {}, label = "Адрес компьютера")
                                HudProgress(0.62f)
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    HudButton("Проверить", {}, icon = NexIcons.Activity)
                                    HudButton("Автопоиск", {}, style = HudButtonStyle.Secondary, icon = NexIcons.Radar)
                                }
                            }
                            HudButton("Подключить ИИ", {}, Modifier.fillMaxWidth(), icon = NexIcons.Zap, accent = Cyber.Yellow)
                            HudButton("Удалить ключ", {}, style = HudButtonStyle.Danger)
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(shot("d03_hud_parts"))
    }
}
