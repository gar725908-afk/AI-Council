package ai.hybrid.commandcenter.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityMatrixTest {

    private fun inputs(
        notificationsEnabled: Boolean = true,
        shizukuInstalled: Boolean = false,
        accessibilityServiceEnabled: Boolean = false,
        rootProbeAttempted: Boolean = false,
        rootConfirmed: Boolean = false
    ) = CapabilityInputs(
        sdkInt = 34,
        notificationsEnabled = notificationsEnabled,
        shizukuInstalled = shizukuInstalled,
        accessibilityServiceEnabled = accessibilityServiceEnabled,
        rootProbeAttempted = rootProbeAttempted,
        rootConfirmed = rootConfirmed
    )

    private fun state(states: List<CapabilityState>, capability: BridgeCapability): CapabilityState =
        states.single { it.capability == capability }

    @Test
    fun `every capability has exactly one row with a reason`() {
        val states = CapabilityMatrix.derive(inputs())
        assertEquals(BridgeCapability.values().size, states.size)
        assertEquals(BridgeCapability.values().toSet(), states.map { it.capability }.toSet())
        assertTrue(states.all { it.reason.isNotBlank() })
    }

    @Test
    fun `standard capabilities are available without root`() {
        val states = CapabilityMatrix.derive(inputs())
        for (capability in listOf(
            BridgeCapability.APP_LAUNCH,
            BridgeCapability.PACKAGE_METADATA,
            BridgeCapability.USER_SELECTED_FILES,
            BridgeCapability.DEVICE_DIAGNOSTICS
        )) {
            val row = state(states, capability)
            assertEquals(CapabilityAvailability.AVAILABLE, row.availability)
            assertEquals(BridgeTransport.NATIVE, row.transport)
        }
    }

    @Test
    fun `disabled notifications require permission instead of pretending`() {
        val states = CapabilityMatrix.derive(inputs(notificationsEnabled = false))
        assertEquals(
            CapabilityAvailability.PERMISSION_REQUIRED,
            state(states, BridgeCapability.NOTIFICATIONS).availability
        )
    }

    @Test
    fun `privileged capabilities are unavailable until root is confirmed`() {
        val states = CapabilityMatrix.derive(inputs())
        for (capability in listOf(
            BridgeCapability.PRIVILEGED_PACKAGE_OPERATIONS,
            BridgeCapability.PRIVILEGED_FILESYSTEM,
            BridgeCapability.PROCESS_INSPECTION
        )) {
            assertEquals(CapabilityAvailability.UNAVAILABLE, state(states, capability).availability)
        }
    }

    @Test
    fun `denied root probe is reported as unavailable with probe context`() {
        val states = CapabilityMatrix.derive(inputs(rootProbeAttempted = true, rootConfirmed = false))
        val row = state(states, BridgeCapability.PRIVILEGED_FILESYSTEM)
        assertEquals(CapabilityAvailability.UNAVAILABLE, row.availability)
        assertTrue(row.reason.contains("Root", ignoreCase = true))
    }

    @Test
    fun `confirmed root yields degraded privileged rows over root transport`() {
        val states = CapabilityMatrix.derive(inputs(rootProbeAttempted = true, rootConfirmed = true))
        val row = state(states, BridgeCapability.PRIVILEGED_PACKAGE_OPERATIONS)
        assertEquals(CapabilityAvailability.DEGRADED, row.availability)
        assertEquals(BridgeTransport.ROOT, row.transport)
    }

    @Test
    fun `unimplemented subsystems never report available`() {
        val states = CapabilityMatrix.derive(
            inputs(shizukuInstalled = true, accessibilityServiceEnabled = true)
        )
        for (capability in listOf(
            BridgeCapability.SYSTEM_SETTINGS,
            BridgeCapability.ACCESSIBLE_UI_TREE,
            BridgeCapability.UI_GESTURES,
            BridgeCapability.SCREEN_CAPTURE
        )) {
            assertEquals(CapabilityAvailability.UNAVAILABLE, state(states, capability).availability)
        }
    }

    @Test
    fun `shizuku app presence alone grants no privilege`() {
        val privileges = CapabilityMatrix.privileges(inputs(shizukuInstalled = true))
        assertEquals(setOf(BridgePrivilege.STANDARD), privileges)
    }

    @Test
    fun `confirmed root adds direct root privilege`() {
        val privileges = CapabilityMatrix.privileges(
            inputs(rootProbeAttempted = true, rootConfirmed = true)
        )
        assertTrue(BridgePrivilege.DIRECT_ROOT in privileges)
        assertTrue(BridgePrivilege.STANDARD in privileges)
    }

    @Test
    fun `snapshot exposes only available capabilities and notes the rest`() {
        val now = System.currentTimeMillis()
        val snapshot = CapabilityMatrix.snapshot(inputs(notificationsEnabled = false), now)
        assertEquals(now, snapshot.checkedAtEpochMillis)
        assertTrue(BridgeCapability.APP_LAUNCH in snapshot.capabilities)
        assertFalse(BridgeCapability.NOTIFICATIONS in snapshot.capabilities)
        assertFalse(BridgeCapability.SCREEN_CAPTURE in snapshot.capabilities)
        assertTrue(snapshot.notes.any { it.startsWith("NOTIFICATIONS:") })
    }
}
