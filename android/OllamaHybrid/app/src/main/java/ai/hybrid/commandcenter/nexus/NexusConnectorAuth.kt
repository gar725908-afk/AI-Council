package ai.hybrid.commandcenter.nexus

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class NexusConnectorId(val title: String, val secretId: String) {
    GITHUB("GitHub", "connector.github.token"),
    NOTION("Notion", "connector.notion.integration-token"),
    TINYFISH("TinyFish", "connector.tinyfish.api-key")
}

data class NexusConnectorAccount(
    val connector: NexusConnectorId,
    val identity: String,
    val detail: String
)

/**
 * Credential-based connector onboarding.
 * Secrets are persisted only after the provider has successfully authenticated the request.
 */
class NexusConnectorAuthService(context: Context) {
    private val appContext = context.applicationContext
    private val vault = AndroidCredentialVault(appContext)
    private val metadata = appContext.getSharedPreferences("nexus_connector_accounts_v1", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    suspend fun connect(connector: NexusConnectorId, credential: String): NexusConnectorAccount =
        withContext(Dispatchers.IO) {
            val normalized = credential.trim()
            require(normalized.isNotBlank() && normalized.length <= 16 * 1024) {
                "Ключ пустой или превышает лимит 16 КиБ."
            }
            val account = verifyBlocking(connector, normalized)
            vault.put(connector.secretId, normalized)
            check(metadata.edit()
                .putString(connector.name + ".identity", account.identity)
                .putString(connector.name + ".detail", account.detail)
                .commit()) { "Не удалось сохранить состояние коннектора." }
            account
        }

    suspend fun verifyStored(connector: NexusConnectorId): NexusConnectorAccount =
        withContext(Dispatchers.IO) {
            val secret = vault.get(connector.secretId)
                ?: throw IllegalStateException("Коннектор не подключён.")
            verifyBlocking(connector, secret)
        }

    fun account(connector: NexusConnectorId): NexusConnectorAccount? {
        if (vault.get(connector.secretId) == null) return null
        val identity = metadata.getString(connector.name + ".identity", null) ?: "Подключено"
        val detail = metadata.getString(connector.name + ".detail", "") ?: ""
        return NexusConnectorAccount(connector, identity, detail)
    }

    fun disconnect(connector: NexusConnectorId) {
        vault.remove(connector.secretId)
        metadata.edit()
            .remove(connector.name + ".identity")
            .remove(connector.name + ".detail")
            .commit()
    }

    private fun verifyBlocking(connector: NexusConnectorId, credential: String): NexusConnectorAccount {
        val request = when (connector) {
            NexusConnectorId.GITHUB -> Request.Builder()
                .url("https://api.github.com/user")
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "NEXUS-Android")
                .header("Authorization", "Bearer " + credential)
                .get()
                .build()
            NexusConnectorId.NOTION -> Request.Builder()
                .url("https://api.notion.com/v1/users/me")
                .header("Authorization", "Bearer " + credential)
                .header("Notion-Version", "2026-03-11")
                .header("Accept", "application/json")
                .get()
                .build()
            NexusConnectorId.TINYFISH -> Request.Builder()
                .url("https://api.search.tinyfish.ai/?query=nexus%20connector%20health")
                .header("X-API-Key", credential)
                .header("Accept", "application/json")
                .header("User-Agent", "NEXUS-Android")
                .get()
                .build()
        }

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val message = when (response.code) {
                    401, 403 -> "Провайдер отклонил ключ. Проверьте ключ и разрешения."
                    404 -> "Проверочный API-адрес не найден."
                    429 -> "Слишком много запросов. Повторите позже."
                    else -> "Проверка коннектора завершилась с HTTP " + response.code + "."
                }
                throw IOException(message)
            }
            val body = response.body?.string()?.take(512 * 1024)
                ?: throw IOException("Провайдер вернул пустой ответ.")

            return when (connector) {
                NexusConnectorId.GITHUB -> {
                    val json = JSONObject(body)
                    val login = json.optString("login").takeIf { it.isNotBlank() }
                        ?: throw IOException("GitHub не вернул имя пользователя.")
                    NexusConnectorAccount(connector, "@$login", "Токен принят GitHub API.")
                }
                NexusConnectorId.NOTION -> {
                    val json = JSONObject(body)
                    val name = json.optString("name").takeIf { it.isNotBlank() }
                        ?: json.optJSONObject("bot")?.optJSONObject("owner")?.optString("workspace_name")
                        ?: "Notion integration"
                    NexusConnectorAccount(connector, name, "Интеграция авторизована. Доступны только страницы, которыми с ней поделились.")
                }
                NexusConnectorId.TINYFISH -> {
                    NexusConnectorAccount(connector, "API key подтверждён", "Бесплатный Search API принял ключ. Запуск платных browser/agent задач не выполнялся.")
                }
            }
        }
    }
}
