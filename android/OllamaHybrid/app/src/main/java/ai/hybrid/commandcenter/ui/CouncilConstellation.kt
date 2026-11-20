package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.nexus.AgentJournalRecord
import ai.hybrid.commandcenter.nexus.CouncilAgent
import ai.hybrid.commandcenter.nexus.CouncilAgentEvent
import ai.hybrid.commandcenter.nexus.CouncilAgentPhase
import ai.hybrid.commandcenter.nexus.CouncilAgentRole
import ai.hybrid.commandcenter.nexus.MissionStatus
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** Phase of one executor node, from orchestrator events or the journal. */
enum class NodePhase(val label: String, val color: Color) {
    IDLE("в резерве", NexusColors.TextMuted),
    ASSIGNED("назначен", NexusColors.NeonCyan),
    RUNNING("выполняет", NexusColors.NeonViolet),
    COMPLETED("готово", NexusColors.SignalGreen),
    FAILED("ошибка", NexusColors.SignalRed)
}

enum class ManagerPhase(val label: String, val color: Color) {
    IDLE("ждёт задачу", NexusColors.TextSecondary),
    DISPATCHING("распределяет и ждёт исполнителей", NexusColors.NeonViolet),
    SYNTHESIS("сводит результаты", NexusColors.NeonCyan),
    DONE("итог готов", NexusColors.SignalGreen),
    FAILED("миссия с ошибкой", NexusColors.SignalRed)
}

data class ConstellationNode(
    val id: String,
    val name: String,
    val role: String,
    val phase: NodePhase,
    val detail: String?,
    val atMillis: Long?,
    @DrawableRes val icon: Int = NexIcons.Bot
)

/**
 * Pure mapping for the Neural Constellation (handoff 5.3). Live view reads
 * orchestrator events; history reads stored agent results. Nothing else.
 */
object ConstellationModel {

    @DrawableRes
    fun roleIcon(role: CouncilAgentRole): Int = when (role) {
        CouncilAgentRole.PLANNER -> NexIcons.ListChecks
        CouncilAgentRole.RESEARCHER -> NexIcons.Search
        CouncilAgentRole.ANALYST -> NexIcons.ChartColumn
        CouncilAgentRole.CRITIC -> NexIcons.MessageSquareWarning
        CouncilAgentRole.CREATIVE -> NexIcons.Lightbulb
        CouncilAgentRole.TECHNICAL -> NexIcons.CodeXml
        CouncilAgentRole.RISK_REVIEWER -> NexIcons.ShieldAlert
        CouncilAgentRole.VERIFIER -> NexIcons.BadgeCheck
    }

    fun roleShort(role: CouncilAgentRole): String = when (role) {
        CouncilAgentRole.PLANNER -> "План"
        CouncilAgentRole.RESEARCHER -> "Поиск"
        CouncilAgentRole.ANALYST -> "Анализ"
        CouncilAgentRole.CRITIC -> "Критика"
        CouncilAgentRole.CREATIVE -> "Идеи"
        CouncilAgentRole.TECHNICAL -> "Техника"
        CouncilAgentRole.RISK_REVIEWER -> "Риски"
        CouncilAgentRole.VERIFIER -> "Проверка"
    }

    fun fromLive(agents: List<CouncilAgent>, latest: Map<String, CouncilAgentEvent>): List<ConstellationNode> =
        agents.map { agent ->
            val event = latest[agent.id]
            ConstellationNode(
                id = agent.id,
                name = agent.name,
                role = roleShort(agent.role),
                phase = when (event?.phase) {
                    null -> NodePhase.IDLE
                    CouncilAgentPhase.ASSIGNED -> NodePhase.ASSIGNED
                    CouncilAgentPhase.RUNNING -> NodePhase.RUNNING
                    CouncilAgentPhase.COMPLETED -> NodePhase.COMPLETED
                    CouncilAgentPhase.FAILED -> NodePhase.FAILED
                },
                detail = event?.detail,
                atMillis = event?.atEpochMillis,
                icon = roleIcon(agent.role)
            )
        }

