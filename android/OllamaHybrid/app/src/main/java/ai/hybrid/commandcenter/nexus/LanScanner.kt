package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Finds Ollama servers in the phone's own Wi-Fi network: one GET of
 * /api/tags on port 11434 per address of the local /24, started only when
 * the owner taps «Найти автоматически». Nothing else is probed or sent.
 */
object LanScanner {
    const val OLLAMA_PORT = 11434

    fun isPrivateIpv4(ip: String): Boolean {
        val p = parse(ip) ?: return false
        return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
    }

    /** Every host of the /24 around [ip], without the network, broadcast and [ip] itself. */
    fun subnetHosts(ip: String): List<String> {
        val p = parse(ip) ?: return emptyList()
        val prefix = "${p[0]}.${p[1]}.${p[2]}"
        return (1..254).filter { it != p[3] }.map { "$prefix.$it" }
    }

    /**
     * Normalises what the owner typed into an Ollama base URL:
     * «192.168.1.10» → «http://192.168.1.10:11434»; a trailing /v1 or /api is dropped.
     */
    fun normalizeOllamaEndpoint(input: String): String {
        var value = input.trim().trimEnd('/')
        if (value.isEmpty()) return ""
        if (!value.startsWith("http://", ignoreCase = true) && !value.startsWith("https://", ignoreCase = true)) {
            value = "http://$value"
        }
        for (suffix in listOf("/v1", "/api", "/api/tags")) {
            if (value.endsWith(suffix, ignoreCase = true)) value = value.dropLast(suffix.length)
        }
        val afterScheme = value.substringAfter("://")
        val hostPort = afterScheme.substringBefore('/')
        val hasPort = if (hostPort.startsWith("[")) hostPort.contains("]:") else hostPort.contains(':')
        if (!hasPort) {
            val rest = afterScheme.removePrefix(hostPort)
            value = value.substringBefore("://") + "://" + hostPort + ":" + OLLAMA_PORT + rest
        }
        return value.trimEnd('/')
    }

    /** The phone's own private IPv4 addresses (Wi-Fi, Ethernet, hotspot). */
    fun localAddresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter(::isPrivateIpv4)
            .distinct()
    }.getOrDefault(emptyList())

    suspend fun scan(
        port: Int = OLLAMA_PORT,
        extraHosts: List<String> = listOf("10.0.2.2"),
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): List<String> = coroutineScope {
        val candidates = (localAddresses().flatMap(::subnetHosts) + extraHosts).distinct()
        val client = OkHttpClient.Builder()
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(1_200, TimeUnit.MILLISECONDS)
            .callTimeout(2_000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val gate = Semaphore(48)
        val found = ConcurrentLinkedQueue<String>()
        val done = AtomicInteger()
        candidates.map { host ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    runCatching {
                        val request = Request.Builder().url("http://$host:$port/api/tags").get().build()
                        client.newCall(request).execute().use { response ->
                            val body = response.body?.string().orEmpty()
                            if (response.isSuccessful && body.contains("\"models\"")) found += "http://$host:$port"
                        }
                    }
                    onProgress(done.incrementAndGet(), candidates.size)
                }
            }
        }.awaitAll()
        found.toList().sorted()
    }

    private fun parse(ip: String): List<Int>? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        val values = parts.map { it.toIntOrNull() ?: return null }
        return values.takeIf { v -> v.all { it in 0..255 } }
    }
}
