package ai.hybrid.commandcenter.androidbridge

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Privileged bridge tools (handoff section 7). They are SENSITIVE, so the
 * Gateway demands a one-shot user approval before anything runs, the
 * transport must actually be ROOT, and the command is chosen from a fixed
 * enum — never supplied as text by a model.
 */
class PrivilegedDiagnosticsTool(private val context: Context) : VerifiableBridgeTool {
    override val id: String = "root.diagnostics"
    override val capability: BridgeCapability = BridgeCapability.PROCESS_INSPECTION
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.SHIZUKU_ADB, BridgeTransport.ROOT)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        if (transport != BridgeTransport.ROOT && transport != BridgeTransport.SHIZUKU_ADB) {
            return BridgeResult(
                false, transport,
                "Инструмент работает только через привилегированный транспорт (Shizuku или Root).",
                errorCode = "E_TRANSPORT"
            )
        }
        val commandId = request.arguments["command"].orEmpty()
        val command = RootShellExecutor.PrivilegedCommand.byId(commandId)
            ?: return BridgeResult(
                false, transport,
                "Неизвестная команда «" + commandId.take(40) + "». Допустимы только: " +
                    RootShellExecutor.PrivilegedCommand.entries.joinToString(", ") { it.id },
                errorCode = "E_UNKNOWN_COMMAND"
            )

        val outcome = withContext(Dispatchers.IO) {
            if (transport == BridgeTransport.SHIZUKU_ADB) ShizukuTransport.run(context, command)
            else RootShellExecutor.run(command)
        }
        if (!outcome.success) {
            return BridgeResult(false, transport, outcome.detail, errorCode = "E_PRIVILEGED_FAILED")
        }

        val structured = when (command) {
            RootShellExecutor.PrivilegedCommand.PROCESS_LIST -> {
                val rows = RootOutputParser.parseProcesses(outcome.output)
                mapOf(
                    "process_count" to rows.size.toString(),
                    "top_by_memory" to rows.sortedByDescending { it.rssKb }.take(10)
                        .joinToString("\n") { "${it.pid} ${it.user} ${it.rssKb} кБ ${it.name}" }
                )
            }
            RootShellExecutor.PrivilegedCommand.PACKAGE_LIST -> {
                val packages = RootOutputParser.parsePackages(outcome.output)
                mapOf("package_count" to packages.size.toString())
            }
            RootShellExecutor.PrivilegedCommand.MEMORY_DETAIL -> {
                val info = RootOutputParser.parseMemInfo(outcome.output)
                mapOf(
                    "mem_total_kb" to (info["MemTotal"]?.toString() ?: "нет данных"),
                    "mem_available_kb" to (info["MemAvailable"]?.toString() ?: "нет данных")
                )
            }
            else -> emptyMap()
        }

        return BridgeResult(
            success = true,
            transport = transport,
            message = outcome.output,
            data = structured + mapOf(
                "command" to command.id,
                "command_title" to command.title,
                "exit_code" to (outcome.exitCode?.toString() ?: "нет"),
                "transport" to transport.name
            )
        )
    }

    /** Postcondition: the run really produced output for the command that was asked for. */
    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification {
        val requested = request.arguments["command"].orEmpty()
        val executed = result.data["command"].orEmpty()
        if (requested != executed) {
            return VerifiableBridgeTool.Verification.Failed(
                "Выполнена не та команда: запрошена «$requested», отработала «$executed»."
            )
        }
        if (result.message.isBlank()) {
            return VerifiableBridgeTool.Verification.Failed("Привилегированная команда вернула пустой вывод.")
        }
        if (result.data["exit_code"] !in setOf("0")) {
            return VerifiableBridgeTool.Verification.Failed("Код возврата не нулевой.")
        }
        return VerifiableBridgeTool.Verification.Passed
    }
}
