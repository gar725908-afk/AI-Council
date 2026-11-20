package ai.hybrid.commandcenter.ui

import ai.hybrid.commandcenter.nexus.AgentJournalRecord
import ai.hybrid.commandcenter.nexus.CouncilAgent
import ai.hybrid.commandcenter.nexus.CouncilAgentEvent
import ai.hybrid.commandcenter.nexus.CouncilAgentPhase
import ai.hybrid.commandcenter.nexus.CouncilAgentRole
import ai.hybrid.commandcenter.nexus.MissionStatus
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
import androidx.compose.ui.text.font.FontFamily
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
    val atMillis: Long?
)

/**
 * Pure mapping for the Neural Constellation (handoff 5.3). Live view reads
 * orchestrator events; history reads stored agent results. Nothing else.
 */
object ConstellationModel {

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
                atMillis = event?.atEpochMillis
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
                atMillis = record?.createdAt
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

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics {
                    contentDescription = "Созвездие совета: менеджер — " + manager.label + ". " +
                        nodes.joinToString("; ") { it.name + " — " + it.phase.label }
                }
                .pointerInput(nodes.size) {
                    detectTapGestures { tap ->
                        val c = Offset(size.width / 2f, size.height / 2f)
                        val orbit = min(size.width, size.height) / 2f - 40.dp.toPx()
                        if (hypot(tap.x - c.x, tap.y - c.y) < 34.dp.toPx()) {
                            selected = MANAGER_ID
                            return@detectTapGestures
                        }
                        val hit = nodes.indices.minByOrNull { i ->
                            val p = ConstellationModel.orbitPosition(i, nodes.size, c, orbit)
                            hypot(tap.x - p.x, tap.y - p.y)
                        }
                        if (hit != null) {
                            val p = ConstellationModel.orbitPosition(hit, nodes.size, c, orbit)
                            if (hypot(tap.x - p.x, tap.y - p.y) < 36.dp.toPx()) selected = nodes[hit].id
                        }
                    }
                }
        ) {
            val c = center
            val orbit = min(size.width, size.height) / 2f - 40.dp.toPx()
            val nodeRadius = 15.dp.toPx()

            // faint orbit ring and haze
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(manager.color.copy(alpha = 0.16f), Color.Transparent),
                    center = c, radius = orbit * 0.9f
                ),
                radius = orbit * 0.9f, center = c
            )
            drawCircle(
                NexusColors.Divider, radius = orbit, center = c,
                style = Stroke(width = 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 10f)))
            )

            nodes.forEachIndexed { index, node ->
                val p = ConstellationModel.orbitPosition(index, nodes.size, c, orbit)
                drawEdge(c, p, node.phase, nodeRadius)
                val progress = pulses[node.id]?.value ?: 1f
                if (progress < 1f) {
                    // assignment travels out; a result travels back to the manager
                    val from = if (node.phase == NodePhase.COMPLETED) p else c
                    val to = if (node.phase == NodePhase.COMPLETED) c else p
                    val dot = Offset(from.x + (to.x - from.x) * progress, from.y + (to.y - from.y) * progress)
                    drawCircle(node.phase.color.copy(alpha = 0.35f), radius = 9.dp.toPx(), center = dot)
                    drawCircle(node.phase.color, radius = 4.dp.toPx(), center = dot)
                }
            }

            drawManager(c, 24.dp.toPx(), manager, selected == MANAGER_ID)

            nodes.forEachIndexed { index, node ->
                val p = ConstellationModel.orbitPosition(index, nodes.size, c, orbit)
                drawNode(p, nodeRadius, node.phase, selected == node.id)
                val label = measurer.measure(
                    node.role.uppercase(Locale.ROOT),
                    style = TextStyle(
                        color = if (node.phase == NodePhase.IDLE) NexusColors.TextMuted else NexusColors.TextPrimary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                drawText(
                    label,
                    topLeft = Offset(p.x - label.size.width / 2f, p.y + nodeRadius * 1.7f + 2.dp.toPx())
                )
            }
        }

        Legend()

        if (showInspector) {
            val node = nodes.firstOrNull { it.id == selected }
            when {
                selected == MANAGER_ID -> Inspector(
                    title = "Менеджер совета",
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
                    "Нажмите на узел, чтобы посмотреть агента.",
                    color = NexusColors.TextMuted,
                    fontSize = 10.sp
                )
            }
        }

        if (events.isNotEmpty()) {
            TelemetryRuler("EVENT STREAM", accent = NexusColors.NeonViolet)
            events.takeLast(6).asReversed().forEach { event ->
                val phase = when (event.phase) {
                    CouncilAgentPhase.ASSIGNED -> NodePhase.ASSIGNED
                    CouncilAgentPhase.RUNNING -> NodePhase.RUNNING
                    CouncilAgentPhase.COMPLETED -> NodePhase.COMPLETED
                    CouncilAgentPhase.FAILED -> NodePhase.FAILED
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(clock(event.atEpochMillis), color = NexusColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    Box(Modifier.size(6.dp).background(phase.color, CircleShape))
                    Text(event.agentName + " · " + phase.label, color = NexusColors.TextSecondary, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(NodePhase.ASSIGNED, NodePhase.RUNNING, NodePhase.COMPLETED, NodePhase.FAILED).forEach { phase ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(7.dp).background(phase.color, CircleShape))
                Text(phase.label, color = NexusColors.TextSecondary, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun Inspector(title: String, subtitle: String, status: String, color: Color, detail: String?, atMillis: Long?) {
    Surface(color = NexusColors.RaisedSurface, shape = NexusShapes.Card, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = NexusColors.TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(subtitle.uppercase(Locale.ROOT), color = NexusColors.TextMuted, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
                Text(status.uppercase(Locale.ROOT), color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            if (!detail.isNullOrBlank()) Text(detail.take(320), color = NexusColors.TextSecondary, fontSize = 11.sp)
            if (atMillis != null) Text(clock(atMillis), color = NexusColors.TextMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        }
    }
}

private fun DrawScope.drawEdge(from: Offset, to: Offset, phase: NodePhase, nodeRadius: Float) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = hypot(dx, dy).coerceAtLeast(1f)
    val end = Offset(to.x - dx / length * nodeRadius, to.y - dy / length * nodeRadius)
    when (phase) {
        NodePhase.IDLE -> drawLine(NexusColors.Divider, from, end, strokeWidth = 1.5f)
        NodePhase.FAILED -> drawLine(
            NexusColors.SignalRed.copy(alpha = 0.8f), from, end, strokeWidth = 2.5f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
        )
        else -> {
            drawLine(phase.color.copy(alpha = 0.18f), from, end, strokeWidth = 9f)
            drawLine(phase.color.copy(alpha = 0.85f), from, end, strokeWidth = 2.5f)
        }
    }
}

private fun DrawScope.drawNode(p: Offset, r: Float, phase: NodePhase, selected: Boolean) {
    if (phase != NodePhase.IDLE) {
        drawCircle(phase.color.copy(alpha = 0.20f), radius = r * 1.7f, center = p)
    }
    drawCircle(NexusColors.DeepSurface, radius = r, center = p)
    drawCircle(
        phase.color.copy(alpha = if (phase == NodePhase.IDLE) 0.6f else 1f),
        radius = r, center = p,
        style = Stroke(
            width = if (phase == NodePhase.IDLE) 2f else 3.5f,
            pathEffect = if (phase == NodePhase.FAILED) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null
        )
    )
    drawCircle(phase.color, radius = r * 0.32f, center = p)
    if (selected) {
        drawCircle(NexusColors.TextPrimary, radius = r * 1.35f, center = p, style = Stroke(width = 2f))
    }
}

private fun DrawScope.drawManager(c: Offset, r: Float, phase: ManagerPhase, selected: Boolean) {
    drawCircle(
        brush = Brush.radialGradient(listOf(phase.color.copy(alpha = 0.45f), Color.Transparent), center = c, radius = r * 2.2f),
        radius = r * 2.2f, center = c
    )
    val hex = Path().apply {
        for (i in 0 until 6) {
            val a = Math.toRadians(-90.0 + i * 60.0)
            val point = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
        }
        close()
    }
    drawPath(hex, brush = Brush.verticalGradient(listOf(NexusColors.NeonViolet, Color(0xFF3B2A8F)), startY = c.y - r, endY = c.y + r))
    drawPath(hex, color = phase.color, style = Stroke(width = 3f))
    drawCircle(NexusColors.Void, radius = r * 0.34f, center = c)
    drawCircle(phase.color, radius = r * 0.20f, center = c)
    if (selected) drawCircle(NexusColors.TextPrimary, radius = r * 1.4f, center = c, style = Stroke(width = 2f))
}

private fun clock(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
