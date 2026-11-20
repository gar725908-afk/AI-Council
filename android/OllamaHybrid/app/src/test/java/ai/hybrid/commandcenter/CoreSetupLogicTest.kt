package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.nexus.LanScanner
import ai.hybrid.commandcenter.nexus.ProviderKind
import ai.hybrid.commandcenter.nexus.defaultProviderEndpoint
import ai.hybrid.commandcenter.ui.HoloGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.math.abs

class CoreSetupLogicTest {

    @Test
    fun `ollama address is normalised from what people actually type`() {
        assertEquals("http://192.168.1.10:11434", LanScanner.normalizeOllamaEndpoint("192.168.1.10"))
        assertEquals("http://192.168.1.10:11434", LanScanner.normalizeOllamaEndpoint(" 192.168.1.10:11434/ "))
        assertEquals("http://192.168.1.10:11434", LanScanner.normalizeOllamaEndpoint("http://192.168.1.10:11434/v1"))
        assertEquals("http://192.168.1.10:11434", LanScanner.normalizeOllamaEndpoint("http://192.168.1.10:11434/api"))
        assertEquals("http://pc.local:8080", LanScanner.normalizeOllamaEndpoint("pc.local:8080"))
        assertEquals("https://ollama.example.com:11434", LanScanner.normalizeOllamaEndpoint("https://ollama.example.com"))
        assertEquals("", LanScanner.normalizeOllamaEndpoint("   "))
    }

    @Test
    fun `scanner covers the local slash 24 without itself`() {
        val hosts = LanScanner.subnetHosts("192.168.1.37")
        assertEquals(253, hosts.size)
        assertTrue("192.168.1.1" in hosts)
        assertTrue("192.168.1.254" in hosts)
        assertFalse("192.168.1.37" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertTrue(LanScanner.subnetHosts("not an ip").isEmpty())
    }

    @Test
    fun `only private ipv4 networks are scanned`() {
        assertTrue(LanScanner.isPrivateIpv4("10.0.0.5"))
        assertTrue(LanScanner.isPrivateIpv4("172.20.1.1"))
        assertTrue(LanScanner.isPrivateIpv4("192.168.0.2"))
        assertFalse(LanScanner.isPrivateIpv4("172.32.0.1"))
        assertFalse(LanScanner.isPrivateIpv4("8.8.8.8"))
        assertFalse(LanScanner.isPrivateIpv4("300.1.1.1"))
    }

    @Test
    fun `wizard picks a sensible default model and keeps the rest`() {
        assertEquals(
            "meta-llama/llama-3.3-70b-instruct:free",
            preferredModel(ProviderKind.OPENROUTER, listOf("openai/gpt-4o", "meta-llama/llama-3.3-70b-instruct:free"))
        )
        assertEquals("deepseek-chat", preferredModel(ProviderKind.DEEPSEEK, listOf("deepseek-reasoner", "deepseek-chat")))
        assertEquals("qwen3:8b", preferredModel(ProviderKind.OLLAMA, listOf("llama3.2:3b", "qwen3:8b")))
        assertEquals("only-one", preferredModel(ProviderKind.NVIDIA, listOf("only-one")))
        assertNull(preferredModel(ProviderKind.GROQ, emptyList()))
    }

    @Test
    fun `sizes read like a person would say them`() {
        assertEquals("2.4 ГБ", formatSize(2_588_147_712L))
        assertEquals("409 МБ", formatSize(428_970_080L))
    }

    @Test
    fun `every cloud option has an https key page and a real endpoint`() {
        for (option in cloudOptions) {
            option.keyUrl?.let { assertTrue(option.title, it.startsWith("https://")) }
            if (option.kind != ProviderKind.OPENAI_COMPATIBLE) {
                assertTrue(option.title, defaultProviderEndpoint(option.kind).startsWith("https://"))
            }
        }
        assertEquals("https://api.fireworks.ai/inference/v1", defaultProviderEndpoint(ProviderKind.FIREWORKS))
    }

    @Test
    fun `transport errors become sentences the owner can act on`() {
        assertTrue(ChatController.friendlyError(IOException("Failed to connect to /192.168.1.10:11434")).startsWith("Сервер не отвечает"))
        assertTrue(ChatController.friendlyError(IllegalStateException("HTTP 401 Unauthorized")).startsWith("Ключ не подошёл"))
        assertTrue(ChatController.friendlyError(IllegalStateException("HTTP 429: rate limit")).startsWith("Сервис просит подождать"))
        assertEquals("что-то своё", ChatController.friendlyError(IllegalStateException("что-то своё")))
    }

    @Test
    fun `hologram head is symmetric and has a nose`() {
        val theta = kotlin.math.acos(-0.2).toFloat()
        val left = HoloGeometry.head(theta, -0.5f)
        val right = HoloGeometry.head(theta, 0.5f)
        assertEquals(left.x, -right.x, 1e-5f)
        assertEquals(left.z, right.z, 1e-5f)
        val noseTip = HoloGeometry.head(theta, 0f)
        val cheek = HoloGeometry.head(theta, 0.35f)
        assertTrue("nose must stand out of the face", noseTip.z > cheek.z + 0.1f)
    }

    @Test
    fun `hologram shoulders are wider than the neck and rings close`() {
        val neck = HoloGeometry.bodyAt(0f)
        val shoulders = HoloGeometry.bodyAt(0.7f)
        assertTrue(shoulders[1] > neck[1] * 2.5f)
        val rings = HoloGeometry.rings()
        assertEquals(HoloGeometry.HEAD_RINGS + 14, rings.size)
        assertTrue(rings.all { ring -> ring.size == 72 })
        val eye = HoloGeometry.eye(left = true)
        assertTrue(eye.x < 0f && abs(eye.y - HoloGeometry.EYE_Y) < 0.01f)
    }
}
