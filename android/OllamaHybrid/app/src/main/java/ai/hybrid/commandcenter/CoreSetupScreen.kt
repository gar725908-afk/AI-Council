package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.InstalledNexusModel
import ai.hybrid.commandcenter.nexus.LanScanner
import ai.hybrid.commandcenter.nexus.ModelExecutionTarget
import ai.hybrid.commandcenter.nexus.ModelRuntimeKind
import ai.hybrid.commandcenter.nexus.NexusModelCatalog
import ai.hybrid.commandcenter.nexus.NexusModelDescriptor
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.defaultProviderEndpoint
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudProgress
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.HudShapes
import ai.hybrid.commandcenter.ui.HudTextField
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.NexHoloStage
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusTypography
import ai.hybrid.commandcenter.ui.hud
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.util.Locale

enum class SetupPath { PHONE, PC, CLOUD }

private enum class SetupStep { CHOOSE, PHONE, PC, CLOUD, DONE }

/** Cloud providers offered in the wizard, with the page where the owner gets a key. */
internal data class CloudOption(val kind: ProviderKind, val title: String, val note: String, val keyUrl: String?)

internal val cloudOptions = listOf(
    CloudOption(ProviderKind.OPENROUTER, "OpenRouter", "сотни моделей, есть бесплатные", "https://openrouter.ai/keys"),
    CloudOption(ProviderKind.DEEPSEEK, "DeepSeek", "сильная и недорогая", "https://platform.deepseek.com/api_keys"),
    CloudOption(ProviderKind.GROQ, "Groq", "очень быстрые ответы", "https://console.groq.com/keys"),
    CloudOption(ProviderKind.GEMINI, "Gemini", "Google AI Studio", "https://aistudio.google.com/apikey"),
    CloudOption(ProviderKind.OPENAI, "OpenAI", "GPT", "https://platform.openai.com/api-keys"),
    CloudOption(ProviderKind.ANTHROPIC, "Anthropic", "Claude", "https://console.anthropic.com/settings/keys"),
    CloudOption(ProviderKind.MISTRAL, "Mistral", "европейские модели", "https://console.mistral.ai/api-keys"),
    CloudOption(ProviderKind.XAI, "xAI", "Grok", "https://console.x.ai"),
    CloudOption(ProviderKind.TOGETHER, "Together", "открытые модели", "https://api.together.ai/settings/api-keys"),
    CloudOption(ProviderKind.FIREWORKS, "Fireworks", "открытые модели", "https://fireworks.ai/account/api-keys"),
    CloudOption(ProviderKind.CEREBRAS, "Cerebras", "быстрый вывод", "https://cloud.cerebras.ai"),
    CloudOption(ProviderKind.NVIDIA, "NVIDIA", "API Catalog", "https://build.nvidia.com"),
    CloudOption(ProviderKind.OPENAI_COMPATIBLE, "Свой сервер", "любой OpenAI-совместимый", null)
)

/**
 * Picks a sensible default from a provider's model list so the owner can
 * connect with one tap; the full list stays available to choose another.
 */
internal fun preferredModel(kind: ProviderKind, models: List<String>): String? {
    if (models.isEmpty()) return null
    val hints = when (kind) {
        ProviderKind.OPENROUTER -> listOf(":free", "deepseek", "llama")
        ProviderKind.DEEPSEEK -> listOf("deepseek-chat")
        ProviderKind.GROQ -> listOf("llama-3.3-70b", "llama")
        ProviderKind.GEMINI -> listOf("flash", "gemini")
        ProviderKind.OPENAI -> listOf("mini", "gpt")
        ProviderKind.ANTHROPIC -> listOf("sonnet", "haiku")
        ProviderKind.MISTRAL -> listOf("mistral-small", "mistral")
        ProviderKind.OLLAMA -> listOf("qwen", "gemma", "llama")
        else -> emptyList()
    }
    for (hint in hints) models.firstOrNull { it.contains(hint, ignoreCase = true) }?.let { return it }
    return models.first()
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.ROOT, "%.1f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.0f МБ", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.ROOT, "%.0f КБ", bytes / 1024.0)
}

