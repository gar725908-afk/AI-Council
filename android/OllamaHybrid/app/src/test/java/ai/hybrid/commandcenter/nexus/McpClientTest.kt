package ai.hybrid.commandcenter.nexus

import ai.hybrid.commandcenter.androidbridge.ActionRisk
import ai.hybrid.commandcenter.androidbridge.McpBridgeTool
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    @Test
    fun `json response matching id is parsed`() {
        val body = """{"jsonrpc":"2.0","id":3,"result":{"tools":[]}}"""
        assertEquals(3, McpProtocol.parseResponse(body, "application/json", 3)!!.getInt("id"))
        assertNull(McpProtocol.parseResponse(body, "application/json", 4))
    }

    @Test
    fun `sse stream yields the message with the requested id`() {
        val stream = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":9,\"result\":{\"ok\":true}}\n\n" +
            "data: {\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}}\n\n"
        val parsed = McpProtocol.parseResponse(stream, "text/event-stream", 4)!!
        assertEquals("hi", McpProtocol.parseCallResult(parsed).text)
    }

    @Test
    fun `tools are parsed with read-only annotation`() {
        val json = JSONObject(
            """{"result":{"tools":[
                {"name":"read_file","description":"Read","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}},
                {"name":"write_file","description":"Write"},
                {"name":""}
            ]}}"""
        )
        val tools = McpProtocol.parseTools(json)
        assertEquals(listOf("read_file", "write_file"), tools.map { it.name })
        assertTrue(tools[0].readOnlyHint)
        assertTrue(!tools[1].readOnlyHint)
    }

    @Test
    fun `call errors are surfaced not swallowed`() {
        val error = McpProtocol.parseCallResult(JSONObject("""{"error":{"code":-32601,"message":"no such tool"}}"""))
        assertTrue(error.isError)
        assertTrue(error.text.contains("no such tool"))
        val toolError = McpProtocol.parseCallResult(JSONObject("""{"result":{"isError":true,"content":[{"type":"text","text":"denied"}]}}"""))
        assertTrue(toolError.isError)
    }

    @Test
    fun `bridge tool risk follows the read-only hint`() {
        val server = McpServer("files", "https://example.invalid/mcp")
        val client = McpClient()
        val ro = McpBridgeTool(server, McpTool("read", "", "{}", true), client)
        val rw = McpBridgeTool(server, McpTool("write", "", "{}", false), client)
        assertEquals(ActionRisk.READ_ONLY, ro.risk)
        assertEquals(ActionRisk.SENSITIVE, rw.risk)
        assertEquals("mcp.files.read", ro.id)
    }

    @Test
    fun `arguments map becomes json, raw json passes through`() {
        assertEquals("v", McpBridgeTool.argumentsJson(mapOf("k" to "v")).getString("k"))
        assertEquals(7, McpBridgeTool.argumentsJson(mapOf("json" to """{"n":7}""")).getInt("n"))
    }
}

class McpClientTest {

    private fun fakeServer(): MockWebServer {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                val json = runCatching { JSONObject(body) }.getOrNull() ?: return MockResponse().setResponseCode(400)
                val id = json.optInt("id", -1)
                return when (json.optString("method")) {
                    "initialize" -> MockResponse()
                        .setHeader("Content-Type", "application/json")
                        .setHeader("Mcp-Session-Id", "sess-1")
                        .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-03-26","capabilities":{},"serverInfo":{"name":"fake"}}}""")
                    "notifications/initialized" -> MockResponse().setResponseCode(202)
                    "tools/list" -> {
                        if (request.getHeader("Mcp-Session-Id") != "sess-1") return MockResponse().setResponseCode(400)
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setBody("""{"jsonrpc":"2.0","id":$id,"result":{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"},"annotations":{"readOnlyHint":true}}]}}""")
                    }
                    "tools/call" -> {
                        val text = json.getJSONObject("params").getJSONObject("arguments").optString("text")
                        MockResponse().setHeader("Content-Type", "text/event-stream")
                            .setBody("data: {\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"echo:$text\"}]}}\n\n")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        return server
    }

    @Test
    fun `full handshake lists tools and calls one over sse`() = runBlocking {
        val mock = fakeServer()
        try {
            val server = McpServer("fake", mock.url("/mcp").toString())
            val client = McpClient()
            val tools = client.listTools(server).getOrThrow()
            assertEquals(listOf("echo"), tools.map { it.name })
            val result = client.callTool(server, "echo", JSONObject().put("text", "nexus")).getOrThrow()
            assertEquals("echo:nexus", result.text)
            assertTrue(!result.isError)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun `http failure is a typed error not a crash`() = runBlocking {
        val mock = MockWebServer()
        mock.enqueue(MockResponse().setResponseCode(500))
        mock.start()
        try {
            val result = McpClient().listTools(McpServer("bad", mock.url("/").toString()))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("500"))
        } finally {
            mock.shutdown()
        }
    }
}
