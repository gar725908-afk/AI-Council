package ai.hybrid.commandcenter.androidbridge

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Honest availability of one bridge capability.
 * Every state carries a human-readable reason; nothing is ever reported as
 * working unless the underlying transport is actually usable right now.
 */
enum class CapabilityAvailability { AVAILABLE, PERMISSION_REQUIRED, DEGRADED, UNAVAILABLE }

data class CapabilityState(
    val capability: BridgeCapability,
    val availability: CapabilityAvailability,
    val transport: BridgeTransport?,
    val reason: String
)

/** Pure inputs for capability derivation; collected from the OS, testable without Android. */
data class CapabilityInputs(
    val sdkInt: Int,
    val notificationsEnabled: Boolean,
    val shizukuInstalled: Boolean,
    val accessibilityServiceEnabled: Boolean,
    val rootProbeAttempted: Boolean,
    val rootConfirmed: Boolean,
    val networkAvailable: Boolean = true
)

/**
 * Pure derivation of the capability matrix from observed facts.
 * Keep free of Android dependencies so unit tests cover every row.
 */
object CapabilityMatrix {

    fun privileges(inputs: CapabilityInputs): Set<BridgePrivilege> = buildSet {
        add(BridgePrivilege.STANDARD)
        if (inputs.rootConfirmed) add(BridgePrivilege.DIRECT_ROOT)
        // Shizuku privileges require a live Binder binding, which is not implemented yet.
        // The mere presence of the Shizuku app never grants SHIZUKU_ADB / SHIZUKU_ROOT.
    }

    fun derive(inputs: CapabilityInputs): List<CapabilityState> = listOf(
        CapabilityState(
            BridgeCapability.APP_LAUNCH,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.NATIVE,
            "Запуск приложений через Intent доступен в Standard-профиле"
        ),
        CapabilityState(
            BridgeCapability.PACKAGE_METADATA,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.NATIVE,
            "Чтение метаданных пакетов — публичный Android API"
        ),
        CapabilityState(
            BridgeCapability.USER_SELECTED_FILES,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.NATIVE,
            "Доступ к файлам через системный выбор (SAF)"
        ),
        CapabilityState(
            BridgeCapability.DEVICE_DIAGNOSTICS,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.NATIVE,
            "Батарея, память и накопитель читаются публичными API"
        ),
        if (inputs.notificationsEnabled) {
            CapabilityState(
                BridgeCapability.NOTIFICATIONS,
                CapabilityAvailability.AVAILABLE,
                BridgeTransport.NATIVE,
                "Уведомления разрешены пользователем"
            )
        } else {
            CapabilityState(
                BridgeCapability.NOTIFICATIONS,
                CapabilityAvailability.PERMISSION_REQUIRED,
                BridgeTransport.NATIVE,
                "Уведомления отключены — включите их для NEXUS в настройках системы"
            )
        },
        CapabilityState(
            BridgeCapability.SYSTEM_SETTINGS,
            CapabilityAvailability.UNAVAILABLE,
            null,
            "Типизированные инструменты настроек ещё не реализованы"
        ),
        privilegedState(BridgeCapability.PRIVILEGED_PACKAGE_OPERATIONS, inputs),
        privilegedState(BridgeCapability.PRIVILEGED_FILESYSTEM, inputs),
        privilegedState(BridgeCapability.PROCESS_INSPECTION, inputs),
        if (inputs.accessibilityServiceEnabled) CapabilityState(
            BridgeCapability.ACCESSIBLE_UI_TREE,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.ACCESSIBILITY,
            "Служба NEXUS Bridge включена — чтение дерева интерфейса доступно"
        ) else CapabilityState(
            BridgeCapability.ACCESSIBLE_UI_TREE,
            CapabilityAvailability.PERMISSION_REQUIRED,
            null,
            "Включите службу NEXUS Bridge в настройках специальных возможностей"
        ),
        if (inputs.accessibilityServiceEnabled) CapabilityState(
            BridgeCapability.UI_GESTURES,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.ACCESSIBILITY,
            "Нажатия выполняются только по подтверждённому запросу через Gateway"
        ) else CapabilityState(
            BridgeCapability.UI_GESTURES,
            CapabilityAvailability.PERMISSION_REQUIRED,
            null,
            "Жесты требуют включённой службы NEXUS Bridge"
        ),
        CapabilityState(
            BridgeCapability.SCREEN_CAPTURE,
            CapabilityAvailability.UNAVAILABLE,
            null,
            "Захват экрана (MediaProjection) не реализован; потребует согласия системы"
        ),
        if (inputs.networkAvailable) CapabilityState(
            BridgeCapability.REMOTE_CONNECTOR,
            CapabilityAvailability.AVAILABLE,
            BridgeTransport.NATIVE,
            "Инструменты MCP-коннекторов доступны через Gateway (одобрение для изменяющих действий)"
        ) else CapabilityState(
            BridgeCapability.REMOTE_CONNECTOR,
            CapabilityAvailability.UNAVAILABLE,
            null,
            "Нет сети — удалённые коннекторы недоступны"
        )
    )

