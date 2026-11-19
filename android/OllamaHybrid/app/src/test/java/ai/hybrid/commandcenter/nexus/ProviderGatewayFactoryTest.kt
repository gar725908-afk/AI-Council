package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderGatewayFactoryTest {

    @Test
    fun createsOpenAiCompatibleAdapter() {
        assertTrue(createModelGateway(ProviderKind.OPENAI_COMPATIBLE) is OpenAiCompatibleGateway)
    }

    @Test
    fun compatibleCloudProvidersUseSharedProtocolAdapter() {
        val compatibleProviders = listOf(
            ProviderKind.OPENAI,
            ProviderKind.DEEPSEEK,
            ProviderKind.OPENROUTER,
            ProviderKind.GROQ,
            ProviderKind.MISTRAL,
            ProviderKind.TOGETHER,
            ProviderKind.FIREWORKS,
            ProviderKind.XAI,
            ProviderKind.CEREBRAS,
            ProviderKind.NVIDIA
        )
        compatibleProviders.forEach { provider ->
            assertTrue(createModelGateway(provider) is OpenAiCompatibleGateway)
        }
    }

    @Test
    fun ollamaUsesTheNativeLanGateway() {
        assertTrue(createModelGateway(ProviderKind.OLLAMA) is OllamaNativeGateway)
    }

    @Test
    fun cloudProfilesRequireKeysButLocalProfilesDoNot() {
        assertTrue(ProviderKind.OPENAI.requiresApiKey())
        assertTrue(ProviderKind.DEEPSEEK.requiresApiKey())
        assertTrue(ProviderKind.XAI.requiresApiKey())
        assertTrue(ProviderKind.CEREBRAS.requiresApiKey())
        assertTrue(ProviderKind.NVIDIA.requiresApiKey())
        assertTrue(ProviderKind.ANTHROPIC.requiresApiKey())
        assertTrue(!ProviderKind.OLLAMA.requiresApiKey())
        assertTrue(!ProviderKind.OPENAI_COMPATIBLE.requiresApiKey())
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
