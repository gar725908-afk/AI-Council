package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.LibraryImportResult
import ai.hybrid.commandcenter.nexus.LibraryItem
import ai.hybrid.commandcenter.nexus.LibrarySearch
import ai.hybrid.commandcenter.nexus.MaterialLibrary
import ai.hybrid.commandcenter.nexus.MaterialTextExtractor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
private val SurfaceDeep = Color(0xFF0E1420)
private val SurfaceRaised = Color(0xFF151E2D)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val SignalGreen = Color(0xFF43D6A0)
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF1F5FA)
private val SecondaryText = Color(0xFFA4B1C2)

private const val MAX_IMPORT_BYTES = 8_000_000

/**
 * Archive / Material Library per handoff section 4.7: import through the
 * system file picker (Standard profile, no broad storage permission),
 * honest parsing of supported formats only, lexical search whose every
 * result is an exact quote with its source and character position.
 */
@Composable
fun ArchiveScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val library = remember { MaterialLibrary(context.applicationContext) }

    var items by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<LibrarySearch.Match>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            items = withContext(Dispatchers.IO) { library.listItems() }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val resolver = context.contentResolver
                val name = runCatching {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                }.getOrNull() ?: "файл"
                val bytes = runCatching {
                    resolver.openInputStream(uri)?.use { it.readBytes() }
                }.getOrNull()
                when {
                    bytes == null -> LibraryImportResult.Rejected("Не удалось прочитать файл.")
                    bytes.size > MAX_IMPORT_BYTES -> LibraryImportResult.Rejected(
                        "Файл больше 8 МБ — такой объём пока не импортируется."
                    )
                    else -> library.import(name, bytes)
                }
            }
            when (result) {
                is LibraryImportResult.Imported -> {
                    statusLine = "Импортировано: ${result.item.displayName} " +
                        "(${result.item.textChars} симв., SHA-256 проверен)"
                    statusIsError = false
                }
                is LibraryImportResult.Rejected -> {
                    statusLine = result.reason
                    statusIsError = true
                }
            }
            importing = false
            refresh()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().background(Void).padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(18.dp))
            Text("АРХИВ", color = PrimaryText, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(
                "MATERIAL LIBRARY · ПОИСК С ЦИТАТАМИ ИЗ ИСТОЧНИКОВ",
                color = Cyan, fontSize = 11.sp, letterSpacing = 1.sp
            )
        }

        item {
            ArchiveCard(title = "ИМПОРТ", accent = Cyan) {
                Text(
                    "Файл выбирается через системный диалог — широкие разрешения на " +
                        "хранилище не нужны. Поддерживаются: " +
                        MaterialTextExtractor.supportedFormatsLabel() +
                        ". Остальные форматы честно отклоняются.",
                    color = SecondaryText, fontSize = 11.sp
                )
                OutlinedButton(
                    onClick = { if (!importing) picker.launch(arrayOf("*/*")) },
                    enabled = !importing,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) {
                    Text(if (importing) "Импорт…" else "Импортировать файл", fontSize = 12.sp)
                }
                val status = statusLine
                if (status != null) {
                    Text(
                        status,
                        color = if (statusIsError) SignalRed else SignalGreen,
                        fontSize = 11.sp
                    )
                }
            }
        }

        item {
            ArchiveCard(title = "ПОИСК ПО МАТЕРИАЛАМ", accent = Violet) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(200) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Запрос") },
                    minLines = 1
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            matches = withContext(Dispatchers.IO) { library.search(query) }
                            searched = true
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text("Найти", fontSize = 12.sp) }
                if (searched && matches.isEmpty()) {
                    Text("Совпадений нет.", color = SecondaryText, fontSize = 12.sp)
                }
                matches.forEach { match ->
                    Surface(
                        color = SurfaceRaised,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    match.itemName.take(48),
                                    color = PrimaryText, fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "совпадений: ${match.score}",
                                    color = Violet, fontSize = 10.sp, fontWeight = FontWeight.Bold
                                )
                            }
                            Text("«${match.snippet}»", color = SecondaryText, fontSize = 11.sp)
                            Text(
                                "Источник: ${match.itemName}, позиция ${match.positionChars} симв.",
                                color = Cyan, fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }

        item {
            Text(
                "МАТЕРИАЛЫ (${items.size})",
                color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp
            )
        }

        if (items.isEmpty()) {
            item {
                Text(
                    "Библиотека пуста. Импортируйте первый файл — он станет доступен " +
                        "для поиска с точными цитатами.",
                    color = SecondaryText, fontSize = 13.sp
                )
            }
        }

        items(items.size) { index ->
            val item = items[index]
            Surface(
                color = SurfaceDeep,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.displayName.take(48),
                            color = PrimaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            item.format.uppercase(),
                            color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "${formatKb(item.sizeBytes)} · ${item.textChars} симв. · " +
                            timeLabel(item.importedAtEpochMillis),
                        color = SecondaryText, fontSize = 10.sp
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { library.delete(item.id) }
                                statusLine = "Удалено: ${item.displayName}"
                                statusIsError = false
                                matches = emptyList()
                                searched = false
                                refresh()
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalRed)
                    ) { Text("Удалить", fontSize = 11.sp) }
                }
            }
        }

        item { Spacer(Modifier.height(22.dp)) }
    }
}

@Composable
private fun ArchiveCard(title: String, accent: Color, content: @Composable () -> Unit) {
    Surface(color = SurfaceDeep, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            content()
        }
    }
}

private fun formatKb(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
    else String.format(Locale.US, "%.0f КБ", bytes / 1024.0)

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
