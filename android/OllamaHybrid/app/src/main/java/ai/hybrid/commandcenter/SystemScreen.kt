package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.MaterialLibrary
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.nexus.MissionExport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import ai.hybrid.commandcenter.nexus.NexusModelStorage
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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

enum class SystemTab(val label: String) {
    DEVICE("УСТРОЙСТВО"),
    MODELS("МОДЕЛИ"),
    SETTINGS("НАСТРОЙКИ"),
    CONNECTORS("КОННЕКТОРЫ"),
    REPAIRS("ИСПРАВЛЕНИЯ"),
    CENTER("ЦЕНТР")
}

/** System space: Device Core, Model Center, Settings and the Platform Center. */
@Composable
fun SystemScreen(
    modifier: Modifier = Modifier,
    initialTab: Int = 0,
    onSendToCouncil: (String) -> Unit = {}
) {
    var tab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, SystemTab.values().lastIndex)) }
    Column(modifier.fillMaxSize().background(Void)) {
        ScrollableTabRow(
            selectedTabIndex = tab,
            containerColor = SurfaceDeep,
            contentColor = Cyan,
            edgePadding = 8.dp
        ) {
            SystemTab.values().forEachIndexed { index, entry ->
                Tab(selected = tab == index, onClick = { tab = index }) {
                    Text(
                        entry.label,
                        color = if (tab == index) Cyan else SecondaryText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp)
                    )
                }
            }
        }
        when (SystemTab.values()[tab]) {
            SystemTab.DEVICE -> DeviceCoreScreen(Modifier.fillMaxSize())
            SystemTab.MODELS -> ModelCenterScreen(Modifier.fillMaxSize())
            SystemTab.SETTINGS -> SettingsPanel(Modifier.fillMaxSize())
            SystemTab.CONNECTORS -> McpConnectorsPanel(Modifier.fillMaxSize())
            SystemTab.REPAIRS -> RepairPanel(
                modifier = Modifier.fillMaxSize(),
                onSendToCouncil = onSendToCouncil
            )
            SystemTab.CENTER -> PlatformCenterScreen(Modifier.fillMaxSize())
        }
    }
}

private data class StorageFacts(
    val missions: Int,
    val conversations: Int,
    val auditEntries: Int,
    val materials: Int,
    val installedModels: Int,
    val journalDbBytes: Long,
    val libraryDbBytes: Long
)

/**
 * Settings per handoff section 4.9: data & privacy with real counts and
 * sizes, destructive actions behind an explicit two-step confirmation,
 * diagnostics from the real audit trail, and About.
 */
