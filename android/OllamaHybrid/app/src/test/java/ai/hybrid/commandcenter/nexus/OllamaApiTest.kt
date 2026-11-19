package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OllamaApiTest {

    @Test
    fun `legacy v1 suffix is stripped from stored endpoints`() {
        assertEquals(
            "http://192.168.1.10:11434/api/chat",
            OllamaApi.chatUrl("http://192.168.1.10:11434/v1")
        )
        assertEquals(
            "http://192.168.1.10:11434/api/tags",
            OllamaApi.tagsUrl("http://192.168.1.10:11434/v1/")
        )
    }

    @Test
    fun `plain endpoint gets native paths`() {
        assertEquals(
            "http://192.168.1.10:11434/api/chat",
            OllamaApi.chatUrl("http://192.168.1.10:11434")
        )
    }

    @Test
    fun `content chunk line is parsed`() {
        val chunk = OllamaApi.parseChatLine(
            """{"model":"qwen3","message":{"role":"assistant","content":"Прив"},"done":false}"""
        )!!
        assertEquals("Прив", chunk.content)
        assertFalse(chunk.done)
        assertNull(chunk.error)
    }

    @Test
    fun `done line terminates the stream`() {
        val chunk = OllamaApi.parseChatLine(
            """{"model":"qwen3","message":{"role":"assistant","content":""},"done":true}"""
        )!!
        assertTrue(chunk.done)
        assertNull(chunk.content)
    }

    @Test
    fun `error line carries the server message`() {
        val chunk = OllamaApi.parseChatLine("""{"error":"model not found"}""")!!
        assertEquals("model not found", chunk.error)
    }

    @Test
    fun `partial or garbage lines never crash the parser`() {
        assertNull(OllamaApi.parseChatLine(""))
        assertNull(OllamaApi.parseChatLine("   "))
        assertNull(OllamaApi.parseChatLine("""{"model":"qwen3","message":{"role":"assist"""))
        assertNull(OllamaApi.parseChatLine("not json at all"))
    }

    @Test
    fun `tags payload yields model names in order`() {
        val names = OllamaApi.parseTags(
            """{"models":[{"name":"qwen3:0.6b","size":1},{"name":"llama3:8b"},{"name":""}]}"""
        )
        assertEquals(listOf("qwen3:0.6b", "llama3:8b"), names)
    }

    @Test
    fun `tags payload without models is empty not crashing`() {
        assertEquals(emptyList<String>(), OllamaApi.parseTags("""{"ok":true}"""))
    }

    @Test
    fun `tags list is bounded`() {
        val payload = buildString {
            append("""{"models":[""")
            repeat(10) { index ->
                if (index > 0) append(',')
                append("""{"name":"m$index"}""")
            }
            append("]}")
        }
        assertEquals(3, OllamaApi.parseTags(payload, maxModels = 3).size)
    }
}
