package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.ChatConversationRecord
import ai.hybrid.commandcenter.nexus.MaterialContext
import ai.hybrid.commandcenter.nexus.MaterialTextExtractor
import ai.hybrid.commandcenter.ui.BackdropScene
import ai.hybrid.commandcenter.ui.ConstellationModel
import ai.hybrid.commandcenter.ui.CouncilConstellation
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudIconButton
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.HudShapes
import ai.hybrid.commandcenter.ui.HudTile
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.MarkdownText
import ai.hybrid.commandcenter.ui.MotionPolicy
import ai.hybrid.commandcenter.ui.NexHoloAvatar
import ai.hybrid.commandcenter.ui.NexHoloStage
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusTypography
import ai.hybrid.commandcenter.ui.hud
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The scene the backdrop should show for the chat right now. */
fun chatScene(chat: ChatController): BackdropScene =
    if (chat.messages.isEmpty() && !chat.isRunning) BackdropScene.CITY else BackdropScene.DIM

/**
 * NEXUS chat: the hologram greets on an empty bridge, then steps back into
 * the header while the conversation runs as HUD transmissions. One input,
 * one mode switch (NEX or the AI Council), everything else is one tap away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTab(
    chat: ChatController,
    draft: String,
    onDraftChange: (String) -> Unit,
    onOpenSetup: () -> Unit,
    onVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val effects = LocalEffects.current
    val haptics = LocalHapticFeedback.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    var attachment by remember { mutableStateOf<ChatAttachment?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var historyOpen by remember { mutableStateOf(false) }
    var focusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2_600)
            notice = null
        }
    }
    // Keep the newest line in view. The index comes from the data, not from the
    // last layout, so a long history opens at its end even before the first frame.
    val empty = chat.messages.isEmpty() && !chat.isRunning
    val itemCount = chat.messages.takeLast(60).size +
        (if (chat.isRunning && chat.councilMode) 1 else 0) +
        (if (chat.isRunning) 1 else 0) +
        (if (chat.lastRunFailed && !chat.isRunning) 1 else 0)
    LaunchedEffect(chat.conversationId, chat.messages.size, chat.streamingAnswer.length, chat.isRunning, chat.lastRunFailed, empty) {
        if (!empty && itemCount > 0) listState.scrollToItem(itemCount - 1, 100_000)
    }
    LaunchedEffect(focusRequested) {
        if (focusRequested) {
            runCatching { focus.requestFocus() }
            focusRequested = false
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "файл"
                    val bytes = resolver.openInputStream(uri)?.use { input ->
                        val data = input.readBytes()
                        require(data.size <= 25L * 1024 * 1024) { "Файл больше 25 МБ — сократите его или загрузите в Архив частями." }
                        data
                    } ?: error("Не удалось открыть файл.")
                    name to MaterialTextExtractor.extract(name, bytes)
                }
            }
            picked.onSuccess { (name, extraction) ->
                when (extraction) {
                    is MaterialTextExtractor.Extraction.Text -> {
                        attachment = ChatAttachment(name, extraction.text, extraction.format)
                        notice = "Файл прикреплён: $name"
                    }
                    is MaterialTextExtractor.Extraction.Unsupported -> notice = extraction.reason
                }
            }.onFailure { notice = it.message ?: "Не удалось прочитать файл." }
        }
    }

    fun send() {
        if (draft.isBlank() && attachment == null) return
        if (!chat.send(draft, attachment)) {
            onOpenSetup()
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onDraftChange("")
        attachment = null
    }

    Column(modifier) {
        ChatHeader(
            chat = chat,
            compact = !empty,
            mode = effects.effective,
            onHistory = { historyOpen = true },
            onNewChat = { chat.newChat(); attachment = null },
            onStatus = onOpenSetup
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (empty) {
                HomeHero(
                    chat = chat,
                    mode = effects.effective,
                    onOpenSetup = onOpenSetup,
                    onPrefix = { prefix ->
                        onDraftChange(prefix)
                        focusRequested = true
                    },
                    onFile = { filePicker.launch(arrayOf("*/*")) },
                    onCouncil = {
                        chat.councilMode = true
                        focusRequested = true
                    },
                    onVoice = onVoice
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("chat-list"),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    items(chat.messages.takeLast(60), key = { it.id }) { message ->
                        if (message.role == "assistant") {
                            NexTransmission(
                                text = message.content,
                                onCopy = {
                                    clipboard.setText(AnnotatedString(ai.hybrid.commandcenter.ui.MarkdownParser.plain(message.content)))
                                    notice = "Скопировано"
                                },
                                onSpeak = { notice = chat.speak(message.content) ?: "Озвучиваю…" }
                            )
                        } else {
                            UserTransmission(message.content)
                        }
                    }
                    if (chat.isRunning && chat.councilMode) {
                        item(key = "council-live") { CouncilLiveCard(chat, effects.effective) }
                    }
                    if (chat.isRunning) {
                        item(key = "streaming") { NexTransmission(text = chat.streamingAnswer, streaming = true) }
                    }
                    if (chat.lastRunFailed && !chat.isRunning) {
                        item(key = "error") {
                            ErrorTransmission(
                                message = chat.lastError ?: chat.status,
                                onRetry = { chat.retry() },
                                onSetup = onOpenSetup
                            )
                        }
                    }
                }
            }
        }

        // ---- notices, attachment, mode, composer ----
        Column(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp, top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            notice?.let { line ->
                Text(line, style = CyberText.Label, color = Cyber.TextDim, modifier = Modifier.align(Alignment.CenterHorizontally),
                    textAlign = TextAlign.Center)
            }
            attachment?.let { att ->
                Row(
                    Modifier.hud(Cyber.Cyan, cut = 6.dp, accents = false).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NexIcon(NexIcons.Paperclip, tint = Cyber.Cyan, size = 16.dp)
                    Text(att.name, style = NexusTypography.labelLarge, color = Cyber.Text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp))
                    Text("${att.text.length / 1000} тыс. знаков", style = CyberText.Label, color = Cyber.TextFaint)
                    HudIconButton(NexIcons.X, { attachment = null }, size = 30.dp, tint = Cyber.TextDim, contentDescription = "Убрать файл")
                }
            }
            ModeSwitch(council = chat.councilMode, enabled = !chat.isRunning) { chat.councilMode = it }
            Composer(
                text = draft,
                onText = onDraftChange,
                council = chat.councilMode,
                running = chat.isRunning,
                hasAttachment = attachment != null,
                focus = focus,
                onAttach = { filePicker.launch(arrayOf("*/*")) },
                onSend = { send() },
                onStop = { chat.cancel() },
                onVoice = onVoice
            )
        }
    }

    if (historyOpen) {
        HistorySheet(
            chat = chat,
            onDismiss = { historyOpen = false },
            onOpen = { id -> chat.openConversation(id); historyOpen = false },
            onNew = { chat.newChat(); historyOpen = false }
        )
    }
}