    private fun privilegedState(capability: BridgeCapability, inputs: CapabilityInputs): CapabilityState =
        when {
            inputs.rootConfirmed -> CapabilityState(
                capability,
                CapabilityAvailability.DEGRADED,
                BridgeTransport.ROOT,
                "Root подтверждён (uid=0); типизированные инструменты в разработке"
            )
            inputs.rootProbeAttempted -> CapabilityState(
                capability,
                CapabilityAvailability.UNAVAILABLE,
                null,
                "Root отклонён или недоступен по результату проверки"
            )
            inputs.shizukuInstalled -> CapabilityState(
                capability,
                CapabilityAvailability.UNAVAILABLE,
                null,
                "Shizuku установлен, но привязка Binder не реализована; Root не проверялся"
            )
            else -> CapabilityState(
                capability,
                CapabilityAvailability.UNAVAILABLE,
                null,
                "Требуется Root или Shizuku; проверка не выполнялась"
            )
        }

    fun snapshot(inputs: CapabilityInputs, nowEpochMillis: Long): CapabilitySnapshot {
        val states = derive(inputs)
        return CapabilitySnapshot(
            privileges = privileges(inputs),
            capabilities = states
                .filter { it.availability == CapabilityAvailability.AVAILABLE }
                .map { it.capability }
                .toSet(),
            checkedAtEpochMillis = nowEpochMillis,
            notes = states
                .filter { it.availability != CapabilityAvailability.AVAILABLE }
                .map { "${it.capability.name}: ${it.reason}" }
        )
    }
}

/** Real measured device state; null means the OS did not provide the value. */
data class DeviceDiagnostics(
    val batteryPercent: Int?,
    val charging: Boolean?,
    val ramFreeBytes: Long?,
    val ramTotalBytes: Long?,
    val storageFreeBytes: Long,
    val storageTotalBytes: Long,
    val thermalLabel: String,
    /** Raw PowerManager.THERMAL_STATUS_* value, null when unavailable. */
    val thermalStatus: Int?,
    val networkLabel: String,
    val measuredAtEpochMillis: Long
)

/** Collects diagnostics and capability inputs from real Android services. */
class DeviceInspector(private val context: Context) {

    fun diagnostics(): DeviceDiagnostics {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val batteryPercent = battery
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val charging = battery?.isCharging

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = activityManager?.let { manager ->
            ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        }

        val stats = StatFs(context.filesDir.absolutePath)

        return DeviceDiagnostics(
            batteryPercent = batteryPercent,
            charging = charging,
            ramFreeBytes = memoryInfo?.availMem,
            ramTotalBytes = memoryInfo?.totalMem,
            storageFreeBytes = stats.availableBytes,
            storageTotalBytes = stats.totalBytes,
            thermalLabel = thermalLabel(),
            thermalStatus = thermalStatus(),
            networkLabel = networkLabel(),
            measuredAtEpochMillis = System.currentTimeMillis()
        )
    }

    fun capabilityInputs(rootProbeAttempted: Boolean, rootConfirmed: Boolean): CapabilityInputs =
        CapabilityInputs(
            sdkInt = Build.VERSION.SDK_INT,
            notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
            shizukuInstalled = isPackageInstalled(SHIZUKU_PACKAGE),
            accessibilityServiceEnabled = isOwnAccessibilityServiceEnabled(),
            rootProbeAttempted = rootProbeAttempted,
            rootConfirmed = rootConfirmed,
            networkAvailable = networkLabel() != "Нет соединения"
        )

    private fun thermalStatus(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return power.currentThermalStatus
    }

    private fun thermalLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return "Недоступно до Android 10"
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return "Сервис питания недоступен"
        return when (power.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Норма"
            PowerManager.THERMAL_STATUS_LIGHT -> "Лёгкий нагрев"
            PowerManager.THERMAL_STATUS_MODERATE -> "Умеренный нагрев"
            PowerManager.THERMAL_STATUS_SEVERE -> "Сильный нагрев"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Критический нагрев"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Аварийный нагрев"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Отключение по нагреву"
            else -> "Неизвестно"
        }
    }

    private fun networkLabel(): String {
        val connectivity =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "Сервис сети недоступен"
        val network = connectivity.activeNetwork ?: return "Нет соединения"
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return "Нет соединения"
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Подключено (другой транспорт)"
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    private fun isOwnAccessibilityServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    private companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}
