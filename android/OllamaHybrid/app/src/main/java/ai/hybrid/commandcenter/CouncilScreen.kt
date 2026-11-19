package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.AgentJournalRecord
import ai.hybrid.commandcenter.nexus.CouncilAgent
import ai.hybrid.commandcenter.nexus.CouncilAgentRole
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.CouncilRoutingStore
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Void = Color(0xFF080B12)
private val SurfaceDeep = Color(0xFF0E1420)
private val SurfaceRaised = Color(0xFF151E2D)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val SignalGreen = Color(0xFF43D6A0)
private val SignalAmber = Color(0xFFF2B65D)
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF1F5FA)
private val SecondaryText = Color(0xFFA4B1C2)

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
    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) tab = 1
    }
    Column(modifier.fillMaxSize().background(Void)) {
        TabRow(
            selectedTabIndex = tab,
            containerColor = SurfaceDeep,
            contentColor = Violet
        ) {
            Tab(selected = tab == 0, onClick = { tab = 0 }) {
                Text("ЦЕНТР", color = if (tab == 0) Violet else SecondaryText,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
            Tab(selected = tab == 1, onClick = { tab = 1 }) {
                Text("ЗАПУСК", color = if (tab == 1) Violet else SecondaryText,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(vertical = 12.dp))
            }
        }
        when (tab) {
            0 -> CouncilCommandCenter(Modifier.fillMaxSize())
            else -> AiStudioScreen(
                councilMode = true,
                modifier = Modifier.fillMaxSize(),
                initialPrompt = initialPrompt,
                onInitialPromptConsumed = onInitialPromptConsumed
            )
        }
    }
}

@Composable
private fun CouncilCommandCenter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val agents = remember { CouncilOrchestrator.defaultAgents() }

    var routes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var missions by remember { mutableStateOf<List<MissionRecord>>(emptyList()) }
    var agentResults by remember { mutableStateOf<Map<String, List<AgentJournalRecord>>>(emptyMap()) }
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
                routeLabels to journal.getRecentMissions(10)
            }
            routes = snapshot.first
            missions = snapshot.second
            loaded = true
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = modifier.background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("NEXUS COUNCIL", color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("КОМАНДНЫЙ ЦЕНТР · СТАТУСЫ ИЗ ЖУРНАЛА МИССИЙ", color = Violet, fontSize = 11.sp, letterSpacing = 1.sp)
        }

        item {
            CenterCard(title = "ОРКЕСТРАТОР", accent = Violet) {
                InfoRow("Исполнителей в составе", agents.size.toString())
                InfoRow("Параллельно", "до 3 агентов")
                InfoRow("Таймаут агента / синтеза", "120 с / 120 с")
                Text(
                    "Параметры соответствуют текущему коду CouncilOrchestrator. " +
                        "Живой прогресс не рисуется: показываются только события, записанные в журнал.",
                    color = SecondaryText, fontSize = 11.sp
                )
            }
        }

        item {
            CenterCard(title = "СОСТАВ И МАРШРУТЫ", accent = Cyan) {
                agents.forEach { agent ->
                    AgentRow(agent, routes[agent.id] ?: "Общий маршрут (активный провайдер)")
                }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("МИССИИ", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                OutlinedButton(
                    onClick = { refresh() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text("Обновить", fontSize = 12.sp) }
            }
        }

        if (loaded && missions.isEmpty()) {
            item {
                Text(
                    "Миссий ещё не было. Запустите первую на вкладке «Запуск».",
                    color = SecondaryText, fontSize = 13.sp
                )
            }
        }

        items(missions.size) { index ->
            val mission = missions[index]
            MissionCard(
                mission = mission,
                expanded = expandedMissionId == mission.id,
                results = agentResults[mission.id],
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

        item { Spacer(Modifier.height(22.dp)) }
    }
}

private fun routeLabel(store: CouncilRoutingStore, agentId: String): String {
    val kind = store.load(agentId) ?: return "Общий маршрут (активный провайдер)"
    val override = store.loadModelOverride(agentId)
    return if (override.isNullOrBlank()) kind.displayName else "${kind.displayName} · $override"
}

@Composable
private fun CenterCard(title: String, accent: Color, content: @Composable () -> Unit) {
    Surface(color = SurfaceDeep, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = SecondaryText, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AgentRow(agent: CouncilAgent, route: String) {
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(agent.name, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(roleLabel(agent.role), color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text("Маршрут: $route", color = SecondaryText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun MissionCard(
    mission: MissionRecord,
    expanded: Boolean,
    results: List<AgentJournalRecord>?,
    onToggle: () -> Unit
) {
    val (label, color) = missionStatusLabel(mission.status)
    Surface(
        color = SurfaceDeep,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable { onToggle() }
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    mission.goal.take(72),
                    color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(timeLabel(mission.updatedAt), color = SecondaryText, fontSize = 10.sp)
            val missionError = mission.error
            if (!missionError.isNullOrBlank()) {
                Text(missionError.take(160), color = SignalRed, fontSize = 11.sp)
            }
            if (expanded) {
                when {
                    results == null -> Text("Чтение результатов…", color = SecondaryText, fontSize = 11.sp)
                    results.isEmpty() -> Text(
                        "В журнале нет результатов агентов для этой миссии.",
                        color = SecondaryText, fontSize = 11.sp
                    )
                    else -> results.forEach { record -> AgentResultRow(record) }
                }
                val result = mission.result
                if (!result.isNullOrBlank()) {
                    Text("ИТОГ", color = SignalGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(result.take(400), color = PrimaryText, fontSize = 11.sp)
                }
            } else {
                Text("Нажмите, чтобы раскрыть результаты агентов", color = SecondaryText, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun AgentResultRow(record: AgentJournalRecord) {
    val success = record.status.equals("COMPLETED", ignoreCase = true)
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(record.agentName, color = PrimaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (success) "ВЫПОЛНЕНО" else "ОШИБКА",
                    color = if (success) SignalGreen else SignalRed,
                    fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
            }
            val output = record.output
            val error = record.error
            when {
                !output.isNullOrBlank() -> Text(output.take(240), color = SecondaryText, fontSize = 10.sp)
                !error.isNullOrBlank() -> Text(error.take(240), color = SignalRed, fontSize = 10.sp)
                else -> Text("Пустой результат", color = SecondaryText, fontSize = 10.sp)
            }
        }
    }
}

private fun roleLabel(role: CouncilAgentRole): String = when (role) {
    CouncilAgentRole.PLANNER -> "ПЛАНИРОВЩИК"
    CouncilAgentRole.RESEARCHER -> "ИССЛЕДОВАТЕЛЬ"
    CouncilAgentRole.ANALYST -> "АНАЛИТИК"
    CouncilAgentRole.CRITIC -> "КРИТИК"
    CouncilAgentRole.CREATIVE -> "ГЕНЕРАТОР"
    CouncilAgentRole.TECHNICAL -> "ТЕХЭКСПЕРТ"
    CouncilAgentRole.RISK_REVIEWER -> "РИСКИ"
    CouncilAgentRole.VERIFIER -> "ПРОВЕРЯЮЩИЙ"
}

private fun missionStatusLabel(status: MissionStatus): Pair<String, Color> = when (status) {
    MissionStatus.DRAFT -> "ЧЕРНОВИК" to SecondaryText
    MissionStatus.QUEUED -> "В ОЧЕРЕДИ" to SignalAmber
    MissionStatus.RUNNING -> "ВЫПОЛНЯЕТСЯ" to SignalAmber
    MissionStatus.VERIFYING -> "ПРОВЕРКА" to SignalAmber
    MissionStatus.AWAITING_INPUT -> "ЖДЁТ ОТВЕТА" to SignalAmber
    MissionStatus.COMPLETED -> "ЗАВЕРШЕНА" to SignalGreen
    MissionStatus.FAILED -> "ОШИБКА" to SignalRed
    MissionStatus.CANCELLED -> "ОТМЕНЕНА" to SecondaryText
    MissionStatus.INTERRUPTED -> "ПРЕРВАНА" to SignalRed
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
