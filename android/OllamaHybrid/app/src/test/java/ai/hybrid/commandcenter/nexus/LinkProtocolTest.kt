package ai.hybrid.commandcenter.nexus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkProtocolTest {

    private val startedAt = 1_000_000L
    private val code = "123456"

    private fun request(
        scopes: Set<LinkProtocol.Scope> = setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.MCP_TOOLS),
        nonce: String = "a".repeat(32)
    ) = LinkProtocol.PairingRequest("Телефон", nonce, scopes)

    private fun offer(
        scopes: Set<LinkProtocol.Scope> = setOf(LinkProtocol.Scope.COUNCIL),
        nonce: String = "b".repeat(32),
        fingerprint: String = "f".repeat(64)
    ) = LinkProtocol.PairingOffer("ПК", nonce, scopes, fingerprint)

    private fun pair(
        request: LinkProtocol.PairingRequest = request(),
        offer: LinkProtocol.PairingOffer = offer(),
        localCode: String = code,
        remoteCode: String = code,
        now: Long = startedAt + 5_000
    ) = LinkProtocol.completePairing(request, offer, localCode, remoteCode, startedAt, now)

    @Test
    fun `matching codes and fresh nonces pair successfully`() {
        val result = pair()
        assertTrue(result is LinkProtocol.PairingResult.Paired)
        val paired = result as LinkProtocol.PairingResult.Paired
        assertEquals(setOf(LinkProtocol.Scope.COUNCIL), paired.scopes)
        assertEquals(64, paired.sessionKey.length)
    }

    @Test
    fun `mismatched codes are rejected`() {
        val result = pair(remoteCode = "654321")
        assertTrue(result is LinkProtocol.PairingResult.Rejected)
        assertTrue((result as LinkProtocol.PairingResult.Rejected).reason.contains("не совпадают"))
    }

    @Test
    fun `expired pairing window is rejected`() {
        val result = pair(now = startedAt + LinkProtocol.PAIRING_WINDOW_MILLIS + 1)
        assertTrue(result is LinkProtocol.PairingResult.Rejected)
        assertTrue((result as LinkProtocol.PairingResult.Rejected).reason.contains("истекло"))
    }

    @Test
    fun `replayed identical nonces are rejected`() {
        val shared = "c".repeat(32)
        val result = pair(request = request(nonce = shared), offer = offer(nonce = shared))
        assertTrue(result is LinkProtocol.PairingResult.Rejected)
        assertTrue((result as LinkProtocol.PairingResult.Rejected).reason.contains("повтора"))
    }

    @Test
    fun `agent without a certificate fingerprint is rejected`() {
        val result = pair(offer = offer(fingerprint = "short"))
        assertTrue(result is LinkProtocol.PairingResult.Rejected)
    }

    @Test
    fun `agent cannot grant scopes the phone never requested`() {
        val result = pair(
            request = request(scopes = setOf(LinkProtocol.Scope.COUNCIL)),
            offer = offer(scopes = setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.WORKSPACE_WRITE))
        )
        assertTrue(result is LinkProtocol.PairingResult.Rejected)
        assertTrue((result as LinkProtocol.PairingResult.Rejected).reason.contains("не запрашивал"))
    }

    @Test
    fun `an agent granting nothing is rejected`() {
        assertTrue(pair(offer = offer(scopes = emptySet())) is LinkProtocol.PairingResult.Rejected)
    }

    @Test
    fun `session key binds code, both nonces and the certificate`() {
        val base = LinkProtocol.deriveSessionKey(code, "a".repeat(32), "b".repeat(32), "f".repeat(64))
        assertNotEquals(base, LinkProtocol.deriveSessionKey("654321", "a".repeat(32), "b".repeat(32), "f".repeat(64)))
        assertNotEquals(base, LinkProtocol.deriveSessionKey(code, "z".repeat(32), "b".repeat(32), "f".repeat(64)))
        assertNotEquals(base, LinkProtocol.deriveSessionKey(code, "a".repeat(32), "z".repeat(32), "f".repeat(64)))
        assertNotEquals(base, LinkProtocol.deriveSessionKey(code, "a".repeat(32), "b".repeat(32), "0".repeat(64)))
    }

    @Test
    fun `deny by default - nothing is allowed without a granted scope`() {
        assertTrue(!LinkProtocol.isAllowed(emptySet(), "council"))
        assertTrue(LinkProtocol.isAllowed(setOf(LinkProtocol.Scope.COUNCIL), "council"))
        assertTrue(!LinkProtocol.isAllowed(setOf(LinkProtocol.Scope.COUNCIL), "build_jobs"))
    }

    @Test
    fun `forbidden capabilities can never be granted or invoked`() {
        for (forbidden in LinkProtocol.Scope.FORBIDDEN_IDS) {
            assertNull("Запрещённая возможность $forbidden не должна существовать", LinkProtocol.Scope.byId(forbidden))
            assertTrue(LinkProtocol.parseScopes(listOf(forbidden)).isEmpty())
            assertTrue(!LinkProtocol.isAllowed(LinkProtocol.Scope.entries.toSet(), forbidden))
        }
    }

    @Test
    fun `unknown scope ids are dropped during parsing`() {
        val parsed = LinkProtocol.parseScopes(listOf("council", "teleport", "SHELL", " mcp_tools "))
        assertEquals(setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.MCP_TOOLS), parsed)
    }

    @Test
    fun `generated codes and nonces have the declared shape`() {
        val generated = LinkProtocol.newPairingCode()
        assertEquals(LinkProtocol.PAIRING_CODE_DIGITS, generated.length)
        assertTrue(generated.all(Char::isDigit))
        assertEquals(LinkProtocol.NONCE_BYTES * 2, LinkProtocol.newNonce().length)
        assertNotEquals(LinkProtocol.newNonce(), LinkProtocol.newNonce())
    }

    @Test
    fun `request json carries protocol version and requested scopes`() {
        val json = JSONObject(LinkProtocol.pairingRequestJson(request()))
        assertEquals("nexus-link", json.getString("protocol"))
        assertEquals(LinkProtocol.VERSION, json.getInt("version"))
        assertEquals(2, json.getJSONArray("scopes").length())
    }

    @Test
    fun `offers from another protocol version are refused`() {
        val wrongVersion = """{"version":99,"agent":"ПК","nonce":"b","scopes":["council"],"fingerprint":"f"}"""
        assertNull(LinkProtocol.parseOffer(wrongVersion))
        assertNull(LinkProtocol.parseOffer("not json"))
    }

    @Test
    fun `parsed offer keeps only legitimate scopes`() {
        val json = """{"version":1,"agent":"ПК","nonce":"${"b".repeat(32)}",
            "scopes":["council","shell","cookies","build_jobs"],"fingerprint":"${"f".repeat(64)}"}"""
        val parsed = LinkProtocol.parseOffer(json)!!
        assertEquals(setOf(LinkProtocol.Scope.COUNCIL, LinkProtocol.Scope.BUILD_JOBS), parsed.grantedScopes)
    }
}
