package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderGatewayFactoryTest {

    @Test
    fun createsOpenAiCompatibleAdapter() {
        assertTrue(createModelGateway(ProviderKind.OPENAI_COMPATIBLE) is OpenAiCompatibleGateway)
    }

    @Test
    fun createsNativeAnthropicAdapter() {
        assertTrue(createModelGateway(ProviderKind.ANTHROPIC) is AnthropicMessagesGateway)
    }

    @Test
    fun createsNativeGeminiAdapter() {
        assertTrue(createModelGateway(ProviderKind.GEMINI) is GeminiGenerateContentGateway)
    }
}