    fun fromJournal(agents: List<CouncilAgent>, records: List<AgentJournalRecord>): List<ConstellationNode> {
        val byAgent = records.groupBy { it.agentId }.mapValues { (_, list) -> list.maxByOrNull { it.createdAt } }
        return agents.map { agent ->
            val record = byAgent[agent.id]
            ConstellationNode(
                id = agent.id,
                name = agent.name,
                role = roleShort(agent.role),
                phase = when {
                    record == null -> NodePhase.IDLE
                    record.status.equals("COMPLETED", ignoreCase = true) -> NodePhase.COMPLETED
                    else -> NodePhase.FAILED
                },
                detail = record?.error?.takeIf { it.isNotBlank() } ?: record?.output,
                atMillis = record?.createdAt,
                icon = roleIcon(agent.role)
            )
        }
    }

    fun managerPhase(nodes: List<ConstellationNode>, running: Boolean, missionStatus: MissionStatus?): ManagerPhase {
        val active = nodes.any { it.phase == NodePhase.ASSIGNED || it.phase == NodePhase.RUNNING }
        val touched = nodes.filter { it.phase != NodePhase.IDLE }
        return when {
            missionStatus == MissionStatus.FAILED || missionStatus == MissionStatus.INTERRUPTED -> ManagerPhase.FAILED
            missionStatus == MissionStatus.COMPLETED && !running -> ManagerPhase.DONE
            active -> ManagerPhase.DISPATCHING
            running && touched.isNotEmpty() -> ManagerPhase.SYNTHESIS
            running -> ManagerPhase.DISPATCHING
            else -> ManagerPhase.IDLE
        }
    }

    /** Node centres on the orbit, first node at the top, clockwise. */
    fun orbitPosition(index: Int, count: Int, center: Offset, radius: Float): Offset {
        val angle = Math.toRadians(-90.0 + index * 360.0 / count.coerceAtLeast(1))
        return Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
    }
}

private const val MANAGER_ID = "__manager__"

/**
 * Manager in the centre, executors on the orbit. An edge lights once when an
 * agent is assigned, a pulse travels back to the manager when its result
 * arrives, and a failed agent gets a dashed red trace. Nothing moves without
 * a real event; Static mode draws the same states without motion.
 */
