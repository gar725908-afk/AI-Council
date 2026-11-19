package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NexusEvolutionRoutingPolicyTest {
    @Test
    fun parsesKnownRolesAndExactProviderNames() {
        val parsed = NexusEvolutionRoutingPolicy.parse(
            """{"version":1,"routes":{"research":"OPENAI","technical":"OLLAMA"},"modelOverrides":{"research":"gpt-4.1-mini"}}"""
        )

        assertEquals(ProviderKind.OPENAI, parsed.routes["research"])
        assertEquals(ProviderKind.OLLAMA, parsed.routes["technical"])
        assertEquals("gpt-4.1-mini", parsed.modelOverrides["research"])
    }

    @Test
    fun rejectsMissingVersionUnknownFieldsAndUnknownRoles() {
        val invalid = listOf(
            """{"routes":{"research":"OPENAI"}}""",
            """{"version":1,"routes":{"research":"OPENAI"},"endpoint":"https://example.com"}""",
            """{"version":1,"routes":{"unknown-role":"OPENAI"}}"""
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                NexusEvolutionRoutingPolicy.parse(payload)
            }
        }
    }

    @Test
    fun rejectsWrongJsonTypesAndInvalidProviderValues() {
        val invalid = listOf(
            """{"version":"1","routes":{"research":"OPENAI"}}""",
            """{"version":1,"routes":{"research":7}}""",
            """{"version":1,"routes":{"research":"NOT_A_PROVIDER"}}""",
            """{"version":1,"routes":{},"modelOverrides":["model"]}""",
            """{"version":1,"routes":{},"modelOverrides":{"research":false}}"""
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                NexusEvolutionRoutingPolicy.parse(payload)
            }
        }
    }

    @Test
    fun rejectsOversizedAndControlCharacterModelIds() {
        val oversized = "x".repeat(201)
        val invalid = listOf(
            """{"version":1,"routes":{},"modelOverrides":{"research":"$oversized"}}""",
            """{"version":1,"routes":{},"modelOverrides":{"research":"bad\u0001id"}}"""
        )

        invalid.forEach { payload ->
            assertThrows(IllegalArgumentException::class.java) {
                NexusEvolutionRoutingPolicy.parse(payload)
            }
        }
    }
}
