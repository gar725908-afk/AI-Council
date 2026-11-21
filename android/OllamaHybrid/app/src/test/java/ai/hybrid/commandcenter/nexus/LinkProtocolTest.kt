package ai.hybrid.commandcenter.nexus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom

class LinkProtocolTest {

    private val agentKey = "a1".repeat(32)
    private val otherKey = "b2".repeat(32)
    private val now = 1_000_000L

    private fun request(
        scopes: Set<LinkProtocol.Scope> = setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.MCP_TOOLS)
    ) = LinkProtocol.ScopeRequest("Телефон", scopes)

    private fun answer(vararg scopes: String, extra: String = "") =
        """{"protocol":"nexus-link","version":2,"agent":"ПК","scopes":[${scopes.joinToString(",") { "\"$it\"" }}]$extra}"""

    private fun grant(vararg scopes: String, session: String = agentKey) =
        LinkProtocol.parseGrant(answer(*scopes), session, now)!!

    /**
     * Why v1 was removed (audit, priority 1). v1: key = SHA-256(code | nonces | fingerprint),
     * code = six digits, nonces and fingerprint sent in clear. Whoever records one
     * exchange plus anything the key protects finds the code by trying all 10^6.
     */
    @Test
    fun `v1 pin handshake falls to an offline search over all codes`() {
        fun v1Key(code: String, phoneNonce: String, agentNonce: String, fingerprint: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest("nexus-link:v1:$code:$phoneNonce:$agentNonce:$fingerprint".toByteArray())

        val random = SecureRandom()
        val secretCode = String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000))
        val phoneNonce = "3f".repeat(16)
        val agentNonce = "9c".repeat(16)
        // what a listener on the LAN records: both nonces, the fingerprint, and a key-confirmation value
        val observed = MessageDigest.getInstance("SHA-256").digest(v1Key(secretCode, phoneNonce, agentNonce, agentKey))

        val started = System.nanoTime()
        var found: String? = null
        var tries = 0
        val sha = MessageDigest.getInstance("SHA-256")
        for (candidate in 0 until 1_000_000) {
            tries++
            val code = String.format(java.util.Locale.ROOT, "%06d", candidate)
            val key = sha.digest("nexus-link:v1:$code:$phoneNonce:$agentNonce:$agentKey".toByteArray())
            if (MessageDigest.isEqual(sha.digest(key), observed)) {
                found = code
                break
            }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("LINK v1: code $secretCode recovered offline after $tries tries in $millis ms on one CPU core")
        assertEquals("the whole code space is searchable", secretCode, found)
    }

    @Test
    fun `the pin handshake is gone from v2`() {
        val names = LinkProtocol::class.java.declaredMethods.map { it.name }.toSet()
        for (removed in listOf("deriveSessionKey", "newPairingCode", "completePairing", "pairingRequestJson", "parseOffer")) {
            assertFalse("$removed must not exist in LINK v2", removed in names)
        }
        assertEquals(2, LinkProtocol.VERSION)
    }

    @Test
    fun `a grant is accepted only from an agent paired through NEXUS Mesh`() {
        val result = LinkProtocol.acceptGrant(request(), grant("council"), meshTrusted = false)
        assertTrue(result is LinkProtocol.GrantResult.Rejected)
        assertTrue((result as LinkProtocol.GrantResult.Rejected).reason.contains("NEXUS Mesh"))
        val accepted = LinkProtocol.acceptGrant(request(), grant("council"), meshTrusted = true)
        assertTrue(accepted is LinkProtocol.GrantResult.Accepted)
        assertEquals(setOf(LinkProtocol.Scope.COUNCIL), (accepted as LinkProtocol.GrantResult.Accepted).grant.scopes)
    }

    @Test
    fun `the agent's identity is its Mesh session key, not what the message claims`() {
        val parsed = LinkProtocol.parseGrant(answer("council", extra = ""","fingerprint":"$otherKey""""), agentKey.uppercase(), now)!!
        assertEquals(agentKey, parsed.agentFingerprint)
    }

    @Test
    fun `a grant is worthless on another device's session`() {
        val council = grant("council")
        assertTrue(LinkProtocol.isAllowed(council, agentKey, "council"))
        assertFalse(LinkProtocol.isAllowed(council, otherKey, "council"))
        assertFalse(LinkProtocol.isAllowed(null, agentKey, "council"))
    }

    @Test
    fun `agent without a pinned key is rejected`() {
        val result = LinkProtocol.acceptGrant(request(), grant("council", session = "short"), meshTrusted = true)
        assertTrue(result is LinkProtocol.GrantResult.Rejected)
    }

    @Test
    fun `agent cannot grant scopes the phone never requested`() {
        val result = LinkProtocol.acceptGrant(
            request(setOf(LinkProtocol.Scope.COUNCIL)), grant("council", "workspace_write"), meshTrusted = true
        )
        assertTrue(result is LinkProtocol.GrantResult.Rejected)
        assertTrue((result as LinkProtocol.GrantResult.Rejected).reason.contains("не запрашивал"))
    }

    @Test
    fun `an agent granting nothing is rejected`() {
        assertTrue(LinkProtocol.acceptGrant(request(), grant(), meshTrusted = true) is LinkProtocol.GrantResult.Rejected)
    }

    @Test
    fun `dangerous scopes are singled out for their own confirmation`() {
        assertEquals(
            setOf(LinkProtocol.Scope.WORKSPACE_WRITE, LinkProtocol.Scope.BUILD_JOBS),
            LinkProtocol.needsConfirmation(
                setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.WORKSPACE_WRITE, LinkProtocol.Scope.BUILD_JOBS)
            )
        )
        assertTrue(LinkProtocol.needsConfirmation(setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.WORKSPACE_READ)).isEmpty())
    }

    @Test
    fun `deny by default - nothing is allowed without a granted scope`() {
        assertFalse(LinkProtocol.isAllowed(emptySet(), "council"))
        assertTrue(LinkProtocol.isAllowed(setOf(LinkProtocol.Scope.COUNCIL), "council"))
        assertFalse(LinkProtocol.isAllowed(setOf(LinkProtocol.Scope.COUNCIL), "build_jobs"))
    }

    @Test
    fun `forbidden capabilities can never be granted or invoked`() {
        for (forbidden in LinkProtocol.Scope.FORBIDDEN_IDS) {
            assertNull("Запрещённая возможность $forbidden не должна существовать", LinkProtocol.Scope.byId(forbidden))
            assertTrue(LinkProtocol.parseScopes(listOf(forbidden)).isEmpty())
            assertFalse(LinkProtocol.isAllowed(LinkProtocol.Scope.entries.toSet(), forbidden))
        }
    }

    @Test
    fun `unknown scope ids are dropped during parsing`() {
        val parsed = LinkProtocol.parseScopes(listOf("council", "teleport", "SHELL", " mcp_tools "))
        assertEquals(setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.MCP_TOOLS), parsed)
    }

    @Test
    fun `request json carries protocol, version and requested scopes`() {
        val json = JSONObject(LinkProtocol.requestJson(request()))
        assertEquals("nexus-link", json.getString("protocol"))
        assertEquals(LinkProtocol.VERSION, json.getInt("version"))
        assertEquals(2, json.getJSONArray("scopes").length())
        assertFalse("no nonce or code travels in v2", json.has("nonce") || json.has("code"))
    }

    @Test
    fun `answers from another protocol or version are refused`() {
        assertNull(LinkProtocol.parseGrant("""{"protocol":"nexus-link","version":1,"agent":"ПК","scopes":["council"]}""", agentKey, now))
        assertNull(LinkProtocol.parseGrant("""{"protocol":"other","version":2,"scopes":["council"]}""", agentKey, now))
        assertNull(LinkProtocol.parseGrant("not json", agentKey, now))
    }

    @Test
    fun `parsed grant keeps only legitimate scopes`() {
        val parsed = grant("council", "shell", "cookies", "build_jobs")
        assertNotNull(parsed)
        assertEquals(setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.BUILD_JOBS), parsed.scopes)
    }
}
