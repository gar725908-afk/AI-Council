package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderHealthCheckTest {

    @Test
    fun `blank endpoint cannot be probed`() {
        assertNull(ProviderHealthCheck.probeUrl(""))
        assertNull(ProviderHealthCheck.probeUrl("   "))
    }

    @Test
    fun `bare host gets https scheme`() {
        assertEquals("https://api.openai.com", ProviderHealthCheck.probeUrl("api.openai.com"))
    }

    @Test
    fun `existing scheme and path are preserved without trailing slash`() {
        assertEquals(
            "https://api.openai.com/v1",
            ProviderHealthCheck.probeUrl("https://api.openai.com/v1/")
        )
        assertEquals(
            "http://192.168.1.10:11434",
            ProviderHealthCheck.probeUrl("http://192.168.1.10:11434/")
        )
    }

    @Test
    fun `non-http schemes are rejected`() {
        assertNull(ProviderHealthCheck.probeUrl("ftp://host"))
        assertNull(ProviderHealthCheck.probeUrl("file:///etc/passwd"))
    }

    @Test
    fun `control characters and spaces inside the address are rejected`() {
        assertNull(ProviderHealthCheck.probeUrl("https://ho\nst"))
        assertNull(ProviderHealthCheck.probeUrl("https://ho st"))
    }

    @Test
    fun `surrounding whitespace is trimmed, not rejected`() {
        assertEquals("https://host", ProviderHealthCheck.probeUrl("  https://host/\n"))
    }

    @Test
    fun `oversized endpoint is rejected`() {
        val long = "https://" + "a".repeat(2100)
        assertNull(ProviderHealthCheck.probeUrl(long))
    }

    @Test
    fun `success codes classify as reachable`() {
        val (reachability, detail) = ProviderHealthCheck.classify(200)
        assertEquals(EndpointReachability.REACHABLE, reachability)
        assertTrue(detail.contains("200"))
    }

    @Test
    fun `auth challenge still proves reachability and says the key was not tested`() {
        val (reachability, detail) = ProviderHealthCheck.classify(401)
        assertEquals(EndpointReachability.REACHABLE, reachability)
        assertTrue(detail.contains("ключ", ignoreCase = true))
    }

    @Test
    fun `not found means host answered`() {
        val (reachability, _) = ProviderHealthCheck.classify(404)
        assertEquals(EndpointReachability.REACHABLE, reachability)
    }

    @Test
    fun `server errors are reachable but honestly labeled`() {
        val (reachability, detail) = ProviderHealthCheck.classify(503)
        assertEquals(EndpointReachability.REACHABLE, reachability)
        assertTrue(detail.contains("503"))
        assertTrue(detail.contains("ошибк", ignoreCase = true))
    }
}
