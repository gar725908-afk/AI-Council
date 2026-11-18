package ai.hybrid.commandcenter.androidbridge

private class FakeTool(
    override val id: String,
    override val capability: BridgeCapability,
    override val risk: ActionRisk,
    override val supportedTransports: Set<BridgeTransport>
) : AndroidBridgeTool {
    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport) =
        BridgeResult(true, transport, "ok")
}

fun main() {
    val registry = BridgeToolRegistry()
    val readTool = FakeTool("device.info", BridgeCapability.DEVICE_DIAGNOSTICS, ActionRisk.READ_ONLY,
        setOf(BridgeTransport.NATIVE, BridgeTransport.ROOT))
    val deleteTool = FakeTool("file.delete", BridgeCapability.PRIVILEGED_FILESYSTEM, ActionRisk.DESTRUCTIVE,
        setOf(BridgeTransport.ROOT))
    registry.register(readTool)
    registry.register(deleteTool)

    check(registry.get("device.info") === readTool)
    check(registry.selectTransport(readTool, setOf(BridgeTransport.NATIVE, BridgeTransport.ROOT)) == BridgeTransport.NATIVE)
    check(registry.selectTransport(deleteTool, setOf(BridgeTransport.NATIVE)) == null)

    val snapshot = CapabilitySnapshot(
        setOf(BridgePrivilege.STANDARD, BridgePrivilege.DIRECT_ROOT),
        setOf(BridgeCapability.DEVICE_DIAGNOSTICS, BridgeCapability.PRIVILEGED_FILESYSTEM), 1L
    )
    check(BridgeActionPolicy.isAllowed(readTool, BridgeRequest("r1", "device.info"), snapshot))
    check(!BridgeActionPolicy.isAllowed(deleteTool, BridgeRequest("r2", "file.delete"), snapshot))
    check(!BridgeActionPolicy.isAllowed(deleteTool,
        BridgeRequest("r2", "file.delete", approval = ApprovalGrant("other", "file.delete", 2L)), snapshot))
    check(BridgeActionPolicy.isAllowed(deleteTool,
        BridgeRequest("r2", "file.delete", approval = ApprovalGrant("r2", "file.delete", 3L)), snapshot))
    check(!BridgeActionPolicy.isAllowed(readTool, BridgeRequest("r3", "wrong.id"), snapshot))

    var duplicateRejected = false
    try { registry.register(readTool) } catch (_: IllegalArgumentException) { duplicateRejected = true }
    check(duplicateRejected)

    println("PASS: registry and duplicate-ID rejection")
    println("PASS: transport priority and unavailable transport handling")
    println("PASS: read-only capability policy")
    println("PASS: destructive action requires request-bound host approval")
}
