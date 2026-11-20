package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceDiagnostics
import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.BrowserHistoryEntry
import ai.hybrid.commandcenter.nexus.BrowserHistoryStore
import ai.hybrid.commandcenter.nexus.ChatConversationRecord
import ai.hybrid.commandcenter.nexus.EndpointReachability
import ai.hybrid.commandcenter.nexus.ErrorEntry
import ai.hybrid.commandcenter.nexus.HealthCheckResult
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import ai.hybrid.commandcenter.nexus.ProviderHealthCheck
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import ai.hybrid.commandcenter.nexus.VoiceInput
import ai.hybrid.commandcenter.ui.ConstellationBackdrop
import ai.hybrid.commandcenter.ui.CoreOrbital
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.NexMind
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusShapes
import ai.hybrid.commandcenter.ui.OrbitalSegment
import ai.hybrid.commandcenter.ui.SegmentStatus
import ai.hybrid.commandcenter.ui.TelemetryRuler
import ai.hybrid.commandcenter.ui.holoCorners
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything the deck shows, read from real stores in one background pass. */
private data class DeckState(
    val providerSettings: ProviderSettings,
    val localModelName: String?,
    val cloudLabel: String?,
    val installedModelCount: Int,
    val missions: List<MissionRecord>,
    val lastConversation: ChatConversationRecord?,
    val diagnostics: DeviceDiagnostics,
    val lastBrowserPage: BrowserHistoryEntry?,
    val browserVisits: Int,
    val bridgeOperations: Int,
    val lastError: ErrorEntry?,
    val errorCount: Int,
    val readAtMillis: Long
)

private fun loadDeckState(appContext: android.content.Context): DeckState {
    val app = appContext as NexusApplication
    val journal = app.missionJournal
    val storage = NexusModelStorage(appContext)
    val settings = ProviderSettingsStore(appContext).load()
    val cloudConfigured = settings.endpoint.isNotBlank() || settings.model.isNotBlank()
    val cloudLabel = if (cloudConfigured) {
        runCatching { ProviderKind.valueOf(settings.providerKind).displayName }.getOrDefault(settings.providerKind)
    } else {
        null
    }
    val history = BrowserHistoryStore(appContext)
    return DeckState(
        providerSettings = settings,
        localModelName = storage.activeModel()?.displayName,
        cloudLabel = cloudLabel,
        installedModelCount = storage.listInstalled().size,
        missions = journal.getRecentMissions(5),
        lastConversation = journal.getRecentConversations(1).firstOrNull(),
        diagnostics = DeviceInspector(appContext).diagnostics(),
        lastBrowserPage = history.recent(1).firstOrNull(),
        browserVisits = history.count(),
        bridgeOperations = journal.countToolAudit(),
        lastError = app.errorJournal.recent(1).firstOrNull(),
        errorCount = app.errorJournal.count(),
        readAtMillis = System.currentTimeMillis()
    )
}

/**
 * Command Deck per handoff 5.1: Header, Core Orbital with NEX, Command
 * Portal, Active Route, Live Systems, quick actions and Recent Operations.
 * Every colour and every word comes from a real store or measurement;
 * states that were never checked say so.
 */
