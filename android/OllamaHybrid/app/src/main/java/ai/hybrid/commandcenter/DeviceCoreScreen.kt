package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.BridgeAuditEntry
import ai.hybrid.commandcenter.androidbridge.BridgeGateway
import ai.hybrid.commandcenter.androidbridge.BridgeRequest
import ai.hybrid.commandcenter.androidbridge.BridgeToolRegistry
import ai.hybrid.commandcenter.androidbridge.BridgeTransport
import ai.hybrid.commandcenter.androidbridge.CapabilityAvailability
import ai.hybrid.commandcenter.androidbridge.CapabilityMatrix
import ai.hybrid.commandcenter.androidbridge.CapabilityState
import ai.hybrid.commandcenter.androidbridge.DeviceDiagnostics
import ai.hybrid.commandcenter.androidbridge.DeviceDiagnosticsTool
import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.androidbridge.GatewayOutcome
import ai.hybrid.commandcenter.androidbridge.GitHubProposeFixTool
import ai.hybrid.commandcenter.androidbridge.GitHubReadFileTool
import ai.hybrid.commandcenter.androidbridge.ApprovalGrant
import ai.hybrid.commandcenter.androidbridge.PermissionCatalog
import ai.hybrid.commandcenter.androidbridge.NexusAccessibilityService
import ai.hybrid.commandcenter.androidbridge.PrivilegedDiagnosticsTool
import ai.hybrid.commandcenter.androidbridge.ReadScreenTool
import ai.hybrid.commandcenter.androidbridge.ScreenCaptureGrant
import ai.hybrid.commandcenter.androidbridge.OpenSettingsScreenTool
import ai.hybrid.commandcenter.androidbridge.ReadSystemSettingTool
import ai.hybrid.commandcenter.androidbridge.ScreenCaptureTool
import ai.hybrid.commandcenter.androidbridge.SystemSettingsCatalog
import ai.hybrid.commandcenter.androidbridge.ShizukuTransport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import ai.hybrid.commandcenter.androidbridge.TapElementTool
import ai.hybrid.commandcenter.androidbridge.RootShellExecutor
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import ai.hybrid.commandcenter.androidbridge.RootAccessProbe
import android.os.Build
import ai.hybrid.commandcenter.androidbridge.RootProbeResult
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
import java.text.SimpleDateFormat
import java.util.UUID
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Void = Color(0xFF080B12)
private val SurfaceDeep = Color(0x14FFFFFF)
private val SurfaceRaised = Color(0x0DFFFFFF)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val SignalGreen = Color(0xFF43D6A0)
private val SignalAmber = Color(0xFFF2B65D)
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF4F6FB)
private val SecondaryText = Color(0xFFA3ABBD)

/**
 * Device Core / NEXUS Bridge: real measured device state and an honest
 * capability matrix. Every value shown here comes from an actual Android API
 * call with its measurement time; nothing is decorative.
 */
