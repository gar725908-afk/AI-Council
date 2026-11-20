package ai.hybrid.commandcenter.androidbridge

/**
 * First real bridge tool: read-only device diagnostics over the NATIVE
 * transport. Returns only measured values with their measurement time.
 */
class DeviceDiagnosticsTool(private val inspector: DeviceInspector) : AndroidBridgeTool {
    override val id: String = "device.diagnostics"
    override val capability: BridgeCapability = BridgeCapability.DEVICE_DIAGNOSTICS
    override val risk: ActionRisk = ActionRisk.READ_ONLY
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val diagnostics = inspector.diagnostics()
        return BridgeResult(
            success = true,
            transport = transport,
            message = "Диагностика устройства измерена.",
            data = buildMap {
                diagnostics.batteryPercent?.let { put("battery_percent", it.toString()) }
                diagnostics.charging?.let { put("charging", it.toString()) }
                diagnostics.ramFreeBytes?.let { put("ram_free_bytes", it.toString()) }
                diagnostics.ramTotalBytes?.let { put("ram_total_bytes", it.toString()) }
                put("storage_free_bytes", diagnostics.storageFreeBytes.toString())
                put("storage_total_bytes", diagnostics.storageTotalBytes.toString())
                put("thermal", diagnostics.thermalLabel)
                put("network", diagnostics.networkLabel)
                put("measured_at_epoch_ms", diagnostics.measuredAtEpochMillis.toString())
            }
        )
    }
}
