package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceDiagnostics
import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.AgentJournalRecord
import ai.hybrid.commandcenter.nexus.BrowserHistoryEntry
import ai.hybrid.commandcenter.nexus.BrowserHistoryStore
import ai.hybrid.commandcenter.nexus.ChatConversationRecord
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
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
import ai.hybrid.commandcenter.ui.CircleIconButton
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.CoreOrbital
import ai.hybrid.commandcenter.ui.Dot
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.GlassCard
import ai.hybrid.commandcenter.ui.HairlineDivider
import ai.hybrid.commandcenter.ui.IconBadge
import ai.hybrid.commandcenter.ui.InfoLine
import ai.hybrid.commandcenter.ui.ListRow
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexMind
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusShapes
import ai.hybrid.commandcenter.ui.NexusText
import ai.hybrid.commandcenter.ui.NodePhase
import ai.hybrid.commandcenter.ui.OrbitalSegment
import ai.hybrid.commandcenter.ui.SectionHeader
import ai.hybrid.commandcenter.ui.SegmentStatus
import ai.hybrid.commandcenter.ui.SegmentedTabs
import ai.hybrid.commandcenter.ui.SoftButton
import ai.hybrid.commandcenter.ui.StatusPill
import ai.hybrid.commandcenter.ui.Tag
import ai.hybrid.commandcenter.ui.glass
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RU = Locale.forLanguageTag("ru")

