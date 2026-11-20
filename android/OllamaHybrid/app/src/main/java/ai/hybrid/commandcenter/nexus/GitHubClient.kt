package ai.hybrid.commandcenter.nexus

import android.util.Base64
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal GitHub REST client so the on-device Council can do what a human
 * engineer does: read the failing source, propose a patch, open a pull
 * request.
 *
 * Safety contract, enforced here and by tests:
 * - **the default branch is never written to.** A proposal always lands on a
 *   new branch and becomes a pull request a human reviews and merges;
 * - the repository is fixed by the user in settings, not chosen by a model;
 * - paths are validated: no absolute paths, no `..`, no escaping the repo;
 * - the token lives in the Android Keystore and never appears in prompts,
 *   logs, error reports or commit messages.
 */
data class GitHubRepo(val owner: String, val name: String, val defaultBranch: String = "main") {
    val slug: String get() = "$owner/$name"
}

data class GitHubFile(val path: String, val content: String, val sha: String)

data class GitHubPullRequest(val number: Int, val url: String, val branch: String)

object GitHubPaths {
    private val valid = Regex("""^[A-Za-z0-9._/\-]{1,255}$""")

    /** Repository-relative paths only. */
    fun isSafe(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || !valid.matches(trimmed)) return false
        if (trimmed.startsWith("/") || trimmed.startsWith("./")) return false
        if (trimmed.contains("..")) return false
        if (trimmed.endsWith("/")) return false
        return true
    }

    /** Branch name for a fix proposal; always distinct from any protected branch. */
    fun proposalBranch(seed: String, nowMillis: Long): String {
        val slug = seed.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(32)
            .ifBlank { "fix" }
        return "nexus/auto-fix/$slug-$nowMillis"
    }

    val PROTECTED_BRANCHES = setOf("main", "master", "release", "production")

    fun isProtected(branch: String): Boolean = branch.trim().lowercase() in PROTECTED_BRANCHES
}

class GitHubClient(
    private val token: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val apiBase: String = "https://api.github.com"
) {
    private val json = "application/json; charset=utf-8".toMediaType()

    suspend fun readFile(repo: GitHubRepo, path: String, ref: String = repo.defaultBranch): Result<GitHubFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(GitHubPaths.isSafe(path)) { "Недопустимый путь в репозитории: " + path.take(60) }
                val body = get("/repos/${repo.slug}/contents/$path?ref=$ref")
                val parsed = JSONObject(body)
                val encoded = parsed.optString("content").replace("\n", "")
                val decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
                GitHubFile(path, decoded.take(MAX_FILE_CHARS), parsed.optString("sha"))
            }
        }

    suspend fun listDirectory(repo: GitHubRepo, path: String, ref: String = repo.defaultBranch): Result<List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(path.isEmpty() || GitHubPaths.isSafe(path)) { "Недопустимый путь." }
                val body = get("/repos/${repo.slug}/contents/$path?ref=$ref")
                val array = JSONArray(body)
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { it.optString("type") + " " + it.optString("path") }
                }.take(400)
            }
        }

    /**
     * Creates a branch, commits the new file content and opens a pull request.
     * It refuses outright to touch a protected branch.
     */
    suspend fun proposeFix(
        repo: GitHubRepo,
        path: String,
        newContent: String,
        baseSha: String,
        title: String,
        body: String,
        branch: String
    ): Result<GitHubPullRequest> = withContext(Dispatchers.IO) {
        runCatching {
            require(GitHubPaths.isSafe(path)) { "Недопустимый путь в репозитории." }
            require(!GitHubPaths.isProtected(branch)) {
                "Запрещено писать напрямую в защищённую ветку «$branch». Исправление идёт только через новую ветку и pull request."
            }
            require(newContent.isNotBlank()) { "Пустое содержимое файла — изменение отклонено." }
            require(newContent.length <= MAX_FILE_CHARS) { "Файл слишком велик для автоматического изменения." }

            val baseRef = JSONObject(get("/repos/${repo.slug}/git/ref/heads/${repo.defaultBranch}"))
            val baseCommitSha = baseRef.getJSONObject("object").getString("sha")

            post(
                "/repos/${repo.slug}/git/refs",
                JSONObject().put("ref", "refs/heads/$branch").put("sha", baseCommitSha)
            )

            put(
                "/repos/${repo.slug}/contents/$path",
                JSONObject()
                    .put("message", title.take(100))
                    .put("content", Base64.encodeToString(newContent.toByteArray(), Base64.NO_WRAP))
                    .put("sha", baseSha)
                    .put("branch", branch)
            )

            val pr = JSONObject(
                post(
                    "/repos/${repo.slug}/pulls",
                    JSONObject()
                        .put("title", title.take(120))
                        .put("body", body.take(MAX_BODY_CHARS))
                        .put("head", branch)
                        .put("base", repo.defaultBranch)
                )
            )
            GitHubPullRequest(pr.optInt("number"), pr.optString("html_url"), branch)
        }
    }

    private fun get(path: String): String = execute(requestBuilder(path).get().build())

    private fun post(path: String, payload: JSONObject): String =
        execute(requestBuilder(path).post(payload.toString().toRequestBody(json)).build())

    private fun put(path: String, payload: JSONObject): String =
        execute(requestBuilder(path).put(payload.toString().toRequestBody(json)).build())

    private fun requestBuilder(path: String): Request.Builder = Request.Builder()
        .url(apiBase + path)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "NEXUS-Android")

    private fun execute(request: Request): String =
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw IOException("GitHub вернул HTTP ${response.code}" + if (message.isBlank()) "." else ": $message")
            }
            body.take(MAX_RESPONSE_CHARS)
        }

    private companion object {
        const val MAX_FILE_CHARS = 200_000
        const val MAX_BODY_CHARS = 20_000
        const val MAX_RESPONSE_CHARS = 2_000_000
    }
}
