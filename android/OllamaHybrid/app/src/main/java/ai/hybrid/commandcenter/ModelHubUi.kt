package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.FoundModelFile
import ai.hybrid.commandcenter.nexus.InstalledNexusModel
import ai.hybrid.commandcenter.nexus.LocalModelScanner
import ai.hybrid.commandcenter.nexus.ModelFit
import ai.hybrid.commandcenter.nexus.ModelLocation
import ai.hybrid.commandcenter.nexus.ModelRuntimeKind
import ai.hybrid.commandcenter.nexus.ModelTier
import ai.hybrid.commandcenter.nexus.NexusModelCatalog
import ai.hybrid.commandcenter.nexus.NexusModelDescriptor
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import ai.hybrid.commandcenter.nexus.OllamaModelHub
import ai.hybrid.commandcenter.nexus.OllamaOffer
import ai.hybrid.commandcenter.nexus.ResourceGovernor
import ai.hybrid.commandcenter.nexus.StorageAccess
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudProgress
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexusTypography
import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call

internal fun tierAccent(tier: ModelTier?): Color = when (tier) {
    ModelTier.LIGHT -> Cyber.Green
    ModelTier.MEDIUM -> Cyber.Cyan
    ModelTier.MAX -> Cyber.Magenta
    null -> Cyber.Violet
}

/** Japanese HUD captions for the tiers: 軽量 light, 標準 standard, 最大 maximum. */
internal fun tierGlyph(tier: ModelTier): String = when (tier) {
    ModelTier.LIGHT -> "軽量"
    ModelTier.MEDIUM -> "標準"
    ModelTier.MAX -> "最大"
}

internal fun fitColor(fit: ModelFit): Color = when (fit) {
    ModelFit.EASY -> Cyber.Green
    ModelFit.OK -> Cyber.Cyan
    ModelFit.TIGHT -> Cyber.Amber
    ModelFit.TOO_BIG -> Cyber.Red
    ModelFit.UNKNOWN -> Cyber.TextDim
}

internal fun totalRamBytes(context: Context): Long = runCatching {
    val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }.totalMem
}.getOrDefault(0L)