/** Header: the wordmark on an empty bridge, a live hologram avatar during a talk, and one honest status chip. */
@Composable
private fun ChatHeader(
    chat: ChatController,
    compact: Boolean,
    mode: EffectsMode,
    onHistory: () -> Unit,
    onNewChat: () -> Unit,
    onStatus: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (compact) {
                NexHoloAvatar(chat.nexState, mode, size = 42.dp, speaking = chat.speaking || (chat.isRunning && chat.streamingAnswer.isNotEmpty()))
                Spacer(Modifier.width(8.dp))
            }
            Text("NEXUS", style = CyberText.Wordmark.copy(fontSize = if (compact) 19.sp else 22.sp))
            Spacer(Modifier.weight(1f))
            HudIconButton(NexIcons.History, onHistory, size = 40.dp, contentDescription = "История чатов")
            Spacer(Modifier.width(8.dp))
            HudIconButton(NexIcons.SquarePen, onNewChat, size = 40.dp, filled = true, enabled = !chat.isRunning,
                contentDescription = "Новый чат")
        }
        Spacer(Modifier.height(6.dp))
        val (text, color) = coreStatus(chat)
        HudChip(text, color, onClick = onStatus, modifier = Modifier.testTag("core-status"))
    }
}

/** One honest line about the core: what is connected and what last happened. */
fun coreStatus(chat: ChatController): Pair<String, Color> = when {
    !chat.settingsLoaded -> "Читаю настройки…" to Cyber.TextDim
    chat.isRunning -> (if (chat.councilMode) "Совет работает…" else "NEX думает…") to Cyber.Violet
    chat.needsSetup -> "Ядро не подключено — нажми, чтобы подключить" to Cyber.Yellow
    chat.lastRunFailed -> "Ошибка связи с ядром" to Cyber.Red
    else -> {
        val name = chat.coreModelName ?: "модель"
        val latency = chat.lastLatencyMs?.let { " · ответ " + String.format(java.util.Locale.ROOT, "%.1f", it / 1000.0) + " с" }.orEmpty()
        ("Ядро: " + name + " · " + chat.coreLocation.label + latency) to Cyber.Green
    }
}

