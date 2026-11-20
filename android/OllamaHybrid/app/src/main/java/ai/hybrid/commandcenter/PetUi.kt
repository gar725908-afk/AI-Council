package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceInspector
import ai.hybrid.commandcenter.nexus.NexPetStore
import ai.hybrid.commandcenter.nexus.PetMood
import ai.hybrid.commandcenter.nexus.PetRules
import ai.hybrid.commandcenter.ui.Cyber
import ai.hybrid.commandcenter.ui.CyberText
import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.HudChip
import ai.hybrid.commandcenter.ui.HudLabel
import ai.hybrid.commandcenter.ui.HudPanel
import ai.hybrid.commandcenter.ui.HudProgress
import ai.hybrid.commandcenter.ui.HudSectionTitle
import ai.hybrid.commandcenter.ui.HudShapes
import ai.hybrid.commandcenter.ui.JpFamily
import ai.hybrid.commandcenter.ui.JpLabel
import ai.hybrid.commandcenter.ui.NexHoloStage
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.NexusTheme
import ai.hybrid.commandcenter.ui.NexusTypography
import ai.hybrid.commandcenter.ui.ThemeState
import ai.hybrid.commandcenter.ui.cjkAware
import ai.hybrid.commandcenter.ui.hud
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal fun moodGlyph(mood: PetMood): String = when (mood) {
    PetMood.HAPPY -> "元気"
    PetMood.PROUD -> "誇"
    PetMood.WORRIED -> "不安"
    PetMood.TIRED -> "疲労"
    PetMood.SLEEPY -> "睡眠"
    PetMood.LONELY -> "寂"
    PetMood.CHARGING -> "充電"
}

internal fun moodColor(mood: PetMood): Color = when (mood) {
    PetMood.HAPPY, PetMood.PROUD -> Cyber.Green
    PetMood.WORRIED -> Cyber.Amber
    PetMood.TIRED -> Cyber.Red
    PetMood.SLEEPY, PetMood.LONELY -> Cyber.Violet
    PetMood.CHARGING -> Cyber.Yellow
}

/** Live facts the pet's mood depends on, refreshed while the home screen is visible. */
private class PetVitals(val now: Long, val battery: Int?, val charging: Boolean)

@Composable
private fun rememberPetVitals(): PetVitals {
    val context = LocalContext.current
    var vitals by remember { mutableStateOf(PetVitals(System.currentTimeMillis(), null, false)) }
    LaunchedEffect(Unit) {
        while (true) {
            val diag = withContext(Dispatchers.IO) { runCatching { DeviceInspector(context.applicationContext).diagnostics() }.getOrNull() }
            vitals = PetVitals(System.currentTimeMillis(), diag?.batteryPercent, diag?.charging == true)
            delay(20_000)
        }
    }
    return vitals
}

