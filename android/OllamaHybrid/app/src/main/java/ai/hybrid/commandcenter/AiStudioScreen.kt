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
import androidx.compose.runtime.DisposableEffect
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
import ai.hybrid.commandcenter.nexus.ProviderMessage
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.nexus.MissionJournal
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.ChatMessageRecord
import ai.hybrid.commandcenter.nexus.ProviderModelCatalog
import ai.hybrid.commandcenter.nexus.NexusEvolutionExecutor
import ai.hybrid.commandcenter.nexus.NexusEngineRegistry
import ai.hybrid.commandcenter.nexus.NexusEvolutionRoutingPolicy
import ai.hybrid.commandcenter.nexus.runLocalCouncil
import ai.hybrid.commandcenter.nexus.EvolutionChangeKind
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
    var providerKind by remember { mutableStateOf(ProviderKind.OPENAI_COMPATIBLE) }
    var settingsLoaded by remember { mutableStateOf(false) }
    val engineRegistry = remember(context) { NexusEngineRegistry(context) }
    DisposableEffect(engineRegistry) {
        onDispose { engineRegistry.close() }
    }
    val gateway = remember(providerKind, engineRegistry) { engineRegistry.gateway(providerKind) }
    val usesOnDeviceModel = providerKind == ProviderKind.ON_DEVICE_LITERT ||
        providerKind == ProviderKind.ON_DEVICE_LLAMA_CPP
    val activeOnDeviceModel = if (usesOnDeviceModel) {
        runCatching { engineRegistry.activeModel() }.getOrNull()
    } else null
    val modelCatalog = remember { ProviderModelCatalog() }
    val routingStore = remember(context) { CouncilRoutingStore(context) }
    val councilAgents = remember { CouncilOrchestrator.defaultAgents() }
    val journal = remember(context) { (context.applicationContext as NexusApplication).missionJournal }
    val evolutionExecutor = remember(context) { NexusEvolutionExecutor(context) }
    val scope = rememberCoroutineScope()
    val providerKeyId = AndroidCredentialVault.providerKeyId(providerKind.name)

    var endpoint by remember { mutableStateOf(defaultProviderEndpoint(providerKind)) }
    var model by remember { mutableStateOf("") }
    var providerMenuExpanded by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    var isCheckingModels by remember { mutableStateOf(false) }
    var discoveredModels by remember(providerKind) { mutableStateOf(emptyList<String>()) }
    var agentRoutes by remember { mutableStateOf(emptyMap<String, ProviderKind>()) }
    var agentModelOverrides by remember { mutableStateOf(emptyMap<String, String>()) }
    var expandedAgentRoute by remember { mutableStateOf<String?>(null) }
    var apiKey by remember { mutableStateOf("") }
    var hasSavedKey by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Провайдер не проверен") }
    var isRunning by remember { mutableStateOf(false) }
    var activeRun by remember { mutableStateOf<Job?>(null) }
    var recentMissions by remember { mutableStateOf(emptyList<ai.hybrid.commandcenter.nexus.MissionRecord>()) }
    var activeConversationId by remember { mutableStateOf<String?>(null) }
    var conversationMessages by remember { mutableStateOf(emptyList<ChatMessageRecord>()) }
    LaunchedEffect(settingsStore) {
        val loaded = withContext(Dispatchers.IO) { settingsStore.load() }
        val loadedKind = runCatching { ProviderKind.valueOf(loaded.providerKind) }
            .getOrDefault(ProviderKind.OPENAI_COMPATIBLE)
        providerKind = loadedKind
        endpoint = loaded.endpoint.ifBlank { defaultProviderEndpoint(loadedKind) }
        model = loaded.model
        settingsLoaded = true
    }
    LaunchedEffect(providerKind, settingsLoaded) {
        if (!settingsLoaded) return@LaunchedEffect
        val routes = withContext(Dispatchers.IO) {
            councilAgents.associate { agent ->
                agent.id to (routingStore.load(agent.id) ?: providerKind)
            }
        }
        val overrides = withContext(Dispatchers.IO) {
            councilAgents.associate { agent ->
                agent.id to routingStore.loadModelOverride(agent.id).orEmpty()
            }
        }
        agentRoutes = routes
        agentModelOverrides = overrides
    }
    LaunchedEffect(journal) {
        val recovery = (context.applicationContext as NexusApplication).awaitStartupRecovery()
        if (recovery.isFailure) {
            status = "Не удалось восстановить журнал миссий: " +
                (recovery.exceptionOrNull()?.message ?: "неизвестная ошибка")
            return@LaunchedEffect
        }
        val initial = withContext(Dispatchers.IO) {
            val id = journal.getLatestConversationId() ?: journal.createConversation()
            id to journal.getChatMessages(id, 100)
        }
        activeConversationId = initial.first
        conversationMessages = initial.second
        recentMissions = withContext(Dispatchers.IO) { journal.getRecentMissions(8) }
    }
    LaunchedEffect(providerKind) {
        hasSavedKey = withContext(Dispatchers.IO) {
            credentialVault.get(providerKeyId) != null
        }
    }

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
                    label = { Text(if (usesOnDeviceModel) "Endpoint не используется для on-device модели" else "API endpoint") },
                    placeholder = {
                        Text(defaultProviderEndpoint(providerKind).ifBlank {
                            "http://IP-АДРЕС-ПК:11434/v1"
                        })
                    },
                    singleLine = true,
                    enabled = !usesOnDeviceModel
                )
                OutlinedTextField(
                    value = if (usesOnDeviceModel) activeOnDeviceModel?.id.orEmpty() else model,
                    onValueChange = { if (!usesOnDeviceModel) model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (usesOnDeviceModel) "Активная модель из Model Center" else "Имя модели") },
                    placeholder = {
                        Text(
                            when (providerKind) {
                                ProviderKind.OPENAI_COMPATIBLE -> "точное имя модели совместимого API"
                                ProviderKind.OPENAI -> "точное имя модели из каталога OpenAI"
                                ProviderKind.DEEPSEEK -> "точное имя модели из каталога DeepSeek"
                                ProviderKind.OPENROUTER -> "provider/model из каталога OpenRouter"
                                ProviderKind.GROQ -> "точное имя модели из каталога Groq"
                                ProviderKind.MISTRAL -> "точное имя модели из каталога Mistral"
                                ProviderKind.TOGETHER -> "provider/model из каталога Together"
                                ProviderKind.FIREWORKS -> "accounts/fireworks/models/…"
                                ProviderKind.XAI -> "grok-4.7 или другая доступная модель"
                                ProviderKind.CEREBRAS -> "точное имя модели из каталога Cerebras"
                                ProviderKind.NVIDIA -> "точное имя модели из NVIDIA API Catalog"
                                ProviderKind.OLLAMA -> "имя установленной модели из Ollama"
                                ProviderKind.ANTHROPIC -> "имя Claude модели из Anthropic Console"
                                ProviderKind.GEMINI -> "имя Gemini модели из Google AI Studio"
                                ProviderKind.ON_DEVICE_LITERT -> "активная модель LiteRT-LM из Model Center"
                                ProviderKind.ON_DEVICE_LLAMA_CPP -> "активная GGUF-модель из Model Center"
                            }
                        )
                    },
                    singleLine = true,
                    enabled = !usesOnDeviceModel
                )
                if (usesOnDeviceModel) {
                    Text(
                        activeOnDeviceModel?.let {
                            "ACTIVE • ${it.displayName} • ${it.runtime} • ${it.sizeBytes / (1024L * 1024L)} MB"
                        } ?: "No active model. Install or import a model and activate it in Model Center.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (activeOnDeviceModel == null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !isCheckingModels,
                        onClick = {
                            scope.launch {
                                isCheckingModels = true
                                status = "Проверяю endpoint и получаю список моделей…"
                                try {
                                    val enteredKey = apiKey
                                    val candidateKey = if (enteredKey.isNotBlank()) {
                                        enteredKey
                                    } else {
                                        withContext(Dispatchers.IO) {
                                            credentialVault.get(providerKeyId).orEmpty()
                                        }
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
                            scope.launch {
                                try {
                                    require(usesOnDeviceModel || endpoint.isNotBlank()) { "Укажите API endpoint." }
                                    require(usesOnDeviceModel || model.isNotBlank()) { "Укажите имя модели." }
                                    val enteredKey = apiKey
                                    withContext(Dispatchers.IO) {
                                        settingsStore.save(ProviderSettings(endpoint, model, providerKind.name))
                                        if (enteredKey.isNotBlank()) credentialVault.put(providerKeyId, enteredKey)
                                    }
                                    if (enteredKey.isNotBlank()) {
                                        apiKey = ""
                                        hasSavedKey = true
                                    }
                                    status = "Настройки сохранены для " + providerKind.displayName
                                } catch (error: Exception) {
                                    status = error.message ?: "Не удалось сохранить настройки"
                                }
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { credentialVault.remove(providerKeyId) }
                                hasSavedKey = false
                                apiKey = ""
                                status = "API-ключ этого провайдера удалён"
                            }
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
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                            OutlinedTextField(
                                value = agentModelOverrides[agent.id].orEmpty(),
                                onValueChange = { value ->
                                    agentModelOverrides = agentModelOverrides + (agent.id to value)
                                    routingStore.saveModelOverride(agent.id, value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Модель роли: " + agent.name) },
                                placeholder = { Text("Пусто — использовать модель профиля") },
                                singleLine = true
                            )
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("ДИАЛОГ", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            TextButton(
                enabled = !isRunning,
                onClick = {
                    scope.launch {
                        val id = withContext(Dispatchers.IO) {
                            journal.createConversation()
                        }
                        activeConversationId = id
                        conversationMessages = emptyList()
                        answer = ""
                        prompt = ""
                        status = "Создан новый диалог"
                    }
                }
            ) {
                Text("Новый диалог")
            }
        }
        conversationMessages.takeLast(24).forEach { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.role == "assistant")
                        MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (message.role == "assistant") "NEX" else "ВЫ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it.take(32_000) },
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Задача") },
            placeholder = { Text("Опишите, что должна сделать система…") },
            minLines = 3
        )

        Button(
            onClick = {
                val submittedPrompt = prompt
                activeRun = scope.launch {
                    isRunning = true
                    answer = ""
                    status = if (councilMode) "Совет анализирует задачу…" else "Модель отвечает…"
                    var missionId: String? = null
                    try {
                        require(submittedPrompt.isNotBlank() && submittedPrompt.length <= 32_000) {
                            "Задача должна содержать от 1 до 32 000 символов."
                        }
                        val selectedModelId = if (usesOnDeviceModel) {
                            engineRegistry.activeModel().id
                        } else {
                            model.trim()
                        }
                        require(usesOnDeviceModel || (endpoint.isNotBlank() && selectedModelId.isNotBlank())) {
                            "Сначала укажите endpoint и модель."
                        }
                        missionId = withContext(Dispatchers.IO) {
                            journal.createMission(submittedPrompt, endpoint, selectedModelId).also { id ->
                                journal.transition(id, MissionStatus.QUEUED, "Миссия поставлена в очередь")
                                journal.transition(id, MissionStatus.RUNNING, "Запрос отправлен провайдеру")
                            }
                        }
                        val chatState = withContext(Dispatchers.IO) {
                            val chatId = activeConversationId
                                ?: journal.getLatestConversationId()
                                ?: journal.createConversation()
                            journal.appendChatMessage(chatId, "user", submittedPrompt)
                            chatId to journal.getChatMessages(chatId, 100)
                        }
                        activeConversationId = chatState.first
                        conversationMessages = chatState.second

                        val enteredKey = apiKey
                        val activeKey = withContext(Dispatchers.IO) {
                            if (enteredKey.isNotBlank()) {
                                credentialVault.put(providerKeyId, enteredKey)
                            }
                            credentialVault.get(providerKeyId)
                        }
                        if (enteredKey.isNotBlank()) {
                            apiKey = ""
                            hasSavedKey = true
                        }
                        if (councilMode) {
                            val councilGoal = withContext(Dispatchers.IO) {
                                buildCouncilConversationContext(
                                    chatState.second,
                                    listOf(
                                        evolutionExecutor.activeConfiguration(EvolutionChangeKind.PROMPT_PROFILE),
                                        evolutionExecutor.activeConfiguration(EvolutionChangeKind.SKILL_PACK)
                                    ).filter { it.isNotBlank() }.joinToString("\n\n")
                                )
                            }
                            val result = if (usesOnDeviceModel) {
                                engineRegistry.runLocalCouncil(councilGoal)
                            } else {
                                val activeRoutingPolicy = withContext(Dispatchers.IO) {
                                    evolutionExecutor.activeConfiguration(EvolutionChangeKind.ROUTING_POLICY)
                                        .takeIf { it.isNotBlank() }
                                        ?.let(NexusEvolutionRoutingPolicy::parse)
                                }
                                CouncilOrchestrator(
                                    gateway = gateway,
                                    maxParallelAgents = 3,
                                    routeResolver = { agent ->
                                        val routeKind = activeRoutingPolicy?.routes?.get(agent.id)
                                            ?: routingStore.load(agent.id)
                                            ?: providerKind
                                        val modelOverride = activeRoutingPolicy?.modelOverrides?.get(agent.id)
                                            ?: routingStore.loadModelOverride(agent.id)
                                        if (routeKind == providerKind) {
                                            CouncilModelRoute(
                                                gateway = gateway,
                                                endpoint = endpoint,
                                                model = modelOverride ?: model,
                                                apiKey = activeKey.orEmpty()
                                            )
                                        } else {
                                            val profile = settingsStore.load(routeKind.name)
                                            val localRoute = routeKind == ProviderKind.ON_DEVICE_LITERT ||
                                                routeKind == ProviderKind.ON_DEVICE_LLAMA_CPP
                                            val routeModel = modelOverride ?: profile.model.ifBlank {
                                                if (localRoute) "active-device-model" else ""
                                            }
                                            require(routeModel.isNotBlank()) {
                                                "No model is configured for role " + agent.name + " / " + routeKind.displayName + "."
                                            }
                                            val routeEndpoint = profile.endpoint.ifBlank {
                                                defaultProviderEndpoint(routeKind)
                                            }
                                            require(localRoute || routeEndpoint.isNotBlank()) {
                                                "No endpoint is configured for role " + agent.name + " / " + routeKind.displayName + "."
                                            }
                                            val routeKey = credentialVault.get(
                                                AndroidCredentialVault.providerKeyId(routeKind.name)
                                            ).orEmpty()
                                            require(!routeKind.requiresApiKey() || routeKey.isNotBlank()) {
                                                "No API key is saved for role " + agent.name + " / " + routeKind.displayName + "."
                                            }
                                            CouncilModelRoute(
                                                gateway = engineRegistry.gateway(routeKind),
                                                endpoint = routeEndpoint,
                                                model = routeModel,
                                                apiKey = routeKey
                                            )
                                        }
                                    }
                                ).run(
                                    goal = councilGoal,
                                    endpoint = endpoint,
                                    model = model,
                                    apiKey = activeKey.orEmpty(),
                                    agents = councilAgents
                                )
                            }
                            withContext(Dispatchers.IO) {
                                result.agentResults.forEach { journal.saveAgentResult(missionId!!, it) }
                            }
                            answer = result.synthesis.orEmpty()
                            when {
                                result.needsUserInput -> {
                                    answer = result.error ?: "The local Council needs additional input to continue."
                                    withContext(Dispatchers.IO) {
                                        journal.transition(
                                            missionId!!,
                                            MissionStatus.AWAITING_INPUT,
                                            "Council requested additional user input",
                                            result = answer
                                        )
                                    }
                                }
                                result.paused -> {
                                    answer = result.error ?: "The local Council paused and can be resumed later."
                                    withContext(Dispatchers.IO) {
                                        journal.transition(
                                            missionId!!,
                                            MissionStatus.INTERRUPTED,
                                            "Council execution paused",
                                            result = answer
                                        )
                                    }
                                }
                                result.synthesis.isNullOrBlank() -> {
                                    withContext(Dispatchers.IO) {
                                        journal.transition(
                                            missionId!!,
                                            MissionStatus.FAILED,
                                            "Council did not produce a verified final result",
                                            error = result.error ?: "Empty synthesis"
                                        )
                                    }
                                }
                                else -> {
                                    withContext(Dispatchers.IO) {
                                        journal.transition(missionId!!, MissionStatus.VERIFYING, "Council results collected")
                                        journal.transition(
                                            missionId!!,
                                            MissionStatus.COMPLETED,
                                            "Synthesis completed",
                                            result = result.synthesis
                                        )
                                    }
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
                            if (answer.isNotBlank()) {
                                conversationMessages = withContext(Dispatchers.IO) {
                                    journal.appendChatMessage(chatState.first, "assistant", answer)
                                    journal.getChatMessages(chatState.first, 100)
                                }
                            }
                        } else {
                            val evolutionPrompt = withContext(Dispatchers.IO) {
                                evolutionExecutor.activeConfiguration(EvolutionChangeKind.PROMPT_PROFILE)
                            }
                            val evolutionSkills = withContext(Dispatchers.IO) {
                                evolutionExecutor.activeConfiguration(EvolutionChangeKind.SKILL_PACK)
                            }
                            val providerHistory = buildProviderConversationMessages(
                                chatState.second,
                                listOf(evolutionPrompt, evolutionSkills).filter { it.isNotBlank() }.joinToString("\n\n")
                            )
                            gateway.stream(
                                CompletionRequest(
                                    endpoint = endpoint,
                                    model = model,
                                    apiKey = activeKey.orEmpty(),
                                    messages = providerHistory
                                )
                            ).collect { chunk -> answer += chunk }
                            require(answer.isNotBlank()) { "Провайдер завершил запрос без текстового ответа." }
                            conversationMessages = withContext(Dispatchers.IO) {
                                journal.appendChatMessage(chatState.first, "assistant", answer)
                                journal.transition(missionId!!, MissionStatus.VERIFYING, "Ответ потока получен")
                                journal.transition(
                                    missionId!!,
                                    MissionStatus.COMPLETED,
                                    "Запрос завершён",
                                    result = answer
                                )
                                journal.getChatMessages(chatState.first, 100)
                            }
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
            enabled = !isRunning && activeConversationId != null && prompt.isNotBlank() &&
                (!usesOnDeviceModel || activeOnDeviceModel != null),
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
                        if (mission.status == MissionStatus.AWAITING_INPUT) {
                            TextButton(
                                enabled = !isRunning,
                                onClick = {
                                    prompt = buildString {
                                        appendLine("FOLLOW-UP TO MISSION ${mission.id}")
                                        appendLine("Original task:")
                                        appendLine(mission.goal)
                                        if (!mission.result.isNullOrBlank()) {
                                            appendLine()
                                            appendLine("Manager clarification request:")
                                            appendLine(mission.result)
                                        }
                                        appendLine()
                                        append("User clarification:")
                                    }
                                    status = "Add your clarification, then send it as a new mission with the prior context."
                                }
                            ) {
                                Text("Continue with clarification")
                            }
                        }
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
    ProviderKind.OPENAI_COMPATIBLE -> ""
    ProviderKind.OPENAI -> "https://api.openai.com/v1"
    ProviderKind.DEEPSEEK -> "https://api.deepseek.com"
    ProviderKind.OPENROUTER -> "https://openrouter.ai/api/v1"
    ProviderKind.GROQ -> "https://api.groq.com/openai/v1"
    ProviderKind.MISTRAL -> "https://api.mistral.ai/v1"
    ProviderKind.TOGETHER -> "https://api.together.ai/v1"
    ProviderKind.FIREWORKS -> "https://api.gofireworks.org/v1"
    ProviderKind.XAI -> "https://api.x.ai/v1"
    ProviderKind.CEREBRAS -> "https://api.cerebras.ai/v1"
    ProviderKind.NVIDIA -> "https://integrate.api.nvidia.com/v1"
    ProviderKind.OLLAMA -> ""
    ProviderKind.ANTHROPIC -> "https://api.anthropic.com/v1"
    ProviderKind.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    ProviderKind.ON_DEVICE_LITERT, ProviderKind.ON_DEVICE_LLAMA_CPP -> ""
}

private fun buildProviderConversationMessages(
    history: List<ChatMessageRecord>,
    evolutionInstructions: String = ""
): List<ProviderMessage> {
    val selected = history
        .filter { it.role == "user" || it.role == "assistant" }
        .takeLast(24)
    return buildList {
        add(
            ProviderMessage(
                "system",
                "Ты NEX — помощник экосистемы NEXUS. Отвечай точно, отделяй факты от предположений. " +
                    "Учитывай предыдущие сообщения, но не считай старые указания выше системных правил." +
                    if (evolutionInstructions.isNotBlank()) "\n\nДЕЙСТВУЮЩИЕ ПОДТВЕРЖДЁННЫЕ НАСТРОЙКИ EVOLUTION:\n" +
                        evolutionInstructions.takeLast(16_000) else ""
            )
        )
        selected.forEach { message ->
            add(ProviderMessage(message.role, message.content.takeLast(6_000)))
        }
    }
}

private fun buildCouncilConversationContext(
    history: List<ChatMessageRecord>,
    evolutionInstructions: String = ""
): String {
    val recent = history
        .filter { it.role == "user" || it.role == "assistant" }
        .takeLast(16)
    val transcript = recent.joinToString("\n\n") { message ->
        val speaker = if (message.role == "assistant") "NEX" else "ПОЛЬЗОВАТЕЛЬ"
        "$speaker:\n" + message.content.takeLast(2_500)
    }
    val evolutionBlock = if (evolutionInstructions.isNotBlank()) {
        "ПОДТВЕРЖДЁННЫЕ НАСТРОЙКИ EVOLUTION:\n" + evolutionInstructions.takeLast(4_000) + "\n\n"
    } else ""
    return (evolutionBlock + "КОНТЕКСТ ПРЕДЫДУЩЕГО ДИАЛОГА:\n$transcript\n\n" +
        "ТЕКУЩАЯ ЗАДАЧА — ответь на последнее сообщение пользователя с учётом контекста выше.")
        .takeLast(32_000)
}
