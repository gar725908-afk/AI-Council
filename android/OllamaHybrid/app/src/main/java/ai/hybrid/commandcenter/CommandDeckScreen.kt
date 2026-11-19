package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceDiagnostics
import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.ChatConversationRecord
import ai.hybrid.commandcenter.nexus.EndpointReachability
import ai.hybrid.commandcenter.nexus.HealthCheckResult
import ai.hybrid.commandcenter.nexus.InstalledNexusModel
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.nexus.NexusModelStorage
import ai.hybrid.commandcenter.nexus.ProviderHealthCheck
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
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
import kotlinx.coroutines.launch
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

/** Everything the deck shows, read from real stores in one background pass. */
private data class DeckState(
    val providerSettings: ProviderSettings,
    val activeLocalModel: InstalledNexusModel?,
    val installedModelCount: Int,
    val recentMissions: List<MissionRecord>,
    val recentConversations: List<ChatConversationRecord>,
    val diagnostics: DeviceDiagnostics
)

/**
 * Command Deck per handoff section 4.1: one primary action zone and compact
 * cards whose every value comes from a real store or measurement. States that
 * were never checked say so instead of pretending.
 */
@Composable
fun CommandDeckScreen(
    onNavigate: (Int) -> Unit,
    onCompose: (String, Int) -> Unit = { _, destination -> onNavigate(destination) },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<DeckState?>(null) }

    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val journal = (appContext as NexusApplication).missionJournal
            val storage = NexusModelStorage(appContext)
            DeckState(
                providerSettings = ProviderSettingsStore(appContext).load(),
                activeLocalModel = storage.activeModel(),
                installedModelCount = storage.listInstalled().size,
                recentMissions = journal.getRecentMissions(3),
                recentConversations = journal.getRecentConversations(1),
                diagnostics = DeviceInspector(appContext).diagnostics()
            )
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("NEXUS", color = PrimaryText, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("SIRIUS TECHNOLOGY SYSTEM", color = Cyan, fontSize = 11.sp, letterSpacing = 2.sp)
        }

        val deck = state
        if (deck == null) {
            item {
                Text("Чтение состояния…", color = SecondaryText, fontSize = 13.sp)
                Spacer(Modifier.height(22.dp))
            }
        } else {
        item { ComposerCard(onCompose) }
            item { ModelProfileCard(deck, onNavigate) }
            item { MissionsCard(deck, onNavigate) }
            item { StudioCard(deck, onNavigate) }
            item { DeviceHealthCard(deck.diagnostics, onNavigate) }
            item {
                Text(
                    "Каждое значение на этой панели прочитано из реального хранилища или измерено " +
                        "системой. Непроверенные состояния помечены как непроверенные.",
                    color = SecondaryText,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

@Composable
private fun ComposerCard(onCompose: (String, Int) -> Unit) {
    var task by remember { mutableStateOf("") }
    DeckCard(title = "COMMAND COMPOSER", accent = Violet) {
        OutlinedTextField(
            value = task,
            onValueChange = { task = it.take(32_000) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Задача") },
            placeholder = { Text("Опишите, что должна сделать система…") },
            minLines = 2
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (task.isNotBlank()) {
                DeckButton("В AI Studio", Cyan) {
                    onCompose(task, 1)
                    task = ""
                }
                DeckButton("В Council", Violet) {
                    onCompose(task, 2)
                    task = ""
                }
            } else {
                Text(
                    "Введите задачу — её можно отправить одиночной модели или совету агентов.",
                    color = SecondaryText, fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ModelProfileCard(deck: DeckState, onNavigate: (Int) -> Unit) {
    val settings = deck.providerSettings
    val providerLabel = runCatching { ProviderKind.valueOf(settings.providerKind).displayName }
        .getOrDefault(settings.providerKind)
    val cloudConfigured = settings.endpoint.isNotBlank() || settings.model.isNotBlank()
    val local = deck.activeLocalModel

    DeckCard(title = "АКТИВНЫЙ ПРОФИЛЬ МОДЕЛИ", accent = Cyan) {
        if (local != null) {
            ValueRow("Локальная модель (ON_DEVICE)", local.displayName)
            ValueRow("Runtime", local.runtime.name)
            ValueRow("Размер", formatGb(local.sizeBytes))
        } else {
            ValueRow("Локальная модель (ON_DEVICE)", "Не активирована")
        }
        Spacer(Modifier.height(4.dp))
        val scope = rememberCoroutineScope()
        var health by remember { mutableStateOf<HealthCheckResult?>(null) }
        var checking by remember { mutableStateOf(false) }
        if (cloudConfigured) {
            ValueRow("Облачный провайдер", providerLabel)
            if (settings.model.isNotBlank()) ValueRow("Модель", settings.model)
            if (settings.endpoint.isNotBlank()) ValueRow("Endpoint", settings.endpoint.take(48))
            val currentHealth = health
            when {
                checking -> StatusLine("Проверка соединения…", SignalAmber)
                currentHealth == null ->
                    StatusLine("Соединение в этой сессии не проверялось", SignalAmber)
                currentHealth.reachability == EndpointReachability.REACHABLE -> StatusLine(
                    "${currentHealth.detail} · ${currentHealth.latencyMillis} мс · " +
                        timeLabel(currentHealth.checkedAtEpochMillis),
                    SignalGreen
                )
                else -> StatusLine(
                    "${currentHealth.detail} · " + timeLabel(currentHealth.checkedAtEpochMillis),
                    SignalRed
                )
            }
        } else {
            ValueRow("Облачный провайдер", "Не настроен")
        }
        ValueRow(
            "Установлено моделей",
            if (deck.installedModelCount == 0) "Нет" else deck.installedModelCount.toString()
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeckButton("Модели", Cyan) { onNavigate(3) }
            DeckButton("AI Studio", Cyan) { onNavigate(1) }
            if (cloudConfigured && settings.endpoint.isNotBlank() && !checking) {
                DeckButton("Проверить", SignalGreen) {
                    checking = true
                    scope.launch {
                        health = ProviderHealthCheck.check(settings.endpoint)
                        checking = false
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionsCard(deck: DeckState, onNavigate: (Int) -> Unit) {
    DeckCard(title = "МИССИИ И COUNCIL", accent = Violet) {
        if (deck.recentMissions.isEmpty()) {
            Text(
                "Миссий ещё не было. Council запустит первую: 1 менеджер и до 8 исполнителей.",
                color = SecondaryText,
                fontSize = 13.sp
            )
        } else {
            deck.recentMissions.forEach { mission ->
                MissionRow(mission)
            }
        }
        Spacer(Modifier.height(6.dp))
        DeckButton("Открыть Council", Violet) { onNavigate(2) }
    }
}

@Composable
private fun MissionRow(mission: MissionRecord) {
    val (label, color) = missionStatusLabel(mission.status)
    Surface(color = SurfaceRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    mission.goal.take(64),
                    color = PrimaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Text(timeLabel(mission.updatedAt), color = SecondaryText, fontSize = 10.sp)
            val error = mission.error
            if (!error.isNullOrBlank()) {
                Text(error.take(96), color = SignalRed, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun StudioCard(deck: DeckState, onNavigate: (Int) -> Unit) {
    DeckCard(title = "AI STUDIO", accent = Cyan) {
        val lastChat = deck.recentConversations.firstOrNull()
        if (lastChat == null) {
            Text("Диалогов ещё не было.", color = SecondaryText, fontSize = 13.sp)
        } else {
            ValueRow("Последний диалог", lastChat.title.take(40))
            ValueRow("Обновлён", timeLabel(lastChat.updatedAt))
        }
        Spacer(Modifier.height(6.dp))
        DeckButton("Продолжить в AI Studio", Cyan) { onNavigate(1) }
    }
}

@Composable
private fun DeviceHealthCard(diag: DeviceDiagnostics, onNavigate: (Int) -> Unit) {
    DeckCard(
        title = "DEVICE HEALTH",
        accent = Cyan,
        modifier = Modifier.clickable { onNavigate(4) }
    ) {
        val battery = diag.batteryPercent?.let { "$it%" } ?: "Нет данных"
        val ram = diag.ramFreeBytes?.let { formatGb(it) + " свободно" } ?: "Нет данных"
        ValueRow("Батарея", battery)
        ValueRow("Память", ram)
        ValueRow("Нагрев", diag.thermalLabel)
        ValueRow("Сеть", diag.networkLabel)
        StatusLine("Измерено в ${timeLabel(diag.measuredAtEpochMillis)} · подробнее — Устройство", SecondaryText)
    }
}

@Composable
private fun DeckCard(
    title: String,
    accent: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        color = SurfaceDeep,
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun ValueRow(name: String, value: String) {
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
private fun StatusLine(text: String, color: Color) {
    Text(text, color = color, fontSize = 10.sp)
}

@Composable
private fun DeckButton(label: String, accent: Color, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = accent)
    ) { Text(label, fontSize = 12.sp) }
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

private fun formatGb(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f ГБ", gb)
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
