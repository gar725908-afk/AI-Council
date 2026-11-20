package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.BackdropScene
import ai.hybrid.commandcenter.ui.CyberBackdrop
import ai.hybrid.commandcenter.ui.NexusTheme
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Targets other screens may ask the shell to open. */
enum class DeckTarget { STUDIO, COUNCIL, MODELS, DEVICE, BROWSER, ARCHIVE, REPAIRS, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContent { NexusApp() }
    }
}

/**
 * NEXUS shell: the night city, the chat with the hologram, and a dock with
 * four tabs and the voice core. Tools open as pages over it and Back always
 * returns. The core setup opens by itself when nothing is connected yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NexusApp(
    startTab: AppTab = AppTab.CHAT,
    startModule: ModulePage? = null,
    startSetup: Boolean = false,
    startVoice: Boolean = false
) {
    NexusTheme {
        val chat = rememberChatController()
        var tab by rememberSaveable { mutableStateOf(startTab) }
        var module by remember { mutableStateOf(startModule) }
        var setupOpen by remember { mutableStateOf(startSetup) }
        var voiceOpen by remember { mutableStateOf(startVoice) }
        var draft by rememberSaveable { mutableStateOf("") }
        val context = LocalContext.current

        // First launch with nothing connected: open the setup by itself, once.
        LaunchedEffect(chat.settingsLoaded) {
            if (chat.settingsLoaded && chat.needsSetup && !NexusProfile.setupPrompted(context)) {
                NexusProfile.markSetupPrompted(context)
                setupOpen = true
            }
        }

        fun toChat(text: String? = null, council: Boolean = false) {
            if (text != null) draft = text.take(32_000)
            if (council) chat.councilMode = true
            module = null
            tab = AppTab.CHAT
        }

        fun openTarget(target: DeckTarget) {
            when (target) {
                DeckTarget.STUDIO -> toChat()
                DeckTarget.COUNCIL -> { module = null; tab = AppTab.COUNCIL }
                DeckTarget.BROWSER -> module = ModulePage.BROWSER
                DeckTarget.ARCHIVE -> module = ModulePage.ARCHIVE
                DeckTarget.MODELS -> module = ModulePage.MODELS
                DeckTarget.DEVICE -> module = ModulePage.DEVICE
                DeckTarget.REPAIRS -> module = ModulePage.REPAIRS
                DeckTarget.SETTINGS -> { module = null; tab = AppTab.SETTINGS }
            }
        }

        BackHandler(enabled = module == null && !setupOpen && !voiceOpen && tab != AppTab.CHAT) { tab = AppTab.CHAT }
        BackHandler(enabled = module != null && !setupOpen && !voiceOpen) { module = null }

        val scene = when {
            voiceOpen -> BackdropScene.CITY
            setupOpen || module != null -> BackdropScene.PLAIN
            tab == AppTab.CHAT -> chatScene(chat)
            else -> BackdropScene.DIM
        }
        CyberBackdrop(Modifier.fillMaxSize(), scene = scene) {
            val frame = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
            Column(frame.imePadding()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        AppTab.CHAT -> ChatTab(
                            chat = chat,
                            draft = draft,
                            onDraftChange = { draft = it },
                            onOpenSetup = { setupOpen = true },
                            onVoice = { voiceOpen = true },
                            modifier = Modifier.fillMaxSize()
                        )
                        AppTab.COUNCIL -> CouncilTab(
                            chat = chat,
                            onAsk = { toChat(council = true) },
                            onRepeat = { goal -> toChat(goal, council = true) },
                            modifier = Modifier.fillMaxSize()
                        )
                        AppTab.MODULES -> ModulesTab(onOpen = { module = it }, modifier = Modifier.fillMaxSize())
                        AppTab.SETTINGS -> SettingsTab(
                            chat = chat,
                            onOpenSetup = { setupOpen = true },
                            onOpenModule = { module = it },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                if (!WindowInsets.isImeVisible) {
                    CyberDock(
                        selected = tab,
                        onSelect = { tab = it; module = null },
                        coreState = chat.nexState,
                        onCore = { voiceOpen = true }
                    )
                }
            }
            module?.let { page ->
                CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.PLAIN) {
                    ToolPage(page.title, onBack = { module = null }, modifier = frame.imePadding()) {
                        ModuleContent(page, chat, onToChat = ::toChat, onTarget = ::openTarget)
                    }
                }
            }
            if (setupOpen) {
                CyberBackdrop(Modifier.fillMaxSize(), scene = BackdropScene.PLAIN) {
                    CoreSetupScreen(
                        chat,
                        onClose = { setupOpen = false },
                        modifier = frame.imePadding(),
                        // «В чат» means exactly that, wherever the setup was opened from
                        onFinish = { setupOpen = false; module = null; tab = AppTab.CHAT }
                    )
                }
            }
            if (voiceOpen) {
                Box(frame) {
                    VoiceOverlay(chat, onClose = { voiceOpen = false }, onNeedSetup = { setupOpen = true })
                }
            }
            // NEX's state around the front camera, above everything (draws nothing without a punch hole)
            if (ai.hybrid.commandcenter.ui.ThemeState.cameraRing) {
                ai.hybrid.commandcenter.ui.CameraRing(chat.nexState, chat.speaking)
            }
        }
    }
}

/** The tool behind each module tile. */
@Composable
private fun ModuleContent(page: ModulePage, chat: ChatController, onToChat: (String?, Boolean) -> Unit, onTarget: (DeckTarget) -> Unit) {
    val fill = Modifier.fillMaxSize()
    when (page) {
        ModulePage.BROWSER -> BrowserScreen(fill, onSendToStudio = { onToChat(it, false) }, onSendToCouncil = { onToChat(it, true) })
        ModulePage.ARCHIVE -> ArchiveScreen(fill, onSendToStudio = { onToChat(it, false) }, onSendToCouncil = { onToChat(it, true) })
        ModulePage.MODELS -> ModelCenterScreen(fill, chat)
        ModulePage.MESH -> MeshCenterScreen(fill)
        ModulePage.DEVICE -> DeviceCoreScreen(fill)
        ModulePage.REPAIRS -> RepairPanel(fill, onSendToCouncil = { onToChat(it, true) })
        ModulePage.CONNECTORS -> McpConnectorsPanel(fill)
        ModulePage.STATUS -> CommandDeckScreen(
            onNavigate = onTarget,
            onCompose = { text, target -> onToChat(text, target == DeckTarget.COUNCIL) },
            modifier = fill
        )
        ModulePage.PLATFORM -> PlatformCenterScreen(fill)
        ModulePage.SYSTEM -> SettingsPanel(fill)
    }
}