/**
 * NEX as a living companion on the home screen. Tap: it answers with real
 * numbers (level, streak, battery). Long press: its profile with achievements.
 * It dozes off after a quiet stretch and wakes up when touched.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PetStage(chat: ChatController, mode: EffectsMode, size: Dp) {
    val vitals = rememberPetVitals()
    val pet = chat.pet
    val hour = remember(vitals.now) { NexPetStore.hourNow() }
    val idle = (vitals.now - chat.lastActivityAt).coerceAtLeast(0L)
    val mood = PetRules.mood(pet, vitals.battery, vitals.charging, vitals.now, hour, idle)
    var bubble by remember { mutableStateOf<String?>(null) }
    var reactUntil by remember { mutableLongStateOf(0L) }
    var sheet by remember { mutableStateOf(false) }
    val busy = chat.isRunning || chat.speaking || chat.nexState != NexState.STANDBY
    val sleeping = !busy && bubble == null && PetRules.sleeping(idle, hour)

    LaunchedEffect(bubble) {
        if (bubble != null) {
            delay(5_000)
            bubble = null
        }
    }
    LaunchedEffect(reactUntil) {
        val wait = reactUntil - System.currentTimeMillis()
        if (wait > 0) delay(wait)
        reactUntil = 0L
    }

    Box(contentAlignment = Alignment.TopCenter) {
        NexHoloStage(
            state = if (reactUntil > 0L) NexState.SUCCESS else chat.nexState,
            mode = mode,
            size = size,
            speaking = chat.speaking,
            modifier = Modifier
                .alpha(if (sleeping) 0.55f else 1f)
                .testTag("pet")
                .semantics { contentDescription = "NEX: нажми — ответит, удержи — профиль" }
                .combinedClickable(
                    onClick = {
                        chat.touchActivity()
                        reactUntil = System.currentTimeMillis() + 900
                        bubble = PetRules.reaction(pet, mood, vitals.battery, hour, chat.userName)
                    },
                    onLongClick = {
                        chat.touchActivity()
                        sheet = true
                    }
                )
        )
        if (sleeping) SleepZ(mode, Modifier.align(Alignment.TopEnd).padding(top = size * 0.12f, end = size * 0.18f))
        AnimatedVisibility(bubble != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Text(
                bubble.orEmpty(),
                style = NexusTypography.bodyMedium,
                color = Cyber.Text,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 18.dp)
                    .hud(Cyber.Cyan, cut = 8.dp, fill = Cyber.Panel.copy(alpha = 0.92f))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("pet-bubble")
            )
        }
    }
    PetStatusLine(chat, mood, vitals)
    chat.petNews?.let { news ->
        LaunchedEffect(news) {
            delay(9_000)
            chat.consumePetNews()
        }
        HudChip(news, Cyber.Yellow, Modifier.testTag("pet-news"), onClick = { chat.consumePetNews() })
    }
    if (sheet) PetSheet(chat, mood, vitals) { sheet = false }
}

@Composable
private fun SleepZ(mode: EffectsMode, modifier: Modifier) {
    val drift = if (mode == EffectsMode.EXPERIMENTAL) {
        rememberInfiniteTransition(label = "z").animateFloat(0f, 1f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "z")
    } else remember { mutableStateOf(0.5f) }
    Column(modifier.alpha(0.9f), horizontalAlignment = Alignment.End) {
        val k = drift.value
        Text("z", style = CyberText.Title.copy(fontSize = (14 + 10 * k).sp), color = Cyber.Violet.copy(alpha = 1f - 0.6f * k))
        Text("z", style = CyberText.Heading.copy(fontSize = 12.sp), color = Cyber.Violet.copy(alpha = 0.7f))
        Text("Z", style = CyberText.Label, color = Cyber.Violet.copy(alpha = 0.5f))
    }
}

@Composable
private fun PetStatusLine(chat: ChatController, mood: PetMood, vitals: PetVitals) {
    val pet = chat.pet
    val level = PetRules.level(pet.xp)
    val nextAt = PetRules.levelStart(level + 1)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("pet-status"),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("NEX · УР. $level", style = CyberText.Label.copy(fontWeight = FontWeight.Bold), color = Cyber.Cyan)
            Box(Modifier.weight(1f)) { HudProgress(PetRules.progress(pet.xp), accent = Cyber.Cyan, segments = 20) }
            Text("${pet.xp}/$nextAt", style = CyberText.Label, color = Cyber.TextDim)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tint = moodColor(mood)
            if (ThemeState.theme == NexusTheme.ANIME) Text(moodGlyph(mood), style = JpLabel.copy(fontSize = 11.sp), color = tint)
            Text(mood.title.uppercase(), style = CyberText.Label, color = tint)
            Text("· " + mood.note, style = CyberText.Label.copy(letterSpacing = 0.2.sp), color = Cyber.TextFaint,
                modifier = Modifier.weight(1f), maxLines = 1)
            vitals.battery?.let {
                Text((if (vitals.charging) "⚡" else "▮") + "$it%", style = CyberText.Label, color = if (it <= 15) Cyber.Red else Cyber.TextDim)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PetSheet(chat: ChatController, mood: PetMood, vitals: PetVitals, onDismiss: () -> Unit) {
    val pet = chat.pet
    val level = PetRules.level(pet.xp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Cyber.Night,
        contentColor = Cyber.Text
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).navigationBarsPadding()
                .testTag("pet-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier.size(72.dp).clip(HudShapes.Octagon).background(Cyber.Cyan.copy(alpha = 0.12f))
                        .border(1.dp, Cyber.Cyan, HudShapes.Octagon),
                    contentAlignment = Alignment.Center
                ) {
                    Text("$level", style = CyberText.Hero.copy(fontSize = 34.sp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("NEX · УРОВЕНЬ $level", style = CyberText.Title, color = Cyber.Text)
                    HudProgress(PetRules.progress(pet.xp), accent = Cyber.Cyan)
                    HudLabel("Опыт ${pet.xp} · до уровня ${level + 1}: ${PetRules.levelStart(level + 1) - pet.xp}", color = Cyber.TextDim)
                }
            }
            HudPanel(Modifier.fillMaxWidth(), accent = moodColor(mood), title = "Настроение", index = moodGlyph(mood)) {
                Text(mood.title + " — " + mood.note + ".", style = NexusTypography.bodyMedium, color = Cyber.Text)
                Text(
                    "Настроение честное: его задают последние ответы, заряд телефона и то, как давно мы общались.",
                    style = NexusTypography.bodySmall, color = Cyber.TextDim
                )
                vitals.battery?.let {
                    HudLabel("Энергия = заряд телефона: $it%" + if (vitals.charging) " · заряжается" else "", color = Cyber.TextDim)
                }
            }
            HudSectionTitle("Счётчики", trailing = "記録")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatBox("Ответов", pet.answers)
                StatBox("Голосом", pet.voiceAnswers)
                StatBox("Миссий совета", pet.councilMissions)
                StatBox("Без интернета", pet.offlineAnswers)
                StatBox("Дней подряд", pet.streakDays)
                StatBox("Ошибок", pet.errors)
            }
            HudSectionTitle("Достижения", trailing = "${pet.achievements.size}/${PetRules.achievements.size}")
            PetRules.achievements.forEach { achievement ->
                val unlocked = achievement.id in pet.achievements
                Row(
                    Modifier.fillMaxWidth().alpha(if (unlocked) 1f else 0.45f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(40.dp).clip(HudShapes.Small)
                            .background(if (unlocked) Cyber.Yellow.copy(alpha = 0.16f) else Cyber.Panel)
                            .border(1.dp, if (unlocked) Cyber.Yellow else Cyber.TextFaint, HudShapes.Small),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (unlocked) achievement.glyph else "?",
                            style = TextStyle(fontFamily = JpFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp),
                            color = if (unlocked) Cyber.Yellow else Cyber.TextFaint
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(achievement.title, style = CyberText.Heading, color = Cyber.Text)
                        Text(achievement.description, style = NexusTypography.bodySmall, color = Cyber.TextDim)
                    }
                }
            }
            Text(
                "Опыт даётся только за настоящие дела: ответ — 10, голосом — 15, миссия совета — 30, новая модель — 20, первый раз за день — ещё 5.",
                style = NexusTypography.bodySmall, color = Cyber.TextFaint
            )
            Box(Modifier.size(1.dp, 12.dp))
        }
    }
}

@Composable
private fun StatBox(title: String, value: Int) {
    Column(
        Modifier.width(104.dp).hud(Cyber.Violet, cut = 8.dp).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("$value", style = CyberText.Title.copy(fontSize = 20.sp), color = Cyber.Text)
        Text(cjkAware(title.uppercase()), style = CyberText.Label.copy(letterSpacing = 0.4.sp), color = Cyber.TextDim, maxLines = 2)
    }
}
