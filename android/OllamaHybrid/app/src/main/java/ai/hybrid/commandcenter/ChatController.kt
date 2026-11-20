package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.ChatConversationRecord
import ai.hybrid.commandcenter.nexus.ChatMessageRecord
import ai.hybrid.commandcenter.nexus.CompletionRequest
import ai.hybrid.commandcenter.nexus.CouncilAgentEvent
import ai.hybrid.commandcenter.nexus.CouncilMissionRunner
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.CouncilRoutingStore
import ai.hybrid.commandcenter.nexus.EvolutionChangeKind
import ai.hybrid.commandcenter.nexus.InstalledNexusModel
import ai.hybrid.commandcenter.nexus.MaterialContext
import ai.hybrid.commandcenter.nexus.MissionJournal
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.ModelRuntimeKind
import ai.hybrid.commandcenter.nexus.NexusEngineRegistry
import ai.hybrid.commandcenter.nexus.NexusEvolutionExecutor
import ai.hybrid.commandcenter.nexus.LocalCommandExecutor
import ai.hybrid.commandcenter.nexus.LocalCommandText
import ai.hybrid.commandcenter.nexus.LocalIntentParser
import ai.hybrid.commandcenter.nexus.NexPetStore
import ai.hybrid.commandcenter.nexus.PendingLocalAction
import ai.hybrid.commandcenter.nexus.isAction
import ai.hybrid.commandcenter.nexus.needsConfirmation
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import ai.hybrid.commandcenter.nexus.PetEvent
import ai.hybrid.commandcenter.nexus.PetRules
import ai.hybrid.commandcenter.nexus.PetSnapshot
import androidx.compose.runtime.snapshots.Snapshot
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.ProviderMessage
import ai.hybrid.commandcenter.nexus.ProviderModelCatalog
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.nexus.ResourceGovernor
import ai.hybrid.commandcenter.nexus.SentenceChunker
import ai.hybrid.commandcenter.nexus.VoiceOutput
import ai.hybrid.commandcenter.nexus.defaultProviderEndpoint
import ai.hybrid.commandcenter.nexus.requiresApiKey
import ai.hybrid.commandcenter.ui.MarkdownParser
import ai.hybrid.commandcenter.ui.NexState
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A file attached to the next message: its extracted text travels as cited context. */
data class ChatAttachment(val name: String, val text: String, val format: String)

/** The owner's personal settings that are not about models: name and voice habits. */
object NexusProfile {
    private const val PREFS = "nexus_profile"

