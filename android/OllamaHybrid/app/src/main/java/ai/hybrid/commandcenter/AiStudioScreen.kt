package ai.hybrid.commandcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.CouncilModelRoute
import ai.hybrid.commandcenter.nexus.CouncilRoutingStore
import ai.hybrid.commandcenter.nexus.CompletionRequest
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.requiresApiKey
import ai.hybrid.commandcenter.nexus.createModelGateway
import ai.hybrid.commandcenter.nexus.ProviderMessage
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.nexus.MissionJournal
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.ProviderModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

@Composable
fun AiStudioScreen(
    councilMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { ProviderSettingsStore(context) }
    val credentialVault = remember(context) { AndroidCredentialVault(context) }
    val initialSettings = remember(context) { settingsStore.load() }
    var providerKind by remember {
        mutableStateOf(
            runCatching { ProviderKind.valueOf(initialSettings.providerKind) }
                .getOrDefault(ProviderKind.OPENAI_COMPATIBLE)
        )
    }
    val gateway = remember(providerKind) { createModelGateway(providerKind) }
    val modelCatalog = remember { ProviderModelCatalog() }
    val routingStore = remember(context) { CouncilRoutingStore(context) }
    val councilAgents = remember { CouncilOrchestrator.defaultAgents() }
    val journal = remember(context) { (context.applicationContext as NexusApplication).missionJournal }
    val scope = rememberCoroutineScope()

    var endpoint by remember {
        mutableStateOf(initialSettings.endpoint.ifBlank { defaultProviderEndpoint(providerKind) })
    }
    var model by remember { mutableStateOf(initialSettings.model) }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var isCheckingModels by remember { mutableStateOf(false) }
    var discoveredModels by remember(providerKind) { mutableStateOf(emptyList<String>()) }
    var agentRoutes by remember(providerKind) {
        mutableStateOf(
            councilAgents.associate { agent ->
                agent.id to (routingStore.load(agent.id) ?: providerKind)
            }
        )
    }
    var expandedAgentRoute by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var hasSavedKey by remember(providerKind) {
        mutableStateOf(
            credentialVault.get(AndroidCredentialVault.providerKeyId(providerKind.name)) != null
        )
    }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Провайдер не проверен") }
    var isRunning by remember { mutableStateOf(false) }
    var activeRun by remember { mutableStateOf<Job?>(null) }
    var recentMissions by remember { mutableStateOf(emptyList<ai.hybrid.commandcenter.nexus.MissionRecord>()) }
    LaunchedEffect(journal) {
        recentMissions = withContext(Dispatchers.IO) { journal.getRecentMissions(8) }
    }
    val providerKeyId = AndroidCredentialVault.providerKeyId(providerKind.name)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (councilMode) "NEXUS COUNCIL" else "NEXUS AI STUDIO",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            if (councilMode) {
                "Коллективное выполнение: восемь ролей, не более трёх параллельных агентов по умолчанию."
            } else {
                "Потоковая генерация через выбранный API-протокол."
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("ПОДКЛЮЧЕНИЕ МОДЕЛИ", fontWeight = FontWeight.SemiBold)
                Box {
                    Button(onClick = { providerMenuExpanded = true }) {
                        Text(providerKind.displayName)
                    }
                    DropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false }
                    ) {
                        ProviderKind.values().forEach { kind ->
                            DropdownMenuItem(
                                text = { Text(kind.displayName) },
                                onClick = {
                                    providerKind = kind
                                    val saved = settingsStore.load(kind.name)
                                    endpoint = saved.endpoint.ifBlank { defaultProviderEndpoint(kind) }
                                    model = saved.model
                                    hasSavedKey = credentialVault.get(
                                        AndroidCredentialVault.providerKeyId(kind.name)
                                    ) != null
                                    apiKey = ""
                                    discoveredModels = emptyList()
                                    modelMenuExpanded = false
                                    providerMenuExpanded = false
                                    status = "Провайдер выбран: " + kind.displayName
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API endpoint") },
                    placeholder = {
                        Text(defaultProviderEndpoint(providerKind).ifBlank {
                            "http://IP-АДРЕС-ПК:11434/v1"
                        })
                    },
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя модели") },
                    placeholder = {
                        Text(
                            when (providerKind) {
                                ProviderKind.OPENAI_COMPATIBLE,
                                ProviderKind.OPENAI -> "gpt-4.1-mini"
                                ProviderKind.DEEPSEEK -> "deepseek-flash или deepseek-v4-pro"
                                ProviderKind.OPENROUTER -> "provider/model"
                                ProviderKind.GROQ -> "точное имя модели из каталога Groq"
                                ProviderKind.MISTRAL -> "mistral-large-latest"
                                ProviderKind.TOGETHER -> "provider/model"
                                ProviderKind.FIREWORKS -> "accounts/fireworks/models/..."
                                ProviderKind.OLLAMA -> "имя локальной модели, например qwen3"
                                ProviderKind.ANTHROPIC -> "имя Claude модели из Anthropic Console"
                                ProviderKind.GEMINI -> "имя Gemini модели из Google AI Studio"
                            }
                        )
                    },
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isCheckingModels,
                        onClick = {
                            scope.launch {
                                isCheckingModels = true
                                status = "Проверяю endpoint и получаю список моделей…"
                                try {
                                    val candidateKey = if (apiKey.isNotBlank()) {
                                        apiKey
                                    } else {
                                        credentialVault.get(providerKeyId).orEmpty()
                                    }
                                    discoveredModels = modelCatalog.listModels(
                                        kind = providerKind,
                                        endpoint = endpoint,
                                        apiKey = candidateKey
                                    )
                                    if (model.isBlank()) model = discoveredModels.first()
                                    modelMenuExpanded = true
                                    status = "Подключение подтверждено. Найдено моделей: " + discoveredModels.size
                                } catch (error: Exception) {
                                    status = error.message ?: "Не удалось проверить провайдера"
                                } finally {
                                    isCheckingModels = false
                                }
                            }
                        }
                    ) {
                        if (isCheckingModels) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text("Проверка…")
                        } else {
                            Text("Проверить и загрузить модели")
                        }
                    }
                    Box {
                        TextButton(
                            enabled = discoveredModels.isNotEmpty(),
                            onClick = { modelMenuExpanded = true }
                        ) {
                            Text("Выбрать модель")
                        }
                        DropdownMenu(
                            expanded = modelMenuExpanded,
                            onDismissRequest = { modelMenuExpanded = false }
                        ) {
                            discoveredModels.take(60).forEach { modelId ->
                                DropdownMenuItem(
                                    text = { Text(modelId) },
                                    onClick = {
                                        model = modelId
                                        modelMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (hasSavedKey) "Новый API-ключ (необязательно)" else
                                if (providerKind.requiresApiKey()) "API-ключ провайдера"
                                else "API-ключ (необязательно для локального сервера)"
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            try {
                                require(endpoint.isNotBlank()) { "Укажите API endpoint." }
                                require(model.isNotBlank()) { "Укажите имя модели." }
                                settingsStore.save(ProviderSettings(endpoint, model, providerKind.name))
                                if (apiKey.isNotBlank()) {
                                    credentialVault.put(providerKeyId, apiKey)
                                    apiKey = ""
                                    hasSavedKey = true
                                }
                                status = "Настройки сохранены для " + providerKind.displayName
                            } catch (error: Exception) {
                                status = error.message ?: "Не удалось сохранить настройки"
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                    TextButton(
                        onClick = {
                            credentialVault.remove(providerKeyId)
                            hasSavedKey = false
                            apiKey = ""
                            status = "API-ключ этого провайдера удалён"
                        }
                    ) {
                        Text("Удалить ключ")
                    }
                }
                Text(
                    if (hasSavedKey) "Ключ сохранён в зашифрованном виде через Android Keystore."
                    else if (providerKind.requiresApiKey())
                        "Для выбранного провайдера требуется API-ключ."
                    else if (providerKind == ProviderKind.OLLAMA)
                        "Для физического телефона укажите LAN IP компьютера, на котором запущен Ollama."
                    else "Ключ можно не указывать для локального OpenAI-compatible сервера без авторизации.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Контекст запроса отправляется выбранному провайдеру. HTTP разрешён только для локальной сети и не шифрует трафик: не используйте с ним ключи облачных сервисов. Внешние endpoint должны использовать HTTPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (councilMode) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("МАРШРУТИЗАЦИЯ АГЕНТОВ", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Назначайте разные AI-провайдеры отдельным ролям. Для каждого выбранного провайдера заранее сохраните endpoint, модель и ключ в AI Studio.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    councilAgents.forEach { agent ->
                        val selectedRoute = agentRoutes[agent.id] ?: providerKind
                        Box {
                            TextButton(onClick = { expandedAgentRoute = agent.id }) {
                                Text(agent.name + "  ·  " + selectedRoute.displayName)
                            }
                            DropdownMenu(
                                expanded = expandedAgentRoute == agent.id,
                                onDismissRequest = { expandedAgentRoute = null }
                            ) {
                                ProviderKind.values().forEach { kind ->
                                    DropdownMenuItem(
                                        text = { Text(kind.displayName) },
                                        onClick = {
                                            routingStore.save(agent.id, kind)
                                            agentRoutes = agentRoutes + (agent.id to kind)
                                            expandedAgentRoute = null
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Задача") },
            placeholder = { Text("Опишите, что должна сделать система…") },
            minLines = 3
        )

        Button(
            onClick = {
                activeRun = scope.launch {
                    isRunning = true
                    answer = ""
                    status = if (councilMode) "Совет анализирует задачу…" else "Модель отвечает…"
                    var missionId: String? = null
                    try {
                        require(endpoint.isNotBlank() && model.isNotBlank()) {
                            "Сначала укажите endpoint и модель."
                        }
                        missionId = withContext(Dispatchers.IO) {
                            journal.createMission(prompt, endpoint, model).also { id ->
                                journal.transition(id, MissionStatus.QUEUED, "Миссия поставлена в очередь")
                                journal.transition(id, MissionStatus.RUNNING, "Запрос отправлен провайдеру")
                            }
                        }

                        val activeKey = if (apiKey.isNotBlank()) {
                            credentialVault.put(providerKeyId, apiKey)
                            apiKey = ""
                            hasSavedKey = true
                            credentialVault.get(providerKeyId)
                        } else {
                            credentialVault.get(providerKeyId)
                        }
                        if (councilMode) {
                            val result = CouncilOrchestrator(
                                gateway = gateway,
                                maxParallelAgents = 3,
                                routeResolver = { agent ->
                                    val routeKind = routingStore.load(agent.id) ?: providerKind
                                    if (routeKind == providerKind) {
                                        CouncilModelRoute(
                                            gateway = gateway,
                                            endpoint = endpoint,
                                            model = model,
                                            apiKey = activeKey.orEmpty()
                                        )
                                    } else {
                                        val profile = settingsStore.load(routeKind.name)
                                        require(profile.model.isNotBlank()) {
                                            "Для роли " + agent.name + " не настроена модель " + routeKind.displayName +
                                                ". Сначала выберите провайдера в AI Studio и сохраните его параметры."
                                        }
                                        val routeEndpoint = profile.endpoint.ifBlank {
                                            defaultProviderEndpoint(routeKind)
                                        }
                                        require(routeEndpoint.isNotBlank()) {
                                            "Для роли " + agent.name + " не указан endpoint провайдера " +
                                                routeKind.displayName + ". Укажите адрес сервера и сохраните профиль."
                                        }
                                        val routeKey = credentialVault.get(
                                            AndroidCredentialVault.providerKeyId(routeKind.name)
                                        ).orEmpty()
                                        require(!routeKind.requiresApiKey() || routeKey.isNotBlank()) {
                                            "Для роли " + agent.name + " не сохранён API-ключ профиля " +
                                                routeKind.displayName + "."
                                        }
                                        CouncilModelRoute(
                                            gateway = createModelGateway(routeKind),
                                            endpoint = routeEndpoint,
                                            model = profile.model,
                                            apiKey = routeKey
                                        )
                                    }
                                }
                            ).run(
                                goal = prompt,
                                endpoint = endpoint,
                                model = model,
                                apiKey = activeKey.orEmpty(),
                                agents = councilAgents
                            )
                            withContext(Dispatchers.IO) {
                                result.agentResults.forEach { journal.saveAgentResult(missionId!!, it) }
                            }
                            answer = result.synthesis.orEmpty()
                            if (result.synthesis.isNullOrBlank()) {
                                withContext(Dispatchers.IO) {
                                    journal.transition(
                                        missionId!!,
                                        MissionStatus.FAILED,
                                        "Council не сформировал проверенный итог",
                                        error = result.error ?: "Пустой итог синтеза"
                                    )
                                }
                            } else {
                                withContext(Dispatchers.IO) {
                                    journal.transition(missionId!!, MissionStatus.VERIFYING, "Результаты агентов собраны")
                                    journal.transition(
                                        missionId!!,
                                        MissionStatus.COMPLETED,
                                        "Синтез завершён",
                                        result = result.synthesis
                                    )
                                }
                            }
                            recentMissions = withContext(Dispatchers.IO) { journal.getRecentMissions(8) }
                            status = result.error ?: if (result.completed) {
                                "Council завершил миссию: ${result.agentResults.count { it.status.name == "COMPLETED" }} агентов успешно."
                            } else {
                                "Council не смог сформировать итог."
                            }
                            if (answer.isBlank() && result.agentResults.isNotEmpty()) {
                                answer = result.agentResults.joinToString("\n\n") {
                                    "## ${it.agent.name}\n${it.output.ifBlank { it.error.orEmpty() }}"
                                }
                            }
                        } else {
                            gateway.stream(
                                CompletionRequest(
                                    endpoint = endpoint,
                                    model = model,
                                    apiKey = activeKey.orEmpty(),
                                    messages = listOf(
                                        ProviderMessage("system", "Ты NEX — помощник экосистемы NEXUS. Отвечай точно, отделяй факты от предположений."),
                                        ProviderMessage("user", prompt)
                                    )
                                )
                            ).collect { chunk -> answer += chunk }
                            withContext(Dispatchers.IO) {
                                journal.transition(missionId!!, MissionStatus.VERIFYING, "Ответ потока получен")
                                journal.transition(
                                    missionId!!,
                                    MissionStatus.COMPLETED,
                                    "Запрос завершён",
                                    result = answer
                                )
                                journal.getRecentMissions(8)
                            }.also { recentMissions = it }
                            status = "Ответ получен"
                        }
                    } catch (cancelled: CancellationException) {
                        withContext(NonCancellable + Dispatchers.IO) {
                            missionId?.let { id ->
                                runCatching {
                                    journal.transition(
                                        id,
                                        MissionStatus.INTERRUPTED,
                                        "Задача была отменена или экран закрыт"
                                    )
                                }
                            }
                            journal.getRecentMissions(8)
                        }.also { recentMissions = it }
                        throw cancelled
                    } catch (error: Exception) {
                        withContext(Dispatchers.IO) {
                            missionId?.let { id ->
                                runCatching {
                                    journal.transition(
                                        id,
                                        MissionStatus.FAILED,
                                        "Ошибка выполнения",
                                        error = error.message ?: error::class.java.simpleName
                                    )
                                }
                            }
                            journal.getRecentMissions(8)
                        }.also { recentMissions = it }
                        status = error.message ?: "Ошибка выполнения запроса"
                    } finally {
                        isRunning = false
                        activeRun = null
                    }
                }
            },
            enabled = !isRunning && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRunning) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(4.dp))
                Text("Выполняется…")
            } else {
                Text(if (councilMode) "Запустить Council" else "Отправить запрос")
            }
        }

        if (isRunning) {
            TextButton(onClick = { activeRun?.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text("Остановить текущую задачу")
            }
        }
        Text(status, style = MaterialTheme.typography.labelMedium)
        if (recentMissions.isNotEmpty()) {
            Text("ИСТОРИЯ МИССИЙ", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            recentMissions.forEach { mission ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(mission.goal, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            "${mission.status} • ${mission.updatedAt}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        mission.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        if (answer.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    answer,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}

private fun defaultProviderEndpoint(kind: ProviderKind): String = when (kind) {
    ProviderKind.OPENAI_COMPATIBLE, ProviderKind.OPENAI -> "https://api.openai.com/v1"
    ProviderKind.DEEPSEEK -> "https://api.deepseek.com"
    ProviderKind.OPENROUTER -> "https://openrouter.ai/api/v1"
    ProviderKind.GROQ -> "https://api.groq.com/openai/v1"
    ProviderKind.MISTRAL -> "https://api.mistral.ai/v1"
    ProviderKind.TOGETHER -> "https://api.together.ai/v1"
    ProviderKind.FIREWORKS -> "https://api.gofireworks.org/v1"
    ProviderKind.OLLAMA -> ""
    ProviderKind.ANTHROPIC -> "https://api.anthropic.com/v1"
    ProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
}