/** Everything the deck shows, read from real stores in one background pass. */
private data class DeckState(
    val providerSettings: ProviderSettings,
    val localModelName: String?,
    val cloudLabel: String?,
    val installedModelCount: Int,
    val missions: List<MissionRecord>,
    val latestAgentResults: List<AgentJournalRecord>,
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
    val missions = journal.getRecentMissions(5)
    return DeckState(
        providerSettings = settings,
        localModelName = storage.activeModel()?.displayName,
        cloudLabel = cloudLabel,
        installedModelCount = storage.listInstalled().size,
        missions = missions,
        latestAgentResults = missions.firstOrNull()?.let { journal.getAgentResults(it.id) }.orEmpty(),
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
 * Command Deck (handoff 5.1): NEX and the Core Orbital, the prompt, live
 * systems, the active route, quick actions and recent operations. Every
 * colour and word comes from a real store or a measurement; whatever was
 * never checked says so.
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

    val deck = state
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item { TopBar(onNavigate) }

        if (deck == null) {
            item {
                Text(
                    "Читаю состояние системы…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexusColors.TextSecondary
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

        item { Greeting(deck.readAtMillis) }

        item {
            Hero(
                nexState = nexState,
                nexLine = nexLine,
                segments = segments,
                mode = effects.effective,
                overviewOpen = overviewOpen,
                onToggleOverview = { overviewOpen = !overviewOpen },
                deck = deck,
                onNavigate = onNavigate
            )
        }

        item { StatusStrip(segments, deck, onNavigate) }

        item { Composer(onCompose = onCompose, onListeningChange = { listening = it }) }

        item { SectionHeader("Системы") }
        item { SystemsBento(deck, segments, onNavigate) }
        item { RouteCard(deck = deck, health = health, onHealth = { health = it }, onNavigate = onNavigate) }

        item { QuickActions(deck, onNavigate) }

        item {
            SectionHeader(
                "Недавнее",
                action = if (deck.missions.isNotEmpty()) "Все миссии" else null,
                onAction = { onNavigate(DeckTarget.COUNCIL) }
            )
        }
        item { RecentOperations(deck, onNavigate) }

        item {
            Text(
                "Данные прочитаны из журналов и датчиков в " + timeLabel(deck.readAtMillis) +
                    ". Непроверенное помечено как непроверенное.",
                style = MaterialTheme.typography.bodySmall,
                color = NexusColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TopBar(onNavigate: (DeckTarget) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("NEXUS", style = NexusText.Wordmark, modifier = Modifier.weight(1f))
        CircleIconButton(
            NexIcons.Settings2,
            onClick = { onNavigate(DeckTarget.SETTINGS) },
            size = 42.dp,
            contentDescription = "Настройки"
        )
    }
}

@Composable
private fun Greeting(nowMillis: Long) {
    val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
    val greeting = when (calendar.get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Доброе утро"
        in 12..16 -> "Добрый день"
        in 17..22 -> "Добрый вечер"
        else -> "Доброй ночи"
    }
    val date = SimpleDateFormat("EEEE, d MMMM", RU).format(Date(nowMillis)).replaceFirstChar { it.titlecase(RU) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(greeting, style = MaterialTheme.typography.displaySmall, color = NexusColors.TextPrimary)
        Text(date, style = MaterialTheme.typography.bodyMedium, color = NexusColors.TextSecondary)
    }
}

@Composable
private fun Hero(
    nexState: NexState,
    nexLine: String,
    segments: List<OrbitalSegment>,
    mode: EffectsMode,
    overviewOpen: Boolean,
    onToggleOverview: () -> Unit,
    deck: DeckState,
    onNavigate: (DeckTarget) -> Unit
) {
    GlassCard(
        shape = NexusShapes.Sheet,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        spacing = 6.dp
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            CoreOrbital(
                segments = segments,
                state = nexState,
                mode = mode,
                diameter = 232.dp,
                description = nexLine,
                onTap = onToggleOverview
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Tag("NEX · " + nexState.title, nexState.color)
        }
        Text(
            nexLine,
            style = MaterialTheme.typography.titleMedium,
            color = NexusColors.TextPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
        Text(
            if (overviewOpen) "Нажмите на NEX, чтобы свернуть обзор" else "Нажмите на NEX — откроется обзор системы",
            style = MaterialTheme.typography.bodySmall,
            color = NexusColors.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        AnimatedVisibility(visible = overviewOpen) {
            SystemOverview(deck, segments, onNavigate)
        }
    }
}

@Composable
private fun SystemOverview(deck: DeckState, segments: List<OrbitalSegment>, onNavigate: (DeckTarget) -> Unit) {
    val diag = deck.diagnostics
    Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HairlineDivider()
        segments.forEach { segment ->
            InfoLine(segment.title, segment.value + " · " + segment.status.label, valueColor = segment.status.color)
        }
        InfoLine("Свободная память", diag.ramFreeBytes?.let { formatGb(it) } ?: "нет данных")
        InfoLine("Хранилище", formatGb(diag.storageFreeBytes) + " из " + formatGb(diag.storageTotalBytes))
        InfoLine("Установлено моделей", if (deck.installedModelCount == 0) "нет" else deck.installedModelCount.toString())
        InfoLine(
            "Ошибок в журнале",
            deck.errorCount.toString(),
            valueColor = if (deck.errorCount > 0) NexusColors.SignalAmber else NexusColors.TextPrimary
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            SoftButton("Устройство", { onNavigate(DeckTarget.DEVICE) }, icon = NexIcons.Smartphone)
            if (deck.errorCount > 0) {
                SoftButton("Исправления", { onNavigate(DeckTarget.REPAIRS) }, icon = NexIcons.Wrench, tint = NexusColors.SignalAmber)
            } else {
                SoftButton("Модели", { onNavigate(DeckTarget.MODELS) }, icon = NexIcons.Boxes)
            }
        }
    }
}

@DrawableRes
private fun segmentIcon(segment: OrbitalSegment, diagnostics: DeviceDiagnostics): Int = when (segment.title) {
    "Модель" -> NexIcons.Cpu
    "Сеть" -> if (segment.status == SegmentStatus.OK) NexIcons.Wifi else NexIcons.WifiOff
    "Устройство" -> when {
        diagnostics.charging == true -> NexIcons.BatteryCharging
        (diagnostics.batteryPercent ?: 100) < 20 -> NexIcons.BatteryLow
        (diagnostics.batteryPercent ?: 100) < 70 -> NexIcons.BatteryMedium
        else -> NexIcons.BatteryFull
    }
    else -> NexIcons.Orbit
}

@Composable
private fun StatusStrip(segments: List<OrbitalSegment>, deck: DeckState, onNavigate: (DeckTarget) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        segments.forEach { segment ->
            StatusPill(
                text = segment.value,
                label = segment.title,
                color = segment.status.color,
                icon = segmentIcon(segment, deck.diagnostics),
                onClick = {
                    onNavigate(
                        when (segment.title) {
                            "Модель" -> DeckTarget.MODELS
                            "Council" -> DeckTarget.COUNCIL
                            else -> DeckTarget.DEVICE
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun Composer(
    onCompose: (String, DeckTarget) -> Unit,
    onListeningChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var task by remember { mutableStateOf("") }
    var target by remember { mutableIntStateOf(0) }
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
                is VoiceInput.Event.Final -> { task = event.text; setListening(false); voiceStatus = null }
                is VoiceInput.Event.Error -> { setListening(false); voiceStatus = event.reason }
                VoiceInput.Event.Ended -> setListening(false)
            }
        }
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else voiceStatus = "Без разрешения на микрофон голосовой ввод невозможен."
    }

    fun send() {
        if (task.isBlank()) return
        onCompose(task.trim(), if (target == 0) DeckTarget.STUDIO else DeckTarget.COUNCIL)
        task = ""
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .glass(NexusShapes.Panel, accent = if (listening) NexusColors.NeonCyan else null)
                .padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.weight(1f).heightIn(min = 44.dp), contentAlignment = Alignment.CenterStart) {
                if (task.isEmpty()) {
                    Text(
                        if (listening) "Говорите…" else "Спросите NEX…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexusColors.TextMuted
                    )
                }
                BasicTextField(
                    value = task,
                    onValueChange = { task = it.take(32_000) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = NexusColors.TextPrimary),
                    cursorBrush = SolidColor(NexusColors.NeonCyan),
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            CircleIconButton(
                if (listening) NexIcons.Square else NexIcons.Mic,
                onClick = {
                    if (listening) {
                        voice.stop(); setListening(false); voiceStatus = null
                    } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        startVoice()
                    } else {
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                size = 44.dp,
                tint = if (listening) NexusColors.NeonCyan else NexusColors.TextSecondary,
                contentDescription = if (listening) "Остановить диктовку" else "Голосовой ввод"
            )
            CircleIconButton(
                NexIcons.ArrowUp,
                onClick = { send() },
                size = 44.dp,
                filled = task.isNotBlank(),
                tint = NexusColors.TextMuted,
                contentDescription = "Отправить"
            )
        }
        SegmentedTabs(listOf("AI Studio", "Council"), target, { target = it })
        Text(
            voiceStatus ?: if (target == 0) {
                "Один ответ выбранной модели, с потоковым выводом."
            } else {
                "Совет из восьми ролей разбирает задачу и сводит итог."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (voiceStatus != null && !listening) NexusColors.SignalAmber else NexusColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

@Composable
private fun SystemsBento(deck: DeckState, segments: List<OrbitalSegment>, onNavigate: (DeckTarget) -> Unit) {
    val council = segments.first { it.title == "Council" }
    val device = segments.first { it.title == "Устройство" }
    Row(Modifier.fillMaxWidth().height(204.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Council: the latest mission as a small constellation.
        GlassCard(
            modifier = Modifier.weight(1.1f).fillMaxHeight(),
            onClick = { onNavigate(DeckTarget.COUNCIL) },
            contentPadding = PaddingValues(16.dp),
            spacing = 6.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(NexIcons.Orbit, NexusColors.NeonViolet, size = 34.dp)
                Spacer(Modifier.weight(1f))
                Tag(council.status.label.replaceFirstChar { it.titlecase(RU) }, council.status.color)
            }
            Text("Council", style = MaterialTheme.typography.titleMedium, color = NexusColors.TextPrimary)
            Text(
                council.value.replaceFirstChar { it.titlecase(RU) },
                style = MaterialTheme.typography.bodySmall,
                color = NexusColors.TextSecondary,
                maxLines = 1
            )
            MiniConstellation(deck.latestAgentResults, Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Device: battery as a gauge.
            GlassCard(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = { onNavigate(DeckTarget.DEVICE) },
                contentPadding = PaddingValues(14.dp),
                spacing = 2.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BatteryGauge(deck.diagnostics.batteryPercent, device.status.color, Modifier.size(44.dp))
                    Column {
                        Text("Устройство", style = MaterialTheme.typography.titleSmall, color = NexusColors.TextPrimary)
                        Text(
                            deck.diagnostics.thermalLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = device.status.color,
                            maxLines = 1
                        )
                    }
                }
            }
            // Browser: the last real page.
            val page = deck.lastBrowserPage
            GlassCard(
                modifier = Modifier.fillMaxWidth().weight(1f),
                onClick = { onNavigate(DeckTarget.BROWSER) },
                contentPadding = PaddingValues(14.dp),
                spacing = 2.dp
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconBadge(NexIcons.Globe, NexusColors.NeonCyan, size = 40.dp)
                    Column {
                        Text("Браузер", style = MaterialTheme.typography.titleSmall, color = NexusColors.TextPrimary)
                        Text(
                            page?.title?.ifBlank { page.url } ?: "Истории пока нет",
                            style = MaterialTheme.typography.bodySmall,
                            color = NexusColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Latest mission's agents as dots on an orbit, coloured by stored results. */
@Composable
private fun MiniConstellation(results: List<AgentJournalRecord>, modifier: Modifier = Modifier) {
    val agents = remember { CouncilOrchestrator.defaultAgents() }
    val nodes = remember(results) { ConstellationModel.fromJournal(agents, results) }
    Canvas(modifier) {
        val c = center
        val orbit = min(size.width, size.height) / 2f - 8.dp.toPx()
        drawCircle(Color.White.copy(alpha = 0.07f), radius = orbit, center = c, style = Stroke(width = 1.dp.toPx()))
        nodes.forEachIndexed { index, node ->
            val a = Math.toRadians(-90.0 + index * 360.0 / nodes.size)
            val p = Offset(c.x + orbit * cos(a).toFloat(), c.y + orbit * sin(a).toFloat())
            val idle = node.phase == NodePhase.IDLE
            val color = if (idle) NexusColors.TextMuted else node.phase.color
            drawLine(color.copy(alpha = if (idle) 0.15f else 0.5f), c, p, strokeWidth = 1.2.dp.toPx())
            drawCircle(color.copy(alpha = 0.25f), radius = 6.dp.toPx(), center = p)
            drawCircle(color, radius = 3.dp.toPx(), center = p)
        }
        drawCircle(
            brush = Brush.radialGradient(listOf(Color(0xFFD8CCFF), NexusColors.NeonViolet, Color(0xFF3A1F9E)), center = c, radius = 10.dp.toPx()),
            radius = 9.dp.toPx(),
            center = c
        )
    }
}

@Composable
private fun BatteryGauge(percent: Int?, color: Color, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = 135f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            if (percent != null) {
                drawArc(
                    color = color,
                    startAngle = 135f, sweepAngle = 270f * percent.coerceIn(0, 100) / 100f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(percent?.toString() ?: "—", style = MaterialTheme.typography.labelMedium, color = NexusColors.TextPrimary)
    }
}

@Composable
private fun RouteCard(
    deck: DeckState,
    health: HealthCheckResult?,
    onHealth: (HealthCheckResult) -> Unit,
    onNavigate: (DeckTarget) -> Unit
) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    val network = DeckModel.networkAvailable(deck.diagnostics)
    val settings = deck.providerSettings
    val local = deck.localModelName != null
    GlassCard(onClick = { onNavigate(DeckTarget.MODELS) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(
                if (local) NexIcons.Cpu else NexIcons.Route,
                if (local) NexusColors.SignalGreen else NexusColors.NeonCyan
            )
            Column(Modifier.weight(1f)) {
                Text("Активный маршрут", style = MaterialTheme.typography.labelMedium, color = NexusColors.TextSecondary)
                Text(
                    DeckModel.routeLine(deck.localModelName, deck.cloudLabel, settings.model, network),
                    style = MaterialTheme.typography.titleSmall,
                    color = NexusColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            NexIcon(NexIcons.ChevronRight, tint = NexusColors.TextMuted, size = 18.dp)
        }
        if (deck.cloudLabel != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (text, color) = when {
                    checking -> "Проверяю облачного провайдера…" to NexusColors.SignalAmber
                    health == null -> "Облако в этой сессии не проверялось" to NexusColors.SignalAmber
                    health.reachability == EndpointReachability.REACHABLE ->
                        "Облако отвечает · ${health.latencyMillis} мс" to NexusColors.SignalGreen
                    else -> health.detail to NexusColors.SignalRed
                }
                Dot(color)
                Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.weight(1f), maxLines = 2)
                if (settings.endpoint.isNotBlank() && !checking) {
                    SoftButton("Проверить", {
                        checking = true
                        scope.launch {
                            onHealth(ProviderHealthCheck.check(settings.endpoint))
                            checking = false
                        }
                    }, icon = NexIcons.Activity)
                }
            }
        }
    }
}

@Composable
private fun QuickActions(deck: DeckState, onNavigate: (DeckTarget) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickAction(NexIcons.Sparkles, "Studio", NexusColors.NeonViolet) { onNavigate(DeckTarget.STUDIO) }
        QuickAction(NexIcons.Archive, "Архив", NexusColors.NeonCyan) { onNavigate(DeckTarget.ARCHIVE) }
        QuickAction(NexIcons.Boxes, "Модели", NexusColors.NeonMagenta) { onNavigate(DeckTarget.MODELS) }
        QuickAction(
            NexIcons.Wrench,
            "Исправления",
            if (deck.errorCount > 0) NexusColors.SignalAmber else NexusColors.SignalGreen,
            badge = deck.errorCount.takeIf { it > 0 }
        ) { onNavigate(DeckTarget.REPAIRS) }
    }
}

@Composable
private fun RowScope.QuickAction(@DrawableRes icon: Int, label: String, accent: Color, badge: Int? = null, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).clip(NexusShapes.Card).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box {
            Box(
                Modifier.size(60.dp).glass(NexusShapes.Card, accent = accent),
                contentAlignment = Alignment.Center
            ) { NexIcon(icon, tint = accent, size = 24.dp) }
            if (badge != null) {
                Text(
                    if (badge > 99) "99+" else badge.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF1A1205),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(NexusShapes.Pill)
                        .background(NexusColors.SignalAmber)
                        .padding(horizontal = 6.dp, vertical = 1.dp)
                )
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = NexusColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun RecentOperations(deck: DeckState, onNavigate: (DeckTarget) -> Unit) {
    if (deck.missions.isEmpty() && deck.lastConversation == null) {
        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(NexIcons.History, NexusColors.TextSecondary)
                Text(
                    "Операций ещё не было. Первая задача появится здесь сразу после запуска.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexusColors.TextSecondary
                )
            }
        }
        return
    }
    GlassCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp), spacing = 0.dp) {
        val rows = deck.missions.take(3)
        rows.forEachIndexed { index, mission ->
            val (label, color) = missionStatusLabel(mission.status)
            ListRow(
                icon = NexIcons.Orbit,
                accent = color,
                title = mission.goal,
                meta = "Council · " + timeLabel(mission.updatedAt),
                trailing = { Tag(label, color) },
                onClick = { onNavigate(DeckTarget.COUNCIL) }
            )
            if (index < rows.lastIndex || deck.lastConversation != null) HairlineDivider()
        }
        deck.lastConversation?.let { chat ->
            ListRow(
                icon = NexIcons.MessageSquare,
                accent = NexusColors.NeonCyan,
                title = chat.title.ifBlank { "Диалог" },
                meta = "AI Studio · " + timeLabel(chat.updatedAt),
                trailing = { Tag("Диалог", NexusColors.NeonCyan) },
                onClick = { onNavigate(DeckTarget.STUDIO) }
            )
        }
    }
}

private fun missionStatusLabel(status: MissionStatus): Pair<String, Color> = when (status) {
    MissionStatus.DRAFT -> "Черновик" to NexusColors.TextSecondary
    MissionStatus.QUEUED -> "В очереди" to NexusColors.SignalAmber
    MissionStatus.RUNNING -> "Работает" to NexusColors.NeonViolet
    MissionStatus.VERIFYING -> "Проверка" to NexusColors.NeonViolet
    MissionStatus.AWAITING_INPUT -> "Ждёт ответа" to NexusColors.SignalAmber
    MissionStatus.COMPLETED -> "Готово" to NexusColors.SignalGreen
    MissionStatus.FAILED -> "Ошибка" to NexusColors.SignalRed
    MissionStatus.CANCELLED -> "Отменена" to NexusColors.TextSecondary
    MissionStatus.INTERRUPTED -> "Прервана" to NexusColors.SignalRed
}

private fun formatGb(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f ГБ", gb)
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm, d MMM", RU).format(Date(epochMillis))
