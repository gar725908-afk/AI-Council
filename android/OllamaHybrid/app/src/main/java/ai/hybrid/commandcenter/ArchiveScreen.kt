package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.LibraryImportResult
import ai.hybrid.commandcenter.nexus.LibraryItem
import ai.hybrid.commandcenter.nexus.LibrarySearch
import ai.hybrid.commandcenter.nexus.MaterialContext
import ai.hybrid.commandcenter.nexus.MaterialLibrary
import ai.hybrid.commandcenter.nexus.MaterialTextExtractor
import ai.hybrid.commandcenter.nexus.OcrEngine
import ai.hybrid.commandcenter.nexus.PdfOperations
import ai.hybrid.commandcenter.nexus.PdfPageRenderer
import java.io.File
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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

private val Void: Color get() = ai.hybrid.commandcenter.ui.Cyber.Void
private val SurfaceDeep = Color(0x14FFFFFF)
private val SurfaceRaised = Color(0x0DFFFFFF)
private val Cyan: Color get() = ai.hybrid.commandcenter.ui.Cyber.Cyan
private val Violet: Color get() = ai.hybrid.commandcenter.ui.Cyber.Violet
private val SignalGreen = Color(0xFF2BFF9A)
private val SignalRed = Color(0xFFFF3B5C)
private val PrimaryText: Color get() = ai.hybrid.commandcenter.ui.Cyber.Text
private val SecondaryText: Color get() = ai.hybrid.commandcenter.ui.Cyber.TextDim

private const val MAX_IMPORT_BYTES = 8_000_000
private const val MAX_OCR_PAGES = 30

/**
 * Archive / Material Library per handoff section 4.7: import through the
 * system file picker (Standard profile, no broad storage permission),
 * honest parsing of supported formats only, lexical search whose every
 * result is an exact quote with its source and character position.
 */
