package ai.hybrid.commandcenter

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
import androidx.compose.material3.Card
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
fun ModelCenterScreen(modifier: Modifier = Modifier) {
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
    var status by remember { mutableStateOf("Каталог подготовлен. Файлы моделей хранятся в закрытом каталоге NEXUS.") }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var activeDownloadCall by remember { mutableStateOf<Call?>(null) }
    var acceptedLicenses by remember { mutableStateOf(setOf<String>()) }
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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                status = "Импортирую модель в закрытое хранилище NEXUS…"
                runCatching { withContext(Dispatchers.IO) { storage.importArtifact(uri) } }
                    .onSuccess {
                        status = "Импорт завершён: " + it.displayName + ". Файл скопирован и SHA-256 рассчитан."
                        refresh()
                    }
                    .onFailure { status = it.message ?: "Не удалось импортировать модель." }
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NEXUS MODEL CENTER", style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text("Локальные модели, веса, движки и управление активной конфигурацией.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { tab = ModelCenterTab.MODELS }, modifier = Modifier.weight(1f)) {
                Text("МОДЕЛИ")
            }
            OutlinedButton(onClick = { tab = ModelCenterTab.EVOLUTION }, modifier = Modifier.weight(1f)) {
                Text("EVOLUTION")
            }
        }

        if (tab == ModelCenterTab.MODELS) {
            val active = installed.firstOrNull { it.id == activeId }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("АКТИВНАЯ ЛОКАЛЬНАЯ МОДЕЛЬ", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary)
                    Text(active?.displayName ?: "Не выбрана",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(active?.let { it.runtime.name + " • " + formatBytes(it.sizeBytes) }
                        ?: "Скачайте или импортируйте модель, затем нажмите «Активировать».",
                        style = MaterialTheme.typography.bodySmall)
                }
            }

            NexusModelCatalog.starterModels.forEach { model ->
                ModelCatalogCard(
                    model = model,
                    installed = installed.firstOrNull { it.id == model.id },
                    isActive = activeId == model.id,
                    isDownloading = downloadingId == model.id,
                    acceptedLicense = model.artifact?.licenseId?.let { it in acceptedLicenses } == true,
                    progress = if (downloadingId == model.id) progress else 0f,
                    progressLabel = if (downloadingId == model.id) progressLabel else "",
                    onLicenseChange = { accepted ->
                        val license = model.artifact?.licenseId
                        if (license != null) acceptedLicenses =
                            if (accepted) acceptedLicenses + license else acceptedLicenses - license
                    },
                    onDownload = {
                        scope.launch {
                            downloadingId = model.id
                            progress = 0f
                            status = "Загрузка " + model.displayName + "…"
                            runCatching {
                                storage.download(model, model.artifact?.licenseId?.takeIf { license ->
                                    license in acceptedLicenses
                                }, onCallCreated = { call ->
                                    scope.launch { activeDownloadCall = call }
                                }) { received, total ->
                                    scope.launch {
                                        progress = if (total > 0) received.toFloat() / total.toFloat() else 0f
                                        progressLabel = formatBytes(received) + " / " + formatBytes(total)
                                    }
                                }
                            }.onSuccess {
                                status = "Модель загружена, SHA-256 подтверждён: " + it.displayName
                                refresh()
                            }.onFailure {
                                status = it.message ?: "Ошибка загрузки модели."
                            }
                            activeDownloadCall = null
                            downloadingId = null
                        }
                    },
                    onActivate = {
                        scope.launch {
                            status = "Проверяю SHA-256 модели перед активацией…"
                            runCatching { withContext(Dispatchers.IO) { storage.activate(model.id) } }
                                .onSuccess { status = "Активная модель: " + it.displayName; refresh() }
                                .onFailure { status = it.message ?: "Не удалось активировать модель." }
                        }
                    },
                    onCancelDownload = {
                        status = "Отменяю загрузку…"
                        activeDownloadCall?.cancel()
                    },
                    onDelete = {
                        scope.launch {
                            runCatching { withContext(Dispatchers.IO) { storage.remove(model.id) } }
                                .onSuccess { status = "Модель удалена из хранилища."; refresh() }
                                .onFailure { status = it.message ?: "Не удалось удалить модель." }
                        }
                    }
                )
            }

            Text("ИМПОРТ СВОИХ МОДЕЛЕЙ", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            Text("Можно выбрать файл .gguf для llama.cpp или .litertlm для LiteRT-LM. NEXUS скопирует его в app-private storage.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Важно: встроенный шаблон диалога llama.cpp сейчас рассчитан на Qwen3 ChatML. Другие GGUF могут требовать отдельную chat template и пока не гарантированно совместимы.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text("Импортировать файл модели")
            }

            installed.filter { it.imported }.forEach { model ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(model.displayName, fontWeight = FontWeight.SemiBold)
                        Text(model.runtime.name + " • " + formatBytes(model.sizeBytes),
                            style = MaterialTheme.typography.bodySmall)
                        Text("SHA-256: " + model.sha256, style = MaterialTheme.typography.labelSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    status = "Проверяю SHA-256 модели перед активацией…"
                                    runCatching { withContext(Dispatchers.IO) { storage.activate(model.id) } }
                                        .onSuccess { status = "Активная модель: " + it.displayName; refresh() }
                                        .onFailure { status = it.message ?: "Ошибка активации." }
                                }
                            }) { Text(if (activeId == model.id) "АКТИВНА" else "Активировать") }
                            TextButton(onClick = {
                                scope.launch {
                                    runCatching { withContext(Dispatchers.IO) { storage.remove(model.id) } }
                                        .onSuccess { status = "Файл модели удалён."; refresh() }
                                        .onFailure { status = it.message ?: "Ошибка удаления." }
                                }
                            }) { Text("Удалить") }
                        }
                    }
                }
            }
        } else {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("КОНТРОЛИРУЕМОЕ САМОРАЗВИТИЕ", fontWeight = FontWeight.Bold,
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
    "research": "OPENAI",
    "analysis": "ON_DEVICE_LITERT"
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

            Text("ОЖИДАЮТ ПОДТВЕРЖДЕНИЯ", fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            if (pending.isEmpty()) Text("Нет ожидающих предложений.", style = MaterialTheme.typography.bodySmall)
            pending.forEach { record ->
                Card(modifier = Modifier.fillMaxWidth()) {
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

            Text("ВЕРСИИ И ОТКАТ", fontWeight = FontWeight.Bold,
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
            Text("ЖУРНАЛ EVOLUTION", fontWeight = FontWeight.Bold,
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

@Composable
private fun ModelCatalogCard(
    model: NexusModelDescriptor,
    installed: InstalledNexusModel?,
    isActive: Boolean,
    isDownloading: Boolean,
    acceptedLicense: Boolean,
    progress: Float,
    progressLabel: String,
    onLicenseChange: (Boolean) -> Unit,
    onDownload: () -> Unit,
    onActivate: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(model.displayName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                if (model.recommendedForBalancedProfile) Text("BALANCED", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Text(model.publisher + " • " + model.approximateParameterLabel,
                style = MaterialTheme.typography.bodySmall)
            Text("Runtime: " + model.runtimes.joinToString() + " • " + model.executionTarget,
                style = MaterialTheme.typography.bodySmall)
            Text(model.capabilities.joinToString(" • ") { it.name },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (model.artifact != null) {
                Text("Размер файла: " + formatBytes(model.artifact.sizeBytes),
                    style = MaterialTheme.typography.bodySmall)
                Text("SHA-256: " + model.artifact.sha256,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Ревизия: " + model.artifact.immutableRevision.take(12),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (model.requiresLicenseAcceptance && installed == null) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = acceptedLicense, onCheckedChange = onLicenseChange)
                        Text("Принимаю лицензию " + model.artifact.licenseId,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (isDownloading) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(progressLabel, style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("Отменить загрузку")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (installed == null) {
                        Button(enabled = model.isDownloadable && !isDownloading &&
                            (!model.requiresLicenseAcceptance || acceptedLicense), onClick = onDownload) {
                            Text(if (isDownloading) "Загрузка…" else "Скачать веса")
                        }
                    } else {
                        Button(onClick = onActivate) { Text(if (isActive) "АКТИВНА" else "Активировать") }
                        TextButton(onClick = onDelete, enabled = !isActive) { Text("Удалить") }
                    }
                }
            } else {
                Text(
                    if (model.executionTarget == ai.hybrid.commandcenter.nexus.ModelExecutionTarget.HOME_PC_LAN)
                        "Это будущий LAN-каталог моделей домашнего ПК. В текущем срезе подключение к Ollama на ПК настраивается отдельно через профиль AI Studio; веса на телефон не скачиваются."
                    else "Карточка каталога готова. Проверенный артефакт для прямой загрузки пока не закреплён; можно импортировать совместимый файл вручную.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val gib = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gib >= 1.0) String.format(Locale.US, "%.2f GB", gib)
    else String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}
