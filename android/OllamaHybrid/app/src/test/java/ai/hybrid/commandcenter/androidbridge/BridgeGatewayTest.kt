package ai.hybrid.commandcenter.androidbridge

import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeGatewayTest {

    private class RecordingSink : BridgeAuditSink {
        val entries = Collections.synchronizedList(mutableListOf<BridgeAuditEntry>())
        override fun record(entry: BridgeAuditEntry) {
            entries.add(entry)
        }
    }

    private class FakeTool(
        override val id: String = "test.readonly",
        override val capability: BridgeCapability = BridgeCapability.DEVICE_DIAGNOSTICS,
        override val risk: ActionRisk = ActionRisk.READ_ONLY,
        override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE),
        private val behavior: suspend (BridgeRequest, BridgeTransport) -> BridgeResult = { _, transport ->
            BridgeResult(true, transport, "ok", mapOf("key" to "value"))
        }
    ) : AndroidBridgeTool {
        override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult =
            behavior(request, transport)
    }

    private fun snapshot(
        capabilities: Set<BridgeCapability> = setOf(BridgeCapability.DEVICE_DIAGNOSTICS)
    ) = CapabilitySnapshot(
        privileges = setOf(BridgePrivilege.STANDARD),
        capabilities = capabilities,
        checkedAtEpochMillis = System.currentTimeMillis()
    )

    private fun gateway(
        sink: RecordingSink,
        vararg tools: AndroidBridgeTool,
        timeoutMillis: Long = 5_000,
        outputLimit: Int = 16_384
    ): BridgeGateway {
        val registry = BridgeToolRegistry()
        tools.forEach(registry::register)
        return BridgeGateway(registry, sink, timeoutMillis, outputLimit)
    }

    @Test
    fun `read-only tool executes and audits started then success`() = runBlocking {
        val sink = RecordingSink()
        val outcome = gateway(sink, FakeTool()).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Success)
        assertEquals(listOf("STARTED", "SUCCESS"), sink.entries.map { it.outcome })
        assertEquals("user", sink.entries.first().initiator)
    }

    @Test
    fun `unknown tool is denied at validation and audited`() = runBlocking {
        val sink = RecordingSink()
        val outcome = gateway(sink, FakeTool()).execute(
            BridgeRequest("req-1", "no.such.tool"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Denied)
        assertEquals(GatewayOutcome.Stage.VALIDATION, (outcome as GatewayOutcome.Denied).stage)
        assertEquals(listOf("DENIED_VALIDATION"), sink.entries.map { it.outcome })
    }

    @Test
    fun `missing capability in snapshot denies execution`() = runBlocking {
        val sink = RecordingSink()
        val outcome = gateway(sink, FakeTool()).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(capabilities = emptySet()), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Denied)
        assertEquals(GatewayOutcome.Stage.CAPABILITY, (outcome as GatewayOutcome.Denied).stage)
    }

    @Test
    fun `sensitive tool without approval is denied at approval stage`() = runBlocking {
        val sink = RecordingSink()
        val tool = FakeTool(id = "test.sensitive", risk = ActionRisk.SENSITIVE)
        val outcome = gateway(sink, tool).execute(
            BridgeRequest("req-1", "test.sensitive"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Denied)
        assertEquals(GatewayOutcome.Stage.APPROVAL, (outcome as GatewayOutcome.Denied).stage)
        assertTrue(sink.entries.single().outcome == "DENIED_APPROVAL")
    }

    @Test
    fun `sensitive tool with a fresh bound grant executes`() = runBlocking {
        val sink = RecordingSink()
        val tool = FakeTool(id = "test.sensitive", risk = ActionRisk.SENSITIVE)
        val grant = ApprovalGrant("req-1", "test.sensitive", System.currentTimeMillis())
        val outcome = gateway(sink, tool).execute(
            BridgeRequest("req-1", "test.sensitive", approval = grant),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Success)
    }

    @Test
    fun `expired grant is rejected`() = runBlocking {
        val sink = RecordingSink()
        val tool = FakeTool(id = "test.sensitive", risk = ActionRisk.SENSITIVE)
        val staleGrant = ApprovalGrant(
            "req-1", "test.sensitive", System.currentTimeMillis() - 10 * 60 * 1000
        )
        val outcome = gateway(sink, tool).execute(
            BridgeRequest("req-1", "test.sensitive", approval = staleGrant),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Denied)
    }

    @Test
    fun `grant bound to another request is rejected`() = runBlocking {
        val sink = RecordingSink()
        val tool = FakeTool(id = "test.sensitive", risk = ActionRisk.SENSITIVE)
        val foreignGrant = ApprovalGrant("other-request", "test.sensitive", System.currentTimeMillis())
        val outcome = gateway(sink, tool).execute(
            BridgeRequest("req-1", "test.sensitive", approval = foreignGrant),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Denied)
    }

    @Test
    fun `unavailable transport is denied at transport stage`() = runBlocking {
        val sink = RecordingSink()
        val outcome = gateway(sink, FakeTool()).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.ACCESSIBILITY), "user"
        )
        assertTrue(outcome is GatewayOutcome.Denied)
        assertEquals(GatewayOutcome.Stage.TRANSPORT, (outcome as GatewayOutcome.Denied).stage)
    }

    @Test
    fun `slow tool times out with a typed failure`() = runBlocking {
        val sink = RecordingSink()
        val slow = FakeTool(behavior = { _, transport ->
            delay(2_000)
            BridgeResult(true, transport, "late")
        })
        val outcome = gateway(sink, slow, timeoutMillis = 100).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Failed)
        assertEquals(listOf("STARTED", "TIMEOUT"), sink.entries.map { it.outcome })
    }

    @Test
    fun `throwing tool produces failed outcome with audit`() = runBlocking {
        val sink = RecordingSink()
        val broken = FakeTool(behavior = { _, _ -> throw IllegalStateException("boom") })
        val outcome = gateway(sink, broken).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Failed)
        assertEquals("boom", (outcome as GatewayOutcome.Failed).reason)
        assertEquals(listOf("STARTED", "FAILED"), sink.entries.map { it.outcome })
    }

    @Test
    fun `oversized output is bounded`() = runBlocking {
        val sink = RecordingSink()
        val chatty = FakeTool(behavior = { _, transport ->
            BridgeResult(true, transport, "x".repeat(100_000), mapOf("big" to "y".repeat(100_000)))
        })
        val outcome = gateway(sink, chatty, outputLimit = 1_000).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        val success = outcome as GatewayOutcome.Success
        assertEquals(1_000, success.result.message.length)
        assertEquals(1_000, success.result.data.getValue("big").length)
    }

    @Test
    fun `tool-reported failure is a typed failed outcome not fake success`() = runBlocking {
        val sink = RecordingSink()
        val failing = FakeTool(behavior = { _, transport ->
            BridgeResult(false, transport, "операция не выполнена", errorCode = "E_DENIED")
        })
        val outcome = gateway(sink, failing).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Failed)
        assertTrue((outcome as GatewayOutcome.Failed).reason.contains("E_DENIED"))
    }

    private class VerifiableFake(
        private val verification: VerifiableBridgeTool.Verification
    ) : VerifiableBridgeTool {
        override val id = "test.verifiable"
        override val capability = BridgeCapability.DEVICE_DIAGNOSTICS
        override val risk = ActionRisk.READ_ONLY
        override val supportedTransports = setOf(BridgeTransport.NATIVE)
        override suspend fun execute(request: BridgeRequest, transport: BridgeTransport) =
            BridgeResult(true, transport, "done")
        override suspend fun verify(request: BridgeRequest, result: BridgeResult) = verification
    }

    @Test
    fun `failed postconditions turn a successful execute into a typed failure`() = runBlocking {
        val sink = RecordingSink()
        val outcome = gateway(sink, VerifiableFake(VerifiableBridgeTool.Verification.Failed("файл не появился"))).execute(
            BridgeRequest("req-1", "test.verifiable"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Failed)
        assertEquals(GatewayOutcome.Stage.VERIFICATION, (outcome as GatewayOutcome.Failed).stage)
        assertEquals(listOf("STARTED", "VERIFY_FAILED"), sink.entries.map { it.outcome })
    }

    @Test
    fun `passed postconditions are recorded in the success audit`() = runBlocking {
        val sink = RecordingSink()
        val outcome = gateway(sink, VerifiableFake(VerifiableBridgeTool.Verification.Passed)).execute(
            BridgeRequest("req-1", "test.verifiable"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Success)
        assertTrue(sink.entries.last().detail.contains("Постусловия проверены"))
    }

    @Test
    fun `broken audit sink never breaks the pipeline`() = runBlocking {
        val throwingSink = BridgeAuditSink { throw RuntimeException("audit db down") }
        val registry = BridgeToolRegistry().apply { register(FakeTool()) }
        val outcome = BridgeGateway(registry, throwingSink).execute(
            BridgeRequest("req-1", "test.readonly"),
            snapshot(), setOf(BridgeTransport.NATIVE), "user"
        )
        assertTrue(outcome is GatewayOutcome.Success)
    }
}
