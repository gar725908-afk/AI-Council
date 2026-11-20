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

/** How big a model is, in the owner's words: phone tiers from the catalog. */
enum class ModelTier(val title: String, val range: String) {
    LIGHT("Лёгкие", "0.6–2B · любой телефон"),
    MEDIUM("Средние", "2–4B · 6 ГБ ОЗУ и больше"),
    MAX("Максимальные", "4–9B · 8–12 ГБ ОЗУ")
}

/**
 * How a GGUF model expects its conversation to be written. llama.cpp's own
 * template engine does not know every new family, so the catalog states it.
 */
enum class PromptStyle {
    /** Qwen3 / Qwen3.5 family: ChatML with an empty think block (answer without reasoning). */
    QWEN_NOTHINK,
    /** Plain ChatML (Qwen2.5, Vikhr and other ChatML fine-tunes). */
    CHATML,
    /** Gemma 4 / Nemotron 3.5 Bolt: <|turn>role … <turn|>, assistant role is "model". */
    GEMMA4,
    /** Let llama.cpp format the chat with the template stored inside the GGUF. */
    NATIVE
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
    val artifact: ModelArtifactManifest? = null,
    val tier: ModelTier? = null,
    val promptStyle: PromptStyle? = null,
    val summary: String = "",
    val russianTuned: Boolean = false,
    val isNew: Boolean = false,
    val ollamaTag: String? = null
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank() && runtimes.isNotEmpty())
    }
    val isDownloadable: Boolean get() = artifact != null && artifact.validationErrors().isEmpty()
}

object NexusModelCatalog {
    private val CHAT = setOf(ModelTaskCapability.CHAT, ModelTaskCapability.MULTILINGUAL)
    private val CHAT_REASONING = CHAT + ModelTaskCapability.REASONING

    private fun hf(
        repo: String, revision: String, file: String, size: Long, sha256: String,
        license: String, runtime: ModelRuntimeKind = ModelRuntimeKind.LLAMA_CPP
    ) = ModelArtifactManifest(
        source = "https://huggingface.co/$repo",
        immutableRevision = revision,
        licenseId = license,
        artifactFileName = file,
        sizeBytes = size,
        sha256 = sha256,
        runtime = runtime,
        downloadUrl = "https://huggingface.co/$repo/resolve/$revision/$file"
    )

    private fun phone(
        id: String, name: String, publisher: String, params: String, tier: ModelTier,
        artifact: ModelArtifactManifest, style: PromptStyle?, summary: String,
        capabilities: Set<ModelTaskCapability> = CHAT_REASONING,
        russian: Boolean = false, recommended: Boolean = false, isNew: Boolean = false, ollama: String? = null
    ) = NexusModelDescriptor(
        id, name, publisher, ModelExecutionTarget.ANDROID_ON_DEVICE, setOf(artifact.runtime), capabilities,
        params, recommendedForBalancedProfile = recommended, artifact = artifact, tier = tier,
        promptStyle = style, summary = summary, russianTuned = russian, isNew = isNew, ollamaTag = ollama
    )

