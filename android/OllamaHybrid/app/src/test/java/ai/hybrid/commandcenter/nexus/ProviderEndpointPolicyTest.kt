package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProviderEndpointPolicyTest {
    @Test
    fun allowsPrivateHttpAddressesAndHttps() {
        val privateEndpoints = listOf(
            "http://127.0.0.1:11434",
            "http://10.0.0.8:11434",
            "http://172.16.0.1:11434",
            "http://172.31.255.254:11434",
            "http://192.168.1.20:11434",
            "http://169.254.10.2:11434",
            "http://[::1]:11434",
            "http://[fc00::1]:11434",
            "http://[fd12:3456::1]:11434",
            "http://[fe80::1]:11434",
            "http://ollama.local:11434"
        )

        privateEndpoints.forEach { endpoint ->
            assertEquals(endpoint, "http", ProviderEndpointPolicy.base(endpoint).scheme)
        }
        assertEquals(
            "https",
            ProviderEndpointPolicy.base("https://api.example.com/v1").scheme
        )
    }

    @Test
    fun rejectsPublicOrMalformedCleartextHosts() {
        val rejected = listOf(
            "http://8.8.8.8:11434",
            "http://172.32.0.1:11434",
            "http://fc-example.com:11434",
            "http://fd.example.com:11434",
            "http://192.168.1.1.example.com:11434",
            "http://192.168.1x.1:11434",
            "http://example.com:11434"
        )

        rejected.forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                ProviderEndpointPolicy.base(endpoint)
            }
        }
    }

    @Test
    fun rejectsCredentialsQueryAndFragmentInBaseEndpoint() {
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointPolicy.base("https://user:secret@example.com/v1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointPolicy.base("https://example.com/v1?token=secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProviderEndpointPolicy.base("https://example.com/v1#fragment")
        }
    }
}
