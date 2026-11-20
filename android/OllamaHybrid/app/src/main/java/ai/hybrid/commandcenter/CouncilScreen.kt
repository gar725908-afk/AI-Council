package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.AgentJournalRecord
import ai.hybrid.commandcenter.nexus.CouncilAgent
import ai.hybrid.commandcenter.nexus.CouncilAgentRole
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.CouncilRoutingStore
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.CouncilConstellation
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.GlassCard
import ai.hybrid.commandcenter.ui.HairlineDivider
import ai.hybrid.commandcenter.ui.IconBadge
import ai.hybrid.commandcenter.ui.InfoLine
import ai.hybrid.commandcenter.ui.ListRow
import ai.hybrid.commandcenter.ui.NexAvatar
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusShapes
import ai.hybrid.commandcenter.ui.ScreenHeader
import ai.hybrid.commandcenter.ui.SectionCard
import ai.hybrid.commandcenter.ui.SectionHeader
import ai.hybrid.commandcenter.ui.SegmentedTabs
import ai.hybrid.commandcenter.ui.SoftButton
import ai.hybrid.commandcenter.ui.Tag
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * NEXUS COUNCIL per handoff section 4.3: a dedicated command center.
 * «Центр» shows the real council composition, per-role routes and the mission
 * journal with every agent's stored result; «Запуск» hosts the proven launch
 * flow. No live progress is invented: the journal is the source of truth.
 */
@Composable
fun CouncilScreen(
    modifier: Modifier = Modifier,
    initialPrompt: String? = null,
    onInitialPromptConsumed: () -> Unit = {}
) {
    var tab by remember { mutableIntStateOf(0) }
    var repeatPrompt by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) tab = 1
    }
    Column(modifier.fillMaxSize()) {
        SegmentedTabs(
            options = listOf("Центр", "Запуск"),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 4.dp)
        )
        when (tab) {
            0 -> CouncilCommandCenter(
                onRepeatMission = { goal ->
                    repeatPrompt = goal
                    tab = 1
                },
                modifier = Modifier.fillMaxSize()
            )
            else -> AiStudioScreen(
                councilMode = true,
                modifier = Modifier.fillMaxSize(),
                initialPrompt = repeatPrompt ?: initialPrompt,
                onInitialPromptConsumed = {
                    repeatPrompt = null
                    onInitialPromptConsumed()
                }
            )
        }
    }
}