private data class QuickTool(@DrawableRes val icon: Int, val title: String, val subtitle: String, val accent: Color, val prefix: String?)

@Composable
private fun HomeHero(
    chat: ChatController,
    mode: EffectsMode,
    onOpenSetup: () -> Unit,
    onPrefix: (String) -> Unit,
    onFile: () -> Unit,
    onCouncil: () -> Unit,
    onVoice: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NexHoloStage(
            state = chat.nexState,
            mode = mode,
            size = if (chat.needsSetup) 214.dp else 262.dp,
            speaking = chat.speaking,
            modifier = Modifier.clickable(onClick = onVoice)
        )
        val name = chat.userName
        Text(
            if (name.isNotBlank()) "ПРИВЕТ, " + name.uppercase() else "NEXUS НА СВЯЗИ",
            style = CyberText.Hero.copy(fontSize = 27.sp),
            textAlign = TextAlign.Center
        )
        Text(
            when {
                chat.needsSetup -> "Я — NEX. Подключи мне ядро, и я начну отвечать."
                chat.councilMode -> "Совет из восьми агентов готов. Опиши задачу."
                else -> "Я — NEX, твой ИИ. Спроси что угодно или нажми на меня, чтобы говорить голосом."
            },
            style = NexusTypography.bodyMedium,
            color = Cyber.TextDim,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp)
        )
        if (chat.settingsLoaded && chat.needsSetup) {
            HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Yellow, title = "Ядро не подключено", index = "SYS-00") {
                HudButton("Подключить ИИ", onOpenSetup, Modifier.fillMaxWidth().testTag("connect-core"),
                    icon = NexIcons.Zap, accent = Cyber.Yellow)
                Text(
                    "Три пути: прямо на телефоне (без интернета), на твоём компьютере (Ollama) или в облаке по ключу.",
                    style = NexusTypography.bodySmall, color = Cyber.TextDim
                )
            }
        } else {
            HudSectionTitle("Быстрый старт", trailing = "06")
            val tools = listOf(
                QuickTool(NexIcons.PenLine, "Написать", "Письмо, пост, текст", Cyber.Cyan, "Напиши "),
                QuickTool(NexIcons.Languages, "Перевести", "На любой язык", Cyber.Magenta, "Переведи на русский: "),
                QuickTool(NexIcons.Lightbulb, "Объяснить", "Просто о сложном", Cyber.Violet, "Объясни простыми словами: "),
                QuickTool(NexIcons.ScanText, "Кратко", "Выжимка из текста", Cyber.Cyan, "Сделай краткую выжимку: "),
                QuickTool(NexIcons.Paperclip, "Файл", "PDF, Word, Excel", Cyber.Magenta, null),
                QuickTool(NexIcons.Users, "Совет ИИ", "8 агентов", Cyber.Yellow, null)
            )
            for (row in tools.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for ((i, tool) in row.withIndex()) {
                        val index = String.format(java.util.Locale.ROOT, "%02d", tools.indexOf(tool) + 1)
                        HudTile(
                            tool.icon, tool.title, Modifier.weight(1f), subtitle = tool.subtitle, index = index, accent = tool.accent
                        ) {
                            when {
                                tool.prefix != null -> onPrefix(tool.prefix)
                                tool.title == "Файл" -> onFile()
                                else -> onCouncil()
                            }
                        }
                        if (i == 0 && row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModeSwitch(council: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        ModeChip("NEX · один ответ", NexIcons.Sparkles, !council, Cyber.Cyan, enabled, Modifier.testTag("mode-nex")) { onChange(false) }
        ModeChip("Совет · 8 агентов", NexIcons.Users, council, Cyber.Magenta, enabled, Modifier.testTag("mode-council")) { onChange(true) }
    }
}

@Composable
private fun ModeChip(label: String, @DrawableRes icon: Int, active: Boolean, accent: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = HudShapes.Small
    Row(
        modifier
            .clip(shape)
            .background(if (active) accent.copy(alpha = 0.16f) else Color(0xB3060C19))
            .border(1.dp, if (active) accent.copy(alpha = 0.8f) else Cyber.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        NexIcon(icon, tint = if (active) accent else Cyber.TextFaint, size = 14.dp)
        Text(label.uppercase(), style = CyberText.Label, color = if (active) accent else Cyber.TextFaint, maxLines = 1)
    }
}

@Composable
private fun Composer(
    text: String,
    onText: (String) -> Unit,
    council: Boolean,
    running: Boolean,
    hasAttachment: Boolean,
    focus: FocusRequester,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onVoice: () -> Unit
) {
    val accent = if (council) Cyber.Magenta else Cyber.Cyan
    Row(
        Modifier
            .fillMaxWidth()
            .hud(accent, cut = 12.dp, fill = Color(0xF2040914), borderAlpha = 0.55f)
            .padding(6.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        HudIconButton(NexIcons.Plus, onAttach, size = 44.dp, tint = accent, enabled = !running, contentDescription = "Прикрепить файл")
        Box(Modifier.weight(1f).heightIn(min = 44.dp).padding(vertical = 11.dp, horizontal = 4.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(
                    if (council) "Задача для совета…" else "Напиши NEXUS…",
                    style = NexusTypography.bodyLarge, color = Cyber.TextFaint
                )
            }
            BasicTextField(
                value = text,
                onValueChange = { onText(it.take(32_000)) },
                enabled = !running,
                textStyle = NexusTypography.bodyLarge.copy(color = Cyber.Text),
                cursorBrush = SolidColor(accent),
                maxLines = 6,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("composer")
            )
        }
        when {
            running -> HudIconButton(NexIcons.Square, onStop, size = 44.dp, tint = Cyber.Red, contentDescription = "Остановить")
            text.isBlank() && !hasAttachment -> HudIconButton(NexIcons.Mic, onVoice, size = 44.dp, tint = accent, contentDescription = "Голос")
            else -> HudIconButton(
                NexIcons.ArrowUp, onSend, size = 44.dp, tint = accent, filled = true,
                contentDescription = if (council) "Запустить совет" else "Отправить",
                modifier = Modifier.testTag("send")
            )
        }
    }
}

/** NEX's reply: a lit rail on the left, a mono call sign, clean Markdown. */
@Composable
private fun NexTransmission(
    text: String,
    streaming: Boolean = false,
    onCopy: (() -> Unit)? = null,
    onSpeak: (() -> Unit)? = null
) {
    Row(Modifier.fillMaxWidth().testTag(if (streaming) "nex-streaming" else "nex-message")) {
        Box(
            Modifier
                .padding(top = 4.dp)
                .width(2.dp)
                .heightIn(min = 36.dp)
                .background(Brush.verticalGradient(listOf(Cyber.Cyan, Cyber.Violet.copy(alpha = 0.2f))))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HudLabel(if (streaming) "NEX › передача…" else "NEX ›", color = Cyber.Cyan)
            if (streaming && text.isBlank()) {
                ThinkingLine()
            } else {
                MarkdownText(
                    text,
                    trailing = if (streaming) buildAnnotatedString { withStyle(SpanStyle(color = Cyber.Cyan)) { append(" ▌") } } else null
                )
            }
            if (!streaming && (onCopy != null || onSpeak != null)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    onCopy?.let { MessageAction(NexIcons.Copy, "Копировать", it) }
                    onSpeak?.let { MessageAction(NexIcons.Volume2, "Озвучить", it) }
                }
            }
        }
    }
}

@Composable
private fun MessageAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(HudShapes.Small).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        NexIcon(icon, tint = Cyber.TextFaint, size = 15.dp, contentDescription = label)
        Text(label.uppercase(), style = CyberText.Label.copy(fontSize = 9.5.sp), color = Cyber.TextFaint)
    }
}

/** A scanning bar while a request is really in flight. */
@Composable
private fun ThinkingLine() {
    val mode = LocalEffects.current.effective
    val phase = if (MotionPolicy.animates(mode)) {
        val transition = rememberInfiniteTransition(label = "thinking")
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_100, easing = LinearEasing), RepeatMode.Restart), label = "scan").value
    } else 0.5f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("ОБРАБАТЫВАЮ ЗАПРОС", style = CyberText.Label, color = Cyber.Violet)
        Row(Modifier.width(180.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val segments = 18
            for (i in 0 until segments) {
                val d = kotlin.math.abs(i / (segments - 1f) - phase)
                Box(Modifier.weight(1f).height(6.dp).background(Cyber.Violet.copy(alpha = (1f - d * 2.2f).coerceIn(0.12f, 1f))))
            }
        }
    }
}

