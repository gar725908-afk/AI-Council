package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResourceGovernorTest {

    private fun verdict(
        workload: ResourceGovernor.Workload = ResourceGovernor.Workload.LOCAL_INFERENCE,
        battery: Int? = 80,
        charging: Boolean? = false,
        thermal: Int? = 0,
        ram: Long? = 2_000_000_000L
    ) = ResourceGovernor.evaluate(workload, battery, charging, thermal, ram)

    @Test
    fun `healthy device allows everything`() {
        for (w in ResourceGovernor.Workload.values()) {
            assertEquals(ResourceGovernor.Verdict.Allow, verdict(workload = w))
        }
    }

    @Test
    fun `severe heat denies heavy work but only warns remote requests`() {
        assertTrue(verdict(thermal = 3) is ResourceGovernor.Verdict.Deny)
        assertTrue(verdict(workload = ResourceGovernor.Workload.MODEL_DOWNLOAD, thermal = 4) is ResourceGovernor.Verdict.Deny)
        assertTrue(verdict(workload = ResourceGovernor.Workload.REMOTE_REQUEST, thermal = 3) is ResourceGovernor.Verdict.Warn)
    }

    @Test
    fun `critical battery on battery power denies heavy work`() {
        assertTrue(verdict(battery = 7, charging = false) is ResourceGovernor.Verdict.Deny)
        assertEquals(ResourceGovernor.Verdict.Allow, verdict(battery = 7, charging = true))
    }

    @Test
    fun `low battery warns with the percentage`() {
        val v = verdict(battery = 15)
        assertTrue(v is ResourceGovernor.Verdict.Warn)
        assertTrue((v as ResourceGovernor.Verdict.Warn).reason.contains("15%"))
    }

    @Test
    fun `memory thresholds apply to local inference only`() {
        assertTrue(verdict(ram = 100L * 1024 * 1024) is ResourceGovernor.Verdict.Deny)
        assertTrue(verdict(ram = 400L * 1024 * 1024) is ResourceGovernor.Verdict.Warn)
        assertEquals(ResourceGovernor.Verdict.Allow,
            verdict(workload = ResourceGovernor.Workload.REMOTE_REQUEST, ram = 100L * 1024 * 1024))
    }

    @Test
    fun `unknown readings never block`() {
        assertEquals(ResourceGovernor.Verdict.Allow, verdict(battery = null, charging = null, thermal = null, ram = null))
    }
}
