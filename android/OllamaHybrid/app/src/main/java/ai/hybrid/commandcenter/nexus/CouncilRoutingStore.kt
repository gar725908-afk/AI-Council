package ai.hybrid.commandcenter.nexus

import android.content.Context

data class CouncilAgentRoute(
    val providerKind: ProviderKind,
    val modelOverride: String? = null
)

/**
 * Stores per-agent provider and optional model overrides.
 * API keys remain in AndroidCredentialVault and are never copied into route preferences.
 */
class CouncilRoutingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(agentId: String): ProviderKind? =
        preferences.getString(providerKey(agentId), null)
            ?.let { saved -> runCatching { ProviderKind.valueOf(saved) }.getOrNull() }

    fun loadModelOverride(agentId: String): String? =
        preferences.getString(modelKey(agentId), null)?.takeIf { it.isNotBlank() }

    fun loadRoute(agentId: String, fallback: ProviderKind): CouncilAgentRoute =
        CouncilAgentRoute(
            providerKind = load(agentId) ?: fallback,
            modelOverride = loadModelOverride(agentId)
        )

    fun save(agentId: String, providerKind: ProviderKind) {
        requireValidId(agentId)
        preferences.edit()
            .putString(providerKey(agentId), providerKind.name)
            .apply()
    }

    fun saveModelOverride(agentId: String, model: String?) {
        requireValidId(agentId)
        val editor = preferences.edit()
        if (model.isNullOrBlank()) {
            editor.remove(modelKey(agentId))
        } else {
            editor.putString(modelKey(agentId), model.trim())
        }
        editor.apply()
    }

    fun saveRoute(agentId: String, route: CouncilAgentRoute) {
        requireValidId(agentId)
        preferences.edit()
            .putString(providerKey(agentId), route.providerKind.name)
            .apply {
                if (route.modelOverride.isNullOrBlank()) remove(modelKey(agentId))
                else putString(modelKey(agentId), route.modelOverride.trim())
            }
            .apply()
    }

    fun clear(agentId: String) {
        requireValidId(agentId)
        preferences.edit()
            .remove(providerKey(agentId))
            .remove(modelKey(agentId))
            .apply()
    }

    private fun providerKey(agentId: String) = KEY_PREFIX + agentId + ".provider"
    private fun modelKey(agentId: String) = KEY_PREFIX + agentId + ".model"

    private fun requireValidId(agentId: String) {
        require(agentId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) {
            "Invalid Council agent identifier."
        }
    }

    companion object {
        private const val PREFS_NAME = "nexus_council_routes"
        private const val KEY_PREFIX = "agent."
    }
}
