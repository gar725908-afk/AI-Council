package ai.hybrid.commandcenter.androidbridge

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The single execution pipeline for bridge tools (handoff section 6.5):
 * models and screens never call a transport directly. Every request passes
 * validation → capability check → approval policy → transport selection →
 * bounded execution → audit, and every outcome is typed — never fake success.
 *
 * Postcondition verification beyond the tool's own typed result is not
 * implemented yet; the audit entry says which checks actually ran.
 */
sealed class GatewayOutcome {
    enum class Stage { VALIDATION, CAPABILITY, APPROVAL, TRANSPORT, EXECUTION }

    data class Success(val result: BridgeResult, val auditId: String) : GatewayOutcome()
    data class Denied(val stage: Stage, val reason: String, val auditId: String) : GatewayOutcome()
    data class Failed(val stage: Stage, val reason: String, val auditId: String) : GatewayOutcome()
}

data class BridgeAuditEntry(
    val id: String,
    val requestId: String,
    val toolId: String,
    val risk: String,
    val transport: String?,
    val initiator: String,
    val approvalPresent: Boolean,
    val outcome: String,
    val detail: String,
    val createdAtEpochMillis: Long
)

/** Append-only audit persistence; implementations must never throw into the pipeline. */
fun interface BridgeAuditSink {
    fun record(entry: BridgeAuditEntry)
}

class BridgeGateway(
    private val registry: BridgeToolRegistry,
    private val auditSink: BridgeAuditSink,
    private val executionTimeoutMillis: Long = 30_000,
    private val outputLimitChars: Int = 16_384
) {

    suspend fun execute(
        request: BridgeRequest,
        snapshot: CapabilitySnapshot,
        availableTransports: Set<BridgeTransport>,
        initiator: String
    ): GatewayOutcome {
        val tool = registry.get(request.toolId)
            ?: return denied(
                GatewayOutcome.Stage.VALIDATION,
                "Инструмент не зарегистрирован: " + request.toolId.take(128),
                request, null, null, initiator
            )

        if (tool.capability !in snapshot.capabilities) {
            return denied(
                GatewayOutcome.Stage.CAPABILITY,
                "Возможность ${tool.capability.name} недоступна в текущем снимке состояния.",
                request, tool, null, initiator
            )
        }

        if (!BridgeActionPolicy.isAllowed(tool, request, snapshot)) {
            val stage = if (BridgeActionPolicy.requiresApproval(tool.risk) && request.approval == null) {
                GatewayOutcome.Stage.APPROVAL
            } else {
                GatewayOutcome.Stage.VALIDATION
            }
            return denied(
                stage,
                if (stage == GatewayOutcome.Stage.APPROVAL) {
                    "Действие класса ${tool.risk.name} требует действительного одобрения пользователя."
                } else {
                    "Политика отклонила запрос: параметры, снимок состояния или одобрение недействительны."
                },
                request, tool, null, initiator
            )
        }

        val transport = registry.selectTransport(tool, availableTransports)
            ?: return denied(
                GatewayOutcome.Stage.TRANSPORT,
                "Ни один поддерживаемый транспорт инструмента не доступен сейчас.",
                request, tool, null, initiator
            )

        // Persist the attempt before any side effect can happen.
        audit(request, tool, transport, initiator, "STARTED", "Запуск через ${transport.name}.")

        val result = try {
            withTimeoutOrNull(executionTimeoutMillis) { tool.execute(request, transport) }
        } catch (cancelled: CancellationException) {
            audit(request, tool, transport, initiator, "CANCELLED", "Выполнение отменено.")
            throw cancelled
        } catch (error: Exception) {
            val reason = error.message ?: error::class.java.simpleName
            val auditId = audit(request, tool, transport, initiator, "FAILED", reason.take(300))
            return GatewayOutcome.Failed(GatewayOutcome.Stage.EXECUTION, reason, auditId)
        }

        if (result == null) {
            val reason = "Инструмент превысил лимит времени $executionTimeoutMillis мс."
            val auditId = audit(request, tool, transport, initiator, "TIMEOUT", reason)
            return GatewayOutcome.Failed(GatewayOutcome.Stage.EXECUTION, reason, auditId)
        }

        val bounded = result.copy(
            message = result.message.take(outputLimitChars),
            data = result.data.entries.associate { (key, value) ->
                key.take(128) to value.take(outputLimitChars)
            }
        )

        return if (bounded.success) {
            val auditId = audit(
                request, tool, transport, initiator, "SUCCESS",
                bounded.message.take(300)
            )
            GatewayOutcome.Success(bounded, auditId)
        } else {
            val reason = bounded.errorCode?.let { "$it: ${bounded.message}" } ?: bounded.message
            val auditId = audit(request, tool, transport, initiator, "FAILED", reason.take(300))
            GatewayOutcome.Failed(GatewayOutcome.Stage.EXECUTION, reason, auditId)
        }
    }

    private fun denied(
        stage: GatewayOutcome.Stage,
        reason: String,
        request: BridgeRequest,
        tool: AndroidBridgeTool?,
        transport: BridgeTransport?,
        initiator: String
    ): GatewayOutcome.Denied {
        val auditId = auditRaw(
            requestId = request.requestId,
            toolId = request.toolId,
            risk = tool?.risk?.name ?: "UNKNOWN",
            transport = transport?.name,
            initiator = initiator,
            approvalPresent = request.approval != null,
            outcome = "DENIED_" + stage.name,
            detail = reason
        )
        return GatewayOutcome.Denied(stage, reason, auditId)
    }

    private fun audit(
        request: BridgeRequest,
        tool: AndroidBridgeTool,
        transport: BridgeTransport,
        initiator: String,
        outcome: String,
        detail: String
    ): String = auditRaw(
        requestId = request.requestId,
        toolId = tool.id,
        risk = tool.risk.name,
        transport = transport.name,
        initiator = initiator,
        approvalPresent = request.approval != null,
        outcome = outcome,
        detail = detail
    )

    private fun auditRaw(
        requestId: String,
        toolId: String,
        risk: String,
        transport: String?,
        initiator: String,
        approvalPresent: Boolean,
        outcome: String,
        detail: String
    ): String {
        val entry = BridgeAuditEntry(
            id = UUID.randomUUID().toString(),
            requestId = requestId.take(128),
            toolId = toolId.take(128),
            risk = risk,
            transport = transport,
            initiator = initiator.take(128),
            approvalPresent = approvalPresent,
            outcome = outcome,
            detail = detail.take(500),
            createdAtEpochMillis = System.currentTimeMillis()
        )
        runCatching { auditSink.record(entry) }
        return entry.id
    }
}