@Composable
private fun CouncilCommandCenter(
    onRepeatMission: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agents = remember { CouncilOrchestrator.defaultAgents() }

    var routes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var missions by remember { mutableStateOf<List<MissionRecord>>(emptyList()) }
    var agentResults by remember { mutableStateOf<Map<String, List<AgentJournalRecord>>>(emptyMap()) }
    var latestResults by remember { mutableStateOf<List<AgentJournalRecord>>(emptyList()) }
    var expandedMissionId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val appContext = context.applicationContext
                val journal = (appContext as NexusApplication).missionJournal
                val routingStore = CouncilRoutingStore(appContext)
                val routeLabels = agents.associate { agent ->
                    agent.id to routeLabel(routingStore, agent.id)
                }
                val recent = journal.getRecentMissions(10)
                Triple(routeLabels, recent, recent.firstOrNull()?.let { journal.getAgentResults(it.id) }.orEmpty())
            }
            routes = snapshot.first
            missions = snapshot.second
            latestResults = snapshot.third
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            ScreenHeader(
                "Council",
                subtitle = "Командный центр · всё из журнала миссий",
                leading = { NexAvatar(NexState.STANDBY, diameter = 48.dp) }
            )
        }

        item {
            val latest = missions.firstOrNull()
            val nodes = ConstellationModel.fromJournal(agents, latestResults)
            val inFlight = latest?.status in setOf(MissionStatus.QUEUED, MissionStatus.RUNNING, MissionStatus.VERIFYING)
            SectionCard(
                title = "Созвездие совета",
                icon = NexIcons.Orbit,
                accent = NexusColors.NeonViolet,
                subtitle = if (latest == null) "Миссий ещё не было — показан состав" else "Последняя миссия: " + latest.goal.take(70)
            ) {
                CouncilConstellation(
                    nodes = nodes,
                    manager = ConstellationModel.managerPhase(nodes, inFlight, latest?.status),
                    // History from the journal: states only, no replayed motion.
                    mode = EffectsMode.STATIC,
                    height = 300.dp
                )
            }
        }

        item {
            SectionCard(title = "Оркестратор", icon = NexIcons.Waypoints, accent = NexusColors.NeonCyan) {
                InfoLine("Исполнителей в составе", agents.size.toString())
                InfoLine("Параллельно", "до 3 агентов")
                InfoLine("Таймаут агента / синтеза", "120 с / 120 с")
                Text(
                    "Параметры совпадают с кодом оркестратора. Живой прогресс не рисуется — только события из журнала.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusColors.TextSecondary
                )
            }
        }

        item {
            SectionCard(title = "Состав и маршруты", icon = NexIcons.Route, accent = NexusColors.NeonMagenta) {
                agents.forEachIndexed { index, agent ->
                    ListRow(
                        icon = roleIcon(agent.role),
                        accent = roleAccent(agent.role),
                        title = agent.name,
                        meta = routes[agent.id] ?: "Общий маршрут (активный провайдер)",
                        trailing = { Tag(roleLabel(agent.role), roleAccent(agent.role)) }
                    )
                    if (index < agents.lastIndex) HairlineDivider()
                }
            }
        }

        item {
            SectionHeader("Миссии", action = "Обновить", onAction = { refresh() })
        }

        if (loaded && missions.isEmpty()) {
            item {
                GlassCard {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconBadge(NexIcons.History, NexusColors.TextSecondary)
                        Text(
                            "Миссий ещё не было. Запустите первую на вкладке «Запуск».",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NexusColors.TextSecondary
                        )
                    }
                }
            }
        }

        items(missions.size) { index ->
            val mission = missions[index]
            MissionCard(
                mission = mission,
                expanded = expandedMissionId == mission.id,
                results = agentResults[mission.id],
                onRepeat = onRepeatMission,
                onToggle = {
                    if (expandedMissionId == mission.id) {
                        expandedMissionId = null
                    } else {
                        expandedMissionId = mission.id
                        if (agentResults[mission.id] == null) {
                            scope.launch {
                                val records = withContext(Dispatchers.IO) {
                                    (context.applicationContext as NexusApplication)
                                        .missionJournal.getAgentResults(mission.id)
                                }
                                agentResults = agentResults + (mission.id to records)
                            }
                        }
                    }
                }
            )
        }
    }
}

private fun routeLabel(store: CouncilRoutingStore, agentId: String): String {
    val kind = store.load(agentId) ?: return "Общий маршрут (активный провайдер)"
    val override = store.loadModelOverride(agentId)
    return if (override.isNullOrBlank()) kind.displayName else "${kind.displayName} · $override"
}

@Composable
private fun MissionCard(
    mission: MissionRecord,
    expanded: Boolean,
    results: List<AgentJournalRecord>?,
    onToggle: () -> Unit,
    onRepeat: (String) -> Unit
) {
    val (label, color) = missionStatusLabel(mission.status)
    GlassCard(onClick = onToggle, accent = if (expanded) color else null, spacing = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(NexIcons.Orbit, color, size = 38.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    mission.goal,
                    style = MaterialTheme.typography.titleSmall,
                    color = NexusColors.TextPrimary,
                    maxLines = if (expanded) 4 else 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(timeLabel(mission.updatedAt), style = MaterialTheme.typography.bodySmall, color = NexusColors.TextSecondary)
            }
            Tag(label, color)
        }
        val missionError = mission.error
        if (!missionError.isNullOrBlank()) {
            Text(missionError.take(200), style = MaterialTheme.typography.bodySmall, color = NexusColors.SignalRed)
        }
        if (expanded) {
            HairlineDivider()
            when {
                results == null -> Text("Читаю результаты…", style = MaterialTheme.typography.bodySmall, color = NexusColors.TextSecondary)
                results.isEmpty() -> Text(
                    "В журнале нет результатов агентов для этой миссии.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusColors.TextSecondary
                )
                else -> results.forEach { record -> AgentResultRow(record) }
            }
            val result = mission.result
            if (!result.isNullOrBlank()) {
                Text("Итог", style = MaterialTheme.typography.labelLarge, color = NexusColors.SignalGreen)
                Text(result.take(600), style = MaterialTheme.typography.bodyMedium, color = NexusColors.TextPrimary)
            }
            SoftButton("Повторить задачу", { onRepeat(mission.goal) }, icon = NexIcons.RotateCcw, tint = NexusColors.NeonViolet)
        } else {
            Text(
                "Нажмите, чтобы раскрыть результаты агентов",
                style = MaterialTheme.typography.bodySmall,
                color = NexusColors.TextMuted
            )
        }
    }
}

