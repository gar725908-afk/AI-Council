package ai.hybrid.commandcenter.nexus

/**
 * Resource Governor per handoff section 11.2: heavy work is gated by the
 * device's real battery, thermal and memory state. Pure decisions over
 * measured inputs; a Deny always carries the reason the user sees.
 * Thermal values follow android.os.PowerManager.THERMAL_STATUS_* (0..6),
 * null when the OS does not report them.
 */
object ResourceGovernor {

    enum class Workload { REMOTE_REQUEST, LOCAL_INFERENCE, MODEL_DOWNLOAD }

    sealed class Verdict {
        object Allow : Verdict()
        data class Warn(val reason: String) : Verdict()
        data class Deny(val reason: String) : Verdict()
    }

    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
    private const val LOW_BATTERY = 20
    private const val CRITICAL_BATTERY = 10
    private const val MIN_FREE_RAM_WARN = 500L * 1024 * 1024
    private const val MIN_FREE_RAM_DENY = 250L * 1024 * 1024

    fun evaluate(
        workload: Workload,
        batteryPercent: Int?,
        charging: Boolean?,
        thermalStatus: Int?,
        ramFreeBytes: Long?
    ): Verdict {
        val onBattery = charging != true
        val heavy = workload != Workload.REMOTE_REQUEST

        if (thermalStatus != null && thermalStatus >= THERMAL_SEVERE && heavy) {
            return Verdict.Deny("Устройство сильно нагрето (уровень $thermalStatus). Тяжёлая задача отложена до охлаждения.")
        }
        if (batteryPercent != null && onBattery && batteryPercent < CRITICAL_BATTERY && heavy) {
            return Verdict.Deny("Заряд $batteryPercent% без зарядки — локальная модель или загрузка разрядят телефон. Подключите питание.")
        }
        if (ramFreeBytes != null && ramFreeBytes < MIN_FREE_RAM_DENY && workload == Workload.LOCAL_INFERENCE) {
            return Verdict.Deny("Свободно менее 250 МБ памяти — локальный инференс не запустится стабильно.")
        }

        val warnings = buildList {
            if (thermalStatus != null && thermalStatus >= THERMAL_MODERATE) add("устройство нагрето")
            if (batteryPercent != null && onBattery && batteryPercent < LOW_BATTERY) add("заряд $batteryPercent% без зарядки")
            if (ramFreeBytes != null && ramFreeBytes < MIN_FREE_RAM_WARN && workload == Workload.LOCAL_INFERENCE) add("мало свободной памяти")
            if (thermalStatus != null && thermalStatus >= THERMAL_SEVERE && !heavy) add("сильный нагрев, ответ может замедлиться")
        }
        return if (warnings.isEmpty()) Verdict.Allow
        else Verdict.Warn("Внимание: " + warnings.joinToString(", ") + ".")
    }
}
