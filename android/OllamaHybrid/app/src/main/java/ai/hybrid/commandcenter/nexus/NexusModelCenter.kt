package ai.hybrid.commandcenter.nexus

enum class ModelRuntimeKind { LITERT_LM, LLAMA_CPP, OLLAMA_LAN }
enum class ModelExecutionTarget { ANDROID_ON_DEVICE, HOME_PC_LAN }
enum class ModelTaskCapability { CHAT, REASONING, TOOL_ROUTING, MULTILINGUAL, IMAGE_INPUT, AUDIO_INPUT, EMBEDDINGS }
enum class ModelInstallState { NOT_INSTALLED, DOWNLOADING, VERIFYING, READY, INCOMPATIBLE, FAILED }

/** Verified immutable artifact metadata. No URL is inferred from a model name. */
data class ModelArtifactManifest(
    val source: String,
    val immutableRevision: String,
    val licenseId: String,
    val artifactFileName: String,
    val sizeBytes: Long,
    val sha256: String,
    val runtime: ModelRuntimeKind,
    val downloadUrl: String = ""
) {
    fun validationErrors(): List<String> = buildList {
        if (source.isBlank()) add("Не указан источник модели.")
        if (!downloadUrl.startsWith("https://")) add("Для артефакта должен быть указан проверенный HTTPS URL.")
        if (immutableRevision.isBlank()) add("Не зафиксирована неизменяемая ревизия модели.")
        if (licenseId.isBlank()) add("Не указана лицензия модели.")
        if (artifactFileName.isBlank() || artifactFileName.contains('/') || artifactFileName.contains('\\')) {
            add("Некорректное имя файла модели.")
        }
        if (sizeBytes <= 0L) add("Размер артефакта должен быть положительным.")
        if (!sha256.matches(Regex("[a-fA-F0-9]{64}"))) add("Для загрузки требуется корректный SHA-256.")
    }
}

data class NexusModelDescriptor(
    val id: String,
    val displayName: String,
    val publisher: String,
    val executionTarget: ModelExecutionTarget,
    val runtimes: Set<ModelRuntimeKind>,
    val capabilities: Set<ModelTaskCapability>,
    val approximateParameterLabel: String,
    val recommendedForBalancedProfile: Boolean = false,
    val requiresLicenseAcceptance: Boolean = true,
    val artifact: ModelArtifactManifest? = null
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank() && runtimes.isNotEmpty())
    }
    val isDownloadable: Boolean get() = artifact != null && artifact.validationErrors().isEmpty()
}

object NexusModelCatalog {
    /** Verified starter artifacts are pinned to immutable upstream revisions and SHA-256 hashes. */
    val starterModels = listOf(
        NexusModelDescriptor(
            "google-gemma-4-e2b-it", "Gemma 4 E2B", "Google",
            ModelExecutionTarget.ANDROID_ON_DEVICE, setOf(ModelRuntimeKind.LITERT_LM),
            setOf(ModelTaskCapability.CHAT, ModelTaskCapability.REASONING, ModelTaskCapability.MULTILINGUAL,
                ModelTaskCapability.IMAGE_INPUT, ModelTaskCapability.AUDIO_INPUT),
            "effective 2B", recommendedForBalancedProfile = true,
            // Download is intentionally disabled until the exact artifact SHA-256 and byte size
            // are verified against this immutable repository revision. The catalog entry remains
            // visible; users can import a .litertlm file in the meantime.
            artifact = null

        ),
        NexusModelDescriptor(
            "qwen3-0.6b", "Qwen3 0.6B", "Qwen",
            ModelExecutionTarget.ANDROID_ON_DEVICE, setOf(ModelRuntimeKind.LLAMA_CPP),
            setOf(ModelTaskCapability.CHAT, ModelTaskCapability.REASONING, ModelTaskCapability.MULTILINGUAL),
            "0.6B"
        ),
        NexusModelDescriptor(
            "functiongemma-270m", "FunctionGemma 270M", "Google",
            ModelExecutionTarget.ANDROID_ON_DEVICE, setOf(ModelRuntimeKind.LITERT_LM),
            setOf(ModelTaskCapability.TOOL_ROUTING, ModelTaskCapability.MULTILINGUAL),
            "270M"
        ),
        NexusModelDescriptor(
            "ollama-lan-dynamic", "Модели домашнего ПК (Ollama)", "User-managed Ollama server",
            ModelExecutionTarget.HOME_PC_LAN, setOf(ModelRuntimeKind.OLLAMA_LAN),
            setOf(ModelTaskCapability.CHAT, ModelTaskCapability.REASONING, ModelTaskCapability.TOOL_ROUTING,
                ModelTaskCapability.MULTILINGUAL),
            "определяется через /api/tags", requiresLicenseAcceptance = false
        )
    )
    fun find(id: String): NexusModelDescriptor? = starterModels.firstOrNull { it.id == id }
}

