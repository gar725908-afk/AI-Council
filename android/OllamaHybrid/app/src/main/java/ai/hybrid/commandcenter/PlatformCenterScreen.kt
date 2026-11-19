package ai.hybrid.commandcenter

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ai.hybrid.commandcenter.nexus.NexusExtensionManifest
import ai.hybrid.commandcenter.nexus.NexusExtensionStore
import ai.hybrid.commandcenter.nexus.NexusRelease
import ai.hybrid.commandcenter.nexus.NexusUpdateChecker
import ai.hybrid.commandcenter.nexus.NexusUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private data class BuiltInConnectorCard(
    val name: String,
    val detail: String,
    val capability: String
)

private val builtInConnectors = listOf(
    BuiltInConnectorCard("GitHub", "Репозитории, исходный код и релизы NEXUS", "Коннектор ещё не подключён"),
    BuiltInConnectorCard("Notion", "Общая база знаний и контекст агентов", "Коннектор ещё не подключён"),
    BuiltInConnectorCard("TinyFish", "Браузерная автоматизация и веб-процессы", "Коннектор ещё не подключён")
)

@Composable
fun PlatformCenterScreen(modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("NEXUS CENTER", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Расширения, коннекторы и обновления приложения",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TabRow(selectedTabIndex = selectedTab) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Расширения") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Обновления") })
        }
        when (selectedTab) {
            0 -> ExtensionsPanel(Modifier.weight(1f))
            else -> UpdatesPanel(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExtensionsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember { NexusExtensionStore(context) }
    val installed = remember { androidx.compose.runtime.mutableStateListOf<NexusExtensionManifest>() }
    var message by remember { mutableStateOf("Можно импортировать локальный nexus-extension.json.") }

    LaunchedEffect(Unit) {
        installed.clear()
        installed.addAll(store.installed())
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val result = runCatching {
                val json = context.contentResolver.openInputStream(uri)?.use { readLimited(it, NexusExtensionManifest.MAX_MANIFEST_BYTES) }
                    ?: error("Не удалось открыть выбранный файл.")
                store.installManifest(json)
            }
            result.onSuccess { manifest ->
                installed.removeAll { it.id == manifest.id }
                installed.add(manifest)
                message = "Манифест «${manifest.name}» добавлен. Исполняемый код не загружается."
            }.onFailure { error ->
                message = error.message ?: "Не удалось импортировать манифест."
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Локальные расширения", fontWeight = FontWeight.SemiBold)
                    Text(
                        "На первом этапе NEXUS принимает и проверяет манифесты. Запуск стороннего кода пока отключён — это защищает ядро и данные.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) }) {
                        Text("Импортировать манифест")
                    }
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Каталог коннекторов", fontWeight = FontWeight.SemiBold) }
        items(builtInConnectors) { connector ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(connector.name, fontWeight = FontWeight.SemiBold)
                    Text(connector.detail, style = MaterialTheme.typography.bodySmall)
                    Text(connector.capability, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
        if (installed.isNotEmpty()) {
            item { Text("Импортированные манифесты", fontWeight = FontWeight.SemiBold) }
            items(installed, key = { it.id }) { manifest ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(manifest.name, fontWeight = FontWeight.SemiBold)
                            Text("v${manifest.version}", style = MaterialTheme.typography.labelMedium)
                        }
                        Text(manifest.description.ifBlank { "Без описания" }, style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (manifest.capabilities.isEmpty()) "Разрешения не запрошены"
                            else "Запрошено: ${manifest.capabilities.joinToString()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = {
                            store.remove(manifest.id)
                            installed.removeAll { it.id == manifest.id }
                            message = "Расширение удалено из локального каталога."
                        }) { Text("Удалить манифест") }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun UpdatesPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { NexusUpdateChecker() }
    val currentVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" }
            .getOrDefault("0.0.0")
    }
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Установлена версия NEXUS $currentVersion. Проверьте GitHub Releases.") }
    var release by remember { mutableStateOf<NexusRelease?>(null) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("GitHub Release", fontWeight = FontWeight.SemiBold)
                Text("Источник обновлений: gar725908-afk/AI-Council", style = MaterialTheme.typography.bodySmall)
                Text(status, style = MaterialTheme.typography.bodyMedium)
                if (checking) CircularProgressIndicator()
                Button(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        release = null
                        scope.launch {
                            val result = runCatching { checker.check(currentVersion) }
                            result.onSuccess { update ->
                                when (update) {
                                    is NexusUpdateResult.Available -> {
                                        release = update.release
                                        status = "Доступна версия ${update.release.version}."
                                    }
                                    is NexusUpdateResult.UpToDate -> {
                                        status = "Установлена актуальная версия ${update.currentVersion}."
                                    }
                                }
                            }.onFailure { error ->
                                status = "Проверка не выполнена: ${error.message ?: "ошибка сети"}"
                            }
                            checking = false
                        }
                    }
                ) { Text(if (checking) "Проверка…" else "Проверить обновления") }
            }
        }
        release?.let { latest ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(latest.title, fontWeight = FontWeight.SemiBold)
                    Text("Версия ${latest.version}", color = MaterialTheme.colorScheme.secondary)
                    if (latest.notes.isNotBlank()) {
                        Text(latest.notes.take(1_200), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        when {
                            latest.apkUrl == null -> "В релизе пока нет APK-файла."
                            latest.apkDigest == null -> "APK найден; контрольная сумма SHA-256 не опубликована."
                            else -> "APK найден; GitHub опубликовал SHA-256 digest."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl)))
                        }.onFailure { status = "Не удалось открыть страницу релиза." }
                    }) { Text("Открыть релиз GitHub") }
                }
            }
        }
        Text(
            "Сейчас NEXUS проверяет релиз и открывает его страницу. Установка APK выполняется только после проверки подписи Android и подтверждения пользователя; фоновая установка не заявляется.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private suspend fun readLimited(input: java.io.InputStream, maxBytes: Int): String = withContext(Dispatchers.IO) {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "Файл манифеста превышает допустимый размер." }
        output.write(buffer, 0, count)
    }
    output.toString(Charsets.UTF_8.name())
}
