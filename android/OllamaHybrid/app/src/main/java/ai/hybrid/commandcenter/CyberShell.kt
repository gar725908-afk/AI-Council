package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.VoiceInput
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.HudButton
import ai.hybrid.commandcenter.ui.HudButtonStyle
import ai.hybrid.commandcenter.ui.HudIconButton
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudTile
import ai.hybrid.commandcenter.ui.LocalEffects
import ai.hybrid.commandcenter.ui.MarkdownParser
import ai.hybrid.commandcenter.ui.MotionPolicy
import ai.hybrid.commandcenter.ui.NexHoloStage
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusTypography
import ai.hybrid.commandcenter.ui.holoColorsFor
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** The four places of the app; voice sits in the middle of the dock. */
enum class AppTab(val label: String, @DrawableRes val icon: Int) {
    CHAT("Чат", NexIcons.MessageSquare),
    COUNCIL("Совет", NexIcons.Users),
    MODULES("Модули", NexIcons.LayoutGrid),
    SETTINGS("Настройки", NexIcons.Settings2)
}

/** Tools that open as a full page from «Модули» (or from other screens). */
enum class ModulePage(val title: String, val subtitle: String, @DrawableRes val icon: Int, val accent: Color) {
    BROWSER("Браузер", "Сайты и агент в браузере", NexIcons.Globe, Cyber.Cyan),
    ARCHIVE("Архив", "Файлы, PDF и поиск по материалам", NexIcons.Archive, Cyber.Violet),
    MODELS("Модели", "ИИ прямо на телефоне", NexIcons.Boxes, Cyber.Cyan),
    DEVICE("Устройство", "Состояние и управление телефоном", NexIcons.Smartphone, Cyber.Green),
    REPAIRS("Ремонт", "Журнал ошибок и самоисправление", NexIcons.Wrench, Cyber.Amber),
    CONNECTORS("Коннекторы", "MCP-серверы и сервисы", NexIcons.Plug, Cyber.Magenta),
    STATUS("Мостик", "Состояние всех систем", NexIcons.Gauge, Cyber.Cyan),
    PLATFORM("Платформа", "Расширения и обновления", NexIcons.Layers, Cyber.Violet),
    SYSTEM("Система", "Хранилище и приватность", NexIcons.HardDrive, Cyber.TextDim)
}

