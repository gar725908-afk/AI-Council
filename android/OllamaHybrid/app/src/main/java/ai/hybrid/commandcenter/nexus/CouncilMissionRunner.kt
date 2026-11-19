package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one Council mission end to end in the runtime layer, per handoff
 * section 6: the runtime — not a Compose screen — owns journal transitions,
 * per-role routing and result persistence. UI layers build a Request and
 * render the returned Outcome.
 *
 * The runner never invents results: every stored status comes from the actual
 * CouncilRunResult, and the fallback answer is the agents' own stored output.
 */
class CouncilMissionRunner(
    private val journal: MissionJournal,
    private val engineRegistry: NexusEngineRegistry,
    private val settingsStore: ProviderSettingsStore,
    private val routingStore: CouncilRoutingStore,
    private val credentialVault: AndroidCredentialVault,
    private val evolutionExecutor: NexusEvolutionExecutor
) {

    data class Request(
        val missionId: String,
        val goal: String,
        val providerKind: ProviderKind,
        val endpoint: String,
        val model: String,
        val apiKey: String,
        val agents: List<CouncilAgent>,
        val useOnDeviceModel: Boolean,
        val maxParallelAgents: Int = 3,
        /** Live orchestrator events; not supported on the on-device path yet. */
        val onAgentEvent: ((CouncilAgentEvent) -> Unit)? = null
    )

    data class Outcome(
        val runResult: CouncilRunResult,
        val finalStatus: MissionStatus,
        val answer: String,
        val statusLine: String
    )

    suspend fun run(request: Request): Outcome {
        require(request.missionId.isNotBlank()) { "Mission id must not be blank." }

        val result = if (request.useOnDeviceModel) {
            engineRegistry.runLocalCouncil(request.goal)
        } else {
            val activeRoutingPolicy = withContext(Dispatchers.IO) {
                evolutionExecutor.activeConfiguration(EvolutionChangeKind.ROUTING_POLICY)
                    .takeIf { it.isNotBlank() }
                    ?.let(NexusEvolutionRoutingPolicy::parse)
            }
            val defaultGateway = engineRegistry.gateway(request.providerKind)
            CouncilOrchestrator(
                gateway = defaultGateway,
                maxParallelAgents = request.maxParallelAgents,
                routeResolver = { agent ->
                    resolveRoute(agent, request, defaultGateway, activeRoutingPolicy)
                }
            ).run(
                goal = request.goal,
                endpoint = request.endpoint,
                model = request.model,
                apiKey = request.apiKey,
                agents = request.agents,
                onEvent = request.onAgentEvent
            )
        }

        withContext(Dispatchers.IO) {
            result.agentResults.forEach { journal.saveAgentResult(request.missionId, it) }
        }

        val decision = decide(result)
        withContext(Dispatchers.IO) {
            when (decision.finalStatus) {
                MissionStatus.COMPLETED -> {
                    journal.transition(
                        request.missionId, MissionStatus.VERIFYING, "Council results collected"
                    )
                    journal.transition(
                        request.missionId, MissionStatus.COMPLETED,
                        "Synthesis completed", result = decision.answer
                    )
                }
                MissionStatus.AWAITING_INPUT -> journal.transition(
                    request.missionId, MissionStatus.AWAITING_INPUT,
                    "Council requested additional user input", result = decision.answer
                )
                MissionStatus.INTERRUPTED -> journal.transition(
                    request.missionId, MissionStatus.INTERRUPTED,
                    "Council execution paused", result = decision.answer
                )
                else -> journal.transition(
                    request.missionId, MissionStatus.FAILED,
                    "Council did not produce a verified final result",
                    error = result.error ?: "Empty synthesis"
                )
            }
        }
        return Outcome(result, decision.finalStatus, decision.answer, decision.statusLine)
    }

    private fun resolveRoute(
        agent: CouncilAgent,
        request: Request,
        defaultGateway: ModelGateway,
        policy: NexusEvolutionRoutingPolicy?
    ): CouncilModelRoute {
        val routeKind = policy?.routes?.get(agent.id)
            ?: routingStore.load(agent.id)
            ?: request.providerKind
        val modelOverride = policy?.modelOverrides?.get(agent.id)
            ?: routingStore.loadModelOverride(agent.id)
        if (routeKind == request.providerKind) {
            return CouncilModelRoute(
                gateway = defaultGateway,
                endpoint = request.endpoint,
                model = modelOverride ?: request.model,
                apiKey = request.apiKey
            )
        }
        val profile = settingsStore.load(routeKind.name)
        val localRoute = routeKind == ProviderKind.ON_DEVICE_LITERT ||
            routeKind == ProviderKind.ON_DEVICE_LLAMA_CPP
        val routeModel = modelOverride ?: profile.model.ifBlank {
            if (localRoute) "active-device-model" else ""
        }
        require(routeModel.isNotBlank()) {
            "No model is configured for role " + agent.name + " / " + routeKind.displayName + "."
        }
        val routeEndpoint = profile.endpoint.ifBlank { defaultProviderEndpoint(routeKind) }
        require(localRoute || routeEndpoint.isNotBlank()) {
            "No endpoint is configured for role " + agent.name + " / " + routeKind.displayName + "."
        }
        val routeKey = credentialVault.get(
            AndroidCredentialVault.providerKeyId(routeKind.name)
        ).orEmpty()
        require(!routeKind.requiresApiKey() || routeKey.isNotBlank()) {
            "No API key is saved for role " + agent.name + " / " + routeKind.displayName + "."
        }
        return CouncilModelRoute(
            gateway = engineRegistry.gateway(routeKind),
            endpoint = routeEndpoint,
            model = routeModel,
            apiKey = routeKey
        )
    }

    data class FinalDecision(
        val finalStatus: MissionStatus,
        val answer: String,
        val statusLine: String
    )

    companion object {

        /** Pure mapping of a council run to its final mission status and user-facing texts. */
        fun decide(result: CouncilRunResult): FinalDecision {
            val statusLine = result.error ?: if (result.completed) {
                "Council завершил миссию: " +
                    result.agentResults.count { it.status == AgentRunStatus.COMPLETED } +
                    " агентов успешно."
            } else {
                "Council не смог сформировать итог."
            }
            return when {
                result.needsUserInput -> FinalDecision(
                    MissionStatus.AWAITING_INPUT,
                    result.error ?: "The local Council needs additional input to continue.",
                    statusLine
                )
                result.paused -> FinalDecision(
                    MissionStatus.INTERRUPTED,
                    result.error ?: "The local Council paused and can be resumed later.",
                    statusLine
                )
                result.synthesis.isNullOrBlank() -> FinalDecision(
                    MissionStatus.FAILED,
                    fallbackAnswer(result),
                    statusLine
                )
                else -> FinalDecision(MissionStatus.COMPLETED, result.synthesis, statusLine)
            }
        }

        /** The agents' own stored outputs, never an invented summary. */
        fun fallbackAnswer(result: CouncilRunResult): String =
            if (result.agentResults.isEmpty()) "" else
                result.agentResults.joinToString("\n\n") {
                    "## ${it.agent.name}\n${it.output.ifBlank { it.error.orEmpty() }}"
                }
    }
}
