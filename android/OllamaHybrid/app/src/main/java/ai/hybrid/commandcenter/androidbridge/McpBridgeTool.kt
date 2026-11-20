package ai.hybrid.commandcenter.androidbridge

import ai.hybrid.commandcenter.nexus.McpClient
import ai.hybrid.commandcenter.nexus.McpServer
import ai.hybrid.commandcenter.nexus.McpTool
import org.json.JSONObject

/**
 * A remote MCP tool as a bridge tool: same Gateway, same approvals, same
 * audit. Read-only hinted tools run without approval; everything else is
 * SENSITIVE and needs a one-shot user grant.
 */
class McpBridgeTool(
    private val server: McpServer,
    val remote: McpTool,
    private val client: McpClient
) : AndroidBridgeTool {
    override val id: String = toolId(server.name, remote.name)
    override val capability: BridgeCapability = BridgeCapability.REMOTE_CONNECTOR
    override val risk: ActionRisk = if (remote.readOnlyHint) ActionRisk.READ_ONLY else ActionRisk.SENSITIVE
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        val arguments = argumentsJson(request.arguments)
        val call = client.callTool(server, remote.name, arguments)
        val result = call.getOrElse { error ->
            return BridgeResult(false, transport, error.message ?: "Вызов MCP не удался.", errorCode = "E_MCP_TRANSPORT")
        }
        return BridgeResult(
            success = !result.isError,
            transport = transport,
            message = result.text,
            data = mapOf("server" to server.name, "tool" to remote.name),
            errorCode = if (result.isError) "E_MCP_TOOL" else null
        )
    }

    companion object {
        fun toolId(serverName: String, toolName: String): String =
            "mcp." + serverName.lowercase().replace(Regex("[^a-z0-9а-я]+"), "_").take(40) + "." + toolName.take(80)

        /** A single "json" argument is passed through as an object; otherwise flat string args. */
        fun argumentsJson(arguments: Map<String, String>): JSONObject {
            arguments["json"]?.let { raw ->
                runCatching { JSONObject(raw) }.getOrNull()?.let { return it }
            }
            return JSONObject().apply { arguments.forEach { (key, value) -> put(key, value) } }
        }
    }
}
