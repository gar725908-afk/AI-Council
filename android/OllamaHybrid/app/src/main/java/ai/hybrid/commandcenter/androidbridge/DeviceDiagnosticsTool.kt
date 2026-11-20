package ai.hybrid.commandcenter.androidbridge

/**
 * First real bridge tool: read-only device diagnostics over the NATIVE
 * transport. Returns only measured values with their measurement time.
 */
class DeviceDiagnosticsTool(private val inspector: DeviceInspector) : VerifiableBridgeTool {
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

    /** Postconditions: a measurement timestamp exists and storage figures are sane. */
    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification {
        val measuredAt = result.data["measured_at_epoch_ms"]?.toLongOrNull()
            ?: return VerifiableBridgeTool.Verification.Failed("Нет отметки времени измерения.")
        if (measuredAt <= 0L || measuredAt > System.currentTimeMillis() + 60_000) {
            return VerifiableBridgeTool.Verification.Failed("Отметка времени измерения недостоверна.")
        }
        val free = result.data["storage_free_bytes"]?.toLongOrNull() ?: -1L
        val total = result.data["storage_total_bytes"]?.toLongOrNull() ?: -1L
        if (free < 0L || total <= 0L || free > total) {
            return VerifiableBridgeTool.Verification.Failed("Показатели накопителя противоречивы.")
        }
        return VerifiableBridgeTool.Verification.Passed
    }
}
