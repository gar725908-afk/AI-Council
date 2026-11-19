package ai.hybrid.commandcenter.nexus

import org.json.JSONObject

/**
 * Strict, credential-free schema for an approved Council routing policy.
 *
 * The policy can select a provider and optional model ID for a known Council role.
 * It cannot supply endpoints, credentials, tool commands, or arbitrary executable content.
 */
data class NexusEvolutionRoutingPolicy(
    val routes: Map<String, ProviderKind>,
    val modelOverrides: Map<String, String>
) {
    companion object {
        private val allowedAgentIds = CouncilOrchestrator.defaultAgents().map { it.id }.toSet()
        private val allowedRootKeys = setOf("version", "routes", "modelOverrides")

        fun parse(payload: String): NexusEvolutionRoutingPolicy {
            require(payload.isNotBlank() && payload.length <= 64 * 1024) {
                "Routing policy must contain 1–65536 characters."
            }
            val root = try {
                JSONObject(payload)
            } catch (error: Exception) {
                throw IllegalArgumentException("Routing policy must be valid JSON.", error)
            }
            val keys = root.keys().asSequence().toSet()
            require(keys.all { it in allowedRootKeys }) {
                "Routing policy contains unsupported fields. Endpoints and credentials must stay in provider settings."
            }
            require(root.optInt("version", 1) == 1) {
                "Only routing policy schema version 1 is supported."
            }
            val routeObject = root.optJSONObject("routes")
                ?: throw IllegalArgumentException("Routing policy must contain a routes object.")
            val routes = linkedMapOf<String, ProviderKind>()
            val routeKeys = routeObject.keys()
            while (routeKeys.hasNext()) {
                val agentId = routeKeys.next()
                require(agentId in allowedAgentIds) {
                    "Routing policy references an unknown Council role: $agentId."
                }
                val providerName = routeObject.optString(agentId).trim()
                require(providerName.isNotBlank()) {
                    "Routing policy provider for $agentId cannot be empty."
                }
                val provider = try {
                    ProviderKind.valueOf(providerName)
                } catch (error: Exception) {
                    throw IllegalArgumentException("Unsupported provider '$providerName' for $agentId.", error)
                }
                routes[agentId] = provider
            }

            val overrideObject = root.optJSONObject("modelOverrides")
            val overrides = linkedMapOf<String, String>()
            if (overrideObject != null) {
                val overrideKeys = overrideObject.keys()
                while (overrideKeys.hasNext()) {
                    val agentId = overrideKeys.next()
                    require(agentId in allowedAgentIds) {
                        "Model override references an unknown Council role: $agentId."
                    }
                    val modelId = overrideObject.optString(agentId).trim()
                    require(modelId.isNotBlank() && modelId.length <= 200) {
                        "Model override for $agentId must contain 1–200 characters."
                    }
                    require(modelId.none { it.code < 0x20 || it.code == 0x7f }) {
                        "Model override for $agentId contains control characters."
                    }
                    overrides[agentId] = modelId
                }
            }
            return NexusEvolutionRoutingPolicy(routes, overrides)
        }
    }
}