/** Bottom dock: four tabs and the voice core in the centre. */
@Composable
fun CyberDock(
    selected: AppTab,
    onSelect: (AppTab) -> Unit,
    coreState: NexState,
    onCore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.fillMaxWidth().height(86.dp)) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(64.dp)
                .drawBehind {
                    drawRect(Color(0xF2030712))
                    drawRect(
                        Brush.horizontalGradient(
                            listOf(Color.Transparent, Cyber.Cyan.copy(alpha = 0.7f), Cyber.Magenta.copy(alpha = 0.7f), Color.Transparent)
                        ),
                        size = Size(size.width, 1.dp.toPx())
                    )
                },
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockItem(AppTab.CHAT, selected == AppTab.CHAT, Modifier.weight(1f)) { onSelect(AppTab.CHAT) }
            DockItem(AppTab.COUNCIL, selected == AppTab.COUNCIL, Modifier.weight(1f)) { onSelect(AppTab.COUNCIL) }
            Spacer(Modifier.width(84.dp))
            DockItem(AppTab.MODULES, selected == AppTab.MODULES, Modifier.weight(1f)) { onSelect(AppTab.MODULES) }
            DockItem(AppTab.SETTINGS, selected == AppTab.SETTINGS, Modifier.weight(1f)) { onSelect(AppTab.SETTINGS) }
        }
        CoreButton(coreState, onCore, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun DockItem(tab: AppTab, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color by animateColorAsState(if (active) Cyber.Cyan else Cyber.TextFaint, tween(160), label = "dock")
    Column(
        modifier
            .fillMaxSize()
            .clickable(role = Role.Tab, onClick = onClick)
            .testTag("tab-" + tab.name.lowercase())
            .semantics { contentDescription = tab.label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.width(22.dp).height(2.dp).background(if (active) Cyber.Cyan else Color.Transparent))
        Spacer(Modifier.height(7.dp))
        NexIcon(tab.icon, tint = color, size = 21.dp)
        Spacer(Modifier.height(4.dp))
        Text(tab.label.uppercase(), style = CyberText.Label.copy(fontSize = 9.5.sp, letterSpacing = 0.8.sp), color = color, maxLines = 1)
        ai.hybrid.commandcenter.ui.ThemeCaptions.tab(tab.label)?.let { caption ->
            Text(caption, style = ai.hybrid.commandcenter.ui.JpLabel.copy(fontSize = 7.5.sp, letterSpacing = 0.5.sp), color = color.copy(alpha = 0.7f), maxLines = 1)
        }
    }
}

/** The voice core: a lit disc with a turning HUD ring, coloured by the real state. */
@Composable
private fun CoreButton(state: NexState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val mode = LocalEffects.current.effective
    val (top, bottom) = holoColorsFor(state)
    val c1 by animateColorAsState(top, tween(300), label = "core1")
    val c2 by animateColorAsState(bottom, tween(300), label = "core2")
    val spin = if (MotionPolicy.animates(mode)) {
        rememberInfiniteTransition(label = "core").animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "spin")
    } else remember { mutableFloatStateOf(20f) }
    Box(
        modifier
            .size(76.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("voice-core")
            .semantics { contentDescription = "Голос: говорить с NEX" },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            drawCircle(Color(0xFF030712), radius = r)
            drawCircle(Brush.radialGradient(listOf(c1.copy(alpha = 0.45f), Color.Transparent), radius = r), radius = r)
            // dashed HUD ring
            val dashes = 24
            for (i in 0 until dashes) {
                if (i % 3 == 2) continue
                drawArc(
                    c1.copy(alpha = 0.85f), startAngle = spin.value + i * (360f / dashes), sweepAngle = 360f / dashes * 0.6f,
                    useCenter = false, topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                    size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt)
                )
            }
            // reactor-style inner ring: ten segments turning against the outer dashes
            val inner = r * 0.80f
            for (i in 0 until 10) {
                drawArc(
                    c2.copy(alpha = 0.75f), startAngle = -spin.value * 1.5f + i * 36f + 4f, sweepAngle = 26f,
                    useCenter = false, topLeft = Offset(size.width / 2f - inner, size.height / 2f - inner),
                    size = Size(inner * 2f, inner * 2f), style = Stroke(3.dp.toPx(), cap = StrokeCap.Butt)
                )
            }
            drawCircle(Brush.linearGradient(listOf(c1, c2)), radius = r * 0.62f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), Color.Transparent), radius = r * 0.62f), radius = r * 0.62f)
            drawCircle(Color.White.copy(alpha = 0.22f), radius = r * 0.62f, style = Stroke(1.dp.toPx()))
        }
        NexIcon(NexIcons.Mic, tint = Cyber.OnNeon, size = 26.dp)
    }
}