    /**
     * Every downloadable file is pinned to an immutable Hugging Face commit and
     * its SHA-256, so a model can never silently change under the owner.
     * Nothing above 9B parameters: those do not run on phones.
     */
    val starterModels = listOf(
        // ------------------------------------------------------------ лёгкие
        phone(
            "qwen3-0.6b", "Qwen3 0.6B", "Qwen", "0.6B", ModelTier.LIGHT,
            hf("ggml-org/Qwen3-0.6B-GGUF", "a41486f", "Qwen3-0.6B-Q4_0.gguf", 428_970_080L,
                "da2572f16c06133561ce56accaa822216f2391ef4d37fba427801cd6736417d4", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Самая маленькая: летает даже на старом телефоне, отвечает просто.",
            ollama = "qwen3:0.6b"
        ),
        phone(
            "qwen3.5-0.8b", "Qwen3.5 0.8B", "Qwen", "0.8B", ModelTier.LIGHT,
            hf("unsloth/Qwen3.5-0.8B-GGUF", "6ab461498e2023f6e3c1baea90a8f0fe38ab64d0", "Qwen3.5-0.8B-Q4_K_M.gguf",
                532_517_120L, "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Новое поколение Qwen в полгигабайта: быстрая и заметно умнее Qwen3 0.6B.",
            ollama = "qwen3.5:0.8b"
        ),
        phone(
            "vikhr-qwen2.5-1.5b", "Vikhr 1.5B", "Vikhr Models", "1.5B", ModelTier.LIGHT,
            hf("Vikhrmodels/Vikhr-Qwen-2.5-1.5B-Instruct-GGUF", "f3d0a36f09a910b9f50ae1bf388d147aca0b75d4",
                "Vikhr-Qwen-2.5-1.5b-Instruct-Q4_K_M.gguf", 985_700_992L,
                "d82b16244a33dbbde4742e38e42b2b3d74dd883f4b8ff4ae53eca29aabf60662", "Apache-2.0"),
            PromptStyle.CHATML, "Дообучена на русском: грамотный язык при скромном размере.",
            capabilities = CHAT, russian = true
        ),
        phone(
            "qwen3.5-2b", "Qwen3.5 2B", "Qwen", "2B", ModelTier.LIGHT,
            hf("unsloth/Qwen3.5-2B-GGUF", "f6d5376be1edb4d416d56da11e5397a961aca8ae", "Qwen3.5-2B-Q4_K_M.gguf",
                1_280_835_840L, "aaf42c8b7c3cab2bf3d69c355048d4a0ee9973d48f16c731c0520ee914699223", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Лучший баланс скорости и ума среди лёгких.",
            recommended = true, ollama = "qwen3.5:2b"
        ),
        // ------------------------------------------------------------ средние
        phone(
            "google-gemma-4-e2b-it", "Gemma 4 E2B", "Google", "E2B", ModelTier.MEDIUM,
            ModelArtifactManifest(
                source = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm",
                immutableRevision = "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1",
                licenseId = "Apache-2.0",
                artifactFileName = "gemma-4-E2B-it.litertlm",
                sizeBytes = 2_588_147_712L,
                sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
                runtime = ModelRuntimeKind.LITERT_LM,
                downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm"
            ),
            null, "Google, движок LiteRT-LM. Хорошо говорит по-русски.",
            capabilities = CHAT_REASONING + ModelTaskCapability.IMAGE_INPUT + ModelTaskCapability.AUDIO_INPUT,
            recommended = true, ollama = "gemma4:e2b"
        ),
        phone(
            "qwen3.5-4b", "Qwen3.5 4B", "Qwen", "4B", ModelTier.MEDIUM,
            hf("unsloth/Qwen3.5-4B-GGUF", "e87f176479d0855a907a41277aca2f8ee7a09523", "Qwen3.5-4B-Q4_K_M.gguf",
                2_740_937_888L, "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Уверенно пишет, переводит и решает задачи.",
            ollama = "qwen3.5:4b"
        ),
        phone(
            "qvikhr-3-4b", "QVikhr-3 4B", "Vikhr Models", "4B", ModelTier.MEDIUM,
            hf("Vikhrmodels/QVikhr-3-4B-Instruction-GGUF", "ab5a2e9090cd1643ea48f3eb86d7b8fde8df8db6",
                "QVikhr-3-4B-Instruction-Q4_K_M.gguf", 2_497_281_088L,
                "9d131e93d5c4841346a6965610daebb698218a1cec83c36f6b1d4829c25d6425", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Qwen3, дообученный на русском языке: живой и точный русский.",
            russian = true, ollama = "hf.co/Vikhrmodels/QVikhr-3-4B-Instruction-GGUF:Q4_K_M"
        ),
        // ------------------------------------------------------------ максимальные
        phone(
            "nvidia-nemotron-3.5-bolt-e4b", "Nemotron 3.5 Bolt E4B", "NVIDIA", "E4B", ModelTier.MAX,
            hf("unsloth/NVIDIA-Nemotron-3.5-Bolt-E4B-GGUF", "b738967d6da3441a7d1c71fcec15e49e32e16ecd",
                "NVIDIA-Nemotron-3.5-Bolt-E4B-UD-Q3_K_XL.gguf", 4_219_133_120L,
                "3f8032b9d2c18536c4ac51b97959a563cc27434de99823640c98147edc62ce9b", "OpenMDW-1.1"),
            PromptStyle.GEMMA4, "Свежая модель NVIDIA для устройств: сильная в рассуждениях и коде.",
            isNew = true, ollama = "nemotron-3.5-bolt:e4b"
        ),
        phone(
            "google-gemma-4-e4b-it", "Gemma 4 E4B", "Google", "E4B", ModelTier.MAX,
            hf("litert-community/gemma-4-E4B-it-litert-lm", "f254b5ba6e36f7d89cc7892e268e9877bc20f0a0",
                "gemma-4-E4B-it.litertlm", 3_659_530_240L,
                "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0", "Apache-2.0",
                ModelRuntimeKind.LITERT_LM),
            null, "Старшая Gemma 4 на LiteRT-LM: умнее E2B, нужен телефон с 8 ГБ ОЗУ.",
            capabilities = CHAT_REASONING + ModelTaskCapability.IMAGE_INPUT + ModelTaskCapability.AUDIO_INPUT,
            ollama = "gemma4:e4b"
        ),
        phone(
            "yandexgpt-5-lite-8b", "YandexGPT 5 Lite 8B", "Яндекс", "8B", ModelTier.MAX,
            hf("yandex/YandexGPT-5-Lite-8B-instruct-GGUF", "9fe287d2f512503046bb008aed350f2b4bbb903d",
                "YandexGPT-5-Lite-8B-instruct-Q4_K_M.gguf", 4_920_741_184L,
                "d9ff5b826f20fbcc2f898f9f2349ac21241579f8fbb79cd32148a333623ba228", "YandexGPT-5-Lite-8B"),
            PromptStyle.NATIVE, "Модель Яндекса: родной русский язык и знание российских реалий.",
            capabilities = CHAT, russian = true,
            ollama = "hf.co/yandex/YandexGPT-5-Lite-8B-instruct-GGUF:Q4_K_M"
        ),
        phone(
            "t-lite-it-2.1", "T-lite 2.1 8B", "Т-Банк", "8B", ModelTier.MAX,
            hf("t-tech/T-lite-it-2.1-GGUF", "fc10186e59064c2e74f59c3d5dbce233931b3b81", "T-lite-it-2.1-Q4_K_M.gguf",
                5_027_750_240L, "68c75f5940c36baca18113058ad3d55f6d1d3e1123a8053bb4bba8481805c960", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Русская модель Т-Банка на базе Qwen3: сильна в русских текстах.",
            russian = true, ollama = "hf.co/t-tech/T-lite-it-2.1-GGUF:Q4_K_M"
        ),
        phone(
            "qwen3.5-9b", "Qwen3.5 9B", "Qwen", "9B", ModelTier.MAX,
            hf("unsloth/Qwen3.5-9B-GGUF", "3885219b6810b007914f3a7950a8d1b469d598a5", "Qwen3.5-9B-Q4_K_M.gguf",
                5_680_522_464L, "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8", "Apache-2.0"),
            PromptStyle.QWEN_NOTHINK, "Самая умная из тех, что помещаются в телефон. Нужен флагман с 12 ГБ ОЗУ.",
            ollama = "qwen3.5:9b"
        ),
        NexusModelDescriptor(
            "ollama-lan-dynamic", "Модели домашнего ПК (Ollama)", "User-managed Ollama server",
            ModelExecutionTarget.HOME_PC_LAN, setOf(ModelRuntimeKind.OLLAMA_LAN),
            setOf(ModelTaskCapability.CHAT, ModelTaskCapability.REASONING, ModelTaskCapability.TOOL_ROUTING,
                ModelTaskCapability.MULTILINGUAL),
            "определяется через /api/tags", requiresLicenseAcceptance = false
        )
    )

    /** Downloadable phone models, in catalog order (tier, then size). */
    val onDevice: List<NexusModelDescriptor> =
        starterModels.filter { it.executionTarget == ModelExecutionTarget.ANDROID_ON_DEVICE && it.isDownloadable }

    fun byTier(tier: ModelTier): List<NexusModelDescriptor> = onDevice.filter { it.tier == tier }

    fun find(id: String?): NexusModelDescriptor? = id?.let { key -> starterModels.firstOrNull { it.id == key } }

    /**
     * A file found on the phone is a catalog model if its size matches exactly;
     * the SHA-256 check before the first launch then proves it.
     */
    fun matchFile(sizeBytes: Long, fileName: String? = null): NexusModelDescriptor? {
        val sameSize = onDevice.filter { it.artifact?.sizeBytes == sizeBytes }
        if (sameSize.size <= 1) return sameSize.firstOrNull()
        return sameSize.firstOrNull { it.artifact?.artifactFileName.equals(fileName, ignoreCase = true) } ?: sameSize.first()
    }
}

/** Will this model fit into the phone's memory? Honest estimate from file size and total RAM. */
enum class ModelFit(val label: String) {
    EASY("Пойдёт легко"),
    OK("Пойдёт"),
    TIGHT("Впритык"),
    TOO_BIG("Не потянет"),
    UNKNOWN("ОЗУ неизвестно");

    companion object {
        /** Weights are memory-mapped; runtime buffers and the KV cache add roughly 15% plus 450 MB. */
        fun requiredBytes(fileBytes: Long): Long = (fileBytes * 1.15).toLong() + 450L * 1024 * 1024

        fun evaluate(fileBytes: Long, ramTotalBytes: Long): ModelFit {
            if (ramTotalBytes <= 0L || fileBytes <= 0L) return UNKNOWN
            val share = requiredBytes(fileBytes).toDouble() / ramTotalBytes
            return when {
                share <= 0.45 -> EASY
                share <= 0.65 -> OK
                share <= 0.85 -> TIGHT
                else -> TOO_BIG
            }
        }
    }
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
        if (beforeVersion < 0 || proposedVersion <= beforeVersion) add("Номер версии должен быть больше родительской версии.")
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