/**
 * «Подключение ядра»: the first-run wizard that makes NEX actually answer.
 * Three honest paths — a model on the phone, Ollama on the owner's PC, or a
 * cloud provider by key — each ends with a real check before it is saved.
 */
@Composable
fun CoreSetupScreen(
    chat: ChatController,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    startPath: SetupPath? = null,
    onFinish: () -> Unit = onClose
) {
    var step by remember {
        mutableStateOf(
            when (startPath) {
                SetupPath.PHONE -> SetupStep.PHONE
                SetupPath.PC -> SetupStep.PC
                SetupPath.CLOUD -> SetupStep.CLOUD
                null -> SetupStep.CHOOSE
            }
        )
    }
    var doneLine by remember { mutableStateOf("") }
    BackHandler { if (step == SetupStep.CHOOSE || step == SetupStep.DONE) onClose() else step = SetupStep.CHOOSE }
    val finish: (String) -> Unit = { line -> doneLine = line; step = SetupStep.DONE }

    ToolPage("Подключение ядра", onBack = {
        if (step == SetupStep.CHOOSE || step == SetupStep.DONE) onClose() else step = SetupStep.CHOOSE
    }, modifier = modifier.testTag("core-setup")) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            when (step) {
                SetupStep.CHOOSE -> ChooseStep { path ->
                    step = when (path) {
                        SetupPath.PHONE -> SetupStep.PHONE
                        SetupPath.PC -> SetupStep.PC
                        SetupPath.CLOUD -> SetupStep.CLOUD
                    }
                }
                SetupStep.PHONE -> PhoneStep(chat, finish)
                SetupStep.PC -> PcStep(chat, finish)
                SetupStep.CLOUD -> CloudStep(chat, finish)
                SetupStep.DONE -> DoneStep(doneLine, onFinish)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ChooseStep(onPick: (SetupPath) -> Unit) {
    Text(
        "Где будет думать NEX?",
        style = CyberText.Title.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
        color = Cyber.Text
    )
    Text(
        "Выбери один путь — потом его можно сменить в «Настройках». Все ключи хранятся зашифрованными в Android Keystore.",
        style = NexusTypography.bodyMedium, color = Cyber.TextDim
    )
    PathCard(
        SetupPath.PHONE, NexIcons.Smartphone, "На телефоне", Cyber.Cyan, "01",
        "Работает без интернета и без ключей. 12 моделей от 0.4 до 5.7 ГБ, или запусти уже скачанную.", "Офлайн · приватно", onPick
    )
    PathCard(
        SetupPath.PC, NexIcons.Laptop, "На компьютере", Cyber.Violet, "02",
        "Ollama на твоём ПК в той же Wi-Fi сети — самые мощные локальные модели бесплатно.", "Wi-Fi · Ollama", onPick
    )
    PathCard(
        SetupPath.CLOUD, NexIcons.Cloud, "В облаке", Cyber.Magenta, "03",
        "OpenRouter, DeepSeek, Groq, Gemini и другие. Нужен API-ключ сервиса.", "Интернет · ключ", onPick
    )
}

@Composable
private fun PathCard(
    path: SetupPath,
    @DrawableRes icon: Int,
    title: String,
    accent: Color,
    index: String,
    text: String,
    tag: String,
    onPick: (SetupPath) -> Unit
) {
    HudPanel(
        Modifier.fillMaxWidth().testTag("path-" + path.name.lowercase()),
        accent = accent, title = tag, index = index, onClick = { onPick(path) }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(52.dp).clip(HudShapes.Small).background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.6f), HudShapes.Small),
                contentAlignment = Alignment.Center
            ) { NexIcon(icon, tint = accent, size = 26.dp) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title.uppercase(), style = CyberText.Heading.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = Cyber.Text)
                Text(text, style = NexusTypography.bodySmall, color = Cyber.TextDim)
            }
            NexIcon(NexIcons.ChevronRight, tint = accent, size = 20.dp)
        }
    }
}