/** Page header used by the Council, Modules and Settings tabs. */
@Composable
fun TabHeader(title: String, subtitle: String, @DrawableRes icon: Int, accent: Color = Cyber.Cyan, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(ai.hybrid.commandcenter.ui.HudShapes.Small).background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) { NexIcon(icon, tint = accent, size = 22.dp) }
        Column(Modifier.weight(1f)) {
            Text(title.uppercase(), style = CyberText.Title.copy(letterSpacing = 3.sp), color = Cyber.Text)
            Text(subtitle, style = NexusTypography.bodySmall, color = Cyber.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** «Модули»: every tool as a tile — one tap opens it as a page. */
@Composable
fun ModulesTab(onOpen: (ModulePage) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        TabHeader("Модули", "Инструменты NEXUS: браузер, архив, модели, устройство и другое", NexIcons.LayoutGrid)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val pages = ModulePage.entries
            for (row in pages.chunked(2)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (page in row) {
                        HudTile(
                            page.icon, page.title, Modifier.weight(1f).testTag("module-" + page.name.lowercase()),
                            subtitle = page.subtitle,
                            index = String.format(java.util.Locale.ROOT, "%02d", page.ordinal + 1),
                            accent = page.accent
                        ) { onOpen(page) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** A full page over the shell: back button, title, then the tool itself. */
@Composable
fun ToolPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            HudIconButton(NexIcons.ArrowLeft, onBack, size = 40.dp, contentDescription = "Назад")
            Text(title.uppercase(), style = CyberText.Title.copy(letterSpacing = 3.sp), color = Cyber.Text, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

/**
 * Talk mode: NEX listens, thinks and answers aloud — the big hologram does it
 * all on screen. Every phase is real: the recogniser's level drives the
 * mouth while listening, the request state drives thinking, the synthesiser
 * drives speaking.
 */
@Composable
fun VoiceOverlay(chat: ChatController, onClose: () -> Unit, onNeedSetup: () -> Unit) {
    val context = LocalContext.current
    val mode = LocalEffects.current.effective
    val voice = remember { VoiceInput(context.applicationContext) }
    var transcript by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("Говори…") }
    var listening by remember { mutableStateOf(false) }
    var level by remember { mutableFloatStateOf(0f) }
    var asked by remember { mutableStateOf(false) }
    val smooth by animateFloatAsState(level, tween(120), label = "mic")

    fun submit(text: String) {
        val question = text.trim()
        if (question.isBlank()) return
        if (!chat.send(question, fromVoice = true)) {
            onClose()
            onNeedSetup()
            return
        }
        asked = true
    }

    fun start() {
        transcript = ""
        asked = false
        voice.start { event ->
            when (event) {
                VoiceInput.Event.Listening -> { line = "Слушаю…"; listening = true }
                is VoiceInput.Event.Level -> level = ((event.rmsDb + 2f) / 12f).coerceIn(0f, 1f)
                is VoiceInput.Event.Partial -> transcript = event.text
                is VoiceInput.Event.Final -> {
                    transcript = event.text
                    listening = false
                    level = 0f
                    submit(event.text)
                }
                is VoiceInput.Event.Error -> { line = event.reason; listening = false; level = 0f }
                VoiceInput.Event.Ended -> { listening = false; level = 0f }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else line = "Без доступа к микрофону голос не работает. Разреши его в настройках Android."
    }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    DisposableEffect(Unit) { onDispose { voice.stop() } }
    BackHandler { voice.stop(); chat.stopSpeaking(); onClose() }

    val state = when {
        listening -> NexState.LISTENING
        chat.isRunning -> NexState.THINKING
        chat.lastRunFailed && asked -> NexState.ERROR
        chat.speaking -> NexState.SUCCESS
        else -> chat.nexState
    }
    val answer = if (asked) {
        if (chat.isRunning) chat.streamingAnswer
        else chat.messages.lastOrNull()?.takeIf { it.role == "assistant" }?.content.orEmpty()
    } else ""

    ai.hybrid.commandcenter.ui.CyberBackdrop(
        Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .testTag("voice-overlay"),
        scene = ai.hybrid.commandcenter.ui.BackdropScene.CITY,
        accent = Cyber.Magenta
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HudLabel(
                    when (state) {
                        NexState.LISTENING -> "Канал: голос · слушаю"
                        NexState.THINKING -> "Канал: голос · думаю"
                        NexState.ERROR -> "Канал: голос · сбой"
                        else -> if (chat.speaking) "Канал: голос · говорю" else "Канал: голос"
                    },
                    color = Cyber.Cyan,
                    modifier = Modifier.weight(1f)
                )
                HudIconButton(NexIcons.X, { voice.stop(); chat.stopSpeaking(); onClose() }, size = 40.dp, contentDescription = "Закрыть")
            }
            NexHoloStage(
                state = state,
                mode = mode,
                size = 300.dp,
                speaking = chat.speaking || (chat.isRunning && chat.streamingAnswer.isNotEmpty()),
                level = smooth
            )
            Text(
                transcript.ifBlank { if (asked) "" else line },
                style = CyberText.Heading.copy(fontSize = 18.sp, lineHeight = 24.sp),
                color = if (transcript.isBlank()) Cyber.TextDim else Cyber.Text,
                textAlign = TextAlign.Center
            )
            if (asked) {
                val shown = MarkdownParser.plain(answer).takeLast(600)
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when {
                        chat.isRunning && shown.isBlank() -> HudLabel("Обрабатываю запрос…", color = Cyber.Violet)
                        chat.lastRunFailed -> Text(chat.lastError.orEmpty(), style = NexusTypography.bodyMedium, color = Cyber.Red,
                            textAlign = TextAlign.Center)
                        else -> Text(shown, style = NexusTypography.bodyLarge, color = Cyber.Text, textAlign = TextAlign.Center)
                    }
                    chat.speakError?.let { Text(it, style = CyberText.Label, color = Cyber.Amber, textAlign = TextAlign.Center) }
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    listening -> HudButton("Готово", {
                        voice.stop()
                        if (transcript.isNotBlank()) submit(transcript)
                    }, icon = NexIcons.Check)
                    chat.isRunning -> HudButton("Стоп", { chat.cancel() }, style = HudButtonStyle.Danger, icon = NexIcons.Square)
                    chat.speaking -> HudButton("Тише", { chat.stopSpeaking() }, style = HudButtonStyle.Secondary, icon = NexIcons.Square)
                    else -> HudButton(if (asked) "Ещё вопрос" else "Говорить", { start() }, icon = NexIcons.Mic, accent = Cyber.Magenta)
                }
            }
        }
    }
}
