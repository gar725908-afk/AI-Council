package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudDivider
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudRow
import ai.hybrid.commandcenter.ui.HudTextField
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexusTypography
import ai.hybrid.commandcenter.ui.SegmentedTabs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * «Настройки»: the core first (what thinks and where), then the owner's
 * name and voice habits, the look, and the advanced tools.
 */
@Composable
fun SettingsTab(
    chat: ChatController,
    onOpenSetup: () -> Unit,
    onOpenModule: (ModulePage) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val effects = LocalEffects.current
    var name by remember(chat.userName) { mutableStateOf(chat.userName) }
    var speakAnswers by remember { mutableStateOf(NexusProfile.speakVoiceAnswers(context)) }
    var checkLine by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var checking by remember { mutableStateOf(false) }

    Column(modifier) {
        TabHeader("Настройки", "Ядро ИИ, профиль, голос и оформление", NexIcons.Settings2)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ---- core ----
            val (statusText, statusColor) = coreStatus(chat)
            HudPanel(Modifier.fillMaxWidth().testTag("settings-core"), accent = if (chat.needsSetup) Cyber.Yellow else Cyber.Cyan,
                title = "Ядро ИИ", index = "CORE") {
                HudChip(statusText, statusColor)
                if (!chat.needsSetup && chat.settingsLoaded) {
                    InfoRow("Где думает", chat.coreLocation.label)
                    InfoRow("Модель", chat.coreModelName ?: "—")
                    if (!chat.usesOnDeviceModel) InfoRow("Сервис", chat.providerKind.displayName)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    HudButton(if (chat.needsSetup) "Подключить" else "Сменить", onOpenSetup, Modifier.weight(1f).testTag("settings-change-core"),
                        icon = NexIcons.Zap, accent = if (chat.needsSetup) Cyber.Yellow else Cyber.Cyan)
                    HudButton(
                        if (checking) "Проверяю…" else "Проверить", {
                            scope.launch {
                                checking = true
                                checkLine = runCatching {
                                    if (chat.usesOnDeviceModel) {
                                        requireNotNull(chat.activeOnDeviceModel) { "Модель на телефоне не выбрана." }
                                        "Модель на месте и проверена по SHA-256 при включении." to true
                                    } else {
                                        val key = chat.storedKey(chat.providerKind)
                                        val models = chat.modelCatalog.listModels(chat.providerKind, chat.endpoint, key)
                                        val has = models.any { it == chat.model }
                                        (if (has) "Сервер отвечает, модель «${chat.model}» доступна." else
                                            "Сервер отвечает, но модели «${chat.model}» в списке нет — выбери другую.") to has
                                    }
                                }.getOrElse { ChatController.friendlyError(it) to false }
                                checking = false
                            }
                        },
                        Modifier.weight(1f), style = HudButtonStyle.Secondary, icon = NexIcons.Activity,
                        enabled = !checking && !chat.needsSetup
                    )
                }
                checkLine?.let { (text, ok) -> Text(text, style = NexusTypography.bodySmall, color = if (ok) Cyber.Green else Cyber.Amber) }
                if (!chat.usesOnDeviceModel && chat.hasSavedKey && chat.providerKind != ProviderKind.OLLAMA) {
                    HudButton("Удалить ключ", { scope.launch { chat.removeKey(chat.providerKind) } },
                        style = HudButtonStyle.Danger, icon = NexIcons.KeyRound, height = 40.dp)
                }
            }

            // ---- profile ----
            HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Magenta, title = "Профиль", index = "USR") {
                HudTextField(name, { name = it.take(40) }, label = "Как к тебе обращаться", placeholder = "Имя",
                    accent = Cyber.Magenta, onDone = { chat.saveName(name) })
                HudButton("Сохранить имя", { chat.saveName(name) }, style = HudButtonStyle.Secondary, accent = Cyber.Magenta,
                    icon = NexIcons.Check, height = 40.dp, enabled = name.trim() != chat.userName)
            }

            // ---- voice ----
            HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Violet, title = "Голос", index = "VOX") {
                HudRow(
                    "Отвечать голосом",
                    subtitle = "Когда спрашиваешь голосом, NEX читает ответ вслух (нужен русский голос в синтезаторе Android).",
                    icon = NexIcons.Volume2, accent = Cyber.Violet
                ) {
                    Switch(
                        checked = speakAnswers,
                        onCheckedChange = {
                            speakAnswers = it
                            NexusProfile.saveSpeakVoiceAnswers(context, it)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Cyber.OnNeon, checkedTrackColor = Cyber.Violet,
                            uncheckedThumbColor = Cyber.TextDim, uncheckedTrackColor = Cyber.Panel
                        )
                    )
                }
                VoicePicker(chat)
            }

            // ---- world ----
            HudPanel(Modifier.fillMaxWidth().testTag("settings-theme"), accent = Cyber.Cyan, title = "Мир NEXUS", index = "テーマ") {
                Text(
                    "Тема меняет весь интерфейс: фон, цвета, кольцо вокруг камеры и подписи.",
                    style = NexusTypography.bodySmall, color = Cyber.TextDim
                )
                ai.hybrid.commandcenter.ui.ThemePicker(
                    selected = ai.hybrid.commandcenter.ui.ThemeState.theme,
                    onSelect = { ai.hybrid.commandcenter.ui.ThemeState.save(context, it) }
                )
                HudRow(
                    "Кольцо вокруг камеры",
                    subtitle = "Живой индикатор NEX вокруг фронтальной камеры: крутится, когда он думает, мигает при ошибке.",
                    icon = NexIcons.ScanFace, accent = Cyber.Cyan
                ) {
                    Switch(
                        checked = ai.hybrid.commandcenter.ui.ThemeState.cameraRing,
                        onCheckedChange = { ai.hybrid.commandcenter.ui.ThemeState.saveCameraRing(context, it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Cyber.OnNeon, checkedTrackColor = Cyber.Cyan,
                            uncheckedThumbColor = Cyber.TextDim, uncheckedTrackColor = Cyber.Panel
                        )
                    )
                }
            }

            // ---- look ----
            HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Cyan, title = "Оформление", index = "FX") {
                val modes = listOf(EffectsMode.STATIC, EffectsMode.SUBTLE, EffectsMode.EXPERIMENTAL)
                SegmentedTabs(
                    options = modes.map { it.title },
                    selected = modes.indexOf(effects.choice).coerceAtLeast(0),
                    onSelect = { effects.choose(modes[it]) }
                )
                Text(
                    when {
                        effects.effective != effects.choice -> "Сейчас статично: Android просит меньше анимаций или включена экономия заряда."
                        effects.choice == EffectsMode.EXPERIMENTAL -> "Голограмма живая: дышит, моргает, частицы и сканлайны."
                        effects.choice == EffectsMode.SUBTLE -> "Спокойно: только плавные движения, без частиц."
                        else -> "Ничего не двигается — экономит заряд."
                    },
                    style = NexusTypography.bodySmall, color = Cyber.TextDim
                )
            }

            // ---- advanced ----
            HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Violet, title = "Для продвинутых", index = "ADV") {
                AdvancedRow("Модели на телефоне", "Скачать, включить, импортировать .gguf", NexIcons.Boxes) { onOpenModule(ModulePage.MODELS) }
                HudDivider()
                AdvancedRow("Журнал ошибок и ремонт", "Что сломалось и как NEX это чинит", NexIcons.Wrench) { onOpenModule(ModulePage.REPAIRS) }
                HudDivider()
                AdvancedRow("Устройство и разрешения", "Доступы, root, Shizuku, диагностика", NexIcons.Smartphone) { onOpenModule(ModulePage.DEVICE) }
                HudDivider()
                AdvancedRow("Хранилище и приватность", "Данные приложения, экспорт, очистка", NexIcons.HardDrive) { onOpenModule(ModulePage.SYSTEM) }
                HudDivider()
                AdvancedRow("Расширения и обновления", "Платформа NEXUS", NexIcons.Layers) { onOpenModule(ModulePage.PLATFORM) }
            }

            Text(
                "NEXUS " + BuildConfig.VERSION_NAME + " · шрифты Exo 2, Inter, JetBrains Mono, Noto Sans JP (OFL) · иконки Lucide (ISC)",
                style = CyberText.Label, color = Cyber.TextFaint
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun InfoRow(name: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(name.uppercase(), style = CyberText.Label, color = Cyber.TextFaint, modifier = Modifier.weight(1f))
        Text(value, style = NexusTypography.bodyMedium, color = Cyber.Text, maxLines = 1)
    }
}

