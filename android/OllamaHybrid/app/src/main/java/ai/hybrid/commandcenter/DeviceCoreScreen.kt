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
        BridgeToolRegistry().apply { register(DeviceDiagnosticsTool(inspector)) }
    }

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
        modifier = modifier.fillMaxSize().background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("DEVICE CORE", color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("NEXUS BRIDGE · STANDARD-ПРОФИЛЬ АКТИВЕН", color = Cyan, fontSize = 11.sp, letterSpacing = 1.sp)
        }

        item {
            SectionCard(title = "ДИАГНОСТИКА УСТРОЙСТВА", accent = Cyan) {
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
            SectionCard(title = "ПРИВИЛЕГИИ ДОСТУПА", accent = Violet) {
                PrivilegeRow(
                    name = "Standard",
                    status = "АКТИВЕН",
                    statusColor = SignalGreen,
                    detail = "Публичные Android API, SAF и Intent. Root не требуется."
                )
                PrivilegeRow(
                    name = "Shizuku",
                    status = if (shizukuInstalled) "НЕ ПОДКЛЮЧЁН" else "НЕ НАЙДЕН",
                    statusColor = SignalAmber,
                    detail = if (shizukuInstalled)
                        "Приложение Shizuku установлено; привязка Binder ещё не реализована."
                    else
                        "Приложение Shizuku не установлено на устройстве."
                )
                val root = rootResult
                PrivilegeRow(
                    name = "Root",
                    status = when {
                        rootProbing -> "ПРОВЕРКА…"
                        root == null -> "НЕ ПРОВЕРЯЛСЯ"
                        root.available -> "ПОДТВЕРЖДЁН (uid=0)"
                        else -> "НЕДОСТУПЕН"
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
            SectionCard(title = "МАТРИЦА ВОЗМОЖНОСТЕЙ", accent = Cyan) {
                if (capabilityStates.isEmpty()) {
                    Text("Сбор данных…", color = SecondaryText, fontSize = 13.sp)
                } else {
                    capabilityStates.forEach { state -> CapabilityRow(state) }
                }
            }
        }

        item {
            SectionCard(title = "ИНСТРУМЕНТЫ ЧЕРЕЗ GATEWAY", accent = Violet) {
                Text(
                    "Единственный путь исполнения инструментов: проверка возможностей, " +
                        "одобрений и лимитов, затем запись в аудит. Прямых вызовов в обход нет.",
                    color = SecondaryText, fontSize = 11.sp
                )
                PrivilegeRow(
                    name = "device.diagnostics",
                    status = "READ_ONLY",
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
            SectionCard(title = "СБОРКА И СРЕДА", accent = Violet) {
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
    Surface(color = SurfaceDeep, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            content()
        }
    }
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
                Text(status, color = statusColor, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(detail, color = SecondaryText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun CapabilityRow(state: CapabilityState) {
    val (label, color) = when (state.availability) {
        CapabilityAvailability.AVAILABLE -> "ДОСТУПНО" to SignalGreen
        CapabilityAvailability.PERMISSION_REQUIRED -> "НУЖНО РАЗРЕШЕНИЕ" to SignalAmber
        CapabilityAvailability.DEGRADED -> "ЧАСТИЧНО" to SignalAmber
        CapabilityAvailability.UNAVAILABLE -> "НЕДОСТУПНО" to SignalRed
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
                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
