package ai.hybrid.commandcenter.androidbridge

import java.util.concurrent.TimeUnit

/**
 * Direct Root executor (handoff section 7). Hard safety contract:
 *
 * - only commands from a fixed enum ever reach `su` — no string from a model,
 *   a page, a file or a tool argument is ever executed;
 * - every run is bounded by a timeout and an output cap;
 * - a non-zero exit or empty output is reported as failure, never as success.
 *
 * Reaching it goes through the Gateway, so approvals and audit always apply.
 */
object RootShellExecutor {

    data class ShellOutcome(
        val success: Boolean,
        val exitCode: Int?,
        val output: String,
        val detail: String
    )

    const val MAX_OUTPUT_CHARS = 32_768

    /** Fixed privileged commands. Adding one here is a deliberate, reviewable act. */
    enum class PrivilegedCommand(val id: String, val shellCommand: String, val title: String) {
        PROCESS_LIST("process_list", "ps -A -o PID,USER,RSS,NAME", "Список процессов"),
        PACKAGE_LIST("package_list", "pm list packages -f", "Установленные пакеты"),
        BATTERY_DETAIL("battery_detail", "dumpsys battery", "Детальный статус батареи"),
        MEMORY_DETAIL("memory_detail", "cat /proc/meminfo", "Детальная память"),
        STORAGE_DETAIL("storage_detail", "df -h", "Разделы накопителя"),
        NETWORK_SOCKETS("network_sockets", "ss -tunap", "Сетевые соединения"),
        SELINUX_STATUS("selinux_status", "getenforce", "Статус SELinux"),
        KERNEL_VERSION("kernel_version", "uname -a", "Версия ядра");

        companion object {
            fun byId(id: String): PrivilegedCommand? = entries.firstOrNull { it.id == id }
        }
    }

    fun run(command: PrivilegedCommand, timeoutSeconds: Long = 15): ShellOutcome {
        val bounded = timeoutSeconds.coerceIn(1L, 60L)
        return try {
            val process = ProcessBuilder("su", "-c", command.shellCommand)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(bounded, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellOutcome(false, null, "", "Команда не завершилась за ${bounded} с и была остановлена.")
            }
            val output = process.inputStream.bufferedReader()
                .use { it.readText() }
                .take(MAX_OUTPUT_CHARS)
            val exit = process.exitValue()
            when {
                exit != 0 -> ShellOutcome(false, exit, output, "Команда завершилась с кодом $exit.")
                output.isBlank() -> ShellOutcome(false, exit, "", "Команда не вернула вывод.")
                else -> ShellOutcome(true, exit, output, "Выполнено за счёт Root (uid=0).")
            }
        } catch (error: Exception) {
            ShellOutcome(false, null, "", "Root недоступен: " + (error.message ?: error.javaClass.simpleName))
        }
    }
}

/** Pure parsers over privileged output, so the UI shows structure and tests cover it. */
object RootOutputParser {

    data class ProcessRow(val pid: Int, val user: String, val rssKb: Long, val name: String)

    /** `ps -A -o PID,USER,RSS,NAME` rows, header skipped, malformed lines dropped. */
    fun parseProcesses(output: String, limit: Int = 200): List<ProcessRow> = output
        .lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val pid = parts[0].toIntOrNull() ?: return@mapNotNull null
            val rss = parts[2].toLongOrNull() ?: return@mapNotNull null
            ProcessRow(pid, parts[1].take(32), rss, parts.drop(3).joinToString(" ").take(128))
        }
        .take(limit.coerceIn(1, 2_000))
        .toList()

    /** `pm list packages -f` lines: `package:/path/base.apk=com.example`. */
    fun parsePackages(output: String, limit: Int = 500): List<Pair<String, String>> = output
        .lineSequence()
        .mapNotNull { line ->
            val body = line.trim().removePrefix("package:")
            val separator = body.lastIndexOf('=')
            if (separator <= 0 || separator == body.length - 1) return@mapNotNull null
            body.substring(separator + 1).take(128) to body.substring(0, separator).take(256)
        }
        .take(limit.coerceIn(1, 5_000))
        .toList()

    /** `/proc/meminfo` key/value pairs in kB. */
    fun parseMemInfo(output: String): Map<String, Long> = output
        .lineSequence()
        .mapNotNull { line ->
            val parts = line.split(':', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val value = parts[1].trim().removeSuffix(" kB").trim().toLongOrNull() ?: return@mapNotNull null
            parts[0].trim().take(64) to value
        }
        .toMap()
}