@Composable
private fun AdvancedRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit) {
    HudRow(title, subtitle = subtitle, icon = icon, accent = Cyber.Violet, onClick = onClick) {
        NexIcon(NexIcons.ChevronRight, tint = Cyber.TextFaint, size = 18.dp)
    }
}

/**
 * Picks the system voice NEX speaks with. Offline voices come first; a voice
 * marked «через интернет» sends the spoken text to the synthesiser's server.
 */
@Composable
private fun VoicePicker(chat: ChatController) {
    var voices by remember { mutableStateOf(emptyList<ai.hybrid.commandcenter.nexus.VoiceOption>()) }
    var selected by remember { mutableStateOf(chat.selectedVoice) }
    var rate by remember { mutableStateOf(chat.speechRate) }
    var note by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        repeat(10) {
            voices = chat.voices()
            if (voices.isNotEmpty()) return@LaunchedEffect
            kotlinx.coroutines.delay(500)
        }
    }
    ai.hybrid.commandcenter.ui.HudDivider(accent = Cyber.Violet)
    ai.hybrid.commandcenter.ui.HudLabel("Голос синтезатора", color = Cyber.Violet)
    if (voices.isEmpty()) {
        Text(
            "Русских голосов в синтезаторе Android не найдено. Установи их: Настройки → Специальные возможности → Синтез речи.",
            style = NexusTypography.bodySmall, color = Cyber.TextDim
        )
    } else {
        val options = listOf<ai.hybrid.commandcenter.nexus.VoiceOption?>(null) + voices.take(8)
        options.forEach { voice ->
            val active = voice?.name == selected
            Row(
                Modifier.fillMaxWidth().clickable {
                    selected = voice?.name
                    chat.selectVoice(voice?.name)
                    note = chat.speak("Я на связи. Так звучит мой голос.") ?: null
                }.padding(vertical = 6.dp).testTag("voice-" + (voice?.name ?: "default")),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(12.dp).border(1.dp, Cyber.Violet, CircleShape)
                        .then(if (active) Modifier.background(Cyber.Violet, CircleShape) else Modifier)
                )
                Text(voice?.label ?: "Системный по умолчанию", style = NexusTypography.bodyMedium, color = Cyber.Text, modifier = Modifier.weight(1f))
                if (voice != null) {
                    ai.hybrid.commandcenter.ui.HudChip(if (voice.offline) "Офлайн" else "Через интернет", if (voice.offline) Cyber.Green else Cyber.Amber)
                }
            }
        }
        Text("Нажми на голос — NEX скажет фразу, чтобы было слышно, как он звучит.", style = NexusTypography.bodySmall, color = Cyber.TextFaint)
    }
    val rates = listOf(0.85f to "Медленно", 1.0f to "Обычно", 1.2f to "Быстро")
    ai.hybrid.commandcenter.ui.SegmentedTabs(
        options = rates.map { it.second },
        selected = rates.indexOfFirst { kotlin.math.abs(it.first - rate) < 0.05f }.coerceAtLeast(1),
        onSelect = {
            rate = rates[it].first
            chat.setSpeechRate(rate)
            note = chat.speak("Скорость речи изменена.")
        }
    )
    note?.let { Text(it, style = NexusTypography.bodySmall, color = Cyber.Amber) }
}
