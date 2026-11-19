package ai.hybrid.commandcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.CouncilOrchestrator
import ai.hybrid.commandcenter.nexus.CompletionRequest
import ai.hybrid.commandcenter.nexus.OpenAiCompatibleGateway
import ai.hybrid.commandcenter.nexus.ProviderMessage
import ai.hybrid.commandcenter.nexus.ProviderSettings
import ai.hybrid.commandcenter.nexus.ProviderSettingsStore
import kotlinx.coroutines.launch

@Composable
fun AiStudioScreen(
    councilMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsStore = remember(context) { ProviderSettingsStore(context) }
    val credentialVault = remember(context) { AndroidCredentialVault(context) }
    val initialSettings = remember(context) { settingsStore.load() }
    val gateway = remember { OpenAiCompatibleGateway() }
    val scope = rememberCoroutineScope()

    var endpoint by remember { mutableStateOf(initialSettings.endpoint) }
    var model by remember { mutableStateOf(initialSettings.model) }
    var apiKey by remember { mutableStateOf("") }
    var hasSavedKey by remember {
        mutableStateOf(credentialVault.get(AndroidCredentialVault.PRIMARY_PROVIDER_KEY) != null)
    }
    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Провайдер не проверен") }
    var isRunning by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            if (councilMode) "NEXUS COUNCIL" else "NEXUS AI STUDIO",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            if (councilMode) {
                "Коллективное выполнение: восемь ролей, не более трёх параллельных агентов по умолчанию."
            } else {
                "Подключение OpenAI-compatible API с потоковой генерацией ответа."
            },
            style = MaterialTheme.typography.bodyMedium
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("ПОДКЛЮЧЕНИЕ МОДЕЛИ", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = { endpoint = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API endpoint") },
                    placeholder = { Text("https://api.openai.com/v1") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя модели") },
                    placeholder = { Text("gpt-4.1-mini или имя локальной модели") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (hasSavedKey) "Новый API-ключ (необязательно)" else "API-ключ (можно пустым для локального сервера)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            try {
                                require(endpoint.isNotBlank()) { "Укажите API endpoint." }
                                require(model.isNotBlank()) { "Укажите имя модели." }
                                settingsStore.save(ProviderSettings(endpoint, model))
                                if (apiKey.isNotBlank()) {
                                    credentialVault.put(AndroidCredentialVault.PRIMARY_PROVIDER_KEY, apiKey)
                                    apiKey = ""
                                    hasSavedKey = true
                                }
                                status = "Настройки сохранены"
                            } catch (error: Exception) {
                                status = error.message ?: "Не удалось сохранить настройки"
                            }
                        }
                    ) {
                        Text("Сохранить")
                    }
                    TextButton(
                        onClick = {
                            credentialVault.remove(AndroidCredentialVault.PRIMARY_PROVIDER_KEY)
                            hasSavedKey = false
                            apiKey = ""
                            status = "API-ключ удалён"
                        }
                    ) {
                        Text("Удалить ключ")
                    }
                }
                Text(
                    if (hasSavedKey) "Ключ сохранён в зашифрованном виде через Android Keystore."
                    else "Ключ не задан. Это допустимо для локального сервера без авторизации; облачный API обычно потребует ключ.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Контекст запроса отправляется выбранному провайдеру. HTTP разрешён только для локальной сети и не шифрует трафик: не используйте с ним ключи облачных сервисов. Внешние endpoint должны использовать HTTPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Задача") },
            placeholder = { Text("Опишите, что должна сделать система…") },
            minLines = 3
        )

        Button(
            onClick = {
                scope.launch {
                    isRunning = true
                    answer = ""
                    status = if (councilMode) "Совет анализирует задачу…" else "Модель отвечает…"
                    try {
                        val activeKey = if (apiKey.isNotBlank()) {
                            credentialVault.put(AndroidCredentialVault.PRIMARY_PROVIDER_KEY, apiKey)
                            apiKey = ""
                            hasSavedKey = true
                            credentialVault.get(AndroidCredentialVault.PRIMARY_PROVIDER_KEY)
                        } else {
                            credentialVault.get(AndroidCredentialVault.PRIMARY_PROVIDER_KEY)
                        }
                        require(endpoint.isNotBlank() && model.isNotBlank()) {
                            "Сначала укажите endpoint и модель."
                        }

                        if (councilMode) {
                            val result = CouncilOrchestrator(gateway, maxParallelAgents = 3).run(
                                goal = prompt,
                                endpoint = endpoint,
                                model = model,
                                apiKey = activeKey!!
                            )
                            answer = result.synthesis.orEmpty()
                            status = result.error ?: if (result.completed) {
                                "Council завершил миссию: ${result.agentResults.count { it.status.name == "COMPLETED" }} агентов успешно."
                            } else {
                                "Council не смог сформировать итог."
                            }
                            if (answer.isBlank() && result.agentResults.isNotEmpty()) {
                                answer = result.agentResults.joinToString("\n\n") {
                                    "## ${it.agent.name}\n${it.output.ifBlank { it.error.orEmpty() }}"
                                }
                            }
                        } else {
                            gateway.stream(
                                CompletionRequest(
                                    endpoint = endpoint,
                                    model = model,
                                    apiKey = activeKey.orEmpty(),
                                    messages = listOf(
                                        ProviderMessage("system", "Ты NEX — помощник экосистемы NEXUS. Отвечай точно, отделяй факты от предположений."),
                                        ProviderMessage("user", prompt)
                                    )
                                )
                            ).collect { chunk -> answer += chunk }
                            status = "Ответ получен"
                        }
                    } catch (error: Exception) {
                        status = error.message ?: "Ошибка выполнения запроса"
                    } finally {
                        isRunning = false
                    }
                }
            },
            enabled = !isRunning && prompt.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRunning) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(Modifier.height(4.dp))
                Text("Выполняется…")
            } else {
                Text(if (councilMode) "Запустить Council" else "Отправить запрос")
            }
        }

        Text(status, style = MaterialTheme.typography.labelMedium)
        if (answer.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(
                    answer,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(18.dp))
    }
}