@Composable
fun ArchiveScreen(
    modifier: Modifier = Modifier,
    onSendToStudio: (String) -> Unit = {},
    onSendToCouncil: (String) -> Unit = {}
) {
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
    var viewingItem by remember { mutableStateOf<LibraryItem?>(null) }

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

    val viewing = viewingItem
    if (viewing != null) {
        MaterialViewer(
            item = viewing,
            library = library,
            onBack = { viewingItem = null },
            onChanged = { refresh() },
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        item {
            ArchiveCard(title = "Импорт", accent = Cyan) {
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
            ArchiveCard(title = "Поиск по материалам", accent = Violet) {
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
                "Материалы (${items.size})",
                color = Cyan, style = androidx.compose.material3.MaterialTheme.typography.titleSmall
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { viewingItem = item },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryText)
                    ) { Text("Просмотр", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) { library.getText(item.id) }
                                if (text != null) {
                                    onSendToStudio(MaterialContext.buildPrompt(item.displayName, text))
                                } else {
                                    statusLine = "Не удалось прочитать материал."
                                    statusIsError = true
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                    ) { Text("В чат", fontSize = 11.sp) }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val text = withContext(Dispatchers.IO) { library.getText(item.id) }
                                if (text != null) {
                                    onSendToCouncil(MaterialContext.buildPrompt(item.displayName, text))
                                }
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                    ) { Text("В совет", fontSize = 11.sp) }
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
        }
    }
}

/**
 * Exact view of a material: PDF pages rendered from the stored original by
 * the platform renderer; other formats show the extracted text verbatim.
 */
@Composable
private fun MaterialViewer(
    item: LibraryItem,
    library: MaterialLibrary,
    onBack: () -> Unit,
    onChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val widthPx = (LocalConfiguration.current.screenWidthDp * 2.5f).toInt().coerceIn(600, 2000)
    var pageCount by remember(item.id) { mutableStateOf(-1) }
    var text by remember(item.id) { mutableStateOf<String?>(null) }
    val isPdf = item.format == "pdf" && item.originalPath != null
    var rangeFrom by remember(item.id) { mutableStateOf("1") }
    var rangeTo by remember(item.id) { mutableStateOf("1") }
    var otherPdfs by remember(item.id) { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var opStatus by remember(item.id) { mutableStateOf<String?>(null) }
    var opError by remember(item.id) { mutableStateOf(false) }
    var ocrRunning by remember(item.id) { mutableStateOf(false) }

    fun importDerived(name: String, bytes: ByteArray, pages: Int) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { library.import(name, bytes) }
            when (result) {
                is LibraryImportResult.Imported -> {
                    opStatus = "Создан материал «${result.item.displayName}» ($pages стр.)"
                    opError = false
                    onChanged()
                }
                is LibraryImportResult.Rejected -> {
                    opStatus = "Результат не сохранён: " + result.reason
                    opError = true
                }
            }
        }
    }

    LaunchedEffect(item.id) {
        withContext(Dispatchers.IO) {
            if (isPdf) {
                pageCount = PdfPageRenderer.pageCount(item.originalPath!!)
                otherPdfs = library.listItems().filter { it.id != item.id && it.format == "pdf" && it.originalPath != null }
            } else {
                text = library.getText(item.id)
            }
        }
        if (isPdf && pageCount > 0) rangeTo = pageCount.toString()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    item.displayName.take(40),
                    color = PrimaryText, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = onBack,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text("Назад", fontSize = 11.sp) }
            }
            Text(
                if (isPdf) "PDF · страниц: ${if (pageCount < 0) "…" else pageCount} · рендер оригинала"
                else item.format.uppercase() + " · извлечённый текст · ${item.textChars} симв.",
                color = SecondaryText, fontSize = 10.sp
            )
        }
        if (isPdf) {
            item {
                Surface(color = SurfaceDeep, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ai.hybrid.commandcenter.ui.prettyTitle("Операции с PDF"), color = Violet, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                        Text(
                            "Распознавание нужно для сканов без текстового слоя. " + OcrEngine.SCOPE_NOTE,
                            color = SecondaryText, fontSize = 10.sp
                        )
                        OutlinedButton(
                            onClick = {
                                if (ocrRunning) return@OutlinedButton
                                ocrRunning = true
                                opStatus = "Распознавание…"
                                opError = false
                                scope.launch {
                                    val pages = withContext(Dispatchers.IO) {
                                        val total = PdfPageRenderer.pageCount(item.originalPath!!)
                                            .coerceAtMost(MAX_OCR_PAGES)
                                        (0 until total).mapNotNull { index ->
                                            PdfPageRenderer.render(item.originalPath, index, 1_600)
                                        }.mapIndexed { index, bitmap -> index + 1 to bitmap }
                                    }
                                    if (pages.isEmpty()) {
                                        opStatus = "Не удалось отрисовать страницы для распознавания."
                                        opError = true
                                        ocrRunning = false
                                        return@launch
                                    }
                                    val recognised = mutableListOf<Pair<Int, String>>()
                                    var failure: String? = null
                                    for ((number, bitmap) in pages) {
                                        when (val result = OcrEngine.recognize(bitmap)) {
                                            is OcrEngine.Result.Text -> recognised += number to result.text
                                            is OcrEngine.Result.Empty -> Unit
                                            is OcrEngine.Result.Failed -> failure = result.reason
                                        }
                                        bitmap.recycle()
                                        if (failure != null) break
                                    }
                                    when {
                                        failure != null -> {
                                            opStatus = failure
                                            opError = true
                                        }
                                        recognised.isEmpty() -> {
                                            opStatus = "Текст не распознан ни на одной странице. " + OcrEngine.SCOPE_NOTE
                                            opError = true
                                        }
                                        else -> importDerived(
                                            item.displayName.removeSuffix(".pdf") + " (распознано).txt",
                                            OcrEngine.assemblePages(recognised).toByteArray(),
                                            recognised.size
                                        )
                                    }
                                    ocrRunning = false
                                }
                            },
                            enabled = !ocrRunning,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalGreen)
                        ) {
                            Text(if (ocrRunning) "Распознавание…" else "Распознать текст (OCR)", fontSize = 11.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = rangeFrom, onValueChange = { rangeFrom = it.filter(Char::isDigit).take(5) },
                                label = { Text("с") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = rangeTo, onValueChange = { rangeTo = it.filter(Char::isDigit).take(5) },
                                label = { Text("по") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    val from = rangeFrom.toIntOrNull() ?: 0
                                    val to = rangeTo.toIntOrNull() ?: 0
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            PdfOperations.extractPages(File(item.originalPath!!).readBytes(), from, to)
                                        }
                                        when (result) {
                                            is PdfOperations.Result.Document -> importDerived(
                                                item.displayName.removeSuffix(".pdf") + " (стр. $from–$to).pdf",
                                                result.bytes, result.pages
                                            )
                                            is PdfOperations.Result.Failed -> { opStatus = result.reason; opError = true }
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                            ) { Text("Извлечь", fontSize = 11.sp) }
                        }
                        if (otherPdfs.isEmpty()) {
                            Text("Объединение: добавьте в Архив второй PDF.", color = SecondaryText, fontSize = 11.sp)
                        } else {
                            Text("Объединить с (добавится после текущего):", color = SecondaryText, fontSize = 11.sp)
                            otherPdfs.take(8).forEach { other ->
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                PdfOperations.merge(
                                                    listOf(
                                                        File(item.originalPath!!).readBytes(),
                                                        File(other.originalPath!!).readBytes()
                                                    )
                                                )
                                            }
                                            when (result) {
                                                is PdfOperations.Result.Document -> importDerived(
                                                    item.displayName.removeSuffix(".pdf") + " + " +
                                                        other.displayName.removeSuffix(".pdf") + ".pdf",
                                                    result.bytes, result.pages
                                                )
                                                is PdfOperations.Result.Failed -> { opStatus = result.reason; opError = true }
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                                ) { Text("+ " + other.displayName.take(32), fontSize = 11.sp) }
                            }
                        }
                        val status = opStatus
                        if (status != null) {
                            Text(status, color = if (opError) SignalRed else SignalGreen, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
        if (isPdf) {
            if (pageCount == 0) {
                item { Text("Оригинал не открывается системным рендером.", color = SignalRed, fontSize = 12.sp) }
            }
            items(pageCount.coerceAtLeast(0)) { index ->
                var bitmap by remember(item.id, index) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(item.id, index) {
                    bitmap = withContext(Dispatchers.IO) {
                        PdfPageRenderer.render(item.originalPath!!, index, widthPx)
                    }
                }
                Surface(color = SurfaceDeep, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Страница ${index + 1}", color = SecondaryText, fontSize = 10.sp)
                        val page = bitmap
                        if (page == null) {
                            Text("Рендер…", color = SecondaryText, fontSize = 11.sp)
                        } else {
                            Image(
                                bitmap = page.asImageBitmap(),
                                contentDescription = "Страница ${index + 1}",
                                modifier = Modifier.fillMaxWidth(),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Surface(color = SurfaceDeep, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text ?: "Чтение…",
                        color = PrimaryText, fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveCard(title: String, accent: Color, content: @Composable () -> Unit) {
    ai.hybrid.commandcenter.ui.SectionCard(
        title = title,
        icon = ai.hybrid.commandcenter.ui.iconForTitle(title),
        accent = accent
    ) { content() }
}

private fun formatKb(bytes: Long): String =
    if (bytes >= 1024 * 1024) String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
    else String.format(Locale.US, "%.0f КБ", bytes / 1024.0)

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