/**
 * Everything about models that live on the phone, in one place: the tiered
 * catalog, models already lying on the phone («скачивать не надо») and files
 * the owner picks by hand. Used by the setup wizard and by the Model Center.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhoneModelHub(chat: ChatController?, onActivated: (InstalledNexusModel) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember(context) { NexusModelStorage(context) }
    val ramTotal = remember(context) { totalRamBytes(context) }
    var installed by remember { mutableStateOf(emptyList<InstalledNexusModel>()) }
    var activeId by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<List<FoundModelFile>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var hasAccess by remember { mutableStateOf(StorageAccess.hasFullRead(context)) }
    var busyKey by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressLine by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var call by remember { mutableStateOf<Call?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var scanRound by remember { mutableIntStateOf(0) }

    suspend fun reload() {
        val (list, active) = withContext(Dispatchers.IO) { storage.listInstalled() to storage.activeModel()?.id }
        installed = list
        activeId = active
    }

    LaunchedEffect(Unit) { reload() }
    // Which llama.cpp CPU build this processor got (dotprod, i8mm…) — shown so speed is explainable.
    var cpuBuild by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { cpuBuild = withContext(Dispatchers.IO) { ai.hybrid.commandcenter.nexus.llamaCpuBackends(context) } }
    LaunchedEffect(hasAccess, scanRound) {
        scanning = true
        found = withContext(Dispatchers.IO) { runCatching { LocalModelScanner.scan(context) }.getOrDefault(emptyList()) }
        scanning = false
    }
    LifecycleResumeEffect(Unit) {
        val now = StorageAccess.hasFullRead(context)
        if (now != hasAccess) hasAccess = now
        onPauseOrDispose { }
    }

    suspend fun activate(record: InstalledNexusModel) {
        progressLine = "Включаю…"
        if (chat != null) chat.activateOnDevice(record)
        else withContext(Dispatchers.IO) { storage.activate(record.id) }
        reload()
        onActivated(record)
    }

    fun runTask(key: String, work: suspend () -> Unit) {
        if (busyKey != null) return
        error = null
        busyKey = key
        progress = 0f
        progressLine = "Готовлю…"
        job = scope.launch {
            try {
                work()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                error = ChatController.friendlyError(e)
            } finally {
                busyKey = null
                call = null
            }
        }
    }

    fun hashProgress(done: Long, total: Long) {
        progress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        progressLine = "Сверяю SHA-256: " + formatSize(done) + " из " + formatSize(total)
    }

    fun launchFound(file: FoundModelFile) = runTask("found:" + file.path) {
        val record = storage.registerFound(file) { done, total -> hashProgress(done, total) }
        activate(record)
    }

    fun download(model: NexusModelDescriptor) = runTask(model.id) {
        val manifest = model.artifact ?: return@runTask
        val verdict = withContext(Dispatchers.IO) {
            val diag = DeviceInspector(context.applicationContext).diagnostics()
            ResourceGovernor.evaluate(
                ResourceGovernor.Workload.MODEL_DOWNLOAD,
                diag.batteryPercent, diag.charging, diag.thermalStatus, diag.ramFreeBytes
            )
        }
        if (verdict is ResourceGovernor.Verdict.Deny) throw IllegalStateException(verdict.reason)
        progressLine = "Подключаюсь…"
        val record = storage.download(
            model = model,
            acceptedLicenseId = manifest.licenseId,
            onProgress = { done, total ->
                val t = if (total > 0) total else manifest.sizeBytes
                progress = (done.toFloat() / t).coerceIn(0f, 1f)
                progressLine = formatSize(done) + " из " + formatSize(t)
            },
            onCallCreated = { call = it }
        )
        activate(record)
    }

    val pickLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask("pick") {
            val record = storage.registerDocument(uri) { done, total -> hashProgress(done, total) }
            activate(record)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAccess = granted || StorageAccess.hasFullRead(context)
    }

    fun requestAccess() {
        if (StorageAccess.usesAllFilesAccess) {
            try {
                context.startActivity(StorageAccess.settingsIntent(context))
            } catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(StorageAccess.fallbackSettingsIntent()) }
            }
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ------------------------------------------------------------ already on the phone
    val installedPaths = installed.map { it.path }.toSet()
    val foundFiles = found.orEmpty().filter { it.path !in installedPaths }
    HudSectionTitle("Уже на телефоне", accent = Cyber.Yellow, trailing = "発見")
    HudPanel(Modifier.fillMaxWidth().testTag("found-models"), accent = Cyber.Yellow, title = "Поиск моделей", index = "GGUF · LiteRT") {
        Text(
            "Если модель уже скачана (в «Загрузках», «Документах» или скопирована с ПК), качать её заново не нужно — NEXUS запустит файл прямо там, где он лежит.",
            style = NexusTypography.bodySmall, color = Cyber.TextDim
        )
        cpuBuild?.takeIf { it.isNotBlank() }?.let { HudLabel("Движок GGUF: llama.cpp · " + it, color = Cyber.TextFaint, maxLines = 3) }
        if (!hasAccess) {
            Text(
                if (StorageAccess.usesAllFilesAccess)
                    "Android прячет чужие файлы. Разреши NEXUS «Доступ ко всем файлам» — он только читает модели и ничего не удаляет."
                else "Разреши NEXUS читать память телефона — он только ищет файлы моделей.",
                style = NexusTypography.bodySmall, color = Cyber.Amber
            )
        }
        // Stacked: side by side the labels were cut to «ИСКАТЬ СН…» on a 6-inch phone.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!hasAccess) {
                HudButton("Разрешить поиск", { requestAccess() }, Modifier.fillMaxWidth().testTag("found-allow"),
                    icon = NexIcons.Radar, accent = Cyber.Yellow, height = 44.dp)
            } else {
                HudButton(if (scanning) "Ищу…" else "Искать снова", { scanRound++ }, Modifier.fillMaxWidth().testTag("found-rescan"),
                    icon = NexIcons.Radar, accent = Cyber.Yellow, enabled = !scanning, height = 44.dp)
            }
            HudButton("Указать файл вручную", { pickLauncher.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth().testTag("found-pick"),
                style = HudButtonStyle.Secondary, icon = NexIcons.FolderOpen, accent = Cyber.Yellow,
                enabled = busyKey == null, height = 44.dp)
        }
        when {
            scanning && found == null -> HudLabel("Ищу файлы .gguf и .litertlm…", color = Cyber.Yellow)
            foundFiles.isEmpty() -> HudLabel(
                if (hasAccess) "Готовых моделей не нашёл" else "Пока проверена только папка NEXUS", color = Cyber.TextFaint
            )
            else -> foundFiles.take(12).also { shown ->
                if (shown.any { it.catalogMatch == null }) {
                    // Honest about provenance: only catalog copies are proven by SHA-256.
                    HudLabel(
                        "Файлы не из каталога NEXUS не сверены с эталонным SHA-256 — запускай только скачанное из надёжного источника.",
                        color = Cyber.Amber, maxLines = 3
                    )
                }
            }.forEach { file ->
                FoundModelRow(
                    file = file,
                    ramTotal = ramTotal,
                    busy = busyKey == "found:" + file.path,
                    enabled = busyKey == null,
                    progress = progress,
                    progressLine = progressLine,
                    onLaunch = { launchFound(file) }
                )
            }
        }
        LocalModelScanner.appModelsDir(context)?.let { dir ->
            Text(
                "С компьютера можно просто скопировать модель по USB в папку: " +
                    dir.absolutePath.substringAfter("/storage/emulated/0/").substringAfter("/0/"),
                style = CyberText.Label.copy(letterSpacing = TextUnit.Unspecified), color = Cyber.TextFaint
            )
        }
    }

    // ------------------------------------------------------------ my files (not from the catalog)
    val ownFiles = installed.filter { it.catalogId == null }
    if (ownFiles.isNotEmpty()) {
        HudSectionTitle("Мои файлы", accent = Cyber.Violet, trailing = "私物")
        ownFiles.forEach { record ->
            HudPanel(Modifier.fillMaxWidth().testTag("own-" + record.id), accent = Cyber.Violet, title = record.runtimeLabel(), index = formatSize(record.sizeBytes)) {
                Text(record.displayName.uppercase(), style = CyberText.Heading, color = Cyber.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val fit = ModelFit.evaluate(record.sizeBytes, ramTotal)
                    HudChip(fit.label, fitColor(fit))
                    HudChip(if (record.location == ModelLocation.MANAGED) "В NEXUS" else "Лежит на телефоне", Cyber.TextDim)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (record.id == activeId) HudChip("Активна", Cyber.Green, Modifier.weight(1f))
                    else HudButton("Включить", { runTask(record.id) { activate(record) } }, Modifier.weight(1f),
                        icon = NexIcons.Power, accent = Cyber.Violet, enabled = busyKey == null, height = 42.dp)
                    HudButton(if (record.location == ModelLocation.MANAGED) "Удалить" else "Забыть", {
                        runTask("forget:" + record.id) {
                            withContext(Dispatchers.IO) { storage.remove(record.id) }
                            reload()
                        }
                    }, style = HudButtonStyle.Ghost, accent = Cyber.TextDim, enabled = busyKey == null && record.id != activeId, height = 42.dp)
                }
            }
        }
    }

    // ------------------------------------------------------------ catalog by tiers
    for (tier in ModelTier.entries) {
        val accent = tierAccent(tier)
        HudSectionTitle(tier.title, accent = accent, trailing = tierGlyph(tier))
        HudLabel(tier.range, color = accent.copy(alpha = 0.8f))
        NexusModelCatalog.byTier(tier).forEach { model ->
            val record = installed.firstOrNull { it.id == model.id }
            val foundFile = found.orEmpty().firstOrNull { it.catalogMatch?.id == model.id && it.runnable && it.path !in installedPaths }
            CatalogModelCard(
                model = model,
                ramTotal = ramTotal,
                isActive = record != null && record.id == activeId,
                isInstalled = record != null,
                foundOnPhone = foundFile,
                busy = busyKey == model.id || (foundFile != null && busyKey == "found:" + foundFile.path),
                enabled = busyKey == null,
                progress = progress,
                progressLine = progressLine,
                onActivate = { record?.let { r -> runTask(model.id) { activate(r) } } },
                onLaunchFound = { foundFile?.let { launchFound(it) } },
                onDownload = { download(model) },
                onCancel = {
                    call?.cancel()
                    job?.cancel()
                    busyKey = null
                }
            )
        }
    }
    error?.let {
        Text(it, style = NexusTypography.bodyMedium, color = Cyber.Red, modifier = Modifier.testTag("setup-error"))
    }
}

private fun InstalledNexusModel.runtimeLabel(): String =
    if (runtime == ModelRuntimeKind.LITERT_LM) "LiteRT-LM" else (architecture ?: "GGUF")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FoundModelRow(
    file: FoundModelFile,
    ramTotal: Long,
    busy: Boolean,
    enabled: Boolean,
    progress: Float,
    progressLine: String,
    onLaunch: () -> Unit
) {
    val accent = if (file.catalogMatch != null) tierAccent(file.catalogMatch.tier) else Cyber.Yellow
    Column(
        Modifier.fillMaxWidth().testTag("found-" + file.fileName),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(file.title.uppercase(), style = CyberText.Heading, color = Cyber.Text, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatSize(file.sizeBytes), style = CyberText.Label, color = accent)
        }
        HudLabel(listOf(file.details, file.folderLabel).filter { it.isNotBlank() }.joinToString(" · "), color = Cyber.TextDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (file.catalogMatch != null) HudChip("Из каталога · " + file.catalogMatch.displayName, accent)
            if (file.runnable) {
                val fit = ModelFit.evaluate(file.sizeBytes, ramTotal)
                HudChip(fit.label, fitColor(fit))
            }
        }
        when {
            busy -> {
                HudProgress(progress, accent = accent)
                HudLabel(progressLine, color = accent)
            }
            file.runnable -> HudButton(
                "Запустить без скачивания", onLaunch, Modifier.fillMaxWidth().testTag("found-run-" + file.fileName),
                icon = NexIcons.Zap, accent = accent, enabled = enabled, height = 42.dp
            )
            else -> Text("Не запустится: " + file.problem, style = NexusTypography.bodySmall, color = Cyber.Amber)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CatalogModelCard(
    model: NexusModelDescriptor,
    ramTotal: Long,
    isActive: Boolean,
    isInstalled: Boolean,
    foundOnPhone: FoundModelFile?,
    busy: Boolean,
    enabled: Boolean,
    progress: Float,
    progressLine: String,
    onActivate: () -> Unit,
    onLaunchFound: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit
) {
    val manifest = model.artifact ?: return
    val accent = tierAccent(model.tier)
    val fit = ModelFit.evaluate(manifest.sizeBytes, ramTotal)
    HudPanel(
        Modifier.fillMaxWidth().testTag("model-" + model.id),
        accent = accent,
        title = model.publisher + " · " + model.approximateParameterLabel,
        index = formatSize(manifest.sizeBytes)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(model.displayName.uppercase(), style = CyberText.Heading, color = Cyber.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (model.isNew) HudChip("Новинка", Cyber.Yellow)
        }
        if (model.summary.isNotBlank()) Text(model.summary, style = NexusTypography.bodySmall, color = Cyber.TextDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudChip(fit.label, fitColor(fit))
            if (model.russianTuned) HudChip("Русская", Cyber.Magenta)
            HudChip(if (manifest.runtime == ModelRuntimeKind.LITERT_LM) "LiteRT-LM" else "llama.cpp", Cyber.TextDim)
            if (model.recommendedForBalancedProfile) HudChip("Рекомендую", Cyber.Cyan)
        }
        when {
            isActive -> HudChip("Активна · NEX думает на ней", Cyber.Green)
            busy -> {
                HudProgress(progress, accent = accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudLabel(progressLine, color = accent, modifier = Modifier.weight(1f))
                    HudButton("Отмена", onCancel, style = HudButtonStyle.Ghost, height = 36.dp)
                }
            }
            isInstalled -> HudButton(
                "Включить", onActivate, Modifier.fillMaxWidth().testTag("activate-" + model.id),
                icon = NexIcons.Power, accent = accent, enabled = enabled
            )
            foundOnPhone != null -> HudButton(
                "Найдена на телефоне — запустить", onLaunchFound,
                Modifier.fillMaxWidth().testTag("run-found-" + model.id),
                icon = NexIcons.Zap, accent = Cyber.Yellow, enabled = enabled
            )
            else -> HudButton(
                "Скачать · " + formatSize(manifest.sizeBytes), onDownload,
                Modifier.fillMaxWidth().testTag("download-" + model.id),
                icon = NexIcons.CloudDownload, accent = accent, enabled = enabled
            )
        }
        if (!isInstalled && !isActive && foundOnPhone == null) {
            Text(
                (if (fit == ModelFit.TOO_BIG) "Этому телефону не хватит памяти. " else "") +
                    "Скачивая, ты принимаешь лицензию " + manifest.licenseId + " (" + manifest.source.removePrefix("https://") + ").",
                style = CyberText.Label.copy(letterSpacing = TextUnit.Unspecified),
                color = Cyber.TextFaint
            )
        }
    }
}

/**
 * Recommended Ollama models for the owner's PC. «Скачать на ПК» asks Ollama
 * on that PC to pull the model — the phone only shows progress.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OllamaOffersPanel(endpoint: String, installedTags: List<String>, onPulled: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var pulling by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var line by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var call by remember { mutableStateOf<Call?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    HudSectionTitle("Скачать на ПК", accent = Cyber.Violet, trailing = "≤ 9B")
    Text(
        "Модель скачает сама Ollama на компьютере — телефон только показывает прогресс. Ничего искать и вводить в терминале не нужно.",
        style = NexusTypography.bodySmall, color = Cyber.TextDim
    )
    for (tier in ModelTier.entries) {
        val accent = tierAccent(tier)
        HudLabel(tier.title + " · " + tierGlyph(tier), color = accent)
        OllamaModelHub.offers.filter { it.tier == tier }.forEach { offer ->
            OllamaOfferRow(
                offer = offer,
                accent = accent,
                installed = OllamaModelHub.isInstalled(offer, installedTags),
                pulling = pulling == offer.tag,
                enabled = pulling == null,
                progress = progress,
                line = line,
                onPull = {
                    error = null
                    pulling = offer.tag
                    progress = 0f
                    line = "Подключаюсь к Ollama…"
                    job = scope.launch {
                        try {
                            OllamaModelHub.pull(endpoint, offer.tag, onProgress = {
                                progress = it.fraction
                                line = it.status + if (it.total > 0) " " + formatSize(it.completed) + " из " + formatSize(it.total) else ""
                            }, onCall = { call = it })
                            onPulled(offer.tag)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (e: Exception) {
                            error = ChatController.friendlyError(e)
                        } finally {
                            pulling = null
                            call = null
                        }
                    }
                },
                onCancel = {
                    call?.cancel()
                    job?.cancel()
                    pulling = null
                }
            )
        }
    }
    error?.let { Text(it, style = NexusTypography.bodyMedium, color = Cyber.Red) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OllamaOfferRow(
    offer: OllamaOffer,
    accent: Color,
    installed: Boolean,
    pulling: Boolean,
    enabled: Boolean,
    progress: Float,
    line: String,
    onPull: () -> Unit,
    onCancel: () -> Unit
) {
    HudPanel(
        Modifier.fillMaxWidth().testTag("ollama-offer-" + offer.tag),
        accent = accent, title = offer.note, index = formatSize(offer.downloadBytes),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), spacing = 8.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(offer.title.uppercase(), style = CyberText.Heading, color = Cyber.Text, modifier = Modifier.weight(1f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (offer.isNew) HudChip("Новинка", Cyber.Yellow)
            if (offer.russianTuned) HudChip("Русская", Cyber.Magenta)
        }
        HudLabel(offer.tag, color = Cyber.TextFaint)
        when {
            installed -> HudChip("Уже на ПК ✓", Cyber.Green)
            pulling -> {
                HudProgress(progress, accent = accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudLabel(line, color = accent, modifier = Modifier.weight(1f))
                    HudButton("Стоп", onCancel, style = HudButtonStyle.Ghost, height = 34.dp)
                }
            }
            else -> HudButton(
                "Скачать на ПК", onPull, Modifier.fillMaxWidth().testTag("ollama-pull-" + offer.tag),
                icon = NexIcons.CloudDownload, accent = accent, enabled = enabled, height = 40.dp
            )
        }
    }
}
