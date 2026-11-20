package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.ResourceGovernor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import ai.hybrid.commandcenter.ui.GlassSurface
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.hybrid.commandcenter.nexus.EvolutionChangeKind
import ai.hybrid.commandcenter.nexus.InstalledNexusModel
import ai.hybrid.commandcenter.nexus.NexusEvolutionExecutor
import ai.hybrid.commandcenter.nexus.NexusEvolutionRecord
import ai.hybrid.commandcenter.nexus.NexusModelCatalog
import ai.hybrid.commandcenter.nexus.NexusModelDescriptor
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import okhttp3.Call
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private enum class ModelCenterTab { MODELS, EVOLUTION }

@Composable
fun ModelCenterScreen(modifier: Modifier = Modifier, chat: ChatController? = null) {
    val context = LocalContext.current
    val storage = remember(context) { NexusModelStorage(context) }
    val evolution = remember(context) { NexusEvolutionExecutor(context) }
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(ModelCenterTab.MODELS) }
    var installed by remember { mutableStateOf(emptyList<InstalledNexusModel>()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf(emptyList<NexusEvolutionRecord>()) }
    var versions by remember { mutableStateOf(emptyList<Long>()) }
    var activeEvolutionVersion by remember { mutableStateOf(0L) }
    var auditEvents by remember { mutableStateOf(emptyList<String>()) }
    var status by remember { mutableStateOf("Модели для телефона: 12 штук в трёх уровнях, плюс те, что уже лежат в памяти.") }
    var proposalTitle by remember { mutableStateOf("") }
    var proposalReason by remember { mutableStateOf("") }
    var proposalPayload by remember { mutableStateOf("") }
    var proposalKind by remember { mutableStateOf(EvolutionChangeKind.PROMPT_PROFILE) }

    fun refresh() {
        scope.launch {
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    Triple(storage.listInstalled(), storage.activeModel()?.id, evolution.pendingProposals()),
                    evolution.activeVersion(),
                    evolution.availableVersions()
                )
            }
            installed = loaded.first.first
            activeId = loaded.first.second
            pending = loaded.first.third
            activeEvolutionVersion = loaded.second
            versions = loaded.third
            auditEvents = withContext(Dispatchers.IO) { evolution.auditTrail().takeLast(8).reversed() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        ai.hybrid.commandcenter.ui.SegmentedTabs(
            options = listOf("Модели", "Evolution"),
            selected = if (tab == ModelCenterTab.MODELS) 0 else 1,
            onSelect = { tab = if (it == 0) ModelCenterTab.MODELS else ModelCenterTab.EVOLUTION }
        )

        if (tab == ModelCenterTab.MODELS) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PhoneModelHub(chat = chat, onActivated = { record ->
                status = "Активная модель: " + record.displayName + ". NEX теперь думает на телефоне."
                refresh()
            })
        } else {
            GlassSurface(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Контролируемое саморазвитие", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Evolution создаёт версии промптов, навыков, маршрутов и конфигураций. Изменение сначала сохраняется как предложение; активация выполняется только после вашего подтверждения. Веса нейросетей и системные разрешения этим механизмом не изменяются.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedTextField(value = proposalTitle, onValueChange = { proposalTitle = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Название изменения") }, singleLine = true)
            OutlinedTextField(value = proposalReason, onValueChange = { proposalReason = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Причина и ожидаемый результат") }, minLines = 2)
            OutlinedTextField(value = proposalPayload, onValueChange = { proposalPayload = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Содержимое конфигурации") }, minLines = 4)
            if (proposalKind == EvolutionChangeKind.ROUTING_POLICY) {
                Text(
                    "Routing policy v1: routes maps Council role IDs to ProviderKind values. " +
                        "Optional modelOverrides maps the same role IDs to exact model IDs. " +
                        "Endpoints and API keys remain in provider settings.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(onClick = {
                    proposalPayload = """{
  "version": 1,
  "routes": {
    "research": "Openai",
    "analysis": "On_device_litert"
  },
  "modelOverrides": {
    "research": "gpt-model-id"
  }
}"""
                }) { Text("Insert routing policy template") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { proposalKind = EvolutionChangeKind.PROMPT_PROFILE }) {
                    Text(if (proposalKind == EvolutionChangeKind.PROMPT_PROFILE) "✓ Промпт" else "Промпт")
                }
                OutlinedButton(onClick = { proposalKind = EvolutionChangeKind.SKILL_PACK }) {
                    Text(if (proposalKind == EvolutionChangeKind.SKILL_PACK) "✓ Навык" else "Навык")
                }
                OutlinedButton(onClick = { proposalKind = EvolutionChangeKind.ROUTING_POLICY }) {
                    Text(if (proposalKind == EvolutionChangeKind.ROUTING_POLICY) "✓ Маршрут" else "Маршрут")
                }
            }
            Button(
                enabled = proposalTitle.isNotBlank() && proposalReason.isNotBlank() && proposalPayload.isNotBlank(),
                onClick = {
                    scope.launch {
                        status = "Проверяю и сохраняю предложение Evolution…"
                        runCatching {
                            withContext(Dispatchers.IO) {
                                evolution.createProposal(proposalKind, proposalTitle, proposalReason, proposalPayload)
                            }
                        }.onSuccess {
                            status = "Предложение Evolution создано. Для активации требуется отдельное подтверждение."
                            proposalTitle = ""; proposalReason = ""; proposalPayload = ""; refresh()
                        }.onFailure { status = it.message ?: "Не удалось создать предложение." }
                    }
                }, modifier = Modifier.fillMaxWidth()
            ) { Text("Создать предложение Evolution") }

            Text("Ожидают подтверждения", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (pending.isEmpty()) Text("Нет ожидающих предложений.", style = MaterialTheme.typography.bodySmall)
            pending.forEach { record ->
                GlassSurface(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(record.proposal.title, fontWeight = FontWeight.SemiBold)
                        Text(record.proposal.kind.name + " • v" + record.proposal.beforeVersion +
                            " → v" + record.proposal.proposedVersion, style = MaterialTheme.typography.labelSmall)
                        Text(record.proposal.rationale, style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256: " + record.proposal.contentSha256, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    status = "Проверяю целостность и активирую Evolution…"
                                    val result = withContext(Dispatchers.IO) {
                                        evolution.approveAndActivate(record.proposal.id)
                                    }
                                    result.onSuccess { status = "Evolution активировал версию " + it.activeVersion; refresh() }
                                        .onFailure { status = it.message ?: "Активация отклонена." }
                                }
                            }) { Text("Применить") }
                            TextButton(onClick = {
                                scope.launch {
                                    val rejected = withContext(Dispatchers.IO) {
                                        evolution.reject(record.proposal.id, "Отклонено пользователем.")
                                    }
                                    if (rejected) { status = "Предложение отклонено."; refresh() }
                                }
                            }) { Text("Отклонить") }
                        }
                    }
                }
            }

            Text("Версии и откат", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Текущая версия: v" + activeEvolutionVersion,
                style = MaterialTheme.typography.bodyMedium)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                versions.filter { it < activeEvolutionVersion }.take(6).forEach { version ->
                    OutlinedButton(onClick = {
                        scope.launch {
                            status = "Проверяю историю версий и выполняю откат…"
                            val result = withContext(Dispatchers.IO) { evolution.rollbackTo(version) }
                            result.onSuccess { status = "Восстановлена версия " + it; refresh() }
                                .onFailure { status = it.message ?: "Откат не выполнен." }
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Восстановить версию v$version")
                    }
                }
                if (versions.none { it < activeEvolutionVersion }) {
                    Text("Предыдущих версий пока нет.", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Журнал Evolution", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (auditEvents.isEmpty()) {
                Text("Событий пока нет.", style = MaterialTheme.typography.bodySmall)
            } else {
                auditEvents.forEach { event ->
                    Text(event, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
    }
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(Locale.US, "%.2f GB", gib)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}
