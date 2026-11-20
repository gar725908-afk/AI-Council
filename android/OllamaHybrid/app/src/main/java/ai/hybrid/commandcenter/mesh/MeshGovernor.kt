package ai.hybrid.commandcenter.mesh

import android.content.Context

/**
 * How much of this phone the owner lends to other trusted phones. Limits are
 * enforced by the worker before a task is accepted; percentages are scheduler
 * quotas, not a promise that Android caps the CPU.
 */
enum class MeshProfile(val title: String, val note: String) {
    OFF("Выключено", "телефон не выполняет чужие задачи"),
    MINIMAL("Минимум", "по одной задаче, только на зарядке и по Wi-Fi"),
    BALANCED("Баланс", "до двух задач, при заряде от 30%"),
    PERFORMANCE("Мощность", "до трёх задач, при заряде от 15%");
}

data class MeshPolicy(
    val profile: MeshProfile,
    val maxConcurrent: Int,
    val onlyCharging: Boolean,
    val onlyWifi: Boolean,
    val minBattery: Int,
    val maxThermalStatus: Int,
    val maxPromptChars: Int,
    /** The worker stops generation once the answer reaches this many characters. */
    val maxOutputChars: Int,
    /** Off by default: a cloud core would spend this phone's API key and send the text to that cloud. */
    val allowCloudCore: Boolean = false
) {
    sealed interface Verdict {
        data object Allow : Verdict
        data class Deny(val reason: String) : Verdict
    }

    data class DeviceState(
        val batteryPercent: Int?,
        val charging: Boolean,
        val onWifi: Boolean,
        val thermalStatus: Int,
        val running: Int
    )

    fun evaluate(op: MeshOp, promptChars: Int, state: DeviceState): Verdict {
        if (profile == MeshProfile.OFF) return Verdict.Deny("Владелец выключил выполнение чужих задач на этом телефоне.")
        if (state.running >= maxConcurrent) return Verdict.Deny("Узел занят: уже выполняется задач — ${state.running}.")
        if (onlyCharging && !state.charging) return Verdict.Deny("Узел принимает задачи только на зарядке.")
        if (onlyWifi && !state.onWifi) return Verdict.Deny("Узел принимает задачи только по Wi-Fi.")
        val battery = state.batteryPercent
        if (battery != null && !state.charging && battery < minBattery) {
            return Verdict.Deny("Заряд $battery% ниже порога $minBattery% — задача отклонена, чтобы не посадить телефон.")
        }
        if (state.thermalStatus > maxThermalStatus) return Verdict.Deny("Телефон перегрет — задачи на паузе.")
        if (op == MeshOp.INFERENCE_RUN && promptChars > maxPromptChars) {
            return Verdict.Deny("Запрос длиннее лимита узла ($maxPromptChars символов).")
        }
        return Verdict.Allow
    }

    companion object {
        // PowerManager thermal status: 0 none, 1 light, 2 moderate, 3 severe…
        fun of(profile: MeshProfile): MeshPolicy = when (profile) {
            MeshProfile.OFF -> MeshPolicy(profile, 0, true, true, 100, 0, 0, 0)
            MeshProfile.MINIMAL -> MeshPolicy(profile, 1, true, true, 50, 1, 4_000, 1_500)
            MeshProfile.BALANCED -> MeshPolicy(profile, 2, false, false, 30, 2, 8_000, 3_000)
            MeshProfile.PERFORMANCE -> MeshPolicy(profile, 3, false, false, 15, 2, 8_000, 4_000)
        }

        private const val PREFS = "nexus_mesh"

        fun load(context: Context): MeshPolicy {
            val prefs = context.getSharedPreferences(PREFS, 0)
            val profile = runCatching { MeshProfile.valueOf(prefs.getString("profile", "BALANCED")!!) }.getOrDefault(MeshProfile.BALANCED)
            return of(profile).copy(allowCloudCore = prefs.getBoolean("allow_cloud", false))
        }

        fun save(context: Context, profile: MeshProfile) {
            context.getSharedPreferences(PREFS, 0).edit().putString("profile", profile.name).apply()
        }

        fun saveAllowCloud(context: Context, allow: Boolean) {
            context.getSharedPreferences(PREFS, 0).edit().putBoolean("allow_cloud", allow).apply()
        }

        /**
         * Whether a core keeps the text on the owner's own hardware: on-device models always,
         * Ollama / OpenAI-compatible servers only when their address is private (LAN, Tailscale, this phone).
         */
        fun isLocalCore(kind: String, endpoint: String): Boolean = when (kind) {
            "ON_DEVICE_LITERT", "ON_DEVICE_LLAMA_CPP" -> true
            "OLLAMA", "OPENAI_COMPATIBLE" -> privateHost(endpoint)
            else -> false
        }

        private fun privateHost(endpoint: String): Boolean {
            val host = runCatching { java.net.URI(endpoint.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase() ?: return false
            if (host == "localhost" || host.endsWith(".local")) return true
            // Only literal addresses are judged; a DNS name could point anywhere.
            val literal = host.matches(Regex("[0-9.]+")) || host.contains(':')
            if (!literal) return false
            return runCatching { MeshAddresses.isPrivate(java.net.InetAddress.getByName(host)) }.getOrDefault(false)
        }
    }
}