// ---------------------------------------------------------------- phone

@Composable
private fun PhoneStep(chat: ChatController, finish: (String) -> Unit) {
    HudSectionTitle("ИИ на телефоне", accent = Cyber.Cyan)
    Text(
        "Выбери модель по силе телефона: лёгкие летают везде, максимальным нужен флагман. Файл качается один раз с Hugging Face и проверяется по SHA-256, дальше NEX отвечает без интернета.",
        style = NexusTypography.bodyMedium, color = Cyber.TextDim
    )
    PhoneModelHub(chat = chat, onActivated = { record -> finish(record.displayName + " · на телефоне") })
}

// ---------------------------------------------------------------- PC (Ollama)

@Composable
private fun PcStep(chat: ChatController, finish: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(if (chat.providerKind == ProviderKind.OLLAMA) chat.endpoint else "") }
    var scanning by remember { mutableStateOf(false) }
    var scanProgress by remember { mutableFloatStateOf(0f) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var checking by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }

    fun check() {
        val endpoint = LanScanner.normalizeOllamaEndpoint(address)
        if (endpoint.isBlank()) { error = "Укажи адрес компьютера."; return }
        address = endpoint
        scope.launch {
            checking = true
            error = null
            models = emptyList()
            try {
                val list = chat.modelCatalog.listModels(ProviderKind.OLLAMA, endpoint, "")
                models = list
                selected = preferredModel(ProviderKind.OLLAMA, list)
                checked = true
                if (list.isEmpty()) error = "Ollama работает, но моделей на ПК пока нет — скачай одну кнопкой ниже."
            } catch (e: Exception) {
                error = ChatController.friendlyError(e)
            } finally {
                checking = false
            }
        }
    }

    HudSectionTitle("Ollama на компьютере", accent = Cyber.Violet)
    HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Violet, title = "Подготовь компьютер", index = "3 шага") {
        Step("1", "Установи Ollama с сайта ollama.com и запусти.")
        Step("2", "Открой доступ из сети: в Windows добавь переменную среды OLLAMA_HOST = 0.0.0.0 и перезапусти Ollama.")
        Step("3", "Нажми «Проверить» — модель можно скачать на ПК прямо отсюда, без терминала.")
        Text("Телефон и компьютер должны быть в одной Wi-Fi сети.", style = NexusTypography.bodySmall, color = Cyber.TextDim)
    }
    HudTextField(
        address, { address = it.take(300) }, label = "Адрес компьютера",
        placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go,
        onDone = { check() }, accent = Cyber.Violet, testTag = "ollama-address"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HudButton(if (checking) "Проверяю…" else "Проверить", { check() }, Modifier.weight(1f).testTag("ollama-check"),
            icon = NexIcons.Activity, accent = Cyber.Violet, enabled = !checking && !scanning)
        HudButton(
            if (scanning) "Ищу…" else "Найти сам", {
                scope.launch {
                    scanning = true
                    error = null
                    found = null
                    scanProgress = 0f
                    found = runCatching {
                        LanScanner.scan(onProgress = { done, total -> scanProgress = done.toFloat() / total.coerceAtLeast(1) })
                    }.getOrElse { emptyList() }
                    scanning = false
                    found?.singleOrNull()?.let { address = it; check() }
                }
            },
            Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.Radar, accent = Cyber.Violet,
            enabled = !scanning && !checking
        )
    }
    if (scanning) {
        HudProgress(scanProgress, accent = Cyber.Violet)
        HudLabel("Проверяю адреса своей Wi-Fi сети на порту 11434…", color = Cyber.Violet)
    }
    found?.let { list ->
        if (list.isEmpty()) {
            Text(
                "В сети не нашёл Ollama. Проверь шаг 2 (OLLAMA_HOST = 0.0.0.0) и брандмауэр Windows, либо введи адрес вручную.",
                style = NexusTypography.bodySmall, color = Cyber.Amber
            )
        } else if (list.size > 1) {
            HudLabel("Найдено серверов: " + list.size, color = Cyber.Violet)
            list.forEach { server -> OptionRow(server, server == address, Cyber.Violet) { address = server; check() } }
        }
    }
    ModelChoice(models, selected, Cyber.Violet) { selected = it }
    if (checked) {
        OllamaOffersPanel(address, models) { tag ->
            scope.launch {
                runCatching { chat.modelCatalog.listModels(ProviderKind.OLLAMA, address, "") }
                    .onSuccess { list ->
                        models = list
                        selected = list.firstOrNull { it.equals(tag, ignoreCase = true) } ?: preferredModel(ProviderKind.OLLAMA, list)
                    }
            }
        }
    }
    if (models.isNotEmpty()) {
        HudButton(
            "Подключить", {
                val model = selected ?: return@HudButton
                scope.launch {
                    runCatching { chat.applyProvider(ProviderKind.OLLAMA, address, model, null) }
                        .onSuccess { finish("$model · на компьютере") }
                        .onFailure { error = it.message }
                }
            },
            Modifier.fillMaxWidth().testTag("ollama-connect"), icon = NexIcons.Zap, accent = Cyber.Yellow, enabled = selected != null
        )
    }
    error?.let { ErrorLine(it) }
}

