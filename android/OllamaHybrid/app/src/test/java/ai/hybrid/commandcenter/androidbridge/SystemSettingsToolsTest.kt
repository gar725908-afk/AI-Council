package ai.hybrid.commandcenter.androidbridge

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SystemSettingsCatalogTest {

    @Test
    fun `settings and screens are addressable only by fixed ids`() {
        assertEquals(
            SystemSettingsCatalog.Readable.AIRPLANE_MODE,
            SystemSettingsCatalog.Readable.byId("airplane_mode")
        )
        assertNull(SystemSettingsCatalog.Readable.byId("android_id"))
        assertNull(SystemSettingsCatalog.Screen.byId("../../etc"))
    }

    @Test
    fun `no identifying setting is exposed for reading`() {
        val forbidden = listOf("android_id", "device_id", "imei", "serial", "bluetooth_name", "location")
        SystemSettingsCatalog.Readable.entries.forEach { setting ->
            forbidden.forEach { token ->
                assertTrue(
                    "Настройка ${setting.id} выглядит идентифицирующей",
                    !setting.key.lowercase().contains(token)
                )
            }
        }
    }

    @Test
    fun `boolean settings are rendered as on or off`() {
        assertEquals("включено", SystemSettingsCatalog.describe(SystemSettingsCatalog.Readable.AUTO_ROTATE, "1"))
        assertEquals("выключено", SystemSettingsCatalog.describe(SystemSettingsCatalog.Readable.ADB_ENABLED, "0"))
    }

    @Test
    fun `timeout is rendered in seconds and missing values are stated`() {
        assertEquals("30 с", SystemSettingsCatalog.describe(SystemSettingsCatalog.Readable.SCREEN_OFF_TIMEOUT, "30000"))
        assertTrue(
            SystemSettingsCatalog.describe(SystemSettingsCatalog.Readable.SCREEN_BRIGHTNESS, null)
                .contains("не задано")
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemSettingsToolsTest {

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `read tool is read-only, open tool needs approval`() {
        assertEquals(ActionRisk.READ_ONLY, ReadSystemSettingTool(context).risk)
        assertEquals(ActionRisk.SENSITIVE, OpenSettingsScreenTool(context).risk)
        assertEquals(BridgeCapability.SYSTEM_SETTINGS, ReadSystemSettingTool(context).capability)
    }

    @Test
    fun `unknown setting is refused with the allowed list`() = runBlocking {
        val result = ReadSystemSettingTool(context).execute(
            BridgeRequest("r1", "system.read_setting", mapOf("setting" to "android_id")),
            BridgeTransport.NATIVE
        )
        assertTrue(!result.success)
        assertEquals("E_UNKNOWN_SETTING", result.errorCode)
        assertTrue(result.message.contains("airplane_mode"))
    }

    @Test
    fun `known setting is read and labelled`() = runBlocking {
        val result = ReadSystemSettingTool(context).execute(
            BridgeRequest("r1", "system.read_setting", mapOf("setting" to "auto_rotate")),
            BridgeTransport.NATIVE
        )
        assertTrue(result.success)
        assertEquals("auto_rotate", result.data["setting"])
        assertTrue(result.message.startsWith("Автоповорот:"))
    }

    @Test
    fun `verification catches a mismatched read`() = runBlocking {
        val tool = ReadSystemSettingTool(context)
        val request = BridgeRequest("r1", tool.id, mapOf("setting" to "auto_rotate"))
        val wrong = BridgeResult(true, BridgeTransport.NATIVE, "x", mapOf("setting" to "airplane_mode"))
        assertTrue(tool.verify(request, wrong) is VerifiableBridgeTool.Verification.Failed)
    }

    @Test
    fun `unknown screen is refused without launching anything`() = runBlocking {
        val result = OpenSettingsScreenTool(context).execute(
            BridgeRequest("r1", "system.open_screen", mapOf("screen" to "root_shell")),
            BridgeTransport.NATIVE
        )
        assertTrue(!result.success)
        assertEquals("E_UNKNOWN_SCREEN", result.errorCode)
    }
}
