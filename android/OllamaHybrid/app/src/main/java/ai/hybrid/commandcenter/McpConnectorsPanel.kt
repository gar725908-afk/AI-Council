package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.ActionRisk
import ai.hybrid.commandcenter.androidbridge.ApprovalGrant
import ai.hybrid.commandcenter.androidbridge.BridgeGateway
import ai.hybrid.commandcenter.androidbridge.BridgeRequest
import ai.hybrid.commandcenter.androidbridge.BridgeToolRegistry
import ai.hybrid.commandcenter.androidbridge.BridgeTransport
import ai.hybrid.commandcenter.androidbridge.CapabilityMatrix
import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.androidbridge.GatewayOutcome
import ai.hybrid.commandcenter.androidbridge.McpBridgeTool
import ai.hybrid.commandcenter.nexus.McpClient
import ai.hybrid.commandcenter.nexus.McpServer
import ai.hybrid.commandcenter.nexus.McpServerStore
import androidx.compose.foundation.background
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
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
 * Connectors (handoff 8) on the open MCP standard: the user adds a server,
 * NEXUS discovers its tools, and every call runs through the Gateway —
 * read-only tools directly, mutating tools only after an explicit one-shot
 * approval — with the result and audit visible right here.
 */
@Composable
fun McpConnectorsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { McpServerStore(context.applicationContext) }
    val client = remember { McpClient() }

    var servers by remember { mutableStateOf<List<McpServer>>(emptyList()) }
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var activeServer by remember { mutableStateOf<McpServer?>(null) }
    var tools by remember { mutableStateOf<List<McpBridgeTool>>(emptyList()) }
    var loadingTools by remember { mutableStateOf(false) }
    var argsByTool by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var armedToolId by remember { mutableStateOf<String?>(null) }
    var armedRequestId by remember { mutableStateOf("") }
    var lastOutcome by remember { mutableStateOf<String?>(null) }

    fun setStatus(text: String, error: Boolean) {
        statusLine = text
        statusIsError = error
    }

    fun refresh() {
        servers = store.list()
    }

    LaunchedEffect(Unit) { refresh() }

    fun discover(server: McpServer) {
        loadingTools = true
        activeServer = server
        tools = emptyList()
        lastOutcome = null
        scope.launch {
            val result = client.listTools(server)
            result.fold(
                onSuccess = { list ->
                    tools = list.map { McpBridgeTool(server, it, client) }
                    setStatus("Сервер «${server.name}» отвечает: инструментов ${list.size}.", false)
                },
                onFailure = { setStatus("«${server.name}»: " + (it.message ?: "ошибка"), true) }
            )
            loadingTools = false
        }
    }

    fun run(tool: McpBridgeTool, approval: ApprovalGrant?, requestId: String) {
        lastOutcome = "Выполняется через Gateway…"
        scope.launch {
            val journal = (context.applicationContext as NexusApplication).missionJournal
            val outcome = withContext(Dispatchers.IO) {
                val inspector = DeviceInspector(context.applicationContext)
                val snapshot = CapabilityMatrix.snapshot(
                    inspector.capabilityInputs(rootProbeAttempted = false, rootConfirmed = false),
                    System.currentTimeMillis()
                )
                val registry = BridgeToolRegistry().apply { tools.forEach(::register) }
                BridgeGateway(registry, { entry -> journal.recordToolAudit(entry) }).execute(
                    request = BridgeRequest(
                        requestId = requestId,
                        toolId = tool.id,
                        arguments = parseArgs(argsByTool[tool.id].orEmpty()),
                        approval = approval
                    ),
                    snapshot = snapshot,
                    availableTransports = setOf(BridgeTransport.NATIVE),
                    initiator = "user"
                )
            }
            lastOutcome = when (outcome) {
                is GatewayOutcome.Success -> "✓ " + outcome.result.message.take(1_500)
                is GatewayOutcome.Denied -> "Отклонено (${outcome.stage}): " + outcome.reason
                is GatewayOutcome.Failed -> "Ошибка (${outcome.stage}): " + outcome.reason
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("КОННЕКТОРЫ", color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("MCP · ЛЮБОЙ СЕРВЕР ПО ОТКРЫТОМУ СТАНДАРТУ · ВСЁ ЧЕРЕЗ GATEWAY", color = Cyan, fontSize = 11.sp, letterSpacing = 1.sp)
        }

        item {
            PanelCard(title = "ДОБАВИТЬ СЕРВЕР", accent = Cyan) {
                Text(
                    "Укажите адрес MCP-сервера (Streamable HTTP). Инструменты только для чтения выполняются сразу, " +
                        "изменяющие — после вашего подтверждения. Каждый вызов записывается в аудит.",
                    color = SecondaryText, fontSize = 11.sp
                )
                OutlinedTextField(value = newName, onValueChange = { newName = it.take(60) }, label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = newUrl, onValueChange = { newUrl = it.take(2048) }, label = { Text("URL, например https://host/mcp") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(
                    onClick = {
                        val problem = store.add(newName, newUrl)
                        if (problem == null) {
                            setStatus("Сервер добавлен: $newName", false)
                            newName = ""; newUrl = ""
                            refresh()
                        } else setStatus(problem, true)
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text("Добавить", fontSize = 12.sp) }
                val status = statusLine
                if (status != null) Text(status, color = if (statusIsError) SignalRed else SignalGreen, fontSize = 11.sp)
            }
        }

        item { Text("СЕРВЕРЫ (${servers.size})", color = Violet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp) }

        if (servers.isEmpty()) {
            item { Text("Серверов пока нет.", color = SecondaryText, fontSize = 13.sp) }
        }

        items(servers.size) { index ->
            val server = servers[index]
            Surface(color = SurfaceDeep, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(server.name, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(server.url.take(80), color = SecondaryText, fontSize = 10.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { discover(server) },
                            enabled = !loadingTools,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) { Text(if (loadingTools && activeServer == server) "Опрос…" else "Инструменты", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = {
                                store.remove(server.name)
                                if (activeServer == server) { activeServer = null; tools = emptyList() }
                                refresh()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalRed)
                        ) { Text("Удалить", fontSize = 11.sp) }
                    }
                }
            }
        }

        val active = activeServer
        if (active != null && tools.isNotEmpty()) {
            item {
                Text(
                    "ИНСТРУМЕНТЫ «${active.name}» (${tools.size})",
                    color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
                )
            }
            items(tools.size) { index ->
                val tool = tools[index]
                val sensitive = tool.risk != ActionRisk.READ_ONLY
                val armed = armedToolId == tool.id
                Surface(color = SurfaceRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(tool.remote.name, color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                if (sensitive) "ТРЕБУЕТ ОДОБРЕНИЯ" else "ТОЛЬКО ЧТЕНИЕ",
                                color = if (sensitive) SignalAmber else SignalGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold
                            )
                        }
                        if (tool.remote.description.isNotBlank()) Text(tool.remote.description.take(200), color = SecondaryText, fontSize = 11.sp)
                        OutlinedTextField(
                            value = argsByTool[tool.id].orEmpty(),
                            onValueChange = { argsByTool = argsByTool + (tool.id to it.take(4_000)) },
                            label = { Text("Аргументы: JSON {\"ключ\":\"значение\"} или ключ=значение;…") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1
                        )
                        OutlinedButton(
                            onClick = {
                                if (!sensitive) {
                                    run(tool, null, UUID.randomUUID().toString())
                                } else if (!armed) {
                                    armedToolId = tool.id
                                    armedRequestId = UUID.randomUUID().toString()
                                    lastOutcome = "Нажмите ещё раз, чтобы подтвердить именно этот вызов (одноразовое одобрение, 5 минут)."
                                } else {
                                    val grant = ApprovalGrant(armedRequestId, tool.id, System.currentTimeMillis())
                                    armedToolId = null
                                    run(tool, grant, armedRequestId)
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (armed) SignalRed else Violet)
                        ) { Text(if (armed) "ПОДТВЕРДИТЬ ВЫПОЛНЕНИЕ" else "Выполнить через Gateway", fontSize = 11.sp) }
                    }
                }
            }
            item {
                val outcome = lastOutcome
                if (outcome != null) {
                    Surface(color = SurfaceDeep, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(outcome, color = PrimaryText, fontSize = 11.sp, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }
    }
}

@Composable
private fun PanelCard(title: String, accent: Color, content: @Composable () -> Unit) {
    Surface(color = SurfaceDeep, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            content()
        }
    }
}

/** `{"json":...}` for raw JSON, otherwise `key=value;key2=value2`. */
private fun parseArgs(raw: String): Map<String, String> {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return emptyMap()
    if (trimmed.startsWith("{")) return mapOf("json" to trimmed)
    return trimmed.split(';').mapNotNull { pair ->
        val index = pair.indexOf('=')
        if (index <= 0) null else pair.substring(0, index).trim() to pair.substring(index + 1).trim()
    }.toMap()
}
