package ai.hybrid.commandcenter

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
private val SurfaceDeep = Color(0xFF0E1420)
private val SurfaceRaised = Color(0xFF151E2D)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val SignalGreen = Color(0xFF43D6A0)
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF1F5FA)
private val SecondaryText = Color(0xFFA4B1C2)

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

    Column(modifier.fillMaxSize().background(Void)) {
        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(tabs.tabs.size) { index ->
                val tab = tabs.tabs[index]
                val selected = tab.id == tabs.selectedId
                OutlinedButton(
                    onClick = {
                        tabs = tabs.select(tab.id)
                        address = tab.url.orEmpty()
                        progress = 100
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (selected) Cyan else SecondaryText,
                        containerColor = if (selected) SurfaceRaised else Color.Transparent
                    )
                ) {
                    Text(
                        (tab.title.ifBlank { tab.url?.let(BrowserUrlPolicy::displayHost) ?: "Новая вкладка" }).take(18),
                        fontSize = 11.sp
                    )
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        tabs = tabs.open()
                        address = ""
                        progress = 100
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text("+", fontSize = 12.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        val closing = tabs.selectedId
                        webViews.remove(closing)?.destroy()
                        tabs = tabs.close(closing)
                        address = tabs.selected.url.orEmpty()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalRed)
                ) { Text("×", fontSize = 12.sp) }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it.take(2048) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Адрес") },
                placeholder = { Text("zakupki.gov.ru") }
            )
            OutlinedButton(
                onClick = { navigateTo(address) },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
            ) { Text("Открыть", fontSize = 12.sp) }
        }

        if (progress in 0..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Cyan,
                trackColor = SurfaceDeep
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                current.title.ifBlank { current.url?.let(BrowserUrlPolicy::displayHost) ?: "Страница не открыта" },
                color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(current.url?.let(BrowserUrlPolicy::displayHost) ?: "", color = SecondaryText, fontSize = 10.sp)
        }

        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = { currentWebView?.let { if (it.canGoBack()) it.goBack() } },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryText)
                ) { Text("Назад", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = { currentWebView?.reload() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryText)
                ) { Text("Обновить", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        showHistory = !showHistory
                        if (showHistory) loadHistory()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (showHistory) Cyan else SecondaryText)
                ) { Text("История", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        withPageText { text ->
                            onSendToStudio(MaterialContext.buildPrompt(current.title.ifBlank { current.url.orEmpty() }, text))
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text("Текст → AI Studio", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        withPageText { text ->
                            onSendToCouncil(MaterialContext.buildPrompt(current.title.ifBlank { current.url.orEmpty() }, text))
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text("Текст → Council", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
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
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Violet)
                ) { Text("Сохранить в Архив", fontSize = 11.sp) }
            }
        }

        val status = statusLine
        if (status != null) {
            Text(
                status,
                color = if (statusIsError) SignalRed else SignalGreen,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (showHistory) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("ИСТОРИЯ (${historyEntries.size})", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { history.clear() }
                                    loadHistory()
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SignalRed)
                        ) { Text("Очистить", fontSize = 10.sp) }
                    }
                }
                if (historyEntries.isEmpty()) {
                    item { Text("История пуста.", color = SecondaryText, fontSize = 12.sp) }
                }
                items(historyEntries.size) { index ->
                    val entry = historyEntries[index]
                    OutlinedButton(
                        onClick = {
                            address = entry.url
                            navigateTo(entry.url)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryText)
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text(entry.title.ifBlank { BrowserUrlPolicy.displayHost(entry.url) }.take(60), fontSize = 11.sp)
                            Text(
                                timeLabel(entry.visitedAtEpochMillis) + " · " + BrowserUrlPolicy.displayHost(entry.url),
                                color = SecondaryText, fontSize = 9.sp
                            )
                        }
                    }
                }
            }
        } else {
            key(current.id) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth().weight(1f),
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
    SimpleDateFormat("HH:mm dd.MM", Locale.getDefault()).format(Date(epochMillis))
