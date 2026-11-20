package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudDivider
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.HudShapes
import ai.hybrid.commandcenter.ui.HudTextField
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexusTypography
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * «Совет»: eight agents that look at a task from different sides and the
 * manager that merges their answers. Here the owner sees the last missions
 * and the routes; the task itself is asked in the chat with the Council mode.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CouncilTab(chat: ChatController, onAsk: () -> Unit, onRepeat: (String) -> Unit, modifier: Modifier = Modifier) {
    var routesOpen by remember { mutableStateOf(false) }
    Column(modifier) {
        TabHeader("Совет ИИ", "8 агентов разбирают задачу с разных сторон, менеджер сводит ответ", NexIcons.Users, accent = Cyber.Magenta)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HudButton("Задать задачу", onAsk, Modifier.weight(1.4f).testTag("council-ask"), icon = NexIcons.MessageSquare, accent = Cyber.Magenta,
                enabled = !chat.isRunning)
            HudButton("Маршруты", { routesOpen = true }, Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.Route,
                accent = Cyber.Magenta)
        }
        CouncilScreen(Modifier.fillMaxSize(), onRepeatMission = onRepeat)
    }
    if (routesOpen) {
        ModalBottomSheet(
            onDismissRequest = { routesOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color(0xFA040914),
            shape = HudShapes.chamfer(18.dp)
        ) {
            RoutesEditor(chat)
        }
    }
}

@Composable
private fun RoutesEditor(chat: ChatController) {
    var expanded by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        HudSectionTitle("Маршруты агентов", accent = Cyber.Magenta)
        Text(
            "Каждой роли можно дать свой сервис и модель. Пусто — роль работает на общем ядре. Ключи берутся из настроек ядра.",
            style = NexusTypography.bodySmall, color = Cyber.TextDim
        )
        chat.councilAgents.forEachIndexed { index, agent ->
            val route = chat.agentRoutes[agent.id] ?: chat.providerKind
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NexIcon(ConstellationModel.roleIcon(agent.role), tint = Cyber.Magenta, size = 18.dp)
                    Text(agent.name, style = NexusTypography.titleSmall, color = Cyber.Text, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Box {
                        Row(
                            Modifier
                                .clip(HudShapes.Small)
                                .background(Cyber.Magenta.copy(alpha = 0.10f))
                                .border(1.dp, Cyber.Magenta.copy(alpha = 0.5f), HudShapes.Small)
                                .clickable { expanded = agent.id }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HudLabel(route.displayName, color = Cyber.Magenta)
                            NexIcon(NexIcons.ChevronDown, tint = Cyber.Magenta, size = 14.dp)
                        }
                        DropdownMenu(expanded = expanded == agent.id, onDismissRequest = { expanded = null }) {
                            ProviderKind.entries.forEach { kind ->
                                DropdownMenuItem(text = { Text(kind.displayName) }, onClick = {
                                    chat.saveRoute(agent.id, kind)
                                    expanded = null
                                })
                            }
                        }
                    }
                }
                HudTextField(
                    chat.agentModelOverrides[agent.id].orEmpty(),
                    { chat.saveModelOverride(agent.id, it) },
                    placeholder = "модель роли (пусто — модель ядра)",
                    accent = Cyber.Magenta
                )
            }
            if (index < chat.councilAgents.lastIndex) HudDivider(accent = Cyber.Magenta)
        }
    }
}
