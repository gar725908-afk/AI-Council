package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.GITHUB_TOKEN_KEY
import ai.hybrid.commandcenter.androidbridge.GitHubSettings
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.ErrorEntry
import ai.hybrid.commandcenter.nexus.ErrorJournal
import ai.hybrid.commandcenter.nexus.ErrorReport
import ai.hybrid.commandcenter.nexus.GitHubClient
import ai.hybrid.commandcenter.nexus.MaterialContext
import android.content.Intent
import android.net.Uri
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
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF4F6FB)
private val SecondaryText = Color(0xFFA3ABBD)

/**
 * Repairs: the loop that closes error → diagnosis → fix → release.
 *
 * The app records what broke, hands the error to Council together with the
 * real source read from GitHub, and Council's proposal becomes a pull
 * request a human reviews. Nothing a model writes reaches the shipped code
 * on its own, and the GitHub token never leaves the Keystore.
 */
@Composable
fun RepairPanel(
    modifier: Modifier = Modifier,
    onSendToCouncil: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val journal = remember { ErrorJournal(context.applicationContext) }
    val vault = remember { AndroidCredentialVault(context.applicationContext) }

    var errors by remember { mutableStateOf<List<ErrorEntry>>(emptyList()) }
    var owner by remember { mutableStateOf("") }
    var repoName by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var token by remember { mutableStateOf("") }
    var tokenSaved by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusError by remember { mutableStateOf(false) }
    var codePath by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            errors = withContext(Dispatchers.IO) { journal.recent(30) }
            GitHubSettings.load(context)?.let { repo ->
                owner = repo.owner
                repoName = repo.name
                branch = repo.defaultBranch
            }
            tokenSaved = withContext(Dispatchers.IO) { !vault.get(GITHUB_TOKEN_KEY).isNullOrBlank() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ai.hybrid.commandcenter.ui.ScreenHeader(
                "Исправления",
                subtitle = "Ошибка → Council → pull request → обновление",
                leading = { ai.hybrid.commandcenter.ui.IconBadge(ai.hybrid.commandcenter.ui.NexIcons.Wrench, ai.hybrid.commandcenter.ui.NexusColors.SignalAmber, size = 48.dp) }
            )
        }

        item {
            RepairCard(title = "Репозиторий", accent = Cyan) {
                Text(
                    "Исправления всегда уходят в отдельную ветку и pull request. " +
                        "Ни одна правка не попадает в основную ветку без вашей проверки. " +
                        "Токен хранится в Keystore и не передаётся модели.",
                    color = SecondaryText, fontSize = 11.sp
                )
                OutlinedTextField(owner, { owner = it.take(60) }, label = { Text("Владелец") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(repoName, { repoName = it.take(100) }, label = { Text("Репозиторий") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(branch, { branch = it.take(60) }, label = { Text("Основная ветка") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it.take(200) },
                    label = { Text(if (tokenSaved) "Токен сохранён — введите новый, чтобы заменить" else "Токен GitHub (repo)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            GitHubSettings.save(context, owner, repoName, branch)
                            if (token.isNotBlank()) {
                                scope.launch {
                                    withContext(Dispatchers.IO) { vault.put(GITHUB_TOKEN_KEY, token) }
                                    token = ""
                                    tokenSaved = true
                                }
                            }
                            status = "Настройки сохранены."
                            statusError = false
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text("Сохранить", fontSize = 12.sp) }
                    if (tokenSaved) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { vault.put(GITHUB_TOKEN_KEY, "") }
                                    tokenSaved = false
                                    status = "Токен удалён."
                                    statusError = false
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalRed)
                        ) { Text("Удалить токен", fontSize = 12.sp) }
                    }
                }
                val line = status
                if (line != null) Text(line, color = if (statusError) SignalRed else SignalGreen, fontSize = 11.sp)
            }
        }

        item {
            RepairCard(title = "Исходный код", accent = Violet) {
                Text(
                    "Council получает ошибку вместе с настоящим кодом из репозитория, " +
                        "а не догадывается по названию файла.",
                    color = SecondaryText, fontSize = 11.sp
                )
                OutlinedTextField(
                    codePath, { codePath = it.take(255) },
                    label = { Text("Путь в репозитории") },
                    placeholder = { Text("android/OllamaHybrid/app/src/main/java/…") },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = {
                        if (busy || codePath.isBlank()) return@OutlinedButton
                        busy = true
                        scope.launch {
                            val repo = GitHubSettings.load(context)
                            val saved = withContext(Dispatchers.IO) { vault.get(GITHUB_TOKEN_KEY) }
                            if (repo == null || saved.isNullOrBlank()) {
                                status = "Сначала сохраните репозиторий и токен."
                                statusError = true
                                busy = false
                                return@launch
                            }
                            val result = withContext(Dispatchers.IO) {
                                GitHubClient(saved).readFile(repo, codePath.trim())
                            }
                            result.fold(
                                onSuccess = { file ->
                                    status = "Прочитано " + file.content.length + " символов из " + file.path
                                    statusError = false
                                    onSendToCouncil(
                                        MaterialContext.buildPrompt(
                                            repo.slug + ":" + file.path,
                                            file.content
                                        ) + "Найди причину ошибки в этом файле и предложи исправленный код целиком."
                                    )
                                },
                                onFailure = {
                                    status = it.message ?: "Не удалось прочитать файл."
                                    statusError = true
                                }
                            )
                            busy = false
                        }
                    },
                    enabled = !busy && codePath.isNotBlank(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text(if (busy) "Чтение…" else "Прочитать и отправить в Council", fontSize = 12.sp) }
            }
        }

        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(ai.hybrid.commandcenter.ui.prettyTitle("Журнал ошибок (${errors.size})"), color = Cyan, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                if (errors.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { journal.clear() }
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalRed)
                    ) { Text("Очистить", fontSize = 11.sp) }
                }
            }
        }

        if (errors.isEmpty()) {
            item {
                Text(
                    "Ошибок не зафиксировано. Падения и сбои инструментов попадают сюда автоматически.",
                    color = SecondaryText, fontSize = 13.sp
                )
            }
        }

        items(errors.size) { index ->
            val entry = errors[index]
            Surface(color = SurfaceDeep, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(entry.summary.take(70), color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(entry.kind.uppercase(), color = SignalRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        timeLabel(entry.occurredAtEpochMillis) + " · " + entry.appVersion + " · " + entry.device,
                        color = SecondaryText, fontSize = 10.sp
                    )
                    Surface(color = SurfaceRaised, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Text(entry.details.take(400), color = SecondaryText, fontSize = 9.sp, modifier = Modifier.padding(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                onSendToCouncil(
                                    MaterialContext.buildPrompt(
                                        "Ошибка NEXUS: " + entry.summary.take(60),
                                        ErrorReport.buildBody(entry)
                                    ) + "Определи причину и назови файл в репозитории, который нужно исправить."
                                )
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                        ) { Text("Разобрать в Council", fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(ErrorReport.issueUrl(entry)))
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure {
                                    status = "Не удалось открыть браузер."
                                    statusError = true
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) { Text("Отчёт в GitHub", fontSize = 11.sp) }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }
    }
}

@Composable
private fun RepairCard(title: String, accent: Color, content: @Composable () -> Unit) {
    ai.hybrid.commandcenter.ui.SectionCard(
        title = title,
        icon = ai.hybrid.commandcenter.ui.iconForTitle(title),
        accent = accent
    ) { content() }
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
