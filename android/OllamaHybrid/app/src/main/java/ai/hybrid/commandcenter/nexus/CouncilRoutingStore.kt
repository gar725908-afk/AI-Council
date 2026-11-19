package ai.hybrid.commandcenter.nexus

import android.content.Context

/**
 * Stores only the provider assignment per Council agent.
 * Model settings and secrets remain in their own provider-specific stores.
 */
class CouncilRoutingStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    fun load(agentId: String): ProviderKind? =
        preferences.getString(KEY_PREFIX + agentId, null)
            ?.let { saved -> runCatching { ProviderKind.valueOf(saved) }.getOrNull() }

    fun save(agentId: String, providerKind: ProviderKind) {
        require(agentId.matches(Regex("[A-Za-z0-9._-]{1,80}"))) { "Invalid Council agent identifier." }
        preferences.edit()
            .putString(KEY_PREFIX + agentId, providerKind.name)
            .apply()
    }

    fun clear(agentId: String) {
        preferences.edit().remove(KEY_PREFIX + agentId).apply()
    }

    companion object {
        private const val PREFS_NAME = "nexus_council_routes"
        private const val KEY_PREFIX = "agent."
    }
}
