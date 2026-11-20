package ai.hybrid.commandcenter.androidbridge

/**
 * Accessibility bridge tools (handoff section 7). Reading the screen is
 * READ_ONLY; tapping changes the device, so it is SENSITIVE and the Gateway
 * requires a one-shot approval. Both refuse outright when the user has not
 * enabled the service.
 */
class ReadScreenTool : VerifiableBridgeTool {
    override val id: String = "ui.read_screen"
    override val capability: BridgeCapability = BridgeCapability.ACCESSIBLE_UI_TREE
    override val risk: ActionRisk = ActionRisk.READ_ONLY
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.ACCESSIBILITY)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val service = NexusAccessibilityService.current()
            ?: return BridgeResult(
                false, transport,
                "Служба NEXUS Bridge не включена в системных настройках специальных возможностей.",
                errorCode = "E_SERVICE_OFF"
            )
        val nodes = service.readTree()
        if (nodes.isEmpty()) {
            return BridgeResult(false, transport, "Активное окно недоступно для чтения.", errorCode = "E_NO_WINDOW")
        }
        return BridgeResult(
            success = true,
            transport = transport,
            message = UiTreeMatcher.render(nodes),
            data = mapOf(
                "node_count" to nodes.size.toString(),
                "clickable_count" to nodes.count { it.clickable }.toString()
            )
        )
    }

    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification =
        if ((result.data["node_count"]?.toIntOrNull() ?: 0) > 0) VerifiableBridgeTool.Verification.Passed
        else VerifiableBridgeTool.Verification.Failed("Дерево интерфейса пришло пустым.")
}

class TapElementTool : VerifiableBridgeTool {
    override val id: String = "ui.tap"
    override val capability: BridgeCapability = BridgeCapability.UI_GESTURES
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.ACCESSIBILITY)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val target = request.arguments["target"].orEmpty().trim()
        if (target.isEmpty() || target.length > 200) {
            return BridgeResult(false, transport, "Укажите текст элемента (аргумент target).", errorCode = "E_BAD_TARGET")
        }
        val service = NexusAccessibilityService.current()
            ?: return BridgeResult(
                false, transport,
                "Служба NEXUS Bridge не включена в системных настройках специальных возможностей.",
                errorCode = "E_SERVICE_OFF"
            )
        val nodes = service.readTree()
        val node = UiTreeMatcher.findClickable(nodes, target)
            ?: return BridgeResult(
                false, transport,
                "На экране не найден элемент «" + target.take(60) + "».",
                errorCode = "E_NOT_FOUND"
            )
        val failure = service.tap(node.bounds)
        return if (failure == null) {
            BridgeResult(
                true, transport,
                "Нажат элемент «" + node.label.take(60) + "».",
                data = mapOf(
                    "target" to target,
                    "matched_label" to node.label,
                    "x" to node.bounds.centerX().toString(),
                    "y" to node.bounds.centerY().toString()
                )
            )
        } else {
            BridgeResult(false, transport, failure, errorCode = "E_GESTURE_FAILED")
        }
    }

    /** Postcondition: the tap landed on a node that actually matches what was asked for. */
    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification {
        val requested = request.arguments["target"].orEmpty().trim().lowercase()
        val matched = result.data["matched_label"].orEmpty().lowercase()
        if (matched.isBlank()) return VerifiableBridgeTool.Verification.Failed("Неизвестно, какой элемент был нажат.")
        if (!matched.contains(requested) && !requested.contains(matched)) {
            return VerifiableBridgeTool.Verification.Failed(
                "Нажат элемент «$matched», который не соответствует запросу «$requested»."
            )
        }
        return VerifiableBridgeTool.Verification.Passed
    }
}
