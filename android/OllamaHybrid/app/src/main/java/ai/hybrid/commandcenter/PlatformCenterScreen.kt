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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ai.hybrid.commandcenter.nexus.NexusConnectorAccount
import ai.hybrid.commandcenter.nexus.NexusConnectorAuthService
import ai.hybrid.commandcenter.nexus.NexusConnectorId
import ai.hybrid.commandcenter.nexus.NexusApkInstaller
import ai.hybrid.commandcenter.nexus.NexusInstallResult
import ai.hybrid.commandcenter.nexus.NexusExtensionManifest
import ai.hybrid.commandcenter.nexus.NexusExtensionPackageReader
import ai.hybrid.commandcenter.nexus.NexusExtensionPermissionStore
import ai.hybrid.commandcenter.nexus.NexusExtensionRuntime
import ai.hybrid.commandcenter.nexus.NexusExtensionStore
import ai.hybrid.commandcenter.nexus.NexusRelease
import ai.hybrid.commandcenter.nexus.NexusUpdateChecker
import ai.hybrid.commandcenter.nexus.NexusUpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

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
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Коннекторы") })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Обновления") })
        }
        when (selectedTab) {
            0 -> ExtensionsPanel(Modifier.weight(1f))
            1 -> ConnectorsPanel(Modifier.weight(1f))
            else -> UpdatesPanel(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ExtensionsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { NexusExtensionStore(context) }
    val permissionStore = remember { NexusExtensionPermissionStore(context) }
    val runtime = remember { NexusExtensionRuntime(store, permissionStore) }
    val installed = remember { mutableStateListOf<NexusExtensionManifest>() }
    var message by remember { mutableStateOf("Импортируйте пакет .nex — ZIP с manifest.json и необязательным README.md.") }

    LaunchedEffect(Unit) {
        installed.clear()
        installed.addAll(store.installed())
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = runCatching {
                    val bytes = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: error("Не удалось открыть выбранный файл.")
                        stream.use { readBoundedBytes(it, NexusExtensionPackageReader.MAX_ARCHIVE_BYTES) }
                    }
                    val isZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte()
                    val manifestJson = if (isZip) {
                        withContext(Dispatchers.IO) { NexusExtensionPackageReader.readManifest(bytes.inputStream()) }
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                    withContext(Dispatchers.IO) { store.installManifest(manifestJson) }
                }
                result.onSuccess { manifest ->
                    installed.removeAll { it.id == manifest.id }
                    installed.add(manifest)
                    message = "Расширение «" + manifest.name + "» установлено. Каждое разрешение нужно выдать отдельно."
                }.onFailure { error ->
                    message = error.message ?: "Не удалось импортировать расширение."
                }
            }
        }
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Каталог сторонних расширений", fontWeight = FontWeight.SemiBold)
                    Text(
                        "NEXUS v1 запускает только ограниченные действия, описанные в манифесте: HTTPS-ссылки, копирование текста и сообщения. JavaScript, DEX, native-библиотеки и произвольный код не загружаются.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/json"))
                    }) { Text("Установить пакет .nex") }
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (installed.isEmpty()) {
            item {
                Text("Пока нет установленных расширений. Пакет .nex должен содержать manifest.json в корне архива.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(installed, key = { it.id }) { manifest ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(manifest.name, fontWeight = FontWeight.SemiBold)
                        Text("v" + manifest.version, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(manifest.description.ifBlank { "Без описания" }, style = MaterialTheme.typography.bodySmall)
                    Text("Разрешения расширения", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                    if (manifest.capabilities.isEmpty()) {
                        Text("Разрешения не запрошены", style = MaterialTheme.typography.bodySmall)
                    }
                    manifest.capabilities.forEach { capability ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = permissionStore.isGranted(manifest.id, capability),
                                onCheckedChange = { granted ->
                                    permissionStore.setGranted(manifest.id, capability, granted)
                                    message = if (granted) "Разрешение " + capability + " выдано."
                                        else "Разрешение " + capability + " отозвано."
                                }
                            )
                            Text(capability, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (manifest.actions.isNotEmpty()) {
                        Text("Действия", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelMedium)
                        manifest.actions.forEach { action ->
                            val capability = when (action.type) {
                                "open_url" -> "open_url"
                                "copy_text" -> "clipboard_write"
                                else -> "show_message"
                            }
                            OutlinedButton(
                                enabled = permissionStore.isGranted(manifest.id, capability),
                                onClick = {
                                    message = runCatching { runtime.execute(context, manifest, action) }
                                        .getOrElse { it.message ?: "Действие не выполнено." }
                                }
                            ) { Text(action.title) }
                        }
                    } else {
                        Text("В пакете нет действий.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {
                        permissionStore.revokeAll(manifest.id)
                        store.remove(manifest.id)
                        installed.removeAll { it.id == manifest.id }
                        message = "Расширение удалено, все его разрешения отозваны."
                    }) { Text("Удалить расширение") }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ConnectorsPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val service = remember { NexusConnectorAuthService(context) }
    LazyColumn(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(
                "Подключение выполняется по токену провайдера. Секреты хранятся в Android Keystore и не попадают в настройки или журнал приложения.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            ConnectorCard(
                connector = NexusConnectorId.GITHUB,
                service = service,
                hint = "Fine-grained personal access token. Ограничьте его нужными репозиториями и только требуемыми read permissions.",
                placeholder = "github_pat_…"
            )
        }
        item {
            ConnectorCard(
                connector = NexusConnectorId.NOTION,
                service = service,
                hint = "Создайте Internal Integration в Notion и поделитесь с ней только теми страницами и базами, к которым NEXUS должен иметь доступ.",
                placeholder = "ntn_… или secret_…"
            )
        }
        item {
            ConnectorCard(
                connector = NexusConnectorId.TINYFISH,
                service = service,
                hint = "API key из TinyFish Dashboard. Проверка использует бесплатный Search API; платные Agent/Browser задачи не запускаются.",
                placeholder = "TinyFish API key"
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ConnectorCard(
    connector: NexusConnectorId,
    service: NexusConnectorAuthService,
    hint: String,
    placeholder: String
) {
    val scope = rememberCoroutineScope()
    var credential by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(service.account(connector)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(connector.title, fontWeight = FontWeight.SemiBold)
                Text(if (account == null) "Не подключён" else "Подключён", color = if (account == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
            }
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (account != null) {
                Text(account!!.identity, fontWeight = FontWeight.Medium)
                Text(account!!.detail, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val result = runCatching { service.verifyStored(connector) }
                                status = result.fold({ "Проверка успешна: " + it.identity }, { it.message ?: "Проверка не пройдена." })
                                busy = false
                            }
                        }
                    ) { Text("Проверить") }
                    OutlinedButton(onClick = {
                        service.disconnect(connector)
                        account = null
                        credential = ""
                        status = "Коннектор отключён. Секрет удалён из хранилища."
                    }) { Text("Отключить") }
                }
            } else {
                OutlinedTextField(
                    value = credential,
                    onValueChange = { credential = it.take(16 * 1024) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ключ доступа") },
                    placeholder = { Text(placeholder) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Button(
                    enabled = !busy && credential.isNotBlank(),
                    onClick = {
                        busy = true
                        status = "Проверяю доступ…"
                        scope.launch {
                            val result = runCatching { service.connect(connector, credential) }
                            result.onSuccess {
                                account = it
                                credential = ""
                                status = "Авторизация успешна."
                            }.onFailure {
                                status = it.message ?: "Авторизация не пройдена."
                            }
                            busy = false
                        }
                    }
                ) {
                    if (busy) CircularProgressIndicator()
                    else Text("Проверить и подключить")
                }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun UpdatesPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val checker = remember { NexusUpdateChecker() }
    val installer = remember { NexusApkInstaller(context) }
    val currentVersion = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0" }
            .getOrDefault("0.0.0")
    }
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Установлена версия NEXUS " + currentVersion + ". Проверьте GitHub Releases.") }
    var release by remember { mutableStateOf<NexusRelease?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                        status = "Доступна версия " + update.release.version + "."
                                    }
                                    is NexusUpdateResult.UpToDate -> {
                                        status = "Установлена актуальная версия " + update.currentVersion + "."
                                    }
                                }
                            }.onFailure { error ->
                                status = "Проверка не выполнена: " + (error.message ?: "ошибка сети")
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
                    Text("Версия " + latest.version, color = MaterialTheme.colorScheme.secondary)
                    if (latest.notes.isNotBlank()) Text(latest.notes.take(1_200), style = MaterialTheme.typography.bodySmall)
                    Text(
                        when {
                            latest.apkUrl == null -> "В релизе пока нет APK-файла."
                            latest.apkDigest == null -> "APK найден; контрольная сумма SHA-256 не опубликована."
                            else -> "APK найден; GitHub опубликовал SHA-256 digest."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (latest.apkUrl != null && latest.apkDigest != null) {
                        Button(
                            enabled = !installing,
                            onClick = {
                                installing = true
                                scope.launch {
                                    val result = runCatching { installer.downloadAndLaunch(latest) }
                                    result.onSuccess { outcome ->
                                        status = when (outcome) {
                                            is NexusInstallResult.InstallerOpened ->
                                                "APK проверен по SHA-256, package ID и подписи. Подтвердите установку в системном окне Android."
                                            is NexusInstallResult.PermissionSettingsOpened ->
                                                "APK проверен. Разрешите NEXUS устанавливать приложения в настройках Android, вернитесь сюда и повторите установку."
                                        }
                                    }.onFailure { error ->
                                        status = error.message ?: "Не удалось проверить или установить APK."
                                    }
                                    installing = false
                                }
                            }
                        ) {
                            if (installing) CircularProgressIndicator()
                            else Text("Скачать и установить APK")
                        }
                    }
                    OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl))) }
                            .onFailure { status = "Не удалось открыть страницу релиза." }
                    }) { Text("Страница релиза") }
                }
            }
        }
        Text(
            "В NEXUS разрешены только Android APK с проверяемым SHA-256 и подписью того же приложения. Установка выполняется через системный установщик Android с подтверждением пользователя.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun readBoundedBytes(input: InputStream, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size() + count <= maxBytes) { "Файл превышает допустимый размер." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
