package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertThrows
import org.junit.Test

class CompletionRequestValidationTest {
    @Test
    fun acceptsBoundedValidRequest() {
        CompletionRequest(
            endpoint = "https://api.example.com/v1",
            model = "model-1",
            apiKey = "key",
            messages = listOf(
                ProviderMessage("system", "Be precise."),
                ProviderMessage("user", "Hello.")
            ),
            temperature = 0.7
        )
    }

    @Test
    fun rejectsInvalidModelEndpointAndTemperature() {
        val messages = listOf(ProviderMessage("user", "hello"))
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("", "", "", messages)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("x".repeat(2_049), "model", "", messages)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("", "model", "", messages, Double.NaN)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("", "model", "", messages, 2.1)
        }
    }

    @Test
    fun rejectsUnsupportedRolesAndOversizedMessages() {
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("", "model", "", listOf(ProviderMessage("tool", "output")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("", "model", "", listOf(ProviderMessage("user", "x".repeat(65_537))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest(
                "",
                "model",
                "",
                listOf(ProviderMessage("user", "x".repeat(262_145)))
            )
        }
    }

    @Test
    fun rejectsExcessiveMessageCountAndCredentialSize() {
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest(
                "",
                "model",
                "",
                List(129) { ProviderMessage("user", "x") }
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionRequest("", "model", "k".repeat(16_385), listOf(ProviderMessage("user", "x")))
        }
    }
}
