package ai.hybrid.commandcenter.androidbridge

enum class BridgePrivilege { STANDARD, SHIZUKU_ADB, SHIZUKU_ROOT, DIRECT_ROOT }

enum class BridgeTransport(val priority: Int) {
    NATIVE(100), APP_ADAPTER(95), SHIZUKU_ROOT(90), ROOT(85),
    SHIZUKU_ADB(80), ACCESSIBILITY(50), VISION_GESTURE(20)
}

enum class BridgeCapability {
    APP_LAUNCH, PACKAGE_METADATA, USER_SELECTED_FILES, NOTIFICATIONS,
    DEVICE_DIAGNOSTICS, SYSTEM_SETTINGS, PRIVILEGED_PACKAGE_OPERATIONS,
    PRIVILEGED_FILESYSTEM, PROCESS_INSPECTION, ACCESSIBLE_UI_TREE,
    UI_GESTURES, SCREEN_CAPTURE
}

enum class ActionRisk { READ_ONLY, REVERSIBLE, SENSITIVE, DESTRUCTIVE }

class ApprovalGrant internal constructor(
    val requestId: String,
    val toolId: String,
    val approvedAtEpochMillis: Long
)

data class BridgeRequest(
    val requestId: String,
    val toolId: String,
    val arguments: Map<String, String> = emptyMap(),
    val approval: ApprovalGrant? = null
)

data class BridgeResult(
    val success: Boolean,
    val transport: BridgeTransport,
    val message: String,
    val data: Map<String, String> = emptyMap(),
    val errorCode: String? = null
)

data class CapabilitySnapshot(
    val privileges: Set<BridgePrivilege>,
    val capabilities: Set<BridgeCapability>,
    val checkedAtEpochMillis: Long,
    val notes: List<String> = emptyList()
)

interface AndroidBridgeTool {
    val id: String
    val capability: BridgeCapability
    val risk: ActionRisk
    val supportedTransports: Set<BridgeTransport>
    suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult
}

object BridgeActionPolicy {
    private const val MAX_REQUEST_ID_CHARS = 128
    private const val MAX_TOOL_ID_CHARS = 128
    private const val MAX_ARGUMENTS = 32
    private const val MAX_ARGUMENT_KEY_CHARS = 64
    private const val MAX_ARGUMENT_VALUE_CHARS = 4_096
    private const val MAX_SNAPSHOT_AGE_MS = 30_000L
    private const val MAX_APPROVAL_AGE_MS = 120_000L
    private const val MAX_FUTURE_SKEW_MS = 5_000L

    fun requiresApproval(risk: ActionRisk): Boolean = risk != ActionRisk.READ_ONLY

    fun isAllowed(tool: AndroidBridgeTool, request: BridgeRequest, snapshot: CapabilitySnapshot): Boolean {
        val now = System.currentTimeMillis()
        if (tool.id.isBlank() || tool.id.length > MAX_TOOL_ID_CHARS) return false
        if (request.requestId.isBlank() || request.requestId.length > MAX_REQUEST_ID_CHARS) return false
        if (request.toolId != tool.id || tool.capability !in snapshot.capabilities) return false
        if (request.arguments.size > MAX_ARGUMENTS || request.arguments.any { (key, value) ->
                key.isBlank() || key.length > MAX_ARGUMENT_KEY_CHARS ||
                    key.any { it.code < 0x20 || it.code == 0x7f } ||
                    value.length > MAX_ARGUMENT_VALUE_CHARS
            }
        ) return false

        val snapshotAge = now - snapshot.checkedAtEpochMillis
        if (snapshotAge < -MAX_FUTURE_SKEW_MS || snapshotAge > MAX_SNAPSHOT_AGE_MS) return false

        if (requiresApproval(tool.risk)) {
            val grant = request.approval ?: return false
            if (grant.requestId != request.requestId || grant.toolId != tool.id) return false
            val approvalAge = now - grant.approvedAtEpochMillis
            if (approvalAge < -MAX_FUTURE_SKEW_MS || approvalAge > MAX_APPROVAL_AGE_MS) return false
        }
        return true
    }
}

class BridgeToolRegistry {
    private val tools = linkedMapOf<String, AndroidBridgeTool>()

    fun register(tool: AndroidBridgeTool) {
        require(tool.id.isNotBlank()) { "Tool id must not be blank" }
        require(tools.putIfAbsent(tool.id, tool) == null) { "Duplicate bridge tool: ${tool.id}" }
    }

    fun get(toolId: String): AndroidBridgeTool? = tools[toolId]
    fun all(): List<AndroidBridgeTool> = tools.values.toList()

    fun selectTransport(tool: AndroidBridgeTool, available: Set<BridgeTransport>): BridgeTransport? =
        tool.supportedTransports.intersect(available).maxByOrNull { it.priority }
}
