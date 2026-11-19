package ai.hybrid.commandcenter.nexus

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Real reachability check of a configured provider endpoint.
 *
 * Honesty contract:
 * - sends no API key and no user content — only an unauthenticated GET;
 * - reports what actually happened (HTTP code, latency, timestamp) and never
 *   upgrades "endpoint answered" into "provider is working";
 * - any verdict is valid only for the moment it was measured.
 */
enum class EndpointReachability { REACHABLE, UNREACHABLE }

data class HealthCheckResult(
    val reachability: EndpointReachability,
    val httpCode: Int?,
    val detail: String,
    val latencyMillis: Long,
    val checkedAtEpochMillis: Long
)

object ProviderHealthCheck {

    private const val TIMEOUT_SECONDS = 6L
    private const val MAX_DETAIL_CHARS = 120

    /**
     * Normalizes a user-configured endpoint for an unauthenticated probe.
     * Returns null when the value cannot be probed safely.
     */
    fun probeUrl(rawEndpoint: String): String? {
        val trimmed = rawEndpoint.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = when {
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("http://", ignoreCase = true) -> trimmed
            trimmed.contains("://") -> return null
            else -> "https://$trimmed"
        }
        if (withScheme.length > 2048) return null
        if (withScheme.any { it.code < 0x20 || it == ' ' }) return null
        return withScheme.trimEnd('/')
    }

    /**
     * Pure classification of a completed HTTP exchange: any HTTP answer means
     * the endpoint is reachable; the detail says what the code implies without
     * promising more than was tested.
     */
    fun classify(httpCode: Int): Pair<EndpointReachability, String> = when (httpCode) {
        in 200..299 -> EndpointReachability.REACHABLE to "Отвечает (HTTP $httpCode)"
        401, 403 -> EndpointReachability.REACHABLE to
            "Отвечает (HTTP $httpCode) — требуется ключ; ключ в проверке не использовался"
        404 , 405 -> EndpointReachability.REACHABLE to
            "Хост отвечает (HTTP $httpCode) — путь проверки не обслуживается, это нормально"
        in 500..599 -> EndpointReachability.REACHABLE to
            "Хост отвечает, но сообщает об ошибке сервера (HTTP $httpCode)"
        else -> EndpointReachability.REACHABLE to "Отвечает (HTTP $httpCode)"
    }

    suspend fun check(rawEndpoint: String, client: OkHttpClient = defaultClient): HealthCheckResult =
        withContext(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val url = probeUrl(rawEndpoint)
                ?: return@withContext HealthCheckResult(
                    reachability = EndpointReachability.UNREACHABLE,
                    httpCode = null,
                    detail = "Endpoint пуст или имеет недопустимый формат",
                    latencyMillis = 0,
                    checkedAtEpochMillis = startedAt
                )
            try {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    val (reachability, detail) = classify(response.code)
                    HealthCheckResult(
                        reachability = reachability,
                        httpCode = response.code,
                        detail = detail.take(MAX_DETAIL_CHARS),
                        latencyMillis = System.currentTimeMillis() - startedAt,
                        checkedAtEpochMillis = startedAt
                    )
                }
            } catch (error: IOException) {
                HealthCheckResult(
                    reachability = EndpointReachability.UNREACHABLE,
                    httpCode = null,
                    detail = ("Недоступен: " + (error.message ?: error.javaClass.simpleName))
                        .take(MAX_DETAIL_CHARS),
                    latencyMillis = System.currentTimeMillis() - startedAt,
                    checkedAtEpochMillis = startedAt
                )
            } catch (error: IllegalArgumentException) {
                HealthCheckResult(
                    reachability = EndpointReachability.UNREACHABLE,
                    httpCode = null,
                    detail = "Недопустимый адрес endpoint",
                    latencyMillis = System.currentTimeMillis() - startedAt,
                    checkedAtEpochMillis = startedAt
                )
            }
        }

    private val defaultClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(false)
            .build()
    }
}
