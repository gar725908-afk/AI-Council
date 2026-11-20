package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.CircleIconButton
import ai.hybrid.commandcenter.ui.Dot
import ai.hybrid.commandcenter.ui.ListRow
import ai.hybrid.commandcenter.ui.NexIcon
import ai.hybrid.commandcenter.ui.NexIcons
import ai.hybrid.commandcenter.ui.NexusBrushes
import ai.hybrid.commandcenter.ui.NexusColors
import ai.hybrid.commandcenter.ui.NexusShapes
import ai.hybrid.commandcenter.ui.SectionHeader
import ai.hybrid.commandcenter.ui.SoftButton
import ai.hybrid.commandcenter.ui.glass
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import ai.hybrid.commandcenter.nexus.BrowserHistoryEntry
import ai.hybrid.commandcenter.nexus.BrowserHistoryStore
import ai.hybrid.commandcenter.nexus.BrowserTabs
import ai.hybrid.commandcenter.nexus.BrowserUrlPolicy
import ai.hybrid.commandcenter.nexus.LibraryImportResult
import ai.hybrid.commandcenter.nexus.MaterialContext
import ai.hybrid.commandcenter.nexus.MaterialLibrary
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

private val Void = Color(0xFF080B12)
private val SurfaceDeep = Color(0x14FFFFFF)
private val SurfaceRaised = Color(0x0DFFFFFF)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val SignalGreen = Color(0xFF43D6A0)
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF4F6FB)
private val SecondaryText = Color(0xFFA3ABBD)

private const val MAX_PAGE_TEXT_CHARS = 200_000

