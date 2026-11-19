package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class NexusRelease(
    val version: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val apkDigest: String?
)

sealed interface NexusUpdateResult {
    data class Available(val release: NexusRelease) : NexusUpdateResult
    data class UpToDate(val currentVersion: String, val latestVersion: String) : NexusUpdateResult
}

class NexusUpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    suspend fun check(currentVersion: String): NexusUpdateResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NEXUS-Android-Updater")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GitHub вернул HTTP ${response.code}.")
            }
            val payload = response.body?.string()
                ?: throw IOException("GitHub вернул пустой ответ.")
            val json = JSONObject(payload)
            val tag = json.optString("tag_name").removePrefix("v").trim()
            require(isSemVer(tag)) { "GitHub Release содержит некорректный tag_name." }

            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkDigest: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        apkDigest = asset.optString("digest").takeIf { it.startsWith("sha256:") }
                        break
                    }
                }
            }

            val release = NexusRelease(
                version = tag,
                title = json.optString("name").ifBlank { "NEXUS $tag" },
                notes = json.optString("body").take(4_000),
                pageUrl = json.optString("html_url").takeIf { it.startsWith("https://github.com/") }
                    ?: RELEASES_URL,
                apkUrl = apkUrl,
                apkDigest = apkDigest
            )
            if (compareVersions(tag, currentVersion) > 0) {
                NexusUpdateResult.Available(release)
            } else {
                NexusUpdateResult.UpToDate(currentVersion, tag)
            }
        }
    }

    private fun isSemVer(value: String): Boolean =
        Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?$").matches(value)

    private fun compareVersions(left: String, right: String): Int {
        fun parts(value: String) = value.substringBefore('-').substringBefore('+')
            .split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (index in 0 until maxOf(a.size, b.size)) {
            val comparison = (a.getOrElse(index) { 0 }).compareTo(b.getOrElse(index) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    private companion object {
        const val LATEST_RELEASE_URL =
            "https://api.github.com/repos/gar725908-afk/AI-Council/releases/latest"
        const val RELEASES_URL = "https://github.com/gar725908-afk/AI-Council/releases"
    }
}
