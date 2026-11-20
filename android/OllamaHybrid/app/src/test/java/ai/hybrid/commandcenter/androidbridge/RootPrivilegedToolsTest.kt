package ai.hybrid.commandcenter.androidbridge

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootOutputParserTest {

    @Test
    fun `process rows are parsed and malformed lines dropped`() {
        val output = """
            PID USER RSS NAME
            1 root 2048 init
            742 system 51200 system_server
            broken line
            900 u0_a12 abc com.example.app
            1203 u0_a44 8192 com.example.two words
        """.trimIndent()
        val rows = RootOutputParser.parseProcesses(output)
        assertEquals(listOf(1, 742, 1203), rows.map { it.pid })
        assertEquals("system_server", rows[1].name)
        assertEquals(51200L, rows[1].rssKb)
        assertEquals("com.example.two words", rows[2].name)
    }

    @Test
    fun `process list respects the limit`() {
        val output = buildString {
            appendLine("PID USER RSS NAME")
            repeat(50) { appendLine("$it root 100 proc$it") }
        }
        assertEquals(5, RootOutputParser.parseProcesses(output, limit = 5).size)
    }

    @Test
    fun `packages map to name and path`() {
        val output = """
            package:/data/app/~~abc==/com.example.one-1/base.apk=com.example.one
            package:/system/app/Settings/Settings.apk=com.android.settings
            garbage
            package:/no/equals/sign
        """.trimIndent()
        val packages = RootOutputParser.parsePackages(output)
        assertEquals(listOf("com.example.one", "com.android.settings"), packages.map { it.first })
        assertTrue(packages[1].second.endsWith("Settings.apk"))
    }

    @Test
    fun `meminfo values are parsed in kB`() {
        val output = """
            MemTotal:        7812345 kB
            MemAvailable:    3011222 kB
            NotANumber:      abc kB
            Broken line
        """.trimIndent()
        val info = RootOutputParser.parseMemInfo(output)
        assertEquals(7812345L, info["MemTotal"])
        assertEquals(3011222L, info["MemAvailable"])
        assertNull(info["NotANumber"])
    }

    @Test
    fun `privileged commands are addressable only by their fixed ids`() {
        assertEquals(
            RootShellExecutor.PrivilegedCommand.PROCESS_LIST,
            RootShellExecutor.PrivilegedCommand.byId("process_list")
        )
        assertNull(RootShellExecutor.PrivilegedCommand.byId("rm -rf /"))
        assertNull(RootShellExecutor.PrivilegedCommand.byId(""))
    }

    @Test
    fun `no privileged command is destructive or takes free-form input`() {
        val forbidden = listOf("rm ", "dd ", "mkfs", "reboot", "setprop", "mount", "chmod", "chown", "$", "`")
        RootShellExecutor.PrivilegedCommand.entries.forEach { command ->
            forbidden.forEach { token ->
                assertTrue(
                    "Команда ${command.id} содержит опасный фрагмент $token",
                    !command.shellCommand.contains(token)
                )
            }
        }
    }
}

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class PrivilegedDiagnosticsToolTest {

    private val tool = PrivilegedDiagnosticsTool(ApplicationProvider.getApplicationContext())

    @Test
    fun `tool is sensitive and root-only`() {
        assertEquals(ActionRisk.SENSITIVE, tool.risk)
        assertEquals(setOf(BridgeTransport.SHIZUKU_ADB, BridgeTransport.ROOT), tool.supportedTransports)
        assertEquals(BridgeCapability.PROCESS_INSPECTION, tool.capability)
    }

    @Test
    fun `non root transport is refused before any shell runs`() = runBlocking {
        val result = tool.execute(
            BridgeRequest("r1", tool.id, mapOf("command" to "process_list")),
            BridgeTransport.NATIVE
        )
        assertTrue(!result.success)
        assertEquals("E_TRANSPORT", result.errorCode)
    }

    @Test
    fun `arbitrary command text is refused, never executed`() = runBlocking {
        val result = tool.execute(
            BridgeRequest("r1", tool.id, mapOf("command" to "rm -rf /data")),
            BridgeTransport.ROOT
        )
        assertTrue(!result.success)
        assertEquals("E_UNKNOWN_COMMAND", result.errorCode)
        assertTrue(result.message.contains("process_list"))
    }

    @Test
    fun `missing command argument is refused`() = runBlocking {
        val result = tool.execute(BridgeRequest("r1", tool.id), BridgeTransport.ROOT)
        assertTrue(!result.success)
        assertEquals("E_UNKNOWN_COMMAND", result.errorCode)
    }

    @Test
    fun `verification rejects a mismatched or empty result`() = runBlocking {
        val request = BridgeRequest("r1", tool.id, mapOf("command" to "process_list"))
        val mismatched = BridgeResult(
            true, BridgeTransport.ROOT, "output",
            mapOf("command" to "battery_detail", "exit_code" to "0")
        )
        assertTrue(tool.verify(request, mismatched) is VerifiableBridgeTool.Verification.Failed)

        val empty = BridgeResult(
            true, BridgeTransport.ROOT, "",
            mapOf("command" to "process_list", "exit_code" to "0")
        )
        assertTrue(tool.verify(request, empty) is VerifiableBridgeTool.Verification.Failed)

        val nonZero = BridgeResult(
            true, BridgeTransport.ROOT, "output",
            mapOf("command" to "process_list", "exit_code" to "1")
        )
        assertTrue(tool.verify(request, nonZero) is VerifiableBridgeTool.Verification.Failed)

        val good = BridgeResult(
            true, BridgeTransport.ROOT, "PID USER RSS NAME\n1 root 100 init",
            mapOf("command" to "process_list", "exit_code" to "0")
        )
        assertEquals(VerifiableBridgeTool.Verification.Passed, tool.verify(request, good))
    }
}
