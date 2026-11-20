package ai.hybrid.commandcenter.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShizukuTransportTest {

    @Test
    fun `missing app grants nothing`() {
        val status = ShizukuTransport.classify(
            appInstalled = false, binderAlive = false, preV11 = false, permissionGranted = false
        )
        assertEquals(ShizukuTransport.State.NOT_INSTALLED, status.state)
        assertTrue(!status.usable)
    }

    @Test
    fun `installed app without a running service is not a privilege`() {
        val status = ShizukuTransport.classify(true, binderAlive = false, preV11 = false, permissionGranted = false)
        assertEquals(ShizukuTransport.State.SERVICE_NOT_RUNNING, status.state)
        assertTrue(!status.usable)
        assertTrue(status.detail.contains("служба не запущена"))
    }

    @Test
    fun `running service without permission is not a privilege`() {
        val status = ShizukuTransport.classify(true, binderAlive = true, preV11 = false, permissionGranted = false)
        assertEquals(ShizukuTransport.State.PERMISSION_REQUIRED, status.state)
        assertTrue(!status.usable)
    }

    @Test
    fun `outdated shizuku is refused even when permission looks granted`() {
        val status = ShizukuTransport.classify(true, binderAlive = true, preV11 = true, permissionGranted = true)
        assertEquals(ShizukuTransport.State.VERSION_TOO_OLD, status.state)
        assertTrue(!status.usable)
    }

    @Test
    fun `only a live binder plus granted permission is usable`() {
        val status = ShizukuTransport.classify(true, binderAlive = true, preV11 = false, permissionGranted = true)
        assertEquals(ShizukuTransport.State.READY, status.state)
        assertTrue(status.usable)
    }

    @Test
    fun `every state carries a human readable reason`() {
        val combinations = listOf(
            ShizukuTransport.classify(false, false, false, false),
            ShizukuTransport.classify(true, false, false, false),
            ShizukuTransport.classify(true, true, true, false),
            ShizukuTransport.classify(true, true, false, false),
            ShizukuTransport.classify(true, true, false, true)
        )
        assertTrue(combinations.all { it.detail.length > 20 })
    }

    @Test
    fun `privileged service reply is parsed into a typed outcome`() {
        val ok = NexusPrivilegedService.parseReply("0|PID USER RSS NAME\n1 root 100 init")
        assertTrue(ok.success)
        assertEquals(0, ok.exitCode)
        assertTrue(ok.output.contains("init"))
        assertTrue(ok.detail.contains("Shizuku"))
    }

    @Test
    fun `non zero exit or empty output is never reported as success`() {
        val failed = NexusPrivilegedService.parseReply("1|permission denied")
        assertTrue(!failed.success)
        assertEquals(1, failed.exitCode)

        val empty = NexusPrivilegedService.parseReply("0|")
        assertTrue(!empty.success)
        assertTrue(empty.detail.contains("не вернула вывод"))
    }

    @Test
    fun `service errors and garbage replies fail honestly`() {
        val refused = NexusPrivilegedService.parseReply("ERR|Неизвестная команда.")
        assertTrue(!refused.success)
        assertTrue(refused.detail.contains("Неизвестная команда"))

        assertTrue(!NexusPrivilegedService.parseReply(null).success)
        assertTrue(!NexusPrivilegedService.parseReply("").success)
        assertTrue(!NexusPrivilegedService.parseReply("no separator").success)
        assertTrue(!NexusPrivilegedService.parseReply("abc|output").success)
    }

    @Test
    fun `shizuku shares the fixed command enum, never free-form shell`() {
        // The transport takes a PrivilegedCommand, so there is no code path that
        // accepts a shell string. Guard the enum content as well.
        assertTrue(RootShellExecutor.PrivilegedCommand.entries.isNotEmpty())
        RootShellExecutor.PrivilegedCommand.entries.forEach { command ->
            assertTrue(!command.shellCommand.contains("rm "))
            assertTrue(!command.shellCommand.contains("&&"))
            assertTrue(!command.shellCommand.contains(";"))
        }
    }
}
