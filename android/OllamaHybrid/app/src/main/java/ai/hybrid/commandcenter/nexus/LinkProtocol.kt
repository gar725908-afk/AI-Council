package ai.hybrid.commandcenter.nexus

import java.security.MessageDigest
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * NEXUS LINK protocol core (handoff section 10.4 / LFC-4).
 *
 * Design rules taken straight from the specification and enforced here:
 * - **deny-by-default**: a peer may invoke nothing until the user grants a
 *   named scope; arbitrary shell, cookies and the home directory have no
 *   scope at all and can never be requested;
 * - **mutual pairing**: a short code is shown on both devices and must match;
 *   the resulting secret is derived from the code plus both nonces, so a
 *   passive listener cannot replay it;
 * - **LAN only**: no public relay, no cloud account;
 * - every decision is a pure function, so the whole handshake is testable
 *   without two physical devices.
 *
 * The desktop agent is a separate program; this file is the phone side plus
 * the shared message formats both sides must agree on.
 */
object LinkProtocol {

    const val VERSION = 1
    const val SERVICE_TYPE = "_nexus-link._tcp"
    const val PAIRING_CODE_DIGITS = 6
    const val PAIRING_WINDOW_MILLIS = 120_000L
    const val NONCE_BYTES = 16

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

    data class PairingRequest(
        val deviceName: String,
        val phoneNonce: String,
        val requestedScopes: Set<Scope>
    )

    data class PairingOffer(
        val agentName: String,
        val agentNonce: String,
        val grantedScopes: Set<Scope>,
        val certificateFingerprint: String
    )

    sealed class PairingResult {
        data class Paired(val sessionKey: String, val scopes: Set<Scope>) : PairingResult()
        data class Rejected(val reason: String) : PairingResult()
    }

    private val random = SecureRandom()

    fun newNonce(): String = ByteArray(NONCE_BYTES)
        .also(random::nextBytes)
        .joinToString("") { "%02x".format(it) }

    /** A short code the user compares on both screens. */
    fun newPairingCode(): String = (0 until PAIRING_CODE_DIGITS)
        .joinToString("") { random.nextInt(10).toString() }

    /** Only known, non-forbidden scope ids survive parsing. */
    fun parseScopes(ids: Collection<String>): Set<Scope> = ids
        .map { it.trim().lowercase() }
        .filterNot { it in Scope.FORBIDDEN_IDS }
        .mapNotNull(Scope::byId)
        .toSet()

    /**
     * Completes pairing. Everything must line up: matching codes, a fresh
     * window, both nonces, a certificate fingerprint and at least one scope
     * the user actually granted.
     */
    fun completePairing(
        request: PairingRequest,
        offer: PairingOffer,
        localCode: String,
        remoteCode: String,
        startedAtMillis: Long,
        nowMillis: Long
    ): PairingResult = when {
        localCode.length != PAIRING_CODE_DIGITS || !localCode.all(Char::isDigit) ->
            PairingResult.Rejected("Код сопряжения имеет неверный формат.")
        localCode != remoteCode ->
            PairingResult.Rejected("Коды на телефоне и компьютере не совпадают — сопряжение отклонено.")
        nowMillis - startedAtMillis > PAIRING_WINDOW_MILLIS ->
            PairingResult.Rejected("Время сопряжения истекло. Начните заново.")
        nowMillis < startedAtMillis ->
            PairingResult.Rejected("Некорректное время сопряжения.")
        request.phoneNonce.length < NONCE_BYTES * 2 || offer.agentNonce.length < NONCE_BYTES * 2 ->
            PairingResult.Rejected("Недостаточно случайности в рукопожатии.")
        request.phoneNonce == offer.agentNonce ->
            PairingResult.Rejected("Обе стороны прислали одинаковый nonce — возможна атака повтора.")
        offer.certificateFingerprint.length < 32 ->
            PairingResult.Rejected("ПК не предъявил отпечаток сертификата.")
        offer.grantedScopes.isEmpty() ->
            PairingResult.Rejected("ПК не выдал ни одной разрешённой возможности.")
        !request.requestedScopes.containsAll(offer.grantedScopes) ->
            PairingResult.Rejected("ПК выдал возможности, которые телефон не запрашивал.")
        else -> PairingResult.Paired(
            sessionKey = deriveSessionKey(localCode, request.phoneNonce, offer.agentNonce, offer.certificateFingerprint),
            scopes = offer.grantedScopes
        )
    }

    /** Session key binds the code, both nonces and the peer certificate. */
    fun deriveSessionKey(code: String, phoneNonce: String, agentNonce: String, fingerprint: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(("nexus-link:v$VERSION:$code:$phoneNonce:$agentNonce:$fingerprint").toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** Deny-by-default check performed before every invocation. */
    fun isAllowed(grantedScopes: Set<Scope>, requestedScopeId: String): Boolean {
        val normalized = requestedScopeId.trim().lowercase()
        if (normalized in Scope.FORBIDDEN_IDS) return false
        val scope = Scope.byId(normalized) ?: return false
        return scope in grantedScopes
    }

    fun pairingRequestJson(request: PairingRequest): String = JSONObject()
        .put("protocol", "nexus-link")
        .put("version", VERSION)
        .put("device", request.deviceName.take(64))
        .put("nonce", request.phoneNonce)
        .put("scopes", JSONArray().apply { request.requestedScopes.forEach { put(it.id) } })
        .toString()

    fun parseOffer(json: String): PairingOffer? = try {
        val parsed = JSONObject(json)
        if (parsed.optInt("version") != VERSION) null
        else {
            val ids = parsed.optJSONArray("scopes")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty()
            PairingOffer(
                agentName = parsed.optString("agent").take(64),
                agentNonce = parsed.optString("nonce"),
                grantedScopes = parseScopes(ids),
                certificateFingerprint = parsed.optString("fingerprint")
            )
        }
    } catch (_: Exception) {
        null
    }
}
