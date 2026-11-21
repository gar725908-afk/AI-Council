package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors

class OllamaNativeGatewayTest {
    private val server = MockWebServer()
    private val fakeMain = Executors.newSingleThreadExecutor { task -> Thread(task, "fake-main") }

    @After
    fun tearDown() {
        server.shutdown()
        fakeMain.shutdownNow()
    }

    /**
     * Found by the emulator E2E: the chat collects on the main thread, and the
     * Ollama stream did its blocking HTTP call right there — Android answers that
     * with NetworkOnMainThreadException. The JVM has no such guard, so this test
     * checks the thread itself.
     */
    @Test
    fun `the ollama stream never touches the network on the collector's thread`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                "{\"message\":{\"role\":\"assistant\",\"content\":\"Привет\"},\"done\":false}\n" +
                    "{\"message\":{\"role\":\"assistant\",\"content\":\"!\"},\"done\":true}\n"
            )
        )
        server.start()
        val networkThreads = Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                networkThreads += Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build()
        val request = CompletionRequest(
            endpoint = server.url("/").toString().trimEnd('/'),
            model = "qwen2.5:0.5b",
            apiKey = "",
            messages = listOf(ProviderMessage("user", "Кто ты?"))
        )
        val pieces = withContext(fakeMain.asCoroutineDispatcher()) {
            OllamaNativeGateway(client).stream(request).toList()
        }
        assertEquals("Привет!", pieces.joinToString(""))
        assertTrue("the request was never made", networkThreads.isNotEmpty())
        // coroutine debug mode appends « @coroutine#N» to thread names, hence startsWith
        assertFalse("HTTP ran on the collector's thread: $networkThreads", networkThreads.any { it.startsWith("fake-main") })
        assertEquals("/api/chat", server.takeRequest().path)
    }
}