data class ModelDownloadRequest(val modelId: String, val availableBytes: Long, val userAcceptedLicenseId: String?)
sealed interface ModelDownloadDecision {
    data class Allowed(val manifest: ModelArtifactManifest) : ModelDownloadDecision
    data class Rejected(val reasons: List<String>) : ModelDownloadDecision
}

object ModelDownloadPolicy {
    fun evaluate(model: NexusModelDescriptor, request: ModelDownloadRequest): ModelDownloadDecision {
        val manifest = model.artifact
            ?: return ModelDownloadDecision.Rejected(listOf("Для этой модели ещё не опубликован проверенный артефакт."))
        val errors = manifest.validationErrors().toMutableList()
        if (request.modelId != model.id) errors += "ID запроса не совпадает с выбранной моделью."
        if (request.availableBytes < manifest.sizeBytes) errors += "Недостаточно свободного места для загрузки модели."
        if (model.requiresLicenseAcceptance && request.userAcceptedLicenseId != manifest.licenseId) {
            errors += "Перед загрузкой необходимо принять лицензию " + manifest.licenseId + "."
        }
        return if (errors.isEmpty()) ModelDownloadDecision.Allowed(manifest)
        else ModelDownloadDecision.Rejected(errors)
    }
}

enum class EvolutionChangeKind { PROMPT_PROFILE, SKILL_PACK, ROUTING_POLICY, TOOL_DESCRIPTION, MODEL_CONFIGURATION }
data class EvolutionProposal(
    val id: String, val kind: EvolutionChangeKind, val title: String, val rationale: String,
    val beforeVersion: Long, val proposedVersion: Long, val contentSha256: String, val createdAtEpochMs: Long
) {
    fun validationErrors(): List<String> = buildList {
        if (id.isBlank() || title.isBlank() || rationale.isBlank()) add("Предложение должно иметь ID, название и обоснование.")
        if (beforeVersion < 0 || proposedVersion != beforeVersion + 1) add("Версия изменения должна увеличиваться ровно на единицу.")
        if (!contentSha256.matches(Regex("[a-fA-F0-9]{64}"))) add("Не указан корректный SHA-256 содержимого.")
        if (createdAtEpochMs <= 0) add("Некорректное время создания предложения.")
    }
}
sealed interface EvolutionApproval {
    data object Approved : EvolutionApproval
    data class Rejected(val reason: String) : EvolutionApproval
}
data class EvolutionActivation(val proposalId: String, val activeVersion: Long, val rollbackToVersion: Long)

object NexusEvolutionPolicy {
    fun activate(proposal: EvolutionProposal, approval: EvolutionApproval): Result<EvolutionActivation> {
        val errors = proposal.validationErrors()
        if (errors.isNotEmpty()) return Result.failure(IllegalArgumentException(errors.joinToString(" ")))
        if (approval !is EvolutionApproval.Approved) {
            val reason = (approval as EvolutionApproval.Rejected).reason.ifBlank { "Изменение отклонено пользователем." }
            return Result.failure(IllegalStateException(reason))
        }
        return Result.success(EvolutionActivation(proposal.id, proposal.proposedVersion, proposal.beforeVersion))
    }
    /** Weight fine-tuning/replacement is never part of silent self-evolution. */
    fun allowsSilentWeightMutation(): Boolean = false
}
