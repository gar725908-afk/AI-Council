package ai.hybrid.commandcenter.androidbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * System settings as typed tools (handoff section 7, capability
 * SYSTEM_SETTINGS).
 *
 * NEXUS never writes a system setting silently: reads are limited to public,
 * non-identifying values, and "changing" a setting means opening the exact
 * system screen so the user makes the change themselves. That keeps the
 * capability useful without ever acting behind the user's back.
 */
object SystemSettingsCatalog {

    /** Public, non-identifying settings that may be read. */
    enum class Readable(val id: String, val title: String, val scope: Scope, val key: String) {
        SCREEN_BRIGHTNESS("screen_brightness", "Яркость экрана", Scope.SYSTEM, Settings.System.SCREEN_BRIGHTNESS),
        SCREEN_OFF_TIMEOUT("screen_off_timeout", "Тайм-аут экрана", Scope.SYSTEM, Settings.System.SCREEN_OFF_TIMEOUT),
        AUTO_ROTATE("auto_rotate", "Автоповорот", Scope.SYSTEM, Settings.System.ACCELEROMETER_ROTATION),
        AIRPLANE_MODE("airplane_mode", "Режим полёта", Scope.GLOBAL, Settings.Global.AIRPLANE_MODE_ON),
        ADB_ENABLED("adb_enabled", "Отладка по USB", Scope.GLOBAL, Settings.Global.ADB_ENABLED),
        AUTO_TIME("auto_time", "Автоматическое время", Scope.GLOBAL, Settings.Global.AUTO_TIME);

        companion object {
            fun byId(id: String): Readable? = entries.firstOrNull { it.id == id }
        }
    }

    enum class Scope { SYSTEM, GLOBAL }

    /** System screens NEXUS may open for the user. */
    enum class Screen(val id: String, val title: String, val action: String, val appScoped: Boolean = false) {
        WIFI("wifi", "Wi-Fi", Settings.ACTION_WIFI_SETTINGS),
        BLUETOOTH("bluetooth", "Bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS),
        DISPLAY("display", "Экран", Settings.ACTION_DISPLAY_SETTINGS),
        SOUND("sound", "Звук", Settings.ACTION_SOUND_SETTINGS),
        BATTERY("battery", "Батарея", Intent.ACTION_POWER_USAGE_SUMMARY),
        DATE_TIME("date_time", "Дата и время", Settings.ACTION_DATE_SETTINGS),
        LOCALE("locale", "Язык и ввод", Settings.ACTION_LOCALE_SETTINGS),
        ACCESSIBILITY("accessibility", "Спец. возможности", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        APP_DETAILS("app_details", "Настройки NEXUS", Settings.ACTION_APPLICATION_DETAILS_SETTINGS, appScoped = true);

        companion object {
            fun byId(id: String): Screen? = entries.firstOrNull { it.id == id }
        }
    }

    fun readableIds(): String = Readable.entries.joinToString(", ") { it.id }

    fun screenIds(): String = Screen.entries.joinToString(", ") { it.id }

    /** Human-readable rendering of a raw setting value. */
    fun describe(setting: Readable, raw: String?): String = when {
        raw.isNullOrBlank() -> "значение не задано системой"
        setting == Readable.AUTO_ROTATE || setting == Readable.AIRPLANE_MODE ||
            setting == Readable.ADB_ENABLED || setting == Readable.AUTO_TIME ->
            if (raw == "1") "включено" else "выключено"
        setting == Readable.SCREEN_OFF_TIMEOUT ->
            raw.toLongOrNull()?.let { (it / 1000).toString() + " с" } ?: raw
        else -> raw
    }
}

class ReadSystemSettingTool(private val context: Context) : VerifiableBridgeTool {
    override val id: String = "system.read_setting"
    override val capability: BridgeCapability = BridgeCapability.SYSTEM_SETTINGS
    override val risk: ActionRisk = ActionRisk.READ_ONLY
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val requested = request.arguments["setting"].orEmpty()
        val setting = SystemSettingsCatalog.Readable.byId(requested)
            ?: return BridgeResult(
                false, transport,
                "Неизвестная настройка «" + requested.take(40) + "». Доступны: " +
                    SystemSettingsCatalog.readableIds(),
                errorCode = "E_UNKNOWN_SETTING"
            )
        val raw = runCatching {
            when (setting.scope) {
                SystemSettingsCatalog.Scope.SYSTEM ->
                    Settings.System.getString(context.contentResolver, setting.key)
                SystemSettingsCatalog.Scope.GLOBAL ->
                    Settings.Global.getString(context.contentResolver, setting.key)
            }
        }.getOrNull()
        return BridgeResult(
            success = true,
            transport = transport,
            message = setting.title + ": " + SystemSettingsCatalog.describe(setting, raw),
            data = mapOf(
                "setting" to setting.id,
                "title" to setting.title,
                "raw" to raw.orEmpty(),
                "readable" to SystemSettingsCatalog.describe(setting, raw)
            )
        )
    }

    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification =
        if (result.data["setting"] == request.arguments["setting"]) VerifiableBridgeTool.Verification.Passed
        else VerifiableBridgeTool.Verification.Failed("Прочитана не та настройка, которую запрашивали.")
}

class OpenSettingsScreenTool(private val context: Context) : AndroidBridgeTool {
    override val id: String = "system.open_screen"
    override val capability: BridgeCapability = BridgeCapability.SYSTEM_SETTINGS

    /** Opening a screen changes what the user sees, so it needs approval. */
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val requested = request.arguments["screen"].orEmpty()
        val screen = SystemSettingsCatalog.Screen.byId(requested)
            ?: return BridgeResult(
                false, transport,
                "Неизвестный экран «" + requested.take(40) + "». Доступны: " +
                    SystemSettingsCatalog.screenIds(),
                errorCode = "E_UNKNOWN_SCREEN"
            )
        val intent = Intent(screen.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            if (screen.appScoped) data = Uri.fromParts("package", context.packageName, null)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return BridgeResult(
                false, transport,
                "Экран «" + screen.title + "» недоступен на этом устройстве.",
                errorCode = "E_NO_SCREEN"
            )
        }
        return runCatching {
            context.startActivity(intent)
            BridgeResult(
                true, transport,
                "Открыт системный экран «" + screen.title + "». Изменения вносите сами — NEXUS ничего не меняет молча.",
                data = mapOf("screen" to screen.id, "title" to screen.title)
            )
        }.getOrElse { error ->
            BridgeResult(
                false, transport,
                "Не удалось открыть экран: " + (error.message ?: error.javaClass.simpleName),
                errorCode = "E_LAUNCH_FAILED"
            )
        }
    }
}
