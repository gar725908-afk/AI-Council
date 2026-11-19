package ai.hybrid.commandcenter.androidbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeActionPolicyTest {
    private class TestTool(
        override val risk: ActionRisk
    ) : AndroidBridgeTool {
        override val id = "device.settings.write"
        override val capability = BridgeCapability.SYSTEM_SETTINGS
        override val supportedTransports = setOf(BridgeTransport.NATIVE)

        override suspend fun execute(
            request: BridgeRequest,
            transport: BridgeTransport
        ) = BridgeResult(true, transport, "ok")
    }

    private fun snapshot(
        checkedAt: Long = System.currentTimeMillis()
    ) = CapabilitySnapshot(
        privileges = setOf(BridgePrivilege.STANDARD),
        capabilities = setOf(BridgeCapability.SYSTEM_SETTINGS),
        checkedAtEpochMillis = checkedAt
    )

    @Test
    fun readOnlyActionsNeedNoApprovalButMutationsDo() {
        val request = BridgeRequest("request-1", "device.settings.write")
        assertTrue(BridgeActionPolicy.isAllowed(TestTool(ActionRisk.READ_ONLY), request, snapshot()))
        assertFalse(BridgeActionPolicy.isAllowed(TestTool(ActionRisk.REVERSIBLE), request, snapshot()))

        val approved = request.copy(
            approval = ApprovalGrant("request-1", "device.settings.write", System.currentTimeMillis())
        )
        assertTrue(BridgeActionPolicy.isAllowed(TestTool(ActionRisk.REVERSIBLE), approved, snapshot()))
    }

    @Test
    fun rejectsStaleCapabilitySnapshotsAndApprovalGrants() {
        val now = System.currentTimeMillis()
        val tool = TestTool(ActionRisk.SENSITIVE)
        val request = BridgeRequest(
            "request-2",
            tool.id,
            approval = ApprovalGrant("request-2", tool.id, now)
        )

        assertFalse(BridgeActionPolicy.isAllowed(tool, request, snapshot(now - 31_000L)))
        assertFalse(
            BridgeActionPolicy.isAllowed(
                tool,
                request.copy(approval = ApprovalGrant("request-2", tool.id, now - 121_000L)),
                snapshot(now)
            )
        )
    }

    @Test
    fun rejectsOversizedOrMismatchedRequests() {
        val tool = TestTool(ActionRisk.READ_ONLY)
        val fresh = snapshot()
        assertFalse(BridgeActionPolicy.isAllowed(tool, BridgeRequest("", tool.id), fresh))
        assertFalse(BridgeActionPolicy.isAllowed(tool, BridgeRequest("request-3", "other.tool"), fresh))
        assertFalse(
            BridgeActionPolicy.isAllowed(
                tool,
                BridgeRequest("request-4", tool.id, arguments = mapOf("payload" to "x".repeat(4_097))),
                fresh
            )
        )
        assertFalse(
            BridgeActionPolicy.isAllowed(
                tool,
                BridgeRequest("request-5", tool.id, arguments = (1..33).associate { "k$it" to "v" }),
                fresh
            )
        )
    }
}
