package ai.hybrid.commandcenter.androidbridge

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Runs inside the Shizuku process with ADB privileges.
 *
 * The only thing that crosses the Binder boundary is a command **id** from
 * the fixed enum, never shell text: an unknown id is refused here, in the
 * privileged process itself, so even a compromised app process cannot make
 * this service run something arbitrary.
 */
class NexusPrivilegedService : INexusPrivilegedService.Stub() {

    override fun runFixedCommand(commandId: String?, timeoutSeconds: Long): String {
        val command = RootShellExecutor.PrivilegedCommand.byId(commandId.orEmpty())
            ?: return ERROR_PREFIX + "Неизвестная команда."
        val bounded = timeoutSeconds.coerceIn(1L, 60L)
        return try {
            val process = ProcessBuilder("sh", "-c", command.shellCommand)
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(bounded, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return ERROR_PREFIX + "Команда не завершилась за $bounded с."
            }
            val output = process.inputStream.bufferedReader()
                .use { it.readText() }
                .take(RootShellExecutor.MAX_OUTPUT_CHARS)
            process.exitValue().toString() + SEPARATOR + output
        } catch (error: Throwable) {
            ERROR_PREFIX + (error.message ?: error.javaClass.simpleName)
        }
    }

    override fun destroy() {
        exitProcess(0)
    }

    companion object {
        const val SEPARATOR = "|"
        const val ERROR_PREFIX = "ERR|"

        /** Pure parsing of the wire format, so the transport is testable. */
        fun parseReply(reply: String?): RootShellExecutor.ShellOutcome {
            if (reply.isNullOrEmpty()) {
                return RootShellExecutor.ShellOutcome(false, null, "", "Привилегированная служба не ответила.")
            }
            if (reply.startsWith(ERROR_PREFIX)) {
                return RootShellExecutor.ShellOutcome(false, null, "", reply.removePrefix(ERROR_PREFIX))
            }
            val separator = reply.indexOf(SEPARATOR)
            if (separator <= 0) {
                return RootShellExecutor.ShellOutcome(false, null, "", "Неразборчивый ответ привилегированной службы.")
            }
            val exit = reply.substring(0, separator).toIntOrNull()
                ?: return RootShellExecutor.ShellOutcome(false, null, "", "Неразборчивый код возврата.")
            val output = reply.substring(separator + 1)
            return when {
                exit != 0 -> RootShellExecutor.ShellOutcome(false, exit, output, "Команда завершилась с кодом $exit.")
                output.isBlank() -> RootShellExecutor.ShellOutcome(false, exit, "", "Команда не вернула вывод.")
                else -> RootShellExecutor.ShellOutcome(true, exit, output, "Выполнено через Shizuku (права ADB).")
            }
        }
    }
}