@Composable
fun CommandDeckScreen(
    onNavigate: (DeckTarget) -> Unit,
    modifier: Modifier = Modifier,
    onCompose: (String, DeckTarget) -> Unit = { _, destination -> onNavigate(destination) },
    autoRefresh: Boolean = true
) {
    val context = LocalContext.current
    val effects = LocalEffects.current
    var state by remember { mutableStateOf<DeckState?>(null) }
    var listening by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<HealthCheckResult?>(null) }
    var overviewOpen by remember { mutableStateOf(false) }

    // Re-read while something is in flight; otherwise refresh slowly.
    LaunchedEffect(Unit) {
        while (true) {
            val fresh = withContext(Dispatchers.IO) { loadDeckState(context.applicationContext) }
            // UI state is written on the main thread, whatever dispatcher resumed us.
            withContext(Dispatchers.Main.immediate) { state = fresh }
            if (!autoRefresh) break
            val busy = DeckModel.runningMission(fresh.missions) != null
            delay(if (busy) 3_000 else 15_000)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(NexusColors.Void),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        val deck = state
        if (deck == null) {
            item {
                DeckHeader(null)
                Text(
                    "Чтение состояния…",
                    color = NexusColors.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
            return@LazyColumn
        }

        val facts = DeckModel.facts(
            nowMillis = System.currentTimeMillis(),
            listening = listening,
            localModelName = deck.localModelName,
            cloudLabel = deck.cloudLabel,
            diagnostics = deck.diagnostics,
            missions = deck.missions,
            lastError = deck.lastError
        )
        val nexState = NexMind.derive(facts)
        val nexLine = NexMind.line(nexState, facts)
        val segments = DeckModel.segments(deck.localModelName, deck.cloudLabel, deck.diagnostics, health, deck.missions)

        item { DeckHeader(nexState) }

        item {
            Box(Modifier.fillMaxWidth().height(232.dp)) {
                ConstellationBackdrop(Modifier.fillMaxSize())
                CoreOrbital(
                    segments = segments,
                    state = nexState,
                    mode = effects.effective,
                    diameter = 184.dp,
                    description = nexLine,
                    modifier = Modifier.align(Alignment.Center),
                    onTap = { overviewOpen = !overviewOpen }
                )
            }
        }

        item { NexSpeech(nexState, nexLine, Modifier.padding(horizontal = 18.dp)) }

        item { SegmentLegend(segments, Modifier.padding(horizontal = 18.dp)) }

        item {
            AnimatedVisibility(visible = overviewOpen) {
                SystemOverview(deck, segments, onNavigate, Modifier.padding(horizontal = 18.dp))
            }
        }

        item {
            CommandPortal(
                onCompose = onCompose,
                onListeningChange = { listening = it },
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }

        item {
            ActiveRoute(
                deck = deck,
                health = health,
                onHealth = { health = it },
                onNavigate = onNavigate,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
        }

        item {
            TelemetryRuler("LIVE SYSTEMS", Modifier.padding(horizontal = 18.dp))
            Spacer(Modifier.height(8.dp))
            LiveSystems(deck, segments, onNavigate, Modifier.padding(horizontal = 18.dp))
        }

        item { QuickActions(deck, onNavigate) }

        item {
            TelemetryRuler("RECENT OPERATIONS", Modifier.padding(horizontal = 18.dp), accent = NexusColors.NeonViolet)
        }
        if (deck.missions.isEmpty() && deck.lastConversation == null) {
            item {
                Text(
                    "Операций ещё не было. Первая задача появится здесь сразу после запуска.",
                    color = NexusColors.TextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 18.dp)
                )
            }
        } else {
            deck.missions.take(3).forEach { mission ->
                item(key = "m-" + mission.id) {
                    MissionRow(mission, Modifier.padding(horizontal = 18.dp).clickable { onNavigate(DeckTarget.COUNCIL) })
                }
            }
            deck.lastConversation?.let { chat ->
                item(key = "c-" + chat.id) {
                    OperationRow(
                        glyph = "✧",
                        title = chat.title.ifBlank { "Диалог" },
                        meta = "AI Studio · " + timeLabel(chat.updatedAt),
                        status = "ДИАЛОГ",
                        statusColor = NexusColors.NeonCyan,
                        modifier = Modifier.padding(horizontal = 18.dp).clickable { onNavigate(DeckTarget.STUDIO) }
                    )
                }
            }
        }

        item {
            Text(
                "Каждое значение здесь прочитано из хранилища или измерено в " + timeLabel(deck.readAtMillis) +
                    ". Непроверенное помечено как непроверенное. Режим эффектов: " +
                    effects.effective.title.lowercase(Locale.ROOT) + ".",
                color = NexusColors.TextMuted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun DeckHeader(nexState: NexState?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("NEXUS", color = NexusColors.TextPrimary, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
            Text(
                "SIRIUS TECHNOLOGY SYSTEM",
                color = NexusColors.NeonCyan,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        if (nexState != null) {
            Row(
                Modifier
                    .border(1.dp, nexState.color.copy(alpha = 0.6f), NexusShapes.Control)
                    .background(nexState.color.copy(alpha = 0.10f), NexusShapes.Control)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(Modifier.size(8.dp).background(nexState.color, CircleShape))
                Text(
                    "NEX · " + nexState.title.uppercase(Locale.ROOT),
                    color = nexState.color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/** NEX speaks: the line is derived from facts by NexMind, never invented. */
@Composable
private fun NexSpeech(state: NexState, line: String, modifier: Modifier = Modifier) {
    Surface(color = NexusColors.DeepSurface, shape = NexusShapes.Card, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(3.dp).height(38.dp).background(state.color, NexusShapes.Control))
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("NEX", color = state.color, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Text(line, color = NexusColors.TextPrimary, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun SegmentLegend(segments: List<OrbitalSegment>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        segments.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { segment ->
                    SegmentChip(segment, Modifier.weight(1f))
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            "Нажмите на ядро — откроется обзор системы.",
            color = NexusColors.TextMuted,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun SegmentChip(segment: OrbitalSegment, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(NexusColors.DeepSurface, NexusShapes.Control)
            .border(1.dp, segment.status.color.copy(alpha = 0.35f), NexusShapes.Control)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.size(8.dp).background(segment.status.color, CircleShape))
        Column {
            Text(segment.title.uppercase(Locale.ROOT), color = NexusColors.TextSecondary, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            Text(
                segment.value,
                color = NexusColors.TextPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** System Overview opened from the Core Orbital (handoff 5.1). */
@Composable
private fun SystemOverview(
    deck: DeckState,
    segments: List<OrbitalSegment>,
    onNavigate: (DeckTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val diag = deck.diagnostics
    DeckPanel("ОБЗОР СИСТЕМЫ", NexusColors.NeonCyan, modifier) {
        segments.forEach { segment ->
            ValueRow(segment.title, segment.value + " · " + segment.status.label, segment.status.color)
        }
        ValueRow("Память", diag.ramFreeBytes?.let { formatGb(it) + " свободно" } ?: "нет данных")
        ValueRow("Хранилище", formatGb(diag.storageFreeBytes) + " из " + formatGb(diag.storageTotalBytes))
        ValueRow("Установлено моделей", if (deck.installedModelCount == 0) "нет" else deck.installedModelCount.toString())
        ValueRow("Ошибок в журнале", deck.errorCount.toString(), if (deck.errorCount > 0) NexusColors.SignalAmber else NexusColors.TextPrimary)
        Text("Измерено в " + timeLabel(diag.measuredAtEpochMillis), color = NexusColors.TextMuted, fontSize = 10.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeckButton("Устройство", NexusColors.NeonCyan) { onNavigate(DeckTarget.DEVICE) }
            DeckButton("Модели", NexusColors.NeonCyan) { onNavigate(DeckTarget.MODELS) }
            if (deck.errorCount > 0) DeckButton("Исправления", NexusColors.SignalAmber) { onNavigate(DeckTarget.REPAIRS) }
        }
    }
}

@Composable
private fun CommandPortal(
    onCompose: (String, DeckTarget) -> Unit,
    onListeningChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var task by remember { mutableStateOf("") }
    var voiceStatus by remember { mutableStateOf<String?>(null) }
    var listening by remember { mutableStateOf(false) }
    val voice = remember { VoiceInput(context.applicationContext) }

    fun setListening(value: Boolean) {
        listening = value
        onListeningChange(value)
    }
    DisposableEffect(Unit) {
        onDispose {
            voice.stop()
            onListeningChange(false)
        }
    }

    fun startVoice() {
        voice.start { event ->
            when (event) {
                VoiceInput.Event.Listening -> { setListening(true); voiceStatus = "Слушаю…" }
                is VoiceInput.Event.Partial -> task = event.text
                is VoiceInput.Event.Final -> { task = event.text; setListening(false); voiceStatus = "Распознано." }
                is VoiceInput.Event.Error -> { setListening(false); voiceStatus = event.reason }
                VoiceInput.Event.Ended -> setListening(false)
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else voiceStatus = "Без разрешения на микрофон голосовой ввод невозможен."
    }

    DeckPanel("COMMAND PORTAL", NexusColors.NeonViolet, modifier.holoCorners(NexusColors.NeonViolet)) {
        OutlinedTextField(
            value = task,
            onValueChange = { task = it.take(32_000) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Опишите задачу — текстом или голосом…", color = NexusColors.TextMuted) },
            minLines = 2,
            shape = NexusShapes.Card,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NexusColors.NeonViolet,
                unfocusedBorderColor = NexusColors.Divider,
                cursorColor = NexusColors.NeonCyan,
                focusedTextColor = NexusColors.TextPrimary,
                unfocusedTextColor = NexusColors.TextPrimary
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeckButton(if (listening) "■ Стоп" else "◉ Голос", if (listening) NexusColors.SignalAmber else NexusColors.NeonCyan) {
                if (listening) {
                    voice.stop(); setListening(false); voiceStatus = "Остановлено."
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    startVoice()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            if (task.isNotBlank()) {
                DeckButton("AI Studio", NexusColors.NeonCyan) {
                    onCompose(task, DeckTarget.STUDIO)
                    task = ""
                }
                DeckButton("Council", NexusColors.NeonViolet) {
                    onCompose(task, DeckTarget.COUNCIL)
                    task = ""
                }
            }
        }
        val voiceLine = voiceStatus
        if (voiceLine != null) {
            Text(voiceLine, color = if (listening) NexusColors.SignalAmber else NexusColors.TextSecondary, fontSize = 10.sp)
        } else if (task.isBlank()) {
            Text(
                "Одна модель — AI Studio. Сложная задача на несколько агентов — Council.",
                color = NexusColors.TextSecondary,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun ActiveRoute(
    deck: DeckState,
    health: HealthCheckResult?,
    onHealth: (HealthCheckResult) -> Unit,
    onNavigate: (DeckTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val network = DeckModel.networkAvailable(deck.diagnostics)
    val settings = deck.providerSettings
    DeckPanel("ACTIVE ROUTE", NexusColors.NeonCyan, modifier) {
        Text(
            DeckModel.routeLine(deck.localModelName, deck.cloudLabel, settings.model, network),
            color = NexusColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (deck.cloudLabel != null) {
            when {
                checking -> StatusLine("Проверка облачного провайдера…", NexusColors.SignalAmber)
                health == null -> StatusLine("Облачный провайдер в этой сессии не проверялся", NexusColors.SignalAmber)
                health.reachability == EndpointReachability.REACHABLE -> StatusLine(
                    "Облако отвечает · ${health.latencyMillis} мс · " + timeLabel(health.checkedAtEpochMillis),
                    NexusColors.SignalGreen
                )
                else -> StatusLine(health.detail + " · " + timeLabel(health.checkedAtEpochMillis), NexusColors.SignalRed)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeckButton("Модели", NexusColors.NeonCyan) { onNavigate(DeckTarget.MODELS) }
            if (settings.endpoint.isNotBlank() && !checking) {
                DeckButton("Проверить облако", NexusColors.SignalGreen) {
                    checking = true
                    scope.launch {
                        onHealth(ProviderHealthCheck.check(settings.endpoint))
                        checking = false
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveSystems(
    deck: DeckState,
    segments: List<OrbitalSegment>,
    onNavigate: (DeckTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val council = segments.first { it.title == "Council" }
    val device = segments.first { it.title == "Устройство" }
    Row(modifier.fillMaxWidth().height(116.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SystemTile(
            glyph = "◎",
            title = "Council",
            value = council.value,
            meta = "миссий: " + deck.missions.size.let { if (it >= 5) "5+" else it.toString() },
            status = council.status,
            modifier = Modifier.weight(1f).clickable { onNavigate(DeckTarget.COUNCIL) }
        )
        val page = deck.lastBrowserPage
        SystemTile(
            glyph = "◍",
            title = "Browser",
            value = page?.title?.ifBlank { page.url } ?: "истории нет",
            meta = "визитов: " + deck.browserVisits,
            status = if (page == null) SegmentStatus.UNKNOWN else SegmentStatus.OK,
            modifier = Modifier.weight(1f).clickable { onNavigate(DeckTarget.BROWSER) }
        )
        SystemTile(
            glyph = "▣",
            title = "Device Bridge",
            value = device.value,
            meta = "операций: " + deck.bridgeOperations,
            status = device.status,
            modifier = Modifier.weight(1f).clickable { onNavigate(DeckTarget.DEVICE) }
        )
    }
}

@Composable
private fun SystemTile(
    glyph: String,
    title: String,
    value: String,
    meta: String,
    status: SegmentStatus,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxHeight()
            .background(NexusColors.DeepSurface, NexusShapes.Card)
            .border(1.dp, status.color.copy(alpha = 0.30f), NexusShapes.Card)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(glyph, color = status.color, fontSize = 18.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(7.dp).background(status.color, CircleShape))
        }
        Text(title, color = NexusColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(value, color = NexusColors.TextSecondary, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text(meta, color = NexusColors.TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun QuickActions(deck: DeckState, onNavigate: (DeckTarget) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        QuickChip("✧ AI Studio", NexusColors.NeonCyan) { onNavigate(DeckTarget.STUDIO) }
        QuickChip("❒ Архив", NexusColors.NeonCyan) { onNavigate(DeckTarget.ARCHIVE) }
        QuickChip("⬡ Модели", NexusColors.NeonViolet) { onNavigate(DeckTarget.MODELS) }
        QuickChip(
            "⚙ Исправления" + if (deck.errorCount > 0) " · " + deck.errorCount else "",
            if (deck.errorCount > 0) NexusColors.SignalAmber else NexusColors.TextSecondary
        ) { onNavigate(DeckTarget.REPAIRS) }
        QuickChip("☰ Настройки", NexusColors.TextSecondary) { onNavigate(DeckTarget.SETTINGS) }
    }
}

@Composable
private fun QuickChip(label: String, accent: Color, onClick: () -> Unit) {
    Text(
        label,
        color = accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .border(1.dp, accent.copy(alpha = 0.45f), NexusShapes.Panel)
            .background(accent.copy(alpha = 0.06f), NexusShapes.Panel)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun MissionRow(mission: MissionRecord, modifier: Modifier = Modifier) {
    val (label, color) = missionStatusLabel(mission.status)
    OperationRow(
        glyph = "◎",
        title = mission.goal,
        meta = "Council · " + timeLabel(mission.updatedAt) +
            (mission.error?.takeIf { it.isNotBlank() }?.let { " · " + it.take(60) } ?: ""),
        status = label,
        statusColor = color,
        modifier = modifier
    )
}

@Composable
private fun OperationRow(
    glyph: String,
    title: String,
    meta: String,
    status: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(NexusColors.DeepSurface, NexusShapes.Card)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(30.dp).border(1.dp, statusColor.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center
        ) { Text(glyph, color = statusColor, fontSize = 13.sp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                color = NexusColors.TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(meta, color = NexusColors.TextSecondary, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(status, color = statusColor, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun DeckPanel(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(color = NexusColors.DeepSurface, shape = NexusShapes.Panel, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                color = accent,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace
            )
            content()
        }
    }
}

@Composable
private fun ValueRow(name: String, value: String, valueColor: Color = NexusColors.TextPrimary) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = NexusColors.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = valueColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp)
}

@Composable
private fun DeckButton(label: String, accent: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = NexusShapes.Control,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
    ) { Text(label, fontSize = 12.sp) }
}

private fun missionStatusLabel(status: MissionStatus): Pair<String, Color> = when (status) {
    MissionStatus.DRAFT -> "ЧЕРНОВИК" to NexusColors.TextSecondary
    MissionStatus.QUEUED -> "В ОЧЕРЕДИ" to NexusColors.SignalAmber
    MissionStatus.RUNNING -> "РАБОТАЕТ" to NexusColors.NeonViolet
    MissionStatus.VERIFYING -> "ПРОВЕРКА" to NexusColors.NeonViolet
    MissionStatus.AWAITING_INPUT -> "ЖДЁТ" to NexusColors.SignalAmber
    MissionStatus.COMPLETED -> "ГОТОВО" to NexusColors.SignalGreen
    MissionStatus.FAILED -> "ОШИБКА" to NexusColors.SignalRed
    MissionStatus.CANCELLED -> "ОТМЕНА" to NexusColors.TextSecondary
    MissionStatus.INTERRUPTED -> "ПРЕРВАНА" to NexusColors.SignalRed
}

private fun formatGb(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f ГБ", gb)
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
