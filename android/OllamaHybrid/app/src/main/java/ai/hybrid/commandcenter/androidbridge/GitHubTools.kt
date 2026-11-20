package ai.hybrid.commandcenter.androidbridge

import ai.hybrid.commandcenter.nexus.AndroidCredentialVault
import ai.hybrid.commandcenter.nexus.GitHubClient
import ai.hybrid.commandcenter.nexus.GitHubPaths
import ai.hybrid.commandcenter.nexus.GitHubRepo
import android.content.Context

/**
 * GitHub as bridge tools, so the on-device Council can work the way a human
 * engineer does — read the failing source, then propose a fix — while every
 * call still passes through the Gateway with approvals and audit.
 *
 * Reading is READ_ONLY. Proposing a fix is SENSITIVE and can only ever
 * create a branch and a pull request: the default branch is never written
 * to, so nothing a model produces reaches the shipped code without a human
 * merging it.
 */
const val GITHUB_TOKEN_KEY = "github_token"

/** Repository the user configured; a model never chooses it. */
object GitHubSettings {
    private const val PREFS = "nexus_github"

    fun load(context: Context): GitHubRepo? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val owner = prefs.getString("owner", null)?.trim().orEmpty()
        val name = prefs.getString("name", null)?.trim().orEmpty()
        if (owner.isEmpty() || name.isEmpty()) return null
        return GitHubRepo(owner, name, prefs.getString("branch", "main")?.trim().orEmpty().ifBlank { "main" })
    }

    fun save(context: Context, owner: String, name: String, branch: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("owner", owner.trim().take(60))
            .putString("name", name.trim().take(100))
            .putString("branch", branch.trim().take(60).ifBlank { "main" })
            .apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

private fun tokenOrNull(context: Context): String? =
    AndroidCredentialVault(context).get(GITHUB_TOKEN_KEY)?.takeIf { it.isNotBlank() }

class GitHubReadFileTool(private val context: Context) : VerifiableBridgeTool {
    override val id: String = "github.read_file"
    override val capability: BridgeCapability = BridgeCapability.REMOTE_CONNECTOR
    override val risk: ActionRisk = ActionRisk.READ_ONLY
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val repo = GitHubSettings.load(context)
            ?: return BridgeResult(false, transport, "Репозиторий не настроен в разделе «Исправления».", errorCode = "E_NO_REPO")
        val token = tokenOrNull(context)
            ?: return BridgeResult(false, transport, "Токен GitHub не сохранён.", errorCode = "E_NO_TOKEN")
        val path = request.arguments["path"].orEmpty().trim()
        if (!GitHubPaths.isSafe(path)) {
            return BridgeResult(false, transport, "Недопустимый путь: " + path.take(60), errorCode = "E_BAD_PATH")
        }
        val file = GitHubClient(token).readFile(repo, path).getOrElse { error ->
            return BridgeResult(false, transport, error.message ?: "Чтение не удалось.", errorCode = "E_READ_FAILED")
        }
        return BridgeResult(
            success = true,
            transport = transport,
            message = file.content,
            data = mapOf("path" to file.path, "sha" to file.sha, "repo" to repo.slug, "chars" to file.content.length.toString())
        )
    }

    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification =
        if (result.data["path"] == request.arguments["path"]?.trim()) VerifiableBridgeTool.Verification.Passed
        else VerifiableBridgeTool.Verification.Failed("Прочитан не тот файл, который запрашивали.")
}

class GitHubProposeFixTool(private val context: Context) : VerifiableBridgeTool {
    override val id: String = "github.propose_fix"
    override val capability: BridgeCapability = BridgeCapability.REMOTE_CONNECTOR

    /** Always requires a one-shot user approval. */
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val repo = GitHubSettings.load(context)
            ?: return BridgeResult(false, transport, "Репозиторий не настроен.", errorCode = "E_NO_REPO")
        val token = tokenOrNull(context)
            ?: return BridgeResult(false, transport, "Токен GitHub не сохранён.", errorCode = "E_NO_TOKEN")
        val path = request.arguments["path"].orEmpty().trim()
        val content = request.arguments["content"].orEmpty()
        val sha = request.arguments["sha"].orEmpty().trim()
        val title = request.arguments["title"].orEmpty().ifBlank { "NEXUS: исправление по журналу ошибок" }
        val body = request.arguments["body"].orEmpty()

        if (!GitHubPaths.isSafe(path)) {
            return BridgeResult(false, transport, "Недопустимый путь: " + path.take(60), errorCode = "E_BAD_PATH")
        }
        if (sha.isEmpty()) {
            return BridgeResult(false, transport, "Нет sha исходного файла — сначала прочитайте файл.", errorCode = "E_NO_BASE")
        }
        if (content.isBlank()) {
            return BridgeResult(false, transport, "Пустое содержимое файла — изменение отклонено.", errorCode = "E_EMPTY")
        }

        val branch = GitHubPaths.proposalBranch(path.substringAfterLast('/'), System.currentTimeMillis())
        val pull = GitHubClient(token).proposeFix(
            repo = repo,
            path = path,
            newContent = content,
            baseSha = sha,
            title = title,
            body = body + "\n\n_Создано NEXUS на устройстве. Изменение не влито: требуется проверка человеком._",
            branch = branch
        ).getOrElse { error ->
            return BridgeResult(false, transport, error.message ?: "Не удалось создать предложение.", errorCode = "E_PROPOSE_FAILED")
        }
        return BridgeResult(
            success = true,
            transport = transport,
            message = "Создан pull request #" + pull.number + " в ветке " + pull.branch +
                ". Код не изменён, пока вы не проверите и не вольёте его.",
            data = mapOf(
                "pr_number" to pull.number.toString(),
                "url" to pull.url,
                "branch" to pull.branch,
                "path" to path,
                "base_branch" to repo.defaultBranch
            )
        )
    }

    /** Postcondition: a pull request on a fresh branch, never a protected one. */
    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification {
        val branch = result.data["branch"].orEmpty()
        if (branch.isBlank()) return VerifiableBridgeTool.Verification.Failed("Ветка предложения неизвестна.")
        if (GitHubPaths.isProtected(branch)) {
            return VerifiableBridgeTool.Verification.Failed("Изменение попало в защищённую ветку — это запрещено.")
        }
        if (result.data["pr_number"].orEmpty().toIntOrNull() == null) {
            return VerifiableBridgeTool.Verification.Failed("Pull request не создан.")
        }
        return VerifiableBridgeTool.Verification.Passed
    }
}