@Composable
fun CouncilConstellation(
    nodes: List<ConstellationNode>,
    manager: ManagerPhase,
    mode: EffectsMode,
    modifier: Modifier = Modifier,
    height: Dp = 300.dp,
    events: List<CouncilAgentEvent> = emptyList(),
    showInspector: Boolean = true
) {
    val animates = MotionPolicy.animates(mode)
    val measurer = rememberTextMeasurer()
    var selected by remember { mutableStateOf<String?>(null) }

    // One Animatable per node; it plays only when that node's phase changes.
    val pulses = nodes.associate { node ->
        node.id to key(node.id) {
            val progress = remember { Animatable(1f) }
            // The phase present when the view opens is history, not an event.
            val opening = remember { booleanArrayOf(true) }
            LaunchedEffect(node.phase, animates) {
                val firstLook = opening[0]
                opening[0] = false
                if (!firstLook && animates &&
                    (node.phase == NodePhase.ASSIGNED || node.phase == NodePhase.COMPLETED)
                ) {
                    progress.snapTo(0f)
                    progress.animateTo(1f, tween(NexusMotion.PANEL * 3, easing = FastOutSlowInEasing))
                } else {
                    progress.snapTo(1f)
                }
            }
            progress
        }
    }

    val painters = nodes.map { painterResource(it.icon) }
    val managerNex = when (manager) {
        ManagerPhase.IDLE -> NexState.STANDBY
        ManagerPhase.DISPATCHING, ManagerPhase.SYNTHESIS -> NexState.THINKING
        ManagerPhase.DONE -> NexState.SUCCESS
        ManagerPhase.FAILED -> NexState.ERROR
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = "Созвездие совета: менеджер — " + manager.label + ". " +
                            nodes.joinToString("; ") { it.name + " — " + it.phase.label }
                    }
                    .pointerInput(nodes.size) {
                        detectTapGestures { tap ->
                            val c = Offset(size.width / 2f, size.height / 2f)
                            val orbit = min(size.width, size.height) / 2f - 34.dp.toPx()
                            if (hypot(tap.x - c.x, tap.y - c.y) < 36.dp.toPx()) {
                                selected = MANAGER_ID
                                return@detectTapGestures
                            }
                            val hit = nodes.indices.minByOrNull { i ->
                                val p = ConstellationModel.orbitPosition(i, nodes.size, c, orbit)
                                hypot(tap.x - p.x, tap.y - p.y)
                            }
                            if (hit != null) {
                                val p = ConstellationModel.orbitPosition(hit, nodes.size, c, orbit)
                                if (hypot(tap.x - p.x, tap.y - p.y) < 34.dp.toPx()) selected = nodes[hit].id
                            }
                        }
                    }
            ) {
                val c = center
                val orbit = min(size.width, size.height) / 2f - 34.dp.toPx()
                val nodeRadius = 21.dp.toPx()

                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(manager.color.copy(alpha = 0.18f), Color.Transparent),
                        center = c, radius = orbit
                    ),
                    radius = orbit, center = c
                )
                drawCircle(Color.White.copy(alpha = 0.06f), radius = orbit, center = c, style = Stroke(width = 1.dp.toPx()))

                nodes.forEachIndexed { index, node ->
                    val p = ConstellationModel.orbitPosition(index, nodes.size, c, orbit)
                    drawEdge(c, p, node.phase, nodeRadius, 34.dp.toPx())
                    val progress = pulses[node.id]?.value ?: 1f
                    if (progress < 1f) {
                        // assignment travels out; a result travels back to the manager
                        val from = if (node.phase == NodePhase.COMPLETED) p else c
                        val to = if (node.phase == NodePhase.COMPLETED) c else p
                        val dot = Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
                        drawCircle(node.phase.color.copy(alpha = 0.35f), radius = 10.dp.toPx(), center = dot)
                        drawCircle(Color.White, radius = 3.5.dp.toPx(), center = dot)
                    }
                }

                nodes.forEachIndexed { index, node ->
                    val p = ConstellationModel.orbitPosition(index, nodes.size, c, orbit)
                    drawNode(p, nodeRadius, node.phase, selected == node.id)
                    val iconSize = nodeRadius * 0.95f
                    translate(p.x - iconSize / 2f, p.y - iconSize / 2f) {
                        with(painters[index]) {
                            draw(
                                Size(iconSize, iconSize),
                                colorFilter = ColorFilter.tint(
                                    if (node.phase == NodePhase.IDLE) NexusColors.TextMuted else node.phase.color
                                )
                            )
                        }
                    }
                    val label = measurer.measure(
                        node.role,
                        style = TextStyle(
                            color = if (node.phase == NodePhase.IDLE) NexusColors.TextMuted else NexusColors.TextSecondary,
                            fontSize = 10.sp,
                            fontFamily = InterFamily,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    drawText(label, topLeft = Offset(p.x - label.size.width / 2f, p.y + nodeRadius + 4.dp.toPx()))
                }
                if (selected == MANAGER_ID) {
                    drawCircle(Color.White.copy(alpha = 0.8f), radius = 33.dp.toPx(), center = c, style = Stroke(width = 1.5.dp.toPx()))
                }
            }
            NexOrb(state = managerNex, mode = EffectsMode.STATIC, diameter = 60.dp)
        }

        Legend()

        if (showInspector) {
            val node = nodes.firstOrNull { it.id == selected }
            when {
                selected == MANAGER_ID -> Inspector(
                    title = "NEX — менеджер совета",
                    subtitle = "Оркестратор",
                    status = manager.label,
                    color = manager.color,
                    detail = "Раздаёт задачу исполнителям (до 3 параллельно), собирает их ответы и сводит итог.",
                    atMillis = null
                )
                node != null -> Inspector(
                    title = node.name,
                    subtitle = node.role,
                    status = node.phase.label,
                    color = node.phase.color,
                    detail = node.detail,
                    atMillis = node.atMillis
                )
                else -> Text(
                    "Нажмите на агента или на NEX, чтобы посмотреть подробности.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexusColors.TextMuted
                )
            }
        }

        if (events.isNotEmpty()) {
            Text("Лента событий", style = MaterialTheme.typography.titleSmall, color = NexusColors.TextPrimary)
            events.takeLast(6).asReversed().forEach { event ->
                val phase = when (event.phase) {
                    CouncilAgentPhase.ASSIGNED -> NodePhase.ASSIGNED
                    CouncilAgentPhase.RUNNING -> NodePhase.RUNNING
                    CouncilAgentPhase.COMPLETED -> NodePhase.COMPLETED
                    CouncilAgentPhase.FAILED -> NodePhase.FAILED
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        clock(event.atEpochMillis),
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        color = NexusColors.TextMuted
                    )
                    Dot(phase.color, size = 7.dp)
                    Text(
                        event.agentName + " · " + phase.label,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexusColors.TextSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(NodePhase.ASSIGNED, NodePhase.RUNNING, NodePhase.COMPLETED, NodePhase.FAILED).forEach { phase ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(phase.color, size = 7.dp)
                Text(phase.label, style = MaterialTheme.typography.labelMedium, color = NexusColors.TextSecondary)
            }
        }
    }
}