@Composable
fun SettingsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var facts by remember { mutableStateOf<StorageFacts?>(null) }
    var armedAction by remember { mutableStateOf<String?>(null) }
    var statusLine by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            facts = withContext(Dispatchers.IO) {
                val app = context.applicationContext as NexusApplication
                val journal = app.missionJournal
                StorageFacts(
                    missions = journal.countMissions(),
                    conversations = journal.countConversations(),
                    auditEntries = journal.countToolAudit(),
                    materials = MaterialLibrary(app).listItems(1000).size,
                    installedModels = NexusModelStorage(app).listInstalled().size,
                    journalDbBytes = app.getDatabasePath("nexus_missions.db").length(),
                    libraryDbBytes = app.getDatabasePath("nexus_library.db").length()
                )
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val journal = (context.applicationContext as NexusApplication).missionJournal
                    val missions = journal.getRecentMissions(500)
                    val results = missions.associate { it.id to journal.getAgentResults(it.id) }
                    val json = MissionExport.toJson(missions, results, System.currentTimeMillis())
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("Не удалось открыть файл для записи.")
                    missions.size
                }
            }
            statusLine = outcome.fold(
                onSuccess = { "Экспортировано миссий: $it (JSON, без ключей)." },
                onFailure = { "Экспорт не выполнен: " + (it.message ?: "ошибка") }
            )
        }
    }

    fun runDestructive(key: String, label: String, action: suspend () -> Unit) {
        if (armedAction != key) {
            armedAction = key
            statusLine = "Нажмите ещё раз, чтобы подтвердить: $label. Отмена снимает защиту."
            return
        }
        armedAction = null
        scope.launch {
            withContext(Dispatchers.IO) { action() }
            statusLine = "Выполнено: $label."
            refresh()
        }
    }

    LazyColumn(
        modifier = modifier.background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("НАСТРОЙКИ", color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("ДАННЫЕ · ПРИВАТНОСТЬ · ДИАГНОСТИКА", color = Cyan, fontSize = 11.sp, letterSpacing = 1.sp)
        }

        item { EffectsCard() }

        item {
            SettingsCard(title = "ХРАНИЛИЩЕ", accent = Cyan) {
                val current = facts
                if (current == null) {
                    Text("Чтение…", color = SecondaryText, fontSize = 13.sp)
                } else {
                    FactRow("Миссии Council", current.missions.toString())
                    FactRow("Диалоги AI Studio", current.conversations.toString())
                    FactRow("Записей аудита инструментов", current.auditEntries.toString())
                    FactRow("Материалов в Архиве", current.materials.toString())
                    FactRow("Локальных моделей", current.installedModels.toString())
                    FactRow("База журнала", formatMb(current.journalDbBytes))
                    FactRow("База Архива", formatMb(current.libraryDbBytes))
                }
            }
        }

        item {
            SettingsCard(title = "ПРИВАТНОСТЬ — КАК ЕСТЬ В КОДЕ", accent = Violet) {
                FactText("Ключи провайдеров хранятся в Android Keystore, не в базе и не в логах.")
                FactText("Содержимое миссий и чатов шифруется локальным ключом устройства.")
                FactText("Сеть используется только для настроенных endpoint-ов и источников моделей по HTTPS; загрузки проверяются по SHA-256.")
                FactText("Телеметрии и сторонней аналитики в приложении нет.")
                FactText("Root и привилегированные действия выполняются только по явному запросу и проходят через Gateway с аудитом.")
            }
        }

        item {
            SettingsCard(title = "ЭКСПОРТ", accent = Cyan) {
                FactText("Ваши данные — ваши. Миссии и результаты агентов выгружаются в JSON через системный диалог сохранения.")
                OutlinedButton(
                    onClick = { exportLauncher.launch("nexus-missions.json") },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text("Экспортировать миссии (JSON)", fontSize = 12.sp) }
            }
        }

        item {
            SettingsCard(title = "УПРАВЛЕНИЕ ДАННЫМИ", accent = SignalAmber) {
                FactText("Каждое действие требует двух нажатий. Отменить после выполнения нельзя.")
                DestructiveButton(
                    label = "Очистить миссии и чаты",
                    armed = armedAction == "journal"
                ) {
                    runDestructive("journal", "очистка миссий и чатов") {
                        (context.applicationContext as NexusApplication).missionJournal.clearMissionsAndChats()
                    }
                }
                DestructiveButton(
                    label = "Очистить аудит инструментов",
                    armed = armedAction == "audit"
                ) {
                    runDestructive("audit", "очистка аудита инструментов") {
                        (context.applicationContext as NexusApplication).missionJournal.clearToolAudit()
                    }
                }
                DestructiveButton(
                    label = "Удалить все материалы Архива",
                    armed = armedAction == "library"
                ) {
                    runDestructive("library", "удаление материалов Архива") {
                        MaterialLibrary(context.applicationContext).clearAll()
                    }
                }
                if (armedAction != null) {
                    OutlinedButton(
                        onClick = {
                            armedAction = null
                            statusLine = "Действие отменено."
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryText)
                    ) { Text("Отмена", fontSize = 12.sp) }
                }
                val status = statusLine
                if (status != null) {
                    Text(status, color = if (armedAction != null) SignalAmber else SignalGreen, fontSize = 11.sp)
                }
            }
        }

        item {
            SettingsCard(title = "ДИАГНОСТИКА", accent = Cyan) {
                var recentAudit by remember { mutableStateOf<List<String>>(emptyList()) }
                LaunchedEffect(facts) {
                    recentAudit = withContext(Dispatchers.IO) {
                        (context.applicationContext as NexusApplication).missionJournal
                            .getRecentToolAudit(8)
                            .map { entry ->
                                timeLabel(entry.createdAtEpochMillis) + " · " + entry.toolId +
                                    " · " + entry.outcome + " · " + entry.initiator
                            }
                    }
                }
                if (recentAudit.isEmpty()) {
                    Text("Аудит инструментов пока пуст.", color = SecondaryText, fontSize = 12.sp)
                } else {
                    recentAudit.forEach { line -> Text(line, color = SecondaryText, fontSize = 10.sp) }
                }
                FactRow("Каталог данных", context.applicationContext.filesDir.absolutePath.take(60))
            }
        }

        item {
            SettingsCard(title = "О ПРОГРАММЕ", accent = Violet) {
                FactRow("NEXUS", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                FactRow("Пакет", BuildConfig.APPLICATION_ID)
                FactRow("Сборка", if (BuildConfig.DEBUG) "debug" else "release")
                FactText("Вселенная SIRIUS · NEXUS · COUNCIL. Состояние реализации — docs/NEXUS_IMPLEMENTATION_MATRIX_RU.md в репозитории.")
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

/** Static / Subtle / Experimental per handoff 5; system reduced-motion always wins. */
@Composable
private fun EffectsCard() {
    val effects = LocalEffects.current
    SettingsCard(title = "ОФОРМЛЕНИЕ И ЭФФЕКТЫ", accent = Violet) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EffectsMode.entries.forEach { mode ->
                val selected = effects.choice == mode
                OutlinedButton(
                    onClick = { effects.choose(mode) },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) Void else Violet,
                        containerColor = if (selected) Violet else Color.Transparent
                    )
                ) { Text(mode.title, fontSize = 11.sp) }
            }
        }
        FactText(
            when (effects.choice) {
                EffectsMode.STATIC -> "Без анимаций: ядро и NEX неподвижны, состояние передаётся цветом и текстом."
                EffectsMode.SUBTLE -> "Анимация только на реальные события: работа Council, голосовой ввод, готовый результат."
                EffectsMode.EXPERIMENTAL -> "Ярче свечение и ореолы. Анимации по-прежнему только на реальные события."
            }
        )
        if (effects.effective != effects.choice) {
            Text(
                "Сейчас действует «" + effects.effective.title + "»: в системе отключены анимации или включено энергосбережение.",
                color = SignalAmber,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, accent: Color, content: @Composable () -> Unit) {
    Surface(color = SurfaceDeep, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            content()
        }
    }
}

@Composable
private fun FactRow(name: String, value: String) {
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
private fun FactText(text: String) {
    Text(text, color = SecondaryText, fontSize = 12.sp)
}

@Composable
private fun DestructiveButton(label: String, armed: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (armed) SurfaceRaised else SurfaceDeep,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedButton(
            onClick = onClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (armed) SignalRed else SignalAmber)
        ) { Text(if (armed) "ПОДТВЕРДИТЬ: $label" else label, fontSize = 12.sp) }
    }
}

private fun formatMb(bytes: Long): String =
    String.format(Locale.US, "%.2f МБ", bytes / (1024.0 * 1024.0))

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
