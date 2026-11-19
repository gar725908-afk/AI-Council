package ai.hybrid.commandcenter.androidbridge

import java.util.concurrent.TimeUnit

data class RootProbeResult(
    val available: Boolean,
    val uid: Int?,
    val managerResponse: String,
    val detail: String
)

/**
 * Call only after the user explicitly requests Root detection.
 * Android has no install-time permission that silently grants superuser access.
 */
object RootAccessProbe {
    fun check(timeoutSeconds: Long = 8): RootProbeResult {
        val boundedTimeout = timeoutSeconds.coerceIn(1L, 15L)
        return try {
            val process = ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(boundedTimeout, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return RootProbeResult(false, null, "timeout", "Root manager did not answer within ${boundedTimeout}s")
            }
            val output = process.inputStream.bufferedReader().use { it.readText().take(4096).trim() }
            val uid = output.lineSequence().lastOrNull()?.trim()?.toIntOrNull()
            val granted = process.exitValue() == 0 && uid == 0
            RootProbeResult(
                granted, uid, output,
                if (granted) "Superuser identity confirmed (uid=0)" else "Root denied, unavailable, or non-root identity"
            )
        } catch (error: Exception) {
            RootProbeResult(false, null, error.javaClass.simpleName, error.message ?: "su unavailable")
        }
    }
}

/** Fixed diagnostics only; never accept shell text from the model. */
enum class RootDiagnosticCommand(val shellCommand: String) {
    IDENTITY("id"),
    ANDROID_PROPERTIES("getprop"),
    BATTERY_STATUS("dumpsys battery"),
    STORAGE_STATUS("df -h /data")
}