    fun name(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("name", "").orEmpty()

    fun saveName(context: Context, name: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("name", name.trim().take(40).filter { it.code >= 0x20 }).apply()
    }

    /** True once the setup wizard has been shown automatically (first launch only). */
    fun setupPrompted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("setup_prompted", false)

    fun markSetupPrompted(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("setup_prompted", true).apply()
    }

    fun speakVoiceAnswers(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("speak_voice_answers", true)

    fun saveSpeakVoiceAnswers(context: Context, value: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("speak_voice_answers", value).apply()
    }
}

/** Where the active model thinks — shown honestly in the HUD. */
enum class CoreLocation(val label: String) { PHONE("на телефоне"), PC("на компьютере"), CLOUD("в облаке"), NONE("не подключено") }

/**
 * Everything the chat, the council and the settings share: provider
 * configuration, the active conversation, a running request and its honest
 * status. One instance lives at the app root, so a reply keeps streaming
 * while the owner switches tabs.
 */
class ChatController(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    val settingsStore = ProviderSettingsStore(appContext)
    val credentialVault = AndroidCredentialVault(appContext)
    private val sharedEngines = appContext as? NexusApplication
    val engineRegistry: NexusEngineRegistry = sharedEngines?.engines ?: NexusEngineRegistry(appContext)
    val modelCatalog = ProviderModelCatalog()
    val routingStore = CouncilRoutingStore(appContext)
    val councilAgents = CouncilOrchestrator.defaultAgents()
    val journal: MissionJournal = (appContext as NexusApplication).missionJournal
    private val evolutionExecutor = NexusEvolutionExecutor(appContext)
    private val voiceOutput = VoiceOutput(appContext)

    // ---- core configuration ----
    var providerKind by mutableStateOf(ProviderKind.OPENAI_COMPATIBLE); private set
    var endpoint by mutableStateOf(""); private set
    var model by mutableStateOf(""); private set
    var settingsLoaded by mutableStateOf(false); private set
    var hasSavedKey by mutableStateOf(false); private set
    var activeOnDeviceModel by mutableStateOf<InstalledNexusModel?>(null); private set

    // ---- conversation ----
    var conversationId by mutableStateOf<String?>(null); private set
    var messages by mutableStateOf(emptyList<ChatMessageRecord>()); private set
    var streamingAnswer by mutableStateOf(""); private set
    var isRunning by mutableStateOf(false); private set
    /** True while the live «передача…» bubble should show; cleared in the same snapshot that saves the answer. */
    var streamVisible by mutableStateOf(false); private set
    var lastRunFailed by mutableStateOf(false); private set
    var lastError by mutableStateOf<String?>(null); private set
    var status by mutableStateOf(""); private set
    var lastLatencyMs by mutableStateOf<Long?>(null); private set
    var successFlash by mutableStateOf(false); private set

    // ---- NEX the pet: level, mood and achievements from real events ----
    private val petStore = NexPetStore(appContext)
    var pet by mutableStateOf(PetSnapshot()); private set
    /** One-shot news for the home screen: level-ups and new achievements. */
    var petNews by mutableStateOf<String?>(null); private set
    /** Last moment the owner did something (sent, tapped NEX); drives the pet's sleep. */
    var lastActivityAt by mutableStateOf(System.currentTimeMillis()); private set
    private var lastFromVoice = false

    // ---- local commands: answered by the phone itself, no model involved ----
    private val localExecutor = LocalCommandExecutor(appContext)
    /** An alarm/timer understood from speech, waiting for the owner's explicit confirmation. */
    var pendingAction by mutableStateOf<PendingLocalAction?>(null); private set
    var speaking by mutableStateOf(false); private set
    var speakError by mutableStateOf<String?>(null); private set
    var councilMode by mutableStateOf(false)
    var userName by mutableStateOf(""); private set

    // ---- council ----
    var liveAgentEvents by mutableStateOf(emptyMap<String, CouncilAgentEvent>()); private set
    var agentEventLog by mutableStateOf(emptyList<CouncilAgentEvent>()); private set
    var lastCouncilStatus by mutableStateOf<MissionStatus?>(null); private set
    var recentMissions by mutableStateOf(emptyList<MissionRecord>()); private set
    var agentRoutes by mutableStateOf(emptyMap<String, ProviderKind>()); private set
    var agentModelOverrides by mutableStateOf(emptyMap<String, String>()); private set

    private var activeRun: Job? = null
    private var lastSubmitted: Pair<String, Boolean>? = null
    private var speakNextAnswer = false

    init {
        voiceOutput.onSpeakingChanged = { value -> scope.launch(Dispatchers.Main) { speaking = value } }
    }

    val usesOnDeviceModel: Boolean
        get() = providerKind == ProviderKind.ON_DEVICE_LITERT || providerKind == ProviderKind.ON_DEVICE_LLAMA_CPP

    val needsSetup: Boolean
        get() = settingsLoaded && (
            (usesOnDeviceModel && activeOnDeviceModel == null) ||
                (!usesOnDeviceModel && (model.isBlank() || endpoint.isBlank())) ||
                (!usesOnDeviceModel && providerKind.requiresApiKey() && !hasSavedKey)
            )

    val coreLocation: CoreLocation
        get() = when {
            !settingsLoaded || needsSetup -> CoreLocation.NONE
            usesOnDeviceModel -> CoreLocation.PHONE
            providerKind == ProviderKind.OLLAMA -> CoreLocation.PC
            providerKind == ProviderKind.OPENAI_COMPATIBLE && isPrivateEndpoint(endpoint) -> CoreLocation.PC
            else -> CoreLocation.CLOUD
        }

    /** The model's short name for the HUD, or null when nothing is connected. */
    val coreModelName: String?
        get() = when {
            !settingsLoaded || needsSetup -> null
            usesOnDeviceModel -> activeOnDeviceModel?.displayName
            else -> model.substringAfterLast('/').ifBlank { null }
        }

    val nexState: NexState
        get() = when {
            isRunning -> NexState.THINKING
            lastRunFailed -> NexState.ERROR
            successFlash -> NexState.SUCCESS
            !settingsLoaded -> NexState.STANDBY
            needsSetup -> NexState.SETUP
            else -> NexState.STANDBY
        }

    // ---- lifecycle ----

    /**
     * Loads everything the shell needs. Each part fails on its own and says
     * so in the status line — a broken journal or keystore never takes the
     * whole app down with it.
     */
    suspend fun start() {
        runCatching { reloadConfig() }.onFailure {
            settingsLoaded = true
            status = "Не удалось прочитать настройки ядра: " + (it.message ?: it::class.java.simpleName)
        }
        userName = runCatching { withContext(Dispatchers.IO) { NexusProfile.name(appContext) } }.getOrDefault("")
        val recovery = runCatching { (appContext as NexusApplication).awaitStartupRecovery() }
            .getOrElse { Result.failure(it) }
        if (recovery.isFailure) {
            status = "Не удалось восстановить журнал: " + (recovery.exceptionOrNull()?.message ?: "неизвестная ошибка")
        }
        runCatching {
            val initial = withContext(Dispatchers.IO) {
                val id = journal.getLatestConversationId() ?: journal.createConversation()
                id to journal.getChatMessages(id, 100)
            }
            conversationId = initial.first
            messages = initial.second
            recentMissions = withContext(Dispatchers.IO) { journal.getRecentMissions(8) }
        }.onFailure { status = "Журнал чатов недоступен: " + (it.message ?: it::class.java.simpleName) }
        runCatching { loadRoutes() }
        pet = runCatching { withContext(Dispatchers.IO) { petStore.load() } }.getOrDefault(PetSnapshot())
        prewarmOnDevice()
    }

    /**
     * Loads the on-device model in the background right after start (or after
     * switching to it), so the first answer does not include model loading.
     * Failures are silent here: a real request reports them honestly.
     */
    private fun prewarmOnDevice() {
        if (!usesOnDeviceModel || activeOnDeviceModel == null) return
        val kind = providerKind
        scope.launch(Dispatchers.IO) {
            delay(1_200)
            runCatching { (engineRegistry.gateway(kind) as? ai.hybrid.commandcenter.nexus.Prewarmable)?.prewarm() }
        }
    }

    /** Applies a real event to the pet and persists it. */
    fun petEvent(event: PetEvent, onDevice: Boolean = false, modelId: String? = null) {
        val result = PetRules.apply(
            pet, event, System.currentTimeMillis(), NexPetStore.hourNow(), NexPetStore.epochDayNow(), onDevice, modelId
        )
        pet = result.snapshot
        val news = buildList {
            result.leveledUpTo?.let { add("NEX вырос до уровня $it!") }
            result.unlocked.forEach { add("Достижение: «" + it.title + "»") }
        }
        if (news.isNotEmpty()) petNews = news.joinToString(" · ")
        scope.launch(Dispatchers.IO) { runCatching { petStore.save(result.snapshot) } }
    }

    fun touchActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    fun consumePetNews() {
        petNews = null
    }

    suspend fun reloadConfig() {
        val loaded = withContext(Dispatchers.IO) { settingsStore.load() }
        val kind = runCatching { ProviderKind.valueOf(loaded.providerKind) }.getOrDefault(ProviderKind.OPENAI_COMPATIBLE)
        providerKind = kind
        endpoint = loaded.endpoint.ifBlank { defaultProviderEndpoint(kind) }
        model = loaded.model
        hasSavedKey = withContext(Dispatchers.IO) {
            runCatching { credentialVault.get(AndroidCredentialVault.providerKeyId(kind.name)) != null }.getOrDefault(false)
        }
        activeOnDeviceModel = if (kind == ProviderKind.ON_DEVICE_LITERT || kind == ProviderKind.ON_DEVICE_LLAMA_CPP) {
            withContext(Dispatchers.IO) { runCatching { engineRegistry.activeModel() }.getOrNull() }
        } else null
        settingsLoaded = true
    }

    private suspend fun loadRoutes() {
        agentRoutes = withContext(Dispatchers.IO) {
            councilAgents.associate { agent -> agent.id to (routingStore.load(agent.id) ?: providerKind) }
        }
        agentModelOverrides = withContext(Dispatchers.IO) {
            councilAgents.associate { agent -> agent.id to routingStore.loadModelOverride(agent.id).orEmpty() }
        }
    }

    fun dispose() {
        activeRun?.cancel()
        voiceOutput.onSpeakingChanged = null
        voiceOutput.shutdown()
        // The registry is application-scoped (shared with the Mesh worker); only a private one is closed here.
        if (sharedEngines == null) engineRegistry.close()
    }

    // ---- configuration changes (wizard, manual settings) ----

    /** Saves a network provider as the active core. A blank [apiKey] keeps the stored key. */
    suspend fun applyProvider(kind: ProviderKind, endpoint: String, model: String, apiKey: String?) {
        require(kind != ProviderKind.ON_DEVICE_LITERT && kind != ProviderKind.ON_DEVICE_LLAMA_CPP) {
            "Для модели на телефоне используйте установку из списка."
        }
        val base = endpoint.trim().ifBlank { defaultProviderEndpoint(kind) }
        require(base.isNotBlank()) { "Укажите адрес сервера." }
        require(model.isNotBlank()) { "Выберите модель." }
        withContext(Dispatchers.IO) {
            settingsStore.save(ProviderSettings(base, model.trim(), kind.name))
            if (!apiKey.isNullOrBlank()) credentialVault.put(AndroidCredentialVault.providerKeyId(kind.name), apiKey.trim())
        }
        reloadConfig()
        loadRoutes()
        lastRunFailed = false
        lastError = null
        status = "Ядро подключено: " + model
        petEvent(PetEvent.MODEL_ACTIVATED, modelId = kind.name + ":" + model.trim())
    }

    /** Activates an installed on-device model as the core. */
    suspend fun activateOnDevice(installed: InstalledNexusModel) {
        val storage = NexusModelStorage(appContext)
        val record = withContext(Dispatchers.IO) { storage.activate(installed.id) }
        val kind = when (record.runtime) {
            ModelRuntimeKind.LITERT_LM -> ProviderKind.ON_DEVICE_LITERT
            ModelRuntimeKind.LLAMA_CPP -> ProviderKind.ON_DEVICE_LLAMA_CPP
            ModelRuntimeKind.OLLAMA_LAN -> throw IllegalStateException("Ollama подключается как сервер компьютера.")
        }
        withContext(Dispatchers.IO) { settingsStore.save(ProviderSettings("", record.id, kind.name)) }
        reloadConfig()
        loadRoutes()
        lastRunFailed = false
        lastError = null
        status = "Ядро на телефоне: " + record.displayName
        petEvent(PetEvent.MODEL_ACTIVATED, onDevice = true, modelId = record.id)
        prewarmOnDevice()
    }

    suspend fun storedKey(kind: ProviderKind): String =
        withContext(Dispatchers.IO) {
            runCatching { credentialVault.get(AndroidCredentialVault.providerKeyId(kind.name)).orEmpty() }.getOrDefault("")
        }

    suspend fun removeKey(kind: ProviderKind) {
        withContext(Dispatchers.IO) { credentialVault.remove(AndroidCredentialVault.providerKeyId(kind.name)) }
        reloadConfig()
    }

    fun saveName(name: String) {
        NexusProfile.saveName(appContext, name)
        userName = NexusProfile.name(appContext)
    }

    fun saveRoute(agentId: String, kind: ProviderKind) {
        routingStore.save(agentId, kind)
        agentRoutes = agentRoutes + (agentId to kind)
    }

    fun saveModelOverride(agentId: String, value: String) {
        val bounded = value.take(200).filter { ch -> ch.code >= 0x20 && ch.code != 0x7f }
        agentModelOverrides = agentModelOverrides + (agentId to bounded)
        routingStore.saveModelOverride(agentId, bounded)
    }

    // ---- conversation ----

    suspend fun conversations(): List<ChatConversationRecord> =
        withContext(Dispatchers.IO) { journal.getRecentConversations(40) }

    fun newChat() {
        if (isRunning) return
        scope.launch {
            val id = withContext(Dispatchers.IO) { journal.createConversation() }
            conversationId = id
            messages = emptyList()
            streamingAnswer = ""
            lastRunFailed = false
            lastError = null
            pendingAction = null
            status = "Новый чат"
        }
    }

    fun openConversation(id: String) {
        if (isRunning) return
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { journal.getChatMessages(id, 100) }
            conversationId = id
            messages = loaded
            streamingAnswer = ""
            lastRunFailed = false
            lastError = null
        }
    }

    fun cancel() {
        activeRun?.cancel()
    }

    private fun isYes(text: String) = text.lowercase().trim('.', '!', ' ') in setOf("да", "ага", "угу", "давай", "конечно", "подтверждаю", "yes")
    private fun isNo(text: String) = text.lowercase().trim('.', '!', ' ') in setOf("нет", "отмена", "не надо", "отмени", "стоп", "no")

    /** Returns true when the phone answered (or queued a confirmation) without any model. */
    private fun handleLocally(text: String, fromVoice: Boolean): Boolean {
        val now = System.currentTimeMillis()
        pendingAction?.let { pending ->
            if (pending.expired(now)) {
                pendingAction = null
            } else if (isYes(text)) {
                // A bare «да» repeats the parameters; only the button performs the action.
                replyLocally(text, pending.summary + ". Чтобы выполнить, нажми «Подтвердить» на карточке.", fromVoice)
                return true
            } else if (isNo(text)) {
                pendingAction = null
                replyLocally(text, "Отменил: " + pending.summary.replaceFirstChar { it.lowercase() } + ".", fromVoice)
                return true
            }
        }
        val intent = LocalIntentParser.parse(text) ?: return false
        val answer = if (intent.isAction() && intent.needsConfirmation(fromVoice)) {
            val summary = LocalCommandText.summary(intent)
            pendingAction = PendingLocalAction(intent, summary, now)
            "$summary? Подтверди кнопкой ниже — без подтверждения ничего не произойдёт. Жду минуту."
        } else {
            localExecutor.answer(intent)
        }
        replyLocally(text, answer, fromVoice)
        return true
    }

    private fun replyLocally(question: String, answer: String, fromVoice: Boolean) {
        touchActivity()
        lastRunFailed = false
        lastError = null
        scope.launch {
            val chatId = conversationId ?: return@launch
            messages = withContext(Dispatchers.IO) {
                journal.appendChatMessage(chatId, "user", question)
                journal.appendChatMessage(chatId, "assistant", answer)
                journal.getChatMessages(chatId, 100)
            }
            status = "Ответил сам телефон — без ИИ"
            if (fromVoice && NexusProfile.speakVoiceAnswers(appContext)) speak(answer)
            petEvent(if (fromVoice) PetEvent.VOICE_ANSWER else PetEvent.ANSWER)
        }
    }

    /** The owner pressed «Подтвердить» on the pending alarm/timer card. */
    fun confirmPending() {
        val pending = pendingAction ?: return
        pendingAction = null
        val result = if (pending.expired(System.currentTimeMillis())) {
            "Время на подтверждение вышло — повтори команду."
        } else localExecutor.perform(pending.intent)
        scope.launch {
            val chatId = conversationId ?: return@launch
            messages = withContext(Dispatchers.IO) {
                journal.appendChatMessage(chatId, "assistant", result)
                journal.getChatMessages(chatId, 100)
            }
        }
    }

    fun cancelPending() {
        val pending = pendingAction ?: return
        pendingAction = null
        scope.launch {
            val chatId = conversationId ?: return@launch
            messages = withContext(Dispatchers.IO) {
                journal.appendChatMessage(chatId, "assistant", "Отменил: " + pending.summary.replaceFirstChar { it.lowercase() } + ".")
                journal.getChatMessages(chatId, 100)
            }
        }
    }

    fun speak(text: String): String? = voiceOutput.speak(MarkdownParser.plain(text)).also { speakError = it }

    fun stopSpeaking() = voiceOutput.stop()

    fun voices() = voiceOutput.voices()
    val selectedVoice: String? get() = voiceOutput.selectedVoice
    val speechRate: Float get() = voiceOutput.speechRate
    fun selectVoice(name: String?) = voiceOutput.selectVoice(name)
    fun setSpeechRate(rate: Float) = voiceOutput.setRate(rate)

    /**
     * Sends the owner's message. Returns false when the core is not set up yet,
     * so the UI can open the setup instead of failing silently.
     */
    fun send(question: String, attachment: ChatAttachment? = null, fromVoice: Boolean = false): Boolean {
        val text = question.trim()
        if (text.isBlank() && attachment == null) return true
        if (!settingsLoaded || conversationId == null || isRunning) return true
        // Time, date, battery, exact arithmetic, alarms and timers work even before an AI core is connected.
        if (attachment == null && handleLocally(text, fromVoice)) return true
        if (needsSetup) return false
        val prompt = if (attachment != null) {
            MaterialContext.buildPrompt(attachment.name, attachment.text) + text.ifBlank { "Кратко перескажи этот материал." }
        } else text
        speakNextAnswer = fromVoice && NexusProfile.speakVoiceAnswers(appContext)
        lastFromVoice = fromVoice
        touchActivity()
        lastSubmitted = prompt to councilMode
        run(prompt.take(32_000), councilMode)
        return true
    }

    /** Repeats the last request after an error, without writing the question twice. */
    fun retry() {
        val (prompt, council) = lastSubmitted ?: return
        if (isRunning || needsSetup) return
        run(prompt, council, appendUser = false)
    }

    private var spokeEarly = false

    private fun speakSentence(sentence: String) {
        val plain = MarkdownParser.plain(sentence)
        if (plain.isBlank()) return
        speakError = voiceOutput.speak(plain, queue = spokeEarly)
        spokeEarly = true
    }

    private fun run(submittedPrompt: String, council: Boolean, appendUser: Boolean = true) {
        spokeEarly = false
        activeRun = scope.launch {
            isRunning = true
            streamVisible = true
            lastRunFailed = false
            lastError = null
            streamingAnswer = ""
            android.util.Log.i(LOG_TAG, "run start council=$council onDevice=$usesOnDeviceModel")
            status = if (council) "Совет разбирает задачу…" else "NEX отвечает…"
            if (council) {
                liveAgentEvents = emptyMap()
                agentEventLog = emptyList()
                lastCouncilStatus = null
            }
            val started = System.currentTimeMillis()
            var missionId: String? = null
            try {
                require(submittedPrompt.isNotBlank() && submittedPrompt.length <= 32_000) {
                    "Сообщение должно содержать от 1 до 32 000 символов."
                }
                // Resource Governor: measured device state gates heavy work.
                val verdict = withContext(Dispatchers.IO) {
                    val diag = DeviceInspector(appContext).diagnostics()
                    ResourceGovernor.evaluate(
                        workload = if (usesOnDeviceModel) ResourceGovernor.Workload.LOCAL_INFERENCE
                        else ResourceGovernor.Workload.REMOTE_REQUEST,
                        batteryPercent = diag.batteryPercent,
                        charging = diag.charging,
                        thermalStatus = diag.thermalStatus,
                        ramFreeBytes = diag.ramFreeBytes
                    )
                }
                when (verdict) {
                    is ResourceGovernor.Verdict.Deny -> throw IllegalStateException(verdict.reason)
                    is ResourceGovernor.Verdict.Warn -> status = verdict.reason + " " + status
                    ResourceGovernor.Verdict.Allow -> Unit
                }
                val selectedModelId = if (usesOnDeviceModel) {
                    withContext(Dispatchers.IO) { engineRegistry.activeModel().id }
                } else model.trim()
                require(usesOnDeviceModel || (endpoint.isNotBlank() && selectedModelId.isNotBlank())) {
                    "Ядро не подключено: выберите модель."
                }
                missionId = withContext(Dispatchers.IO) {
                    journal.createMission(submittedPrompt, endpoint, selectedModelId).also { id ->
                        journal.transition(id, MissionStatus.QUEUED, "Миссия поставлена в очередь")
                        journal.transition(id, MissionStatus.RUNNING, "Запрос отправлен провайдеру")
                    }
                }
                val chatState = withContext(Dispatchers.IO) {
                    val chatId = conversationId ?: journal.getLatestConversationId() ?: journal.createConversation()
                    if (appendUser) journal.appendChatMessage(chatId, "user", submittedPrompt)
                    chatId to journal.getChatMessages(chatId, 100)
                }
                conversationId = chatState.first
                messages = chatState.second
                val activeKey = withContext(Dispatchers.IO) {
                    credentialVault.get(AndroidCredentialVault.providerKeyId(providerKind.name))
                }
                if (council) {
                    val goal = withContext(Dispatchers.IO) {
                        buildCouncilConversationContext(
                            chatState.second,
                            listOf(
                                evolutionExecutor.activeConfiguration(EvolutionChangeKind.PROMPT_PROFILE),
                                evolutionExecutor.activeConfiguration(EvolutionChangeKind.SKILL_PACK)
                            ).filter { it.isNotBlank() }.joinToString("\n\n")
                        )
                    }
                    val runner = CouncilMissionRunner(
                        journal = journal,
                        engineRegistry = engineRegistry,
                        settingsStore = settingsStore,
                        routingStore = routingStore,
                        credentialVault = credentialVault,
                        evolutionExecutor = evolutionExecutor
                    )
                    val outcome = runner.run(
                        CouncilMissionRunner.Request(
                            missionId = missionId!!,
                            goal = goal,
                            providerKind = providerKind,
                            endpoint = endpoint,
                            model = model,
                            apiKey = activeKey.orEmpty(),
                            agents = councilAgents,
                            useOnDeviceModel = usesOnDeviceModel,
                            onAgentEvent = { event ->
                                liveAgentEvents = liveAgentEvents + (event.agentId to event)
                                agentEventLog = (agentEventLog + event).takeLast(40)
                            }
                        )
                    )
                    streamingAnswer = outcome.answer
                    status = outcome.statusLine
                    recentMissions = withContext(Dispatchers.IO) { journal.getRecentMissions(8) }
                    if (outcome.answer.isNotBlank()) {
                        val saved = withContext(Dispatchers.IO) {
                            journal.appendChatMessage(chatState.first, "assistant", outcome.answer)
                            journal.getChatMessages(chatState.first, 100)
                        }
                        commitAnswer(saved)
                    }
                } else {
                    val instructions = withContext(Dispatchers.IO) {
                        listOf(
                            evolutionExecutor.activeConfiguration(EvolutionChangeKind.PROMPT_PROFILE),
                            evolutionExecutor.activeConfiguration(EvolutionChangeKind.SKILL_PACK)
                        ).filter { it.isNotBlank() }.joinToString("\n\n")
                    }
                    val history = buildProviderConversationMessages(chatState.second, instructions, userName)
                    // Voice questions: start speaking after the first finished sentence, not after the whole answer.
                    val chunker = if (speakNextAnswer) SentenceChunker() else null
                    engineRegistry.gateway(providerKind).stream(
                        CompletionRequest(
                            endpoint = endpoint,
                            model = model,
                            apiKey = activeKey.orEmpty(),
                            messages = history
                        )
                    ).collect { chunk ->
                        streamingAnswer += chunk
                        chunker?.add(chunk)?.forEach { sentence -> speakSentence(sentence) }
                    }
                    chunker?.flush()?.let { speakSentence(it) }
                    require(streamingAnswer.isNotBlank()) { "Модель завершила ответ без текста." }
                    val answer = streamingAnswer
                    val saved = withContext(Dispatchers.IO) {
                        journal.appendChatMessage(chatState.first, "assistant", answer)
                        journal.transition(missionId!!, MissionStatus.VERIFYING, "Ответ потока получен")
                        journal.transition(missionId!!, MissionStatus.COMPLETED, "Запрос завершён", result = answer)
                        journal.getChatMessages(chatState.first, 100)
                    }
                    commitAnswer(saved)
                    status = "Ответ получен"
                }
                lastLatencyMs = System.currentTimeMillis() - started
                val final = messages.lastOrNull()?.takeIf { it.role == "assistant" }?.content
                if (speakNextAnswer && final != null && !spokeEarly) speak(final)
                flashSuccess()
                petEvent(
                    when {
                        council -> PetEvent.COUNCIL_DONE
                        lastFromVoice -> PetEvent.VOICE_ANSWER
                        else -> PetEvent.ANSWER
                    },
                    onDevice = usesOnDeviceModel
                )
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable + Dispatchers.IO) {
                    missionId?.let { id ->
                        runCatching { journal.transition(id, MissionStatus.INTERRUPTED, "Задача остановлена") }
                    }
                    journal.getRecentMissions(8)
                }.also { recentMissions = it }
                status = "Остановлено"
                throw cancelled
            } catch (error: Exception) {
                withContext(Dispatchers.IO) {
                    missionId?.let { id ->
                        runCatching {
                            journal.transition(id, MissionStatus.FAILED, "Ошибка выполнения",
                                error = error.message ?: error::class.java.simpleName)
                        }
                    }
                    journal.getRecentMissions(8)
                }.also { recentMissions = it }
                lastRunFailed = true
                lastError = friendlyError(error)
                status = lastError.orEmpty()
                petEvent(PetEvent.ERROR)
            } finally {
                if (council) lastCouncilStatus = recentMissions.firstOrNull { it.id == missionId }?.status
                speakNextAnswer = false
                Snapshot.withMutableSnapshot {
                    isRunning = false
                    streamVisible = false
                }
                activeRun = null
                android.util.Log.i(LOG_TAG, "run end in ${System.currentTimeMillis() - started} ms failed=$lastRunFailed")
            }
        }
    }