@Composable
private fun Step(number: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(number, style = CyberText.Label, color = Cyber.Violet)
        Text(text, style = NexusTypography.bodyMedium, color = Cyber.Text)
    }
}

// ---------------------------------------------------------------- cloud

@Composable
private fun CloudStep(chat: ChatController, finish: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var option by remember { mutableStateOf(cloudOptions.first()) }
    var key by remember { mutableStateOf("") }
    var customEndpoint by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf(emptyList<String>()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var savedKey by remember { mutableStateOf(false) }
    LaunchedEffect(option) {
        savedKey = chat.storedKey(option.kind).isNotBlank()
        models = emptyList()
        selected = null
        error = null
    }

    HudSectionTitle("Облачный ИИ", accent = Cyber.Magenta)
    Text("Выбери сервис:", style = NexusTypography.bodyMedium, color = Cyber.TextDim)
    for (row in cloudOptions.chunked(2)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (o in row) {
                val active = o == option
                Column(
                    Modifier
                        .weight(1f)
                        .hud(if (active) Cyber.Magenta else Cyber.Violet, cut = 8.dp, borderAlpha = if (active) 0.8f else 0.25f, accents = active)
                        .clickable { option = o }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(o.title.uppercase(), style = CyberText.Heading, color = if (active) Cyber.Magenta else Cyber.Text, maxLines = 1)
                    Text(o.note, style = NexusTypography.bodySmall, color = Cyber.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
    val custom = option.kind == ProviderKind.OPENAI_COMPATIBLE
    if (custom) {
        HudTextField(customEndpoint, { customEndpoint = it.take(2_048) }, label = "Адрес API",
            placeholder = "https://сервер/v1", keyboardType = KeyboardType.Uri, accent = Cyber.Magenta)
    }
    HudTextField(
        key, { key = it.take(16_384) },
        label = if (savedKey) "Ключ сохранён · можно ввести новый" else if (custom) "API-ключ (если нужен)" else "API-ключ " + option.title,
        placeholder = if (savedKey) "••••••••" else "вставь ключ сюда", secret = true, accent = Cyber.Magenta, testTag = "cloud-key"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HudButton(
            if (checking) "Проверяю…" else "Проверить", {
                scope.launch {
                    checking = true
                    error = null
                    try {
                        val candidate = key.ifBlank { chat.storedKey(option.kind) }
                        val endpoint = if (custom) customEndpoint else defaultProviderEndpoint(option.kind)
                        val list = chat.modelCatalog.listModels(option.kind, endpoint, candidate)
                        models = list
                        selected = preferredModel(option.kind, list)
                    } catch (e: Exception) {
                        error = ChatController.friendlyError(e)
                    } finally {
                        checking = false
                    }
                }
            },
            Modifier.weight(1f), icon = NexIcons.Activity, accent = Cyber.Magenta, enabled = !checking
        )
        option.keyUrl?.let { url ->
            HudButton("Взять ключ", {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }, Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.ExternalLink, accent = Cyber.Magenta)
        }
    }
    if (models.size > 12) {
        HudTextField(filter, { filter = it.take(60) }, label = "Поиск модели", placeholder = "например: free, llama, gpt", accent = Cyber.Magenta)
    }
    val shown = if (filter.isBlank()) models else models.filter { it.contains(filter.trim(), ignoreCase = true) }
    ModelChoice(shown, selected, Cyber.Magenta) { selected = it }
    if (models.isNotEmpty()) {
        HudButton(
            "Подключить", {
                val model = selected ?: return@HudButton
                scope.launch {
                    runCatching {
                        chat.applyProvider(option.kind, if (custom) customEndpoint else defaultProviderEndpoint(option.kind), model, key.ifBlank { null })
                    }.onSuccess {
                        key = ""
                        finish(model.substringAfterLast('/') + " · в облаке " + option.title)
                    }.onFailure { error = it.message }
                }
            },
            Modifier.fillMaxWidth(), icon = NexIcons.Zap, accent = Cyber.Yellow, enabled = selected != null
        )
    }
    Text(
        "Сообщения уходят выбранному сервису. Если проверка не проходит — неверный ключ, нет интернета или сервис недоступен в твоём регионе.",
        style = NexusTypography.bodySmall, color = Cyber.TextFaint
    )
    error?.let { ErrorLine(it) }
}

@Composable
private fun ModelChoice(models: List<String>, selected: String?, accent: Color, onSelect: (String) -> Unit) {
    if (models.isEmpty()) return
    HudLabel("Модели: " + models.size + " · выбрана отмеченная", color = accent)
    models.take(60).forEach { model -> OptionRow(model, model == selected, accent) { onSelect(model) } }
}

@Composable
private fun OptionRow(text: String, active: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(HudShapes.Small)
            .background(if (active) accent.copy(alpha = 0.14f) else Color(0x99060C19))
            .border(1.dp, if (active) accent.copy(alpha = 0.8f) else Cyber.Line, HudShapes.Small)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(10.dp).background(if (active) accent else Color.Transparent).border(1.dp, accent.copy(alpha = 0.7f)))
        Text(text, style = CyberText.Mono.copy(fontSize = 13.sp),
            color = if (active) Cyber.Text else Cyber.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ErrorLine(text: String) {
    HudPanel(Modifier.fillMaxWidth().testTag("setup-error"), accent = Cyber.Red, title = "Не получилось", index = "ERR") {
        Text(text, style = NexusTypography.bodyMedium, color = Cyber.Text)
    }
}

@Composable
private fun DoneStep(line: String, onClose: () -> Unit) {
    val mode = LocalEffects.current.effective
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NexHoloStage(NexState.SUCCESS, mode, size = 240.dp)
        Text("ЯДРО ПОДКЛЮЧЕНО", style = CyberText.Hero.copy(fontSize = 24.sp),
            textAlign = TextAlign.Center)
        HudChip(line, Cyber.Green, modifier = Modifier.testTag("setup-done"))
        Text("Теперь просто напиши или скажи, что нужно.", style = NexusTypography.bodyMedium, color = Cyber.TextDim, textAlign = TextAlign.Center)
        HudButton("В чат", onClose, Modifier.fillMaxWidth().testTag("setup-finish"), icon = NexIcons.MessageSquare)
    }
}