@Composable
fun DeviceCoreScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val inspector = remember { DeviceInspector(context.applicationContext) }
    val scope = rememberCoroutineScope()

    var diagnostics by remember { mutableStateOf<DeviceDiagnostics?>(null) }
    var rootResult by remember { mutableStateOf<RootProbeResult?>(null) }
    var rootProbeAttempted by remember { mutableStateOf(false) }
    var rootProbing by remember { mutableStateOf(false) }
    var capabilityStates by remember { mutableStateOf<List<CapabilityState>>(emptyList()) }
    var shizukuInstalled by remember { mutableStateOf(false) }
    var measuredAtLabel by remember { mutableStateOf("—") }
    var gatewayResult by remember { mutableStateOf<String?>(null) }
    var gatewayData by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var gatewayRunning by remember { mutableStateOf(false) }
    var auditTrail by remember { mutableStateOf<List<BridgeAuditEntry>>(emptyList()) }
    val toolRegistry = remember {
        BridgeToolRegistry().apply {
            register(DeviceDiagnosticsTool(inspector))
            register(PrivilegedDiagnosticsTool(context.applicationContext))
            register(ReadScreenTool())
            register(TapElementTool())
            register(ScreenCaptureTool(context.applicationContext))
            register(ReadSystemSettingTool(context.applicationContext))
            register(OpenSettingsScreenTool(context.applicationContext))
            register(GitHubReadFileTool(context.applicationContext))
            register(GitHubProposeFixTool(context.applicationContext))
        }
    }
    var settingsOutcome by remember { mutableStateOf<String?>(null) }
    var settingsRunning by remember { mutableStateOf(false) }
    var captureOutcome by remember { mutableStateOf<String?>(null) }
    var captureRunning by remember { mutableStateOf(false) }
    fun runCapture() {
        captureRunning = true
        val requestId = UUID.randomUUID().toString()
        scope.launch {
            val journal = (context.applicationContext as NexusApplication).missionJournal
            val outcome = withContext(Dispatchers.IO) {
                val inputs = inspector.capabilityInputs(
                    rootProbeAttempted = rootProbeAttempted,
                    rootConfirmed = rootResult?.available == true
                )
                BridgeGateway(
                    registry = toolRegistry,
                    auditSink = { entry -> journal.recordToolAudit(entry) }
                ).execute(
                    request = BridgeRequest(
                        requestId = requestId,
                        toolId = "ui.capture_screen",
                        approval = ApprovalGrant(requestId, "ui.capture_screen", System.currentTimeMillis())
                    ),
                    snapshot = CapabilityMatrix.snapshot(inputs, System.currentTimeMillis()),
                    availableTransports = setOf(BridgeTransport.NATIVE),
                    initiator = "user"
                )
            }
            captureOutcome = when (outcome) {
                is GatewayOutcome.Success ->
                    "✓ " + outcome.result.message + " (" +
                        outcome.result.data["width"] + "×" + outcome.result.data["height"] + ")"
                is GatewayOutcome.Denied -> "Отклонено (" + outcome.stage + "): " + outcome.reason
                is GatewayOutcome.Failed -> "Ошибка (" + outcome.stage + "): " + outcome.reason
            }
            auditTrail = withContext(Dispatchers.IO) { journal.getRecentToolAudit(5) }
            captureRunning = false
        }
    }

    val captureConsent = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            ScreenCaptureGrant.store(result.resultCode, result.data)
            runCapture()
        } else {
            captureOutcome = "Система не выдала согласие на снимок экрана."
        }
    }

    var uiTapTarget by remember { mutableStateOf("") }
    var uiArmed by remember { mutableStateOf(false) }
    var uiRequestId by remember { mutableStateOf("") }
    var uiOutcome by remember { mutableStateOf<String?>(null) }
    var uiRunning by remember { mutableStateOf(false) }
    var privilegedCommand by remember { mutableStateOf(RootShellExecutor.PrivilegedCommand.PROCESS_LIST) }
    var privilegedArmed by remember { mutableStateOf(false) }
    var privilegedRequestId by remember { mutableStateOf("") }
    var privilegedOutcome by remember { mutableStateOf<String?>(null) }
    var privilegedRunning by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val diag = inspector.diagnostics()
                val inputs = inspector.capabilityInputs(
                    rootProbeAttempted = rootProbeAttempted,
                    rootConfirmed = rootResult?.available == true
                )
                Triple(diag, CapabilityMatrix.derive(inputs), inputs.shizukuInstalled)
            }
            diagnostics = snapshot.first
            capabilityStates = snapshot.second
            shizukuInstalled = snapshot.third
            measuredAtLabel = timeLabel(snapshot.first.measuredAtEpochMillis)
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        item {
            SectionCard(title = "Диагностика устройства", accent = Cyan) {
                val diag = diagnostics
                if (diag == null) {
                    Text("Измерение…", color = SecondaryText, fontSize = 13.sp)
                } else {
                    DiagnosticRow("Батарея", batteryLabel(diag))
                    DiagnosticRow("Оперативная память", ramLabel(diag))
                    DiagnosticRow("Накопитель (данные приложения)", storageLabel(diag))
                    DiagnosticRow("Температурный статус", diag.thermalLabel)
                    DiagnosticRow("Сеть", diag.networkLabel)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Измерено в $measuredAtLabel", color = SecondaryText, fontSize = 11.sp)
                        OutlinedButton(
                            onClick = { refresh() },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) { Text("Обновить", fontSize = 12.sp) }
                    }
                }
            }
        }

        item {
            SectionCard(title = "Привилегии доступа", accent = Violet) {
                PrivilegeRow(
                    name = "Standard",
                    status = "Активен",
                    statusColor = SignalGreen,
                    detail = "Публичные Android API, SAF и Intent. Root не требуется."
                )
                val shizukuStatus = remember(shizukuInstalled, measuredAtLabel) {
                    ShizukuTransport.status(shizukuInstalled)
                }
                PrivilegeRow(
                    name = "Shizuku",
                    status = when (shizukuStatus.state) {
                        ShizukuTransport.State.READY -> "Подключён (ADB)"
                        ShizukuTransport.State.PERMISSION_REQUIRED -> "Нужно разрешение"
                        ShizukuTransport.State.SERVICE_NOT_RUNNING -> "Служба не запущена"
                        ShizukuTransport.State.VERSION_TOO_OLD -> "Устаревшая версия"
                        ShizukuTransport.State.NOT_INSTALLED -> "Не найден"
                    },
                    statusColor = if (shizukuStatus.usable) SignalGreen else SignalAmber,
                    detail = shizukuStatus.detail
                )
                if (shizukuStatus.state == ShizukuTransport.State.PERMISSION_REQUIRED) {
                    OutlinedButton(
                        onClick = {
                            ShizukuTransport.requestPermission()
                            refresh()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text("Запросить разрешение Shizuku", fontSize = 12.sp) }
                }
                val root = rootResult
                PrivilegeRow(
                    name = "Root",
                    status = when {
                        rootProbing -> "Проверка…"
                        root == null -> "Не проверялся"
                        root.available -> "ПОДТВЕРЖДЁН (uid=0)"
                        else -> "Недоступен"
                    },
                    statusColor = when {
                        rootProbing -> SignalAmber
                        root == null -> SecondaryText
                        root.available -> SignalGreen
                        else -> SignalRed
                    },
                    detail = root?.detail
                        ?: "Проверка запускается только вручную и спрашивает Root-менеджер напрямую."
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        if (rootProbing) return@OutlinedButton
                        rootProbing = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { RootAccessProbe.check() }
                            rootResult = result
                            rootProbeAttempted = true
                            rootProbing = false
                            refresh()
                        }
                    },
                    enabled = !rootProbing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text(if (rootProbing) "Проверка…" else "Проверить Root", fontSize = 12.sp) }
            }
        }

        item {
            SectionCard(title = "Матрица возможностей", accent = Cyan) {
                if (capabilityStates.isEmpty()) {
                    Text("Сбор данных…", color = SecondaryText, fontSize = 13.sp)
                } else {
                    capabilityStates.forEach { state -> CapabilityRow(state) }
                }
            }
        }

        item {
            SectionCard(title = "Инструменты через Gateway", accent = Violet) {
                Text(
                    "Единственный путь исполнения инструментов: проверка возможностей, " +
                        "одобрений и лимитов, затем запись в аудит. Прямых вызовов в обход нет.",
                    color = SecondaryText, fontSize = 11.sp
                )
                PrivilegeRow(
                    name = "device.diagnostics",
                    status = "Только чтение",
                    statusColor = SignalGreen,
                    detail = "Диагностика устройства через NATIVE-транспорт; одобрение не требуется."
                )
                OutlinedButton(
                    onClick = {
                        if (gatewayRunning) return@OutlinedButton
                        gatewayRunning = true
                        scope.launch {
                            val journal =
                                (context.applicationContext as NexusApplication).missionJournal
                            val outcome = withContext(Dispatchers.IO) {
                                val inputs = inspector.capabilityInputs(
                                    rootProbeAttempted = rootProbeAttempted,
                                    rootConfirmed = rootResult?.available == true
                                )
                                BridgeGateway(
                                    registry = toolRegistry,
                                    auditSink = { entry -> journal.recordToolAudit(entry) }
                                ).execute(
                                    request = BridgeRequest(
                                        requestId = UUID.randomUUID().toString(),
                                        toolId = "device.diagnostics"
                                    ),
                                    snapshot = CapabilityMatrix.snapshot(
                                        inputs, System.currentTimeMillis()
                                    ),
                                    availableTransports = setOf(BridgeTransport.NATIVE),
                                    initiator = "user"
                                )
                            }
                            when (outcome) {
                                is GatewayOutcome.Success -> {
                                    gatewayResult = outcome.result.message
                                    gatewayData = outcome.result.data
                                }
                                is GatewayOutcome.Denied -> {
                                    gatewayResult = "Отклонено: " + outcome.reason
                                    gatewayData = emptyMap()
                                }
                                is GatewayOutcome.Failed -> {
                                    gatewayResult = "Ошибка: " + outcome.reason
                                    gatewayData = emptyMap()
                                }
                            }
                            auditTrail = withContext(Dispatchers.IO) {
                                journal.getRecentToolAudit(5)
                            }
                            gatewayRunning = false
                        }
                    },
                    enabled = !gatewayRunning,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) {
                    Text(
                        if (gatewayRunning) "Выполняется…" else "Выполнить device.diagnostics",
                        fontSize = 12.sp
                    )
                }
                val resultText = gatewayResult
                if (resultText != null) {
                    Text(resultText, color = PrimaryText, fontSize = 12.sp)
                    gatewayData.forEach { (key, value) ->
                        DiagnosticRow(key, value.take(40))
                    }
                }
                if (auditTrail.isNotEmpty()) {
                    Text(
                        "АУДИТ (последние записи)",
                        color = Violet, fontSize = 10.sp, fontWeight = FontWeight.Bold
                    )
                    auditTrail.forEach { entry ->
                        Text(
                            timeLabel(entry.createdAtEpochMillis) + " · " + entry.toolId +
                                " · " + entry.outcome,
                            color = SecondaryText, fontSize = 10.sp
                        )
                    }
                }
            }
        }

        item {
            SectionCard(title = "Экран и жесты (Accessibility)", accent = Cyan) {
                val serviceOn = NexusAccessibilityService.isRunning()
                Text(
                    "Служба NEXUS Bridge читает экран и выполняет нажатия только по запросу через " +
                        "Gateway. Она не реагирует на события сама и ничего не записывает.",
                    color = SecondaryText, fontSize = 11.sp
                )
                PrivilegeRow(
                    name = "Служба NEXUS Bridge",
                    status = if (serviceOn) "Включена" else "Выключена",
                    statusColor = if (serviceOn) SignalGreen else SignalAmber,
                    detail = if (serviceOn)
                        "Чтение дерева интерфейса и нажатия доступны."
                    else
                        "Включите NEXUS Bridge в системных настройках специальных возможностей."
                )
                if (!serviceOn) {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text("Открыть настройки спец. возможностей", fontSize = 12.sp) }
                } else {
                    OutlinedButton(
                        onClick = {
                            if (uiRunning) return@OutlinedButton
                            uiRunning = true
                            val requestId = UUID.randomUUID().toString()
                            scope.launch {
                                val journal = (context.applicationContext as NexusApplication).missionJournal
                                val outcome = withContext(Dispatchers.IO) {
                                    val inputs = inspector.capabilityInputs(
                                        rootProbeAttempted = rootProbeAttempted,
                                        rootConfirmed = rootResult?.available == true
                                    )
                                    BridgeGateway(
                                        registry = toolRegistry,
                                        auditSink = { entry -> journal.recordToolAudit(entry) }
                                    ).execute(
                                        request = BridgeRequest(requestId, "ui.read_screen"),
                                        snapshot = CapabilityMatrix.snapshot(inputs, System.currentTimeMillis()),
                                        availableTransports = setOf(BridgeTransport.ACCESSIBILITY),
                                        initiator = "user"
                                    )
                                }
                                uiOutcome = when (outcome) {
                                    is GatewayOutcome.Success -> outcome.result.message.take(2_000)
                                    is GatewayOutcome.Denied -> "Отклонено (" + outcome.stage + "): " + outcome.reason
                                    is GatewayOutcome.Failed -> "Ошибка (" + outcome.stage + "): " + outcome.reason
                                }
                                auditTrail = withContext(Dispatchers.IO) { journal.getRecentToolAudit(5) }
                                uiRunning = false
                            }
                        },
                        enabled = !uiRunning,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text(if (uiRunning) "Чтение…" else "Прочитать экран", fontSize = 12.sp) }

                    OutlinedTextField(
                        value = uiTapTarget,
                        onValueChange = {
                            uiTapTarget = it.take(200)
                            uiArmed = false
                        },
                        label = { Text("Текст элемента для нажатия") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            if (uiRunning || uiTapTarget.isBlank()) return@OutlinedButton
                            if (!uiArmed) {
                                uiArmed = true
                                uiRequestId = UUID.randomUUID().toString()
                                uiOutcome = "Подтвердите нажатие «" + uiTapTarget.take(40) +
                                    "». Одобрение одноразовое."
                                return@OutlinedButton
                            }
                            uiArmed = false
                            uiRunning = true
                            val requestId = uiRequestId
                            val target = uiTapTarget
                            scope.launch {
                                val journal = (context.applicationContext as NexusApplication).missionJournal
                                val outcome = withContext(Dispatchers.IO) {
                                    val inputs = inspector.capabilityInputs(
                                        rootProbeAttempted = rootProbeAttempted,
                                        rootConfirmed = rootResult?.available == true
                                    )
                                    BridgeGateway(
                                        registry = toolRegistry,
                                        auditSink = { entry -> journal.recordToolAudit(entry) }
                                    ).execute(
                                        request = BridgeRequest(
                                            requestId = requestId,
                                            toolId = "ui.tap",
                                            arguments = mapOf("target" to target),
                                            approval = ApprovalGrant(requestId, "ui.tap", System.currentTimeMillis())
                                        ),
                                        snapshot = CapabilityMatrix.snapshot(inputs, System.currentTimeMillis()),
                                        availableTransports = setOf(BridgeTransport.ACCESSIBILITY),
                                        initiator = "user"
                                    )
                                }
                                uiOutcome = when (outcome) {
                                    is GatewayOutcome.Success -> "✓ " + outcome.result.message
                                    is GatewayOutcome.Denied -> "Отклонено (" + outcome.stage + "): " + outcome.reason
                                    is GatewayOutcome.Failed -> "Ошибка (" + outcome.stage + "): " + outcome.reason
                                }
                                auditTrail = withContext(Dispatchers.IO) { journal.getRecentToolAudit(5) }
                                uiRunning = false
                            }
                        },
                        enabled = !uiRunning && uiTapTarget.isNotBlank(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (uiArmed) SignalRed else Violet
                        )
                    ) {
                        Text(if (uiArmed) "Подтвердить нажатие" else "Нажать элемент", fontSize = 12.sp)
                    }
                }
                val screenResult = uiOutcome
                if (screenResult != null) {
                    Text(screenResult, color = PrimaryText, fontSize = 10.sp)
                }
            }
        }

        item {
            SectionCard(title = "Системные настройки", accent = Cyan) {
                Text(
                    "NEXUS читает только публичные настройки и открывает системные экраны — " +
                        "изменения вносите вы сами. Ничего не меняется молча.",
                    color = SecondaryText, fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = {
                        if (settingsRunning) return@OutlinedButton
                        settingsRunning = true
                        scope.launch {
                            val journal = (context.applicationContext as NexusApplication).missionJournal
                            val lines = withContext(Dispatchers.IO) {
                                val inputs = inspector.capabilityInputs(
                                    rootProbeAttempted = rootProbeAttempted,
                                    rootConfirmed = rootResult?.available == true
                                )
                                val snapshot = CapabilityMatrix.snapshot(inputs, System.currentTimeMillis())
                                val gateway = BridgeGateway(
                                    registry = toolRegistry,
                                    auditSink = { entry -> journal.recordToolAudit(entry) }
                                )
                                SystemSettingsCatalog.Readable.entries.map { setting ->
                                    val outcome = gateway.execute(
                                        request = BridgeRequest(
                                            requestId = UUID.randomUUID().toString(),
                                            toolId = "system.read_setting",
                                            arguments = mapOf("setting" to setting.id)
                                        ),
                                        snapshot = snapshot,
                                        availableTransports = setOf(BridgeTransport.NATIVE),
                                        initiator = "user"
                                    )
                                    when (outcome) {
                                        is GatewayOutcome.Success -> outcome.result.message
                                        is GatewayOutcome.Denied -> setting.title + ": отклонено"
                                        is GatewayOutcome.Failed -> setting.title + ": ошибка"
                                    }
                                }
                            }
                            settingsOutcome = lines.joinToString("\n")
                            auditTrail = withContext(Dispatchers.IO) { journal.getRecentToolAudit(5) }
                            settingsRunning = false
                        }
                    },
                    enabled = !settingsRunning,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text(if (settingsRunning) "Чтение…" else "Прочитать настройки", fontSize = 12.sp) }
                val settingsResult = settingsOutcome
                if (settingsResult != null) {
                    Text(settingsResult, color = PrimaryText, fontSize = 11.sp)
                }
            }
        }

        item {
            SectionCard(title = "Снимок экрана", accent = Violet) {
                Text(
                    "Один кадр по вашему запросу. Android каждый раз показывает своё окно согласия, " +
                        "разрешение сгорает после одного снимка, файл остаётся во внутренней памяти приложения.",
                    color = SecondaryText, fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = {
                        if (captureRunning) return@OutlinedButton
                        val intent = ScreenCaptureGrant.consentIntent(context)
                        if (intent == null) {
                            captureOutcome = "Сервис захвата экрана недоступен на этом устройстве."
                        } else {
                            captureOutcome = null
                            captureConsent.launch(intent)
                        }
                    },
                    enabled = !captureRunning,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text(if (captureRunning) "Снимок…" else "Разрешить снимок и сделать кадр", fontSize = 12.sp) }
                val result = captureOutcome
                if (result != null) {
                    Text(result, color = PrimaryText, fontSize = 11.sp)
                }
            }
        }

        item {
            SectionCard(title = "Привилегированные инструменты (Root)", accent = Violet) {
                val rootConfirmed = rootResult?.available == true
                val shizukuUsable = remember(measuredAtLabel) {
                    ShizukuTransport.status(shizukuInstalled).usable
                }
                val privilegedTransport = when {
                    shizukuUsable -> BridgeTransport.SHIZUKU_ADB
                    rootConfirmed -> BridgeTransport.ROOT
                    else -> null
                }
                Text(
                    "Модель не может выполнить произвольную команду: доступен только фиксированный " +
                        "набор диагностических команд. Каждый запуск требует отдельного подтверждения " +
                        "и попадает в аудит.",
                    color = SecondaryText, fontSize = 11.sp
                )
                if (privilegedTransport == null) {
                    Text(
                        "Нет привилегированного доступа. Подключите Shizuku выше или нажмите «Проверить Root».",
                        color = SignalAmber, fontSize = 11.sp
                    )
                } else {
                    Text(
                        "Транспорт: " + if (privilegedTransport == BridgeTransport.SHIZUKU_ADB)
                            "Shizuku (права ADB)" else "Root (uid=0)",
                        color = SignalGreen, fontSize = 11.sp
                    )
                    Text("Команда:", color = SecondaryText, fontSize = 11.sp)
                    RootShellExecutor.PrivilegedCommand.entries.forEach { command ->
                        val selected = privilegedCommand == command
                        OutlinedButton(
                            onClick = {
                                privilegedCommand = command
                                privilegedArmed = false
                                privilegedOutcome = null
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = if (selected) Cyan else SecondaryText
                            )
                        ) { Text((if (selected) "● " else "") + command.title, fontSize = 11.sp) }
                    }
                    OutlinedButton(
                        onClick = {
                            if (privilegedRunning) return@OutlinedButton
                            if (!privilegedArmed) {
                                privilegedArmed = true
                                privilegedRequestId = UUID.randomUUID().toString()
                                privilegedOutcome =
                                    "Подтвердите выполнение «" + privilegedCommand.title +
                                        "». Одобрение одноразовое."
                                return@OutlinedButton
                            }
                            privilegedArmed = false
                            privilegedRunning = true
                            val requestId = privilegedRequestId
                            val command = privilegedCommand
                            scope.launch {
                                val journal = (context.applicationContext as NexusApplication).missionJournal
                                val outcome = withContext(Dispatchers.IO) {
                                    val inputs = inspector.capabilityInputs(
                                        rootProbeAttempted = rootProbeAttempted,
                                        rootConfirmed = rootConfirmed
                                    )
                                    BridgeGateway(
                                        registry = toolRegistry,
                                        auditSink = { entry -> journal.recordToolAudit(entry) }
                                    ).execute(
                                        request = BridgeRequest(
                                            requestId = requestId,
                                            toolId = "root.diagnostics",
                                            arguments = mapOf("command" to command.id),
                                            approval = ApprovalGrant(
                                                requestId, "root.diagnostics", System.currentTimeMillis()
                                            )
                                        ),
                                        snapshot = CapabilityMatrix.snapshot(inputs, System.currentTimeMillis()),
                                        availableTransports = setOf(privilegedTransport),
                                        initiator = "user"
                                    )
                                }
                                privilegedOutcome = when (outcome) {
                                    is GatewayOutcome.Success -> {
                                        val structured = outcome.result.data
                                            .filterKeys { it !in setOf("command", "command_title", "exit_code") }
                                            .entries.joinToString("\n") { it.key + ": " + it.value }
                                        (if (structured.isBlank()) "" else structured + "\n\n") +
                                            outcome.result.message.take(2_000)
                                    }
                                    is GatewayOutcome.Denied -> "Отклонено (" + outcome.stage + "): " + outcome.reason
                                    is GatewayOutcome.Failed -> "Ошибка (" + outcome.stage + "): " + outcome.reason
                                }
                                auditTrail = withContext(Dispatchers.IO) { journal.getRecentToolAudit(5) }
                                privilegedRunning = false
                            }
                        },
                        enabled = !privilegedRunning,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (privilegedArmed) SignalRed else Violet
                        )
                    ) {
                        Text(
                            when {
                                privilegedRunning -> "Выполняется…"
                                privilegedArmed -> "Подтвердить привилегированный запуск"
                                else -> "Выполнить: " + privilegedCommand.title
                            },
                            fontSize = 12.sp
                        )
                    }
                }
                val result = privilegedOutcome
                if (result != null) {
                    Text(result, color = PrimaryText, fontSize = 10.sp)
                }
            }
        }

        item {
            SectionCard(title = "Центр разрешений", accent = Cyan) {
                Text(
                    "Что приложение объявляет в манифесте и зачем. Список в коде сверяется с манифестом тестом.",
                    color = SecondaryText, fontSize = 11.sp
                )
                PermissionCatalog.declared.forEach { entry ->
                    val granted = when (entry.grant) {
                        PermissionCatalog.Grant.INSTALL_TIME -> "Выдано при установке" to SignalGreen
                        PermissionCatalog.Grant.USER_SETTING ->
                            if (context.packageManager.canRequestPackageInstalls()) "Разрешено" to SignalGreen
                            else "Выключено в системе" to SignalAmber
                        PermissionCatalog.Grant.RUNTIME ->
                            if (androidx.core.content.ContextCompat.checkSelfPermission(context, entry.permission) ==
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) "Выдано" to SignalGreen else "Не выдано (запрос по кнопке в функции)" to SignalAmber
                    }
                    PrivilegeRow(
                        name = entry.title,
                        status = granted.first,
                        statusColor = granted.second,
                        detail = entry.purpose
                    )
                }
                ai.hybrid.commandcenter.ui.Tag(ai.hybrid.commandcenter.ui.prettyTitle("Не запрашивается"), Violet)
                PermissionCatalog.notRequested.forEach { (name, why) ->
                    Text("• $name — $why", color = SecondaryText, fontSize = 11.sp)
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text("Открыть системные настройки приложения", fontSize = 12.sp) }
            }
        }

        item {
            SectionCard(title = "Сборка и среда", accent = Violet) {
                DiagnosticRow("Версия NEXUS", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                DiagnosticRow("Пакет", BuildConfig.APPLICATION_ID)
                DiagnosticRow("Тип сборки", if (BuildConfig.DEBUG) "debug" else "release")
                DiagnosticRow("Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                DiagnosticRow("Устройство", Build.MANUFACTURER + " " + Build.MODEL)
                DiagnosticRow("ABI", Build.SUPPORTED_ABIS.firstOrNull() ?: "неизвестно")
            }
        }

        item {
            Text(
                "Все статусы на этом экране — результат реальных проверок с отметкой времени. " +
                    "Недоступные функции показаны как недоступные, а не спрятаны.",
                color = SecondaryText,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun SectionCard(title: String, accent: Color, content: @Composable () -> Unit) {
    ai.hybrid.commandcenter.ui.SectionCard(
        title = title,
        icon = ai.hybrid.commandcenter.ui.iconForTitle(title),
        accent = accent
    ) { content() }
}

@Composable
private fun DiagnosticRow(name: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(name, color = SecondaryText, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PrivilegeRow(name: String, status: String, statusColor: Color, detail: String) {
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                ai.hybrid.commandcenter.ui.Tag(ai.hybrid.commandcenter.ui.prettyTitle(status), statusColor)
            }
            Text(detail, color = SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CapabilityRow(state: CapabilityState) {
    val (label, color) = when (state.availability) {
        CapabilityAvailability.AVAILABLE -> "Доступно" to SignalGreen
        CapabilityAvailability.PERMISSION_REQUIRED -> "Нужно разрешение" to SignalAmber
        CapabilityAvailability.DEGRADED -> "Частично" to SignalAmber
        CapabilityAvailability.UNAVAILABLE -> "Недоступно" to SignalRed
    }
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    capabilityTitle(state),
                    color = PrimaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                ai.hybrid.commandcenter.ui.Tag(ai.hybrid.commandcenter.ui.prettyTitle(label), color)
            }
            Text(state.reason, color = SecondaryText, fontSize = 11.sp)
        }
    }
}

private fun capabilityTitle(state: CapabilityState): String = when (state.capability) {
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.APP_LAUNCH -> "Запуск приложений"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.PACKAGE_METADATA -> "Метаданные пакетов"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.USER_SELECTED_FILES -> "Файлы (SAF)"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.NOTIFICATIONS -> "Уведомления"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.DEVICE_DIAGNOSTICS -> "Диагностика устройства"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.SYSTEM_SETTINGS -> "Системные настройки"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.PRIVILEGED_PACKAGE_OPERATIONS -> "Привилегированные операции с пакетами"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.PRIVILEGED_FILESYSTEM -> "Привилегированная файловая система"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.PROCESS_INSPECTION -> "Инспекция процессов"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.ACCESSIBLE_UI_TREE -> "Дерево интерфейса (Accessibility)"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.UI_GESTURES -> "Жесты интерфейса"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.SCREEN_CAPTURE -> "Захват экрана"
    ai.hybrid.commandcenter.androidbridge.BridgeCapability.REMOTE_CONNECTOR -> "MCP-коннекторы"
}

private fun batteryLabel(diag: DeviceDiagnostics): String {
    val percent = diag.batteryPercent ?: return "Нет данных от системы"
    val charging = when (diag.charging) {
        true -> ", заряжается"
        false -> ""
        null -> ""
    }
    return "$percent%$charging"
}

private fun ramLabel(diag: DeviceDiagnostics): String {
    val free = diag.ramFreeBytes ?: return "Нет данных от системы"
    val total = diag.ramTotalBytes ?: return "Нет данных от системы"
    return "${formatGb(free)} свободно из ${formatGb(total)}"
}

private fun storageLabel(diag: DeviceDiagnostics): String =
    "${formatGb(diag.storageFreeBytes)} свободно из ${formatGb(diag.storageTotalBytes)}"

private fun formatGb(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f ГБ", gb)
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