    /**
     * The saved answer replaces the live bubble in one snapshot: no frame can show
     * both the stored message and the still-streaming copy of the same text.
     */
    private fun commitAnswer(saved: List<ChatMessageRecord>) {
        Snapshot.withMutableSnapshot {
            messages = saved
            streamVisible = false
            streamingAnswer = ""
        }
        android.util.Log.i(LOG_TAG, "answer saved (${saved.size} messages)")
    }

    private fun flashSuccess() {
        scope.launch {
            successFlash = true
            delay(2_400)
            successFlash = false
        }
    }

    companion object {
        /** Turns transport errors into a sentence the owner can act on; never hides the cause. */
        fun friendlyError(error: Throwable): String {
            val raw = error.message ?: error::class.java.simpleName
            val lower = raw.lowercase()
            return when {
                "failed to connect" in lower || "connection refused" in lower || "econnrefused" in lower ->
                    "Сервер не отвечает. Проверь, что он запущен и телефон в той же сети. ($raw)"
                "timeout" in lower || "timed out" in lower ->
                    "Сервер слишком долго не отвечает. ($raw)"
                "unable to resolve host" in lower || "no address associated" in lower ->
                    "Нет интернета или адрес сервера указан неверно. ($raw)"
                "401" in lower || "unauthorized" in lower || "invalid api key" in lower || "incorrect api key" in lower ->
                    "Ключ не подошёл: сервис ответил «нет доступа». Проверь ключ в настройках ядра. ($raw)"
                "403" in lower || "forbidden" in lower ->
                    "Сервис запретил доступ — возможно, он недоступен в твоём регионе или у ключа нет прав. ($raw)"
                "429" in lower || "rate limit" in lower ->
                    "Сервис просит подождать: превышен лимит запросов. ($raw)"
                else -> raw
            }
        }

        private fun isPrivateEndpoint(endpoint: String): Boolean {
            val host = runCatching { java.net.URI(endpoint.trim()).host }.getOrNull()?.lowercase() ?: return false
            return host == "localhost" || host.startsWith("10.") || host.startsWith("192.168.") ||
                host.startsWith("127.") || Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) ||
                host.endsWith(".local")
        }
    }
}