/** The owner's message: a magenta HUD panel on the right; attached files show as a line above. */
@Composable
private fun UserTransmission(content: String) {
    val material = content.contains(MaterialContext.MATERIAL_BEGIN)
    val source = if (material) content.lineSequence().firstOrNull { it.startsWith("Источник: ") }?.removePrefix("Источник: ")?.trim() else null
    val question = if (material) content.substringAfterLast("Вопрос: ").trim() else content
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(max = 310.dp)
                .hud(Cyber.Magenta, cut = 10.dp, fill = Color(0xE01A0A22), borderAlpha = 0.6f)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("user-message"),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (source != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NexIcon(NexIcons.Paperclip, tint = Cyber.Magenta, size = 13.dp)
                    Text(source, style = CyberText.Label, color = Cyber.Magenta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(question, style = NexusTypography.bodyLarge, color = Cyber.Text)
        }
    }
}

@Composable
private fun ErrorTransmission(message: String, onRetry: () -> Unit, onSetup: () -> Unit) {
    HudPanel(Modifier.fillMaxWidth().testTag("error-panel"), accent = Cyber.Red, title = "Сбой передачи", index = "ERR") {
        Text(message, style = NexusTypography.bodyMedium, color = Cyber.Text)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HudButton("Повторить", onRetry, icon = NexIcons.RotateCcw, accent = Cyber.Red, height = 42.dp)
            HudButton("Ядро", onSetup, style = HudButtonStyle.Secondary, icon = NexIcons.Cpu, height = 42.dp)
        }
    }
}

