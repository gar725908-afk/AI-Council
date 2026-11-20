package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.AuroraBackground
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexusBrushes
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusShapes
import ai.hybrid.commandcenter.ui.NexusTheme
import android.graphics.Color as AndroidColor
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Primary spaces per handoff section 4 — each one is real, none is a placeholder. */
enum class NexusDestination(val label: String, @DrawableRes val icon: Int) {
    DECK("Главная", NexIcons.House),
    STUDIO("Studio", NexIcons.Sparkles),
    COUNCIL("Council", NexIcons.Orbit),
    BROWSER("Браузер", NexIcons.Globe),
    ARCHIVE("Архив", NexIcons.Archive),
    SYSTEM("Система", NexIcons.SlidersHorizontal)
}

/** Targets other screens may ask the shell to open. */
enum class DeckTarget { STUDIO, COUNCIL, MODELS, DEVICE, BROWSER, ARCHIVE, REPAIRS, SETTINGS }

/** Space the floating navigation bar takes, so screens can keep content clear of it. */
private val NavBarReserve = 92.dp

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

@Composable
private fun NexusApp() {
    NexusTheme {
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
                DeckTarget.BROWSER -> selected = NexusDestination.BROWSER
                DeckTarget.ARCHIVE -> selected = NexusDestination.ARCHIVE
                DeckTarget.REPAIRS -> {
                    systemTab = SystemTab.REPAIRS.ordinal
                    selected = NexusDestination.SYSTEM
                }
                DeckTarget.SETTINGS -> {
                    systemTab = SystemTab.SETTINGS.ordinal
                    selected = NexusDestination.SYSTEM
                }
            }
        }

        AuroraBackground(Modifier.fillMaxSize()) {
            val screen = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = NavBarReserve)
            when (selected) {
                NexusDestination.DECK -> CommandDeckScreen(
                    onNavigate = { open(it) },
                    onCompose = { text, target ->
                        pendingPrompt = text
                        open(target)
                    },
                    modifier = screen
                )
                NexusDestination.STUDIO -> AiStudioScreen(
                    councilMode = false,
                    modifier = screen,
                    initialPrompt = pendingPrompt,
                    onInitialPromptConsumed = { pendingPrompt = null }
                )
                NexusDestination.COUNCIL -> CouncilScreen(
                    modifier = screen,
                    initialPrompt = pendingPrompt,
                    onInitialPromptConsumed = { pendingPrompt = null }
                )
                NexusDestination.BROWSER -> BrowserScreen(
                    modifier = screen,
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
                    modifier = screen,
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
                    modifier = screen,
                    initialTab = systemTab,
                    onSendToCouncil = { text ->
                        pendingPrompt = text
                        selected = NexusDestination.COUNCIL
                    }
                )
            }
            FloatingNavBar(
                selected = selected,
                onSelect = { selected = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
            )
        }
    }
}

/**
 * Floating glass navigation: every space gets an icon and a short name; the
 * active one sits on a gradient pill. Equal widths, so it fits any phone.
 */
@Composable
fun FloatingNavBar(
    selected: NexusDestination,
    onSelect: (NexusDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(70.dp)
            .shadow(24.dp, NexusShapes.Panel, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(NexusShapes.Panel)
            .background(Color(0xEB0F1119))
            .border(1.dp, NexusBrushes.GlassBorder, NexusShapes.Panel)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        NexusDestination.entries.forEach { destination ->
            val active = destination == selected
            Column(
                Modifier
                    .weight(1f)
                    .clip(NexusShapes.Control)
                    .clickable { onSelect(destination) }
                    .semantics {
                        this.selected = active
                        contentDescription = destination.label
                    }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    Modifier
                        .size(width = 48.dp, height = 30.dp)
                        .clip(NexusShapes.Pill)
                        .then(if (active) Modifier.background(NexusBrushes.Primary) else Modifier),
                    contentAlignment = Alignment.Center
                ) {
                    NexIcon(
                        destination.icon,
                        tint = if (active) Color.White else NexusColors.TextSecondary,
                        size = 20.dp
                    )
                }
                Text(
                    destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) NexusColors.TextPrimary else NexusColors.TextMuted,
                    maxLines = 1
                )
            }
        }
    }
}