private const val LOG_TAG = "NexusChat"

@Composable
fun rememberChatController(): ChatController {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember(context) { ChatController(context, scope) }
    LaunchedEffect(controller) { controller.start() }
    DisposableEffect(controller) { onDispose { controller.dispose() } }
    return controller
}

internal fun buildProviderConversationMessages(
    history: List<ChatMessageRecord>,
    evolutionInstructions: String = "",
    userName: String = ""
): List<ProviderMessage> {
    val selected = history.filter { it.role == "user" || it.role == "assistant" }.takeLast(24)
    return buildList {
        add(
            ProviderMessage(
                "system",
                "Ты NEX — ИИ-помощник экосистемы NEXUS. Отвечай по-русски, если пользователь пишет по-русски. " +
                    "Отвечай точно, отделяй факты от предположений, не выдумывай. " +
                    (if (userName.isNotBlank()) "Пользователя зовут $userName. " else "") +
                    "Учитывай предыдущие сообщения, но не считай старые указания выше системных правил." +
                    if (evolutionInstructions.isNotBlank()) "\n\nДЕЙСТВУЮЩИЕ ПОДТВЕРЖДЁННЫЕ НАСТРОЙКИ EVOLUTION:\n" +
                        evolutionInstructions.takeLast(16_000) else ""
            )
        )
        selected.forEach { message -> add(ProviderMessage(message.role, message.content.takeLast(6_000))) }
    }
}

internal fun buildCouncilConversationContext(history: List<ChatMessageRecord>, evolutionInstructions: String = ""): String {
    val recent = history.filter { it.role == "user" || it.role == "assistant" }.takeLast(16)
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