@Composable
private fun CouncilLiveCard(chat: ChatController, mode: EffectsMode) {
    HudPanel(Modifier.fillMaxWidth(), accent = Cyber.Magenta, title = "Совет работает", index = "8 АГЕНТОВ") {
        val nodes = ConstellationModel.fromLive(chat.councilAgents, chat.liveAgentEvents)
        CouncilConstellation(
            nodes = nodes,
            manager = ConstellationModel.managerPhase(nodes, chat.isRunning, chat.lastCouncilStatus),
            mode = mode,
            height = 230.dp,
            events = chat.agentEventLog,
            showInspector = false
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(chat: ChatController, onDismiss: () -> Unit, onOpen: (String) -> Unit, onNew: () -> Unit) {
    var conversations by remember { mutableStateOf<List<ChatConversationRecord>?>(null) }
    LaunchedEffect(Unit) { conversations = chat.conversations() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFA040914),
        shape = HudShapes.chamfer(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            HudSectionTitle("История чатов", trailing = conversations?.size?.toString())
            HudButton("Новый чат", onNew, Modifier.fillMaxWidth(), icon = NexIcons.SquarePen, enabled = !chat.isRunning)
            val list = conversations
            when {
                list == null -> HudLabel("Загружаю…")
                list.isEmpty() -> Text("Чатов пока нет.", style = NexusTypography.bodyMedium, color = Cyber.TextDim)
                else -> list.forEach { conversation ->
                    val active = conversation.id == chat.conversationId
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .hud(if (active) Cyber.Cyan else Cyber.Violet, cut = 8.dp, borderAlpha = if (active) 0.6f else 0.25f, accents = active)
                            .clickable(enabled = !chat.isRunning) { onOpen(conversation.id) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NexIcon(NexIcons.MessageSquare, tint = if (active) Cyber.Cyan else Cyber.TextDim, size = 18.dp)
                        Column(Modifier.weight(1f)) {
                            Text(conversation.title.ifBlank { "Чат" }, style = NexusTypography.titleSmall, color = Cyber.Text,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale("ru"))
                                    .format(java.util.Date(conversation.updatedAt)),
                                style = CyberText.Label, color = Cyber.TextFaint
                            )
                        }
                        if (active) HudLabel("Открыт", color = Cyber.Cyan)
                    }
                }
            }
        }
    }
}