/**
 * Cyber Browser (handoff 4.4): tabs with retained WebViews, honest address
 * policy, persistent clearable history, downloads handed to the system
 * DownloadManager (https only), and Agent Dock actions that route page text
 * to AI Studio / Council / Archive as cited untrusted context.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    modifier: Modifier = Modifier,
    onSendToStudio: (String) -> Unit = {},
    onSendToCouncil: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val history = remember { BrowserHistoryStore(context.applicationContext) }

    var tabs by remember { mutableStateOf(BrowserTabs()) }
    val webViews = remember { mutableMapOf<String, WebView>() }
    var address by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var historyEntries by remember { mutableStateOf<List<BrowserHistoryEntry>>(emptyList()) }

    val current = tabs.selected
    val currentWebView = webViews[current.id]

    fun setStatus(text: String, error: Boolean) {
        statusLine = text
        statusIsError = error
    }

    fun loadHistory() {
        scope.launch { historyEntries = withContext(Dispatchers.IO) { history.recent(50) } }
    }

    fun navigateTo(input: String) {
        val url = BrowserUrlPolicy.normalize(input)
        if (url == null) {
            setStatus("Введите адрес сайта (например, zakupki.gov.ru). Поиск по словам не подставляется автоматически.", true)
            return
        }
        statusLine = null
        showHistory = false
        webViews[current.id]?.loadUrl(url)
    }

    fun withPageText(onText: (String) -> Unit) {
        val view = webViews[current.id]
        if (view == null || current.url == null) {
            setStatus("Сначала откройте страницу.", true)
            return
        }
        view.evaluateJavascript("(function(){return document.body ? document.body.innerText : '';})();") { encoded ->
            val text = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull().orEmpty()
            if (text.isBlank()) setStatus("На странице не найден текст.", true)
            else onText(text.take(MAX_PAGE_TEXT_CHARS))
        }
    }

    fun createWebView(ctx: Context, tabId: String): WebView = WebView(ctx).apply {
        // Empty tabs match the dark interface instead of flashing white.
        setBackgroundColor(android.graphics.Color.parseColor("#0D0F16"))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val target = request.url.toString()
                if (BrowserUrlPolicy.allowsNavigation(target)) return false
                setStatus("Переход заблокирован политикой: " + target.take(60), true)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tabs = tabs.update(tabId, url = url, title = "")
                if (tabs.selectedId == tabId) address = url
            }

            override fun onPageFinished(view: WebView, url: String) {
                val title = view.title.orEmpty()
                tabs = tabs.update(tabId, url = url, title = title)
                scope.launch(Dispatchers.IO) { history.record(url, title) }
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (tabs.selectedId == tabId) progress = newProgress
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                tabs = tabs.update(tabId, title = title.orEmpty())
            }
        }
        setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            if (!url.lowercase().startsWith("https://")) {
                setStatus("Загрузка разрешена только по HTTPS.", true)
                return@setDownloadListener
            }
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val request = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mimeType)
                .addRequestHeader("User-Agent", userAgent)
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            val manager = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (manager == null) {
                setStatus("Системный менеджер загрузок недоступен.", true)
            } else {
                manager.enqueue(request)
                setStatus("Загрузка передана системе: $fileName (папка Downloads).", false)
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        // Tabs
        LazyRow(
            Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(tabs.tabs.size) { index ->
                val tab = tabs.tabs[index]
                val selected = tab.id == tabs.selectedId
                Row(
                    Modifier
                        .clip(NexusShapes.Pill)
                        .then(if (selected) Modifier.background(NexusBrushes.Primary) else Modifier.glass(NexusShapes.Pill))
                        .clickable {
                            tabs = tabs.select(tab.id)
                            address = tab.url.orEmpty()
                            progress = 100
                        }
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NexIcon(NexIcons.Globe, tint = if (selected) Color.White else SecondaryText, size = 15.dp)
                    Text(
                        (tab.title.ifBlank { tab.url?.let(BrowserUrlPolicy::displayHost) ?: "Новая вкладка" }).take(18),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) Color.White else SecondaryText,
                        maxLines = 1
                    )
                }
            }
            item {
                CircleIconButton(
                    NexIcons.Plus,
                    onClick = {
                        tabs = tabs.open()
                        address = ""
                        progress = 100
                    },
                    size = 38.dp,
                    contentDescription = "Новая вкладка"
                )
            }
            item {
                CircleIconButton(
                    NexIcons.X,
                    onClick = {
                        val closing = tabs.selectedId
                        webViews.remove(closing)?.destroy()
                        tabs = tabs.close(closing)
                        address = tabs.selected.url.orEmpty()
                    },
                    size = 38.dp,
                    tint = SignalRed,
                    contentDescription = "Закрыть вкладку"
                )
            }
        }

        // Address bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .glass(NexusShapes.Panel)
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            NexIcon(if (current.url?.startsWith("https") == true) NexIcons.Lock else NexIcons.Search, tint = SecondaryText, size = 18.dp)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (address.isEmpty()) {
                    Text("Адрес или поиск", style = MaterialTheme.typography.bodyLarge, color = NexusColors.TextMuted)
                }
                BasicTextField(
                    value = address,
                    onValueChange = { address = it.take(2048) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = PrimaryText),
                    cursorBrush = SolidColor(Cyan),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { navigateTo(address) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            CircleIconButton(
                NexIcons.ArrowUp,
                onClick = { navigateTo(address) },
                size = 40.dp,
                filled = address.isNotBlank(),
                tint = NexusColors.TextMuted,
                contentDescription = "Открыть"
            )
        }

        if (progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 6.dp).height(3.dp).clip(NexusShapes.Pill),
                color = Cyan,
                trackColor = Color(0x14FFFFFF),
                drawStopIndicator = {}
            )
        } else {
            Spacer(Modifier.height(10.dp))
        }

        // Page title and actions
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    current.title.ifBlank { current.url?.let(BrowserUrlPolicy::displayHost) ?: "Страница не открыта" },
                    style = MaterialTheme.typography.titleSmall,
                    color = PrimaryText,
                    maxLines = 1
                )
                Text(
                    current.url?.let(BrowserUrlPolicy::displayHost) ?: "Введите адрес выше",
                    style = MaterialTheme.typography.bodySmall,
                    color = SecondaryText,
                    maxLines = 1
                )
            }
            CircleIconButton(
                NexIcons.ArrowLeft,
                onClick = { currentWebView?.let { if (it.canGoBack()) it.goBack() } },
                size = 38.dp,
                tint = SecondaryText,
                contentDescription = "Назад"
            )
            CircleIconButton(
                NexIcons.RefreshCw,
                onClick = { currentWebView?.reload() },
                size = 38.dp,
                tint = SecondaryText,
                contentDescription = "Обновить"
            )
            CircleIconButton(
                NexIcons.History,
                onClick = {
                    showHistory = !showHistory
                    if (showHistory) loadHistory()
                },
                size = 38.dp,
                tint = if (showHistory) Cyan else SecondaryText,
                contentDescription = "История"
            )
        }

        // Agent Dock: page text to Studio, Council or the Archive
        LazyRow(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                SoftButton("В Studio", {
                    withPageText { text ->
                        onSendToStudio(MaterialContext.buildPrompt(current.title.ifBlank { current.url.orEmpty() }, text))
                    }
                }, icon = NexIcons.Sparkles, tint = Violet)
            }
            item {
                SoftButton("В Council", {
                    withPageText { text ->
                        onSendToCouncil(MaterialContext.buildPrompt(current.title.ifBlank { current.url.orEmpty() }, text))
                    }
                }, icon = NexIcons.Orbit, tint = Violet)
            }
            item {
                SoftButton("В архив", {
                    withPageText { text ->
                        scope.launch {
                            val host = current.url?.let(BrowserUrlPolicy::displayHost) ?: "page"
                            val name = (current.title.ifBlank { host }).take(80) + " ($host).txt"
                            val result = withContext(Dispatchers.IO) {
                                MaterialLibrary(context.applicationContext).import(name, text.toByteArray())
                            }
                            when (result) {
                                is LibraryImportResult.Imported -> setStatus("Сохранено в Архив: ${result.item.displayName}", false)
                                is LibraryImportResult.Rejected -> setStatus(result.reason, true)
                            }
                        }
                    }
                }, icon = NexIcons.Archive, tint = Cyan)
            }
        }

        val status = statusLine
        if (status != null) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Dot(if (statusIsError) SignalRed else SignalGreen, size = 6.dp)
                Text(status, style = MaterialTheme.typography.bodySmall, color = if (statusIsError) SignalRed else SignalGreen)
            }
        }

        if (showHistory) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    SectionHeader(
                        "История · ${historyEntries.size}",
                        action = "Очистить",
                        onAction = {
                            scope.launch {
                                withContext(Dispatchers.IO) { history.clear() }
                                loadHistory()
                            }
                        }
                    )
                }
                if (historyEntries.isEmpty()) {
                    item { Text("История пуста.", style = MaterialTheme.typography.bodyMedium, color = SecondaryText) }
                }
                items(historyEntries.size) { index ->
                    val entry = historyEntries[index]
                    ListRow(
                        icon = NexIcons.Globe,
                        accent = Cyan,
                        title = entry.title.ifBlank { BrowserUrlPolicy.displayHost(entry.url) },
                        meta = timeLabel(entry.visitedAtEpochMillis) + " · " + BrowserUrlPolicy.displayHost(entry.url),
                        onClick = {
                            address = entry.url
                            navigateTo(entry.url)
                        }
                    )
                }
            }
        } else {
            key(current.id) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp)
                        .clip(NexusShapes.Card),
                    factory = { ctx ->
                        val view = webViews.getOrPut(current.id) { createWebView(ctx, current.id) }
                        (view.parent as? ViewGroup)?.removeView(view)
                        view
                    }
                )
            }
        }
    }
}

private fun timeLabel(epochMillis: Long): String =
    SimpleDateFormat("HH:mm, d MMM", Locale.forLanguageTag("ru")).format(Date(epochMillis))
