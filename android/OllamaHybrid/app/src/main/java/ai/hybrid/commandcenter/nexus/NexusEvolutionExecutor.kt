package ai.hybrid.commandcenter.nexus

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

enum class NexusEvolutionProposalStatus { PENDING, ACTIVE, REJECTED, SUPERSEDED }

data class NexusEvolutionRecord(
    val proposal: EvolutionProposal,
    val payload: String,
    val status: NexusEvolutionProposalStatus
)

/**
 * Applies versioned configuration payloads only.
 *
 * This executor does not compile/run code, change Android permissions, call tools, or mutate
 * model weights. Every activation is initiated by an explicit user approval in the UI.
 */
class NexusEvolutionExecutor(context: Context) {
    private val root = File(context.applicationContext.filesDir, "nexus-evolution")
    private val proposalsDir = File(root, "proposals")
    private val versionsDir = File(root, "versions")
    private val activeFile = File(root, "active.json")
    private val auditFile = File(root, "audit.jsonl")

    init {
        proposalsDir.mkdirs()
        versionsDir.mkdirs()
        if (!activeFile.exists()) {
            writeJsonAtomic(activeFile, JSONObject().put("version", 0L))
            writeTextAtomic(versionFile(0L), "")
        }
    }

    @Synchronized
    fun createProposal(
        kind: EvolutionChangeKind,
        title: String,
        rationale: String,
        payload: String
    ): EvolutionProposal {
        require(payload.isNotBlank()) { "Пустое содержимое изменения нельзя предложить." }
        require(payload.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
            "Изменение превышает допустимый размер 256 КБ."
        }
        validatePayload(kind, payload)
        val current = activeVersion()
        val nextVersion = maxOf(
            availableVersions().maxOrNull() ?: 0L,
            history().maxOfOrNull { it.proposal.proposedVersion } ?: 0L
        ) + 1L
        val proposal = EvolutionProposal(
            id = UUID.randomUUID().toString(),
            kind = kind,
            title = title.trim(),
            rationale = rationale.trim(),
            beforeVersion = current,
            proposedVersion = nextVersion,
            contentSha256 = sha256(payload),
            createdAtEpochMs = System.currentTimeMillis()
        )
        val validation = proposal.validationErrors()
        require(validation.isEmpty()) { validation.joinToString(" ") }
        saveRecord(NexusEvolutionRecord(proposal, payload, NexusEvolutionProposalStatus.PENDING))
        appendAudit("PROPOSAL_CREATED", proposal.id, current, proposal.proposedVersion, proposal.title)
        return proposal
    }

    @Synchronized
    fun pendingProposals(): List<NexusEvolutionRecord> =
        proposalFiles().mapNotNull { file -> readRecord(file) }
            .filter { it.status == NexusEvolutionProposalStatus.PENDING }
            .sortedBy { it.proposal.createdAtEpochMs }

    @Synchronized
    fun history(): List<NexusEvolutionRecord> =
        proposalFiles().mapNotNull { file -> readRecord(file) }
            .sortedByDescending { it.proposal.createdAtEpochMs }

    @Synchronized
    fun activeVersion(): Long = runCatching {
        JSONObject(activeFile.readText()).getLong("version")
    }.getOrDefault(0L)

    @Synchronized
    fun activePayload(): String = File(versionsDir, activeVersion().toString() + ".txt")
        .takeIf { it.isFile }?.readText().orEmpty()

    /**
     * Resolves the most recent configuration of this kind on the active version's parent chain.
     * Changes of other kinds remain active, while rollback excludes all descendants of the target.
     */
    @Synchronized
    fun activeConfiguration(kind: EvolutionChangeKind): String {
        val activeRecords = history()
            .filter { it.status == NexusEvolutionProposalStatus.ACTIVE }
            .associateBy { it.proposal.proposedVersion }
        var version = activeVersion()
        while (version > 0L) {
            val record = activeRecords[version] ?: return ""
            if (record.proposal.kind == kind) return record.payload
            version = record.proposal.beforeVersion
        }
        return ""
    }

    @Synchronized
    fun activeRecord(): NexusEvolutionRecord? {
        val active = activeVersion()
        if (active == 0L) return null
        return readRecord(proposalFileForVersion(active))
            ?.takeIf { it.status == NexusEvolutionProposalStatus.ACTIVE }
    }

    @Synchronized
    fun auditTrail(): List<String> = auditFile.takeIf { it.isFile }
        ?.readLines(Charsets.UTF_8)
        .orEmpty()

    private fun proposalFileForVersion(version: Long): File {
        val record = proposalFiles().firstOrNull { file ->
            val parsed = readRecord(file)
            parsed?.proposal?.proposedVersion == version
        } ?: throw IllegalStateException("Не найдена запись Evolution для версии " + version)
        return record
    }

