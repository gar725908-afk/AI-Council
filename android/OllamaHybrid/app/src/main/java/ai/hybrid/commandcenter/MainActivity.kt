package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.CircleIconButton
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusTheme
import ai.hybrid.commandcenter.ui.SpaceBackground
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * One main screen — the chat, as in ChatGPT. Every tool (council journal,
 * browser, archive, system state, settings) opens as a page from the chat's
 * drawer and returns with Back. No tab bar.
 */
@Composable
fun NexusApp(startPage: AppPage = AppPage.CHAT) {
    NexusTheme {
        var page by remember { mutableStateOf(startPage) }
        var systemTab by remember { mutableIntStateOf(SystemTab.SETTINGS.ordinal) }
        var pendingPrompt by remember { mutableStateOf<String?>(null) }
        var councilStart by remember { mutableStateOf(false) }
        var nexState by remember { mutableStateOf<NexState?>(null) }

        fun toChat(text: String? = null, council: Boolean = false) {
            pendingPrompt = text
            councilStart = council
            page = AppPage.CHAT
        }

        fun openTarget(target: DeckTarget) {
            when (target) {
                DeckTarget.STUDIO -> toChat()
                DeckTarget.COUNCIL -> page = AppPage.COUNCIL
                DeckTarget.BROWSER -> page = AppPage.BROWSER
                DeckTarget.ARCHIVE -> page = AppPage.ARCHIVE
                DeckTarget.MODELS -> { systemTab = SystemTab.MODELS.ordinal; page = AppPage.SETTINGS }
                DeckTarget.DEVICE -> { systemTab = SystemTab.DEVICE.ordinal; page = AppPage.SETTINGS }
                DeckTarget.REPAIRS -> { systemTab = SystemTab.REPAIRS.ordinal; page = AppPage.SETTINGS }
                DeckTarget.SETTINGS -> { systemTab = SystemTab.SETTINGS.ordinal; page = AppPage.SETTINGS }
            }
        }

        BackHandler(enabled = page != AppPage.CHAT) { page = AppPage.CHAT }

        SpaceBackground(
            Modifier.fillMaxSize(),
            state = if (page == AppPage.CHAT) nexState else null,
            showHorizon = page == AppPage.CHAT
        ) {
            val frame = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
            when (page) {
                AppPage.CHAT -> NexusChatScreen(
                    modifier = frame,
                    initialPrompt = pendingPrompt,
                    onInitialPromptConsumed = { pendingPrompt = null },
                    startInCouncil = councilStart,
                    onOpenPage = { target ->
                        if (target == AppPage.SETTINGS) systemTab = SystemTab.SETTINGS.ordinal
                        page = target
                    },
                    onNexState = { nexState = it }
                )
                AppPage.COUNCIL -> ToolPage("Совет и миссии", { page = AppPage.CHAT }, frame) {
                    CouncilScreen(
                        modifier = Modifier.fillMaxSize(),
                        onRepeatMission = { goal -> toChat(goal, council = true) }
                    )
                }
                AppPage.BROWSER -> ToolPage("Браузер", { page = AppPage.CHAT }, frame) {
                    BrowserScreen(
                        modifier = Modifier.fillMaxSize(),
                        onSendToStudio = { text -> toChat(text) },
                        onSendToCouncil = { text -> toChat(text, council = true) }
                    )
                }
                AppPage.ARCHIVE -> ToolPage("Архив файлов", { page = AppPage.CHAT }, frame) {
                    ArchiveScreen(
                        modifier = Modifier.fillMaxSize(),
                        onSendToStudio = { text -> toChat(text) },
                        onSendToCouncil = { text -> toChat(text, council = true) }
                    )
                }
                AppPage.STATUS -> ToolPage("Состояние системы", { page = AppPage.CHAT }, frame) {
                    CommandDeckScreen(
                        onNavigate = { openTarget(it) },
                        onCompose = { text, target -> toChat(text, council = target == DeckTarget.COUNCIL) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                AppPage.SETTINGS -> ToolPage("Настройки", { page = AppPage.CHAT }, frame) {
                    SystemScreen(
                        modifier = Modifier.fillMaxSize(),
                        initialTab = systemTab,
                        onSendToCouncil = { text -> toChat(text, council = true) }
                    )
                }
            }
        }
    }
}

/** A tool opened from the drawer: a slim bar with Back and the title, then the tool. */
@Composable
fun ToolPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircleIconButton(NexIcons.ArrowLeft, onClick = onBack, size = 42.dp, contentDescription = "Назад")
            Text(title, style = MaterialTheme.typography.titleLarge, color = NexusColors.TextPrimary)
        }
        content()
    }
}