@Composable
private fun Inspector(title: String, subtitle: String, status: String, color: Color, detail: String?, atMillis: Long?) {
    Column(
        Modifier.fillMaxWidth().glass(NexusShapes.Card, accent = color).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = NexusColors.TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = NexusColors.TextSecondary)
            }
            Tag(status.replaceFirstChar { it.titlecase(Locale.ROOT) }, color)
        }
        if (!detail.isNullOrBlank()) {
            Text(detail.take(400), style = MaterialTheme.typography.bodySmall, color = NexusColors.TextSecondary)
        }
        if (atMillis != null) {
            Text(clock(atMillis), style = MaterialTheme.typography.labelSmall, color = NexusColors.TextMuted)
        }
    }
}

private fun DrawScope.drawEdge(from: Offset, to: Offset, phase: NodePhase, nodeRadius: Float, hubRadius: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy).coerceAtLeast(1f)
    val start = Offset(from.x + dx / length * hubRadius, from.y + dy / length * hubRadius)
    val end = Offset(to.x - dx / length * nodeRadius, to.y - dy / length * nodeRadius)
    when (phase) {
        NodePhase.IDLE -> drawLine(Color.White.copy(alpha = 0.08f), start, end, strokeWidth = 1.dp.toPx())
        NodePhase.FAILED -> drawLine(
            NexusColors.SignalRed.copy(alpha = 0.85f), start, end, strokeWidth = 2.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)), cap = StrokeCap.Round
        )
        else -> {
            val brush = Brush.linearGradient(listOf(NexusColors.NeonViolet, phase.color), start = start, end = end)
            drawLine(brush, start, end, strokeWidth = 8.dp.toPx(), alpha = 0.18f, cap = StrokeCap.Round)
            drawLine(brush, start, end, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawNode(p: Offset, r: Float, phase: NodePhase, selected: Boolean) {
    if (phase != NodePhase.IDLE) {
        drawCircle(
            brush = Brush.radialGradient(listOf(phase.color.copy(alpha = 0.35f), Color.Transparent), center = p, radius = r * 1.9f),
            radius = r * 1.9f,
            center = p
        )
    }
    drawCircle(Color(0xF00E1019), radius = r, center = p)
    drawCircle(
        brush = Brush.verticalGradient(
            listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.02f)),
            startY = p.y - r, endY = p.y + r
        ),
        radius = r,
        center = p
    )
    drawCircle(
        color = if (phase == NodePhase.IDLE) Color.White.copy(alpha = 0.14f) else phase.color,
        radius = r,
        center = p,
        style = Stroke(
            width = if (phase == NodePhase.IDLE) 1.dp.toPx() else 2.dp.toPx(),
            pathEffect = if (phase == NodePhase.FAILED) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null
        )
    )
    if (selected) {
        drawCircle(Color.White.copy(alpha = 0.85f), radius = r * 1.32f, center = p, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun clock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
