package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.BrowserUrlPolicy
import ai.hybrid.commandcenter.nexus.LibraryImportResult
import ai.hybrid.commandcenter.nexus.MaterialContext
import ai.hybrid.commandcenter.nexus.MaterialLibrary
import android.annotation.SuppressLint
import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONTokener

private val Void = Color(0xFF080B12)
private val SurfaceDeep = Color(0xFF0E1420)
private val Cyan = Color(0xFF42D9F5)
private val Violet = Color(0xFF9B7BFF)
private val SignalGreen = Color(0xFF43D6A0)
private val SignalRed = Color(0xFFFF647C)
private val PrimaryText = Color(0xFFF1F5FA)
private val SecondaryText = Color(0xFFA4B1C2)

private const val MAX_PAGE_TEXT_CHARS = 200_000

/**
 * Cyber Browser, first real slice of handoff section 4.4: a sandboxed
 * WebView with an honest address policy, real progress and title, a
 * session history list, and two working Agent Dock actions — send the
 * page text to AI Studio as cited untrusted context, or save it into the
 * Archive. Tabs, downloads and persistent history are not pretended.
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

    var address by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf<String?>(null) }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(100) }
    var statusLine by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var sessionHistory by remember { mutableStateOf<List<String>>(emptyList()) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun navigateTo(input: String) {
        val url = BrowserUrlPolicy.normalize(input)
        if (url == null) {
            statusLine = "Введите адрес сайта (например, zakupki.gov.ru). Поиск по словам не подставляется автоматически."
            statusIsError = true
            return
        }
        statusLine = null
        webViewRef?.loadUrl(url)
    }

    fun withPageText(onText: (String) -> Unit) {
        val view = webViewRef
        if (view == null || currentUrl == null) {
            statusLine = "Сначала откройте страницу."
            statusIsError = true
            return
        }
        view.evaluateJavascript(
            "(function(){return document.body ? document.body.innerText : '';})();"
        ) { encoded ->
            val text = runCatching { JSONTokener(encoded).nextValue() as? String }.getOrNull().orEmpty()
            if (text.isBlank()) {
                statusLine = "На странице не найден текст."
                statusIsError = true
            } else {
                onText(text.take(MAX_PAGE_TEXT_CHARS))
            }
        }
    }

    Column(modifier.fillMaxSize().background(Void)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                pageTitle.ifBlank { currentUrl?.let(BrowserUrlPolicy::displayHost) ?: "Страница не открыта" },
                color = PrimaryText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                currentUrl?.let(BrowserUrlPolicy::displayHost) ?: "",
                color = SecondaryText, fontSize = 10.sp
            )
        }

        LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = { webViewRef?.let { if (it.canGoBack()) it.goBack() } },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryText)
                ) { Text("Назад", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = { webViewRef?.reload() },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SecondaryText)
                ) { Text("Обновить", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        withPageText { text ->
                            val name = pageTitle.ifBlank { currentUrl.orEmpty() }
                            onSendToStudio(MaterialContext.buildPrompt(name, text))
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                ) { Text("Текст → AI Studio", fontSize = 11.sp) }
            }
            item {
                OutlinedButton(
                    onClick = {
                        withPageText { text ->
                            val name = pageTitle.ifBlank { currentUrl.orEmpty() }
                            onSendToCouncil(MaterialContext.buildPrompt(name, text))
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
                                val host = currentUrl?.let(BrowserUrlPolicy::displayHost) ?: "page"
                                val name = (pageTitle.ifBlank { host }).take(80) + " ($host).txt"
                                val result = withContext(Dispatchers.IO) {
                                    MaterialLibrary(context.applicationContext)
                                        .import(name, text.toByteArray())
                                }
                                when (result) {
                                    is LibraryImportResult.Imported -> {
                                        statusLine = "Сохранено в Архив: ${result.item.displayName}"
                                        statusIsError = false
                                    }
                                    is LibraryImportResult.Rejected -> {
                                        statusLine = result.reason
                                        statusIsError = true
                                    }
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

        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.setSupportMultipleWindows(false)
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest
                        ): Boolean {
                            val target = request.url.toString()
                            if (BrowserUrlPolicy.allowsNavigation(target)) return false
                            statusLine = "Переход заблокирован политикой: " + target.take(60)
                            statusIsError = true
                            return true
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            currentUrl = url
                            address = url
                            pageTitle = ""
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            currentUrl = url
                            pageTitle = view.title.orEmpty()
                            if (sessionHistory.firstOrNull() != url) {
                                sessionHistory = (listOf(url) + sessionHistory).take(30)
                            }
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView, title: String?) {
                            pageTitle = title.orEmpty()
                        }
                    }
                    webViewRef = this
                }
            }
        )

        if (sessionHistory.isNotEmpty()) {
            Text(
                "ИСТОРИЯ СЕССИИ (в памяти, не сохраняется): " +
                    sessionHistory.take(3).joinToString(" · ") { BrowserUrlPolicy.displayHost(it) },
                color = SecondaryText, fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