@Composable
private fun AgentResultRow(record: AgentJournalRecord) {
    val success = record.status.equals("COMPLETED", ignoreCase = true)
    val color = if (success) NexusColors.SignalGreen else NexusColors.SignalRed
    Column(
        Modifier
            .fillMaxWidth()
            .clip(NexusShapes.Control)
            .background(Color(0x0AFFFFFF))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NexIcon(if (success) NexIcons.CircleCheck else NexIcons.CircleX, tint = color, size = 16.dp)
            Text(
                "  " + record.agentName,
                style = MaterialTheme.typography.labelLarge,
                color = NexusColors.TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Tag(if (success) "Выполнено" else "Ошибка", color)
        }
        val output = record.output
        val error = record.error
        when {
            !output.isNullOrBlank() -> Text(output.take(320), style = MaterialTheme.typography.bodySmall, color = NexusColors.TextSecondary)
            !error.isNullOrBlank() -> Text(error.take(320), style = MaterialTheme.typography.bodySmall, color = NexusColors.SignalRed)
            else -> Text("Пустой результат", style = MaterialTheme.typography.bodySmall, color = NexusColors.TextMuted)
        }
    }
}

@DrawableRes
private fun roleIcon(role: CouncilAgentRole): Int = when (role) {
    CouncilAgentRole.PLANNER -> NexIcons.ListChecks
    CouncilAgentRole.RESEARCHER -> NexIcons.Search
    CouncilAgentRole.ANALYST -> NexIcons.ChartColumn
    CouncilAgentRole.CRITIC -> NexIcons.MessageSquareWarning
    CouncilAgentRole.CREATIVE -> NexIcons.Lightbulb
    CouncilAgentRole.TECHNICAL -> NexIcons.CodeXml
    CouncilAgentRole.RISK_REVIEWER -> NexIcons.ShieldAlert
    CouncilAgentRole.VERIFIER -> NexIcons.BadgeCheck
}

private fun roleAccent(role: CouncilAgentRole): Color = when (role) {
    CouncilAgentRole.PLANNER, CouncilAgentRole.VERIFIER -> NexusColors.NeonViolet
    CouncilAgentRole.RESEARCHER, CouncilAgentRole.TECHNICAL -> NexusColors.NeonCyan
    CouncilAgentRole.ANALYST, CouncilAgentRole.CREATIVE -> NexusColors.NeonMagenta
    CouncilAgentRole.CRITIC, CouncilAgentRole.RISK_REVIEWER -> NexusColors.SignalAmber
}

private fun roleLabel(role: CouncilAgentRole): String = when (role) {
    CouncilAgentRole.PLANNER -> "План"
    CouncilAgentRole.RESEARCHER -> "Поиск"
    CouncilAgentRole.ANALYST -> "Анализ"
    CouncilAgentRole.CRITIC -> "Критика"
    CouncilAgentRole.CREATIVE -> "Идеи"
    CouncilAgentRole.TECHNICAL -> "Техника"
    CouncilAgentRole.RISK_REVIEWER -> "Риски"
    CouncilAgentRole.VERIFIER -> "Проверка"
}

private fun missionStatusLabel(status: MissionStatus): Pair<String, Color> = when (status) {
    MissionStatus.DRAFT -> "Черновик" to NexusColors.TextSecondary
    MissionStatus.QUEUED -> "В очереди" to NexusColors.SignalAmber
    MissionStatus.RUNNING -> "Выполняется" to NexusColors.NeonViolet
    MissionStatus.VERIFYING -> "Проверка" to NexusColors.NeonViolet
    MissionStatus.AWAITING_INPUT -> "Ждёт ответа" to NexusColors.SignalAmber
    MissionStatus.COMPLETED -> "Завершена" to NexusColors.SignalGreen
    MissionStatus.FAILED -> "Ошибка" to NexusColors.SignalRed
    MissionStatus.CANCELLED -> "Отменена" to NexusColors.TextSecondary
    MissionStatus.INTERRUPTED -> "Прервана" to NexusColors.SignalRed
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm, d MMM", Locale.forLanguageTag("ru")).format(Date(epochMillis))