    @Synchronized
    fun approveAndActivate(proposalId: String): Result<EvolutionActivation> = runCatching {
        val file = proposalFile(proposalId)
        val record = readRecord(file) ?: throw IllegalArgumentException("Предложение Evolution не найдено.")
        check(record.status == NexusEvolutionProposalStatus.PENDING) {
            "Предложение уже обработано и не может быть применено повторно."
        }
        check(record.proposal.beforeVersion == activeVersion()) {
            "Предложение устарело: активная версия уже изменилась. Создайте новое предложение на основе текущей версии."
        }
        check(sha256(record.payload) == record.proposal.contentSha256) {
            "Контрольная сумма содержимого предложения не совпадает."
        }
        validatePayload(record.proposal.kind, record.payload)
        val activation = NexusEvolutionPolicy.activate(record.proposal, EvolutionApproval.Approved).getOrThrow()
        writeTextAtomic(versionFile(activation.activeVersion), record.payload)
        writeJsonAtomic(activeFile, JSONObject().put("version", activation.activeVersion))
        saveRecord(record.copy(status = NexusEvolutionProposalStatus.ACTIVE))
        appendAudit("VERSION_ACTIVATED", proposalId, activation.rollbackToVersion, activation.activeVersion, record.proposal.title)
        activation
    }

    @Synchronized
    fun reject(proposalId: String, reason: String): Boolean {
        val file = proposalFile(proposalId)
        val record = readRecord(file) ?: return false
        if (record.status != NexusEvolutionProposalStatus.PENDING) return false
        saveRecord(record.copy(status = NexusEvolutionProposalStatus.REJECTED))
        appendAudit("PROPOSAL_REJECTED", proposalId, activeVersion(), activeVersion(), reason.take(500))
        return true
    }

    @Synchronized
    fun rollbackTo(version: Long): Result<Long> = runCatching {
        require(version >= 0L) { "Номер версии не может быть отрицательным." }
        val target = versionFile(version)
        require(target.isFile) { "Версия " + version + " не найдена в архиве Evolution." }
        val previous = activeVersion()
        require(version <= previous) { "Нельзя откатить систему вперёд по истории версий." }
        if (previous == version) return@runCatching version
        writeJsonAtomic(activeFile, JSONObject().put("version", version))
        appendAudit("ROLLBACK", "rollback", previous, version, "Пользователь восстановил сохранённую версию.")
        version
    }

    fun availableVersions(): List<Long> = versionsDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "txt" }
        .mapNotNull { it.nameWithoutExtension.toLongOrNull() }
        .sortedDescending()

    private fun proposalFiles(): List<File> = proposalsDir.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "json" }

    private fun proposalFile(id: String): File {
        require(id.matches(Regex("[A-Fa-f0-9-]{16,64}"))) { "Некорректный ID предложения." }
        return File(proposalsDir, id + ".json")
    }

    private fun versionFile(version: Long) = File(versionsDir, version.toString() + ".txt")

    private fun saveRecord(record: NexusEvolutionRecord) {
        val p = record.proposal
        val json = JSONObject()
            .put("id", p.id)
            .put("kind", p.kind.name)
            .put("title", p.title)
            .put("rationale", p.rationale)
            .put("beforeVersion", p.beforeVersion)
            .put("proposedVersion", p.proposedVersion)
            .put("contentSha256", p.contentSha256)
            .put("createdAtEpochMs", p.createdAtEpochMs)
            .put("payload", record.payload)
            .put("status", record.status.name)
        writeJsonAtomic(proposalFile(p.id), json)
    }

    private fun readRecord(file: File): NexusEvolutionRecord? = runCatching {
        val json = JSONObject(file.readText())
        val proposal = EvolutionProposal(
            id = json.getString("id"),
            kind = EvolutionChangeKind.valueOf(json.getString("kind")),
            title = json.getString("title"),
            rationale = json.getString("rationale"),
            beforeVersion = json.getLong("beforeVersion"),
            proposedVersion = json.getLong("proposedVersion"),
            contentSha256 = json.getString("contentSha256"),
            createdAtEpochMs = json.getLong("createdAtEpochMs")
        )
        NexusEvolutionRecord(
            proposal = proposal,
            payload = json.getString("payload"),
            status = NexusEvolutionProposalStatus.valueOf(json.getString("status"))
        )
    }.getOrNull()

    private fun appendAudit(action: String, id: String, from: Long, to: Long, detail: String) {
        val entry = JSONObject()
            .put("timestamp", System.currentTimeMillis())
            .put("action", action)
            .put("id", id)
            .put("fromVersion", from)
            .put("toVersion", to)
            .put("detail", detail.take(500))
        FileOutputStream(auditFile, true).use { stream ->
            stream.write((entry.toString() + "\n").toByteArray(Charsets.UTF_8))
            stream.fd.sync()
        }
    }

    private fun writeJsonAtomic(file: File, json: JSONObject) =
        writeTextAtomic(file, json.toString())

    private fun writeTextAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { output ->
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            // On Android's private filesystem rename is performed within the same directory.
            if (!tmp.renameTo(file)) {
                tmp.delete()
                throw IOException("Не удалось атомарно сохранить файл Evolution.")
            }
        }
    }

    private fun validatePayload(kind: EvolutionChangeKind, payload: String) {
        when (kind) {
            EvolutionChangeKind.ROUTING_POLICY -> NexusEvolutionRoutingPolicy.parse(payload)
            EvolutionChangeKind.PROMPT_PROFILE, EvolutionChangeKind.SKILL_PACK,
            EvolutionChangeKind.TOOL_DESCRIPTION, EvolutionChangeKind.MODEL_CONFIGURATION -> Unit
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_PAYLOAD_BYTES = 256 * 1024
    }
}
