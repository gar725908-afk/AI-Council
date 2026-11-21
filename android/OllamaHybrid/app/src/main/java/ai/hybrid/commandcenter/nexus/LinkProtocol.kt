package ai.hybrid.commandcenter.nexus

import org.json.JSONArray
import org.json.JSONObject

/**
 * NEXUS LINK v2 — what a paired desktop agent lets this phone do
 * (handoff section 10.4 / LFC-4).
 *
 * LINK has no pairing and no cryptography of its own. Version 1 derived a session
 * key from a six-digit code with a single SHA-256; anyone who recorded that
 * exchange could try all 10^6 codes offline in about a second (LinkProtocolTest
 * shows it). v1 never had a transport or a desktop agent, so nothing was exposed,
 * and it is removed rather than patched.
 *
 * In v2 a desktop agent is simply a device in NEXUS Mesh, the one private network
 * all NEXUS services share: it pairs exactly like a phone (128-bit invite secret,
 * mutual TLS with pinned keys, HMAC proof, commit–reveal six-digit code confirmed
 * on both screens). LINK is only the authorisation layer on top of that channel:
 * - **deny-by-default**: nothing until the agent grants a named scope; shell,
 *   cookies, the home directory and credentials have no scope and can never be
 *   requested or invoked;
 * - **identity comes from the channel**: a grant belongs to the pinned Mesh key
 *   of the session it arrived on, never to a fingerprint written in the message,
 *   and it is worthless on any other device's session;
 * - an agent can only narrow what the phone asked for; dangerous scopes are asked
 *   for only after a separate confirmation in the UI;
 * - every decision is a pure function, testable without two devices.
 *
 * The desktop agent is a separate program and is not written yet; this file is
 * the phone side plus the message formats both sides must agree on.
 */
object LinkProtocol {

    const val VERSION = 2
    const val PROTOCOL = "nexus-link"

    /** Capabilities a desktop agent may publish. Nothing outside this list exists. */
    enum class Scope(val id: String, val title: String, val dangerous: Boolean) {
        COUNCIL("council", "AI Consilium на ПК", false),
        MCP_TOOLS("mcp_tools", "MCP-инструменты ПК", false),
        WORKSPACE_READ("workspace_read", "Чтение рабочей папки", false),
        WORKSPACE_WRITE("workspace_write", "Запись в рабочую папку", true),
        BUILD_JOBS("build_jobs", "Сборочные задания", true),
        BROWSER_AUTOMATION("browser_automation", "Автоматизация браузера", true);

        companion object {
            fun byId(id: String): Scope? = entries.firstOrNull { it.id == id }

            /** Requests that must never be honoured, whatever a peer claims. */
            val FORBIDDEN_IDS = setOf(
                "shell", "powershell", "cmd", "exec", "cookies", "home", "filesystem",
                "root", "keychain", "credentials", "ssh"
            )
        }
    }

    /** What the phone asks a paired agent for. */
    data class ScopeRequest(val deviceName: String, val requestedScopes: Set<Scope>)

    /** What an agent granted, bound to the pinned NEXUS Mesh key of the session it came on. */
    data class Grant(val agentName: String, val agentFingerprint: String, val scopes: Set<Scope>, val receivedAt: Long)

    sealed class GrantResult {
        data class Accepted(val grant: Grant) : GrantResult()
        data class Rejected(val reason: String) : GrantResult()
    }

    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    /** Only known, non-forbidden scope ids survive parsing. */
    fun parseScopes(ids: Collection<String>): Set<Scope> = ids
        .map { it.trim().lowercase() }
        .filterNot { it in Scope.FORBIDDEN_IDS }
        .mapNotNull(Scope::byId)
        .toSet()

    /** Scopes the owner must confirm one by one before the phone asks for them. */
    fun needsConfirmation(scopes: Set<Scope>): Set<Scope> = scopes.filter { it.dangerous }.toSet()

    fun requestJson(request: ScopeRequest): String = JSONObject()
        .put("protocol", PROTOCOL)
        .put("version", VERSION)
        .put("device", request.deviceName.take(64))
        .put("scopes", JSONArray().apply { request.requestedScopes.forEach { put(it.id) } })
        .toString()

    /**
     * An agent's answer as it arrived on the Mesh session whose pinned key is
     * [sessionFingerprint]. Any fingerprint written inside the message is ignored:
     * the identity is the channel's. Null for another protocol or version, or garbage.
     */
    fun parseGrant(json: String, sessionFingerprint: String, nowMillis: Long): Grant? = try {
        val parsed = JSONObject(json)
        if (parsed.optString("protocol") != PROTOCOL || parsed.optInt("version") != VERSION) null
        else {
            val ids = parsed.optJSONArray("scopes")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty()
            Grant(
                agentName = parsed.optString("agent").take(64),
                agentFingerprint = sessionFingerprint.lowercase(),
                scopes = parseScopes(ids),
                receivedAt = nowMillis
            )
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Accepts a grant only from a device the owner paired through NEXUS Mesh,
     * and only if it narrows what the phone asked for.
     */
    fun acceptGrant(request: ScopeRequest, grant: Grant, meshTrusted: Boolean): GrantResult = when {
        !meshTrusted ->
            GrantResult.Rejected("ПК не сопряжён через NEXUS Mesh — сначала сверь код на обоих экранах.")
        !FINGERPRINT.matches(grant.agentFingerprint) ->
            GrantResult.Rejected("У ПК нет закреплённого ключа NEXUS Mesh.")
        grant.scopes.isEmpty() ->
            GrantResult.Rejected("ПК не выдал ни одной разрешённой возможности.")
        !request.requestedScopes.containsAll(grant.scopes) ->
            GrantResult.Rejected("ПК выдал возможности, которые телефон не запрашивал.")
        else -> GrantResult.Accepted(grant)
    }

    /** Deny-by-default check before every call to the agent behind the session with [sessionFingerprint]. */
    fun isAllowed(grant: Grant?, sessionFingerprint: String, requestedScopeId: String): Boolean {
        if (grant == null || grant.agentFingerprint != sessionFingerprint.lowercase()) return false
        return isAllowed(grant.scopes, requestedScopeId)
    }

    /** The scope-only part of the check: forbidden and unknown ids never pass. */
    fun isAllowed(grantedScopes: Set<Scope>, requestedScopeId: String): Boolean {
        val normalized = requestedScopeId.trim().lowercase()
        if (normalized in Scope.FORBIDDEN_IDS) return false
        val scope = Scope.byId(normalized) ?: return false
        return scope in grantedScopes
    }
}
