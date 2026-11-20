package ai.hybrid.commandcenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val Void = Color(0xFF080B12)
private val SurfaceDeep = Color(0xFF0E1420)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val PrimaryText = Color(0xFFF1F5FA)

/** Primary spaces per handoff section 4 — each one is real, none is a placeholder. */
enum class NexusDestination(val label: String, val glyph: String) {
    DECK("Главная", "⌂"),
    STUDIO("AI Studio", "✧"),
    COUNCIL("Council", "◎"),
    BROWSER("Браузер", "◍"),
    ARCHIVE("Архив", "❒"),
    SYSTEM("Система", "▣")
}

/** Targets other screens may ask the shell to open. */
enum class DeckTarget { STUDIO, COUNCIL, MODELS, DEVICE }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { NexusApp() }
    }
}

@Composable
private fun NexusApp() {
    val colors = darkColorScheme(
        primary = Cyan,
        secondary = Violet,
        background = Void,
        surface = SurfaceDeep,
        onPrimary = Void,
        onBackground = PrimaryText,
        onSurface = PrimaryText
    )
    MaterialTheme(colorScheme = colors) {
        var selected by remember { mutableStateOf(NexusDestination.DECK) }
        var systemTab by remember { mutableIntStateOf(0) }
        var pendingPrompt by remember { mutableStateOf<String?>(null) }

        fun open(target: DeckTarget) {
            when (target) {
                DeckTarget.STUDIO -> selected = NexusDestination.STUDIO
                DeckTarget.COUNCIL -> selected = NexusDestination.COUNCIL
                DeckTarget.MODELS -> {
                    systemTab = SystemTab.MODELS.ordinal
                    selected = NexusDestination.SYSTEM
                }
                DeckTarget.DEVICE -> {
                    systemTab = SystemTab.DEVICE.ordinal
                    selected = NexusDestination.SYSTEM
                }
            }
        }

        Scaffold(
            containerColor = Void,
            bottomBar = {
                NavigationBar(containerColor = SurfaceDeep) {
                    NexusDestination.values().forEach { destination ->
                        NavigationBarItem(
                            selected = selected == destination,
                            onClick = { selected = destination },
                            icon = { Text(destination.glyph, fontSize = 18.sp) },
                            label = { Text(destination.label, fontSize = 10.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                NexusDestination.DECK -> CommandDeckScreen(
                    onNavigate = { open(it) },
                    onCompose = { text, target ->
                        pendingPrompt = text
                        open(target)
                    },
                    modifier = Modifier.padding(padding)
                )
                NexusDestination.STUDIO -> AiStudioScreen(
                    councilMode = false,
                    modifier = Modifier.padding(padding),
                    initialPrompt = pendingPrompt,
                    onInitialPromptConsumed = { pendingPrompt = null }
                )
                NexusDestination.COUNCIL -> CouncilScreen(
                    modifier = Modifier.padding(padding),
                    initialPrompt = pendingPrompt,
                    onInitialPromptConsumed = { pendingPrompt = null }
                )
                NexusDestination.BROWSER -> BrowserScreen(
                    modifier = Modifier.padding(padding),
                    onSendToStudio = { text ->
                        pendingPrompt = text
                        selected = NexusDestination.STUDIO
                    },
                    onSendToCouncil = { text ->
                        pendingPrompt = text
                        selected = NexusDestination.COUNCIL
                    }
                )
                NexusDestination.ARCHIVE -> ArchiveScreen(
                    modifier = Modifier.padding(padding),
                    onSendToStudio = { text ->
                        pendingPrompt = text
                        selected = NexusDestination.STUDIO
                    },
                    onSendToCouncil = { text ->
                        pendingPrompt = text
                        selected = NexusDestination.COUNCIL
                    }
                )
                NexusDestination.SYSTEM -> SystemScreen(
                    modifier = Modifier.padding(padding),
                    initialTab = systemTab,
                    onSendToCouncil = { text ->
                        pendingPrompt = text
                        selected = NexusDestination.COUNCIL
                    }
                )
            }
        }
    }
}
