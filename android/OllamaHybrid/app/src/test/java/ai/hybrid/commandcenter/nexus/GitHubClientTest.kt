package ai.hybrid.commandcenter.nexus

import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class GitHubPathsTest {

    @Test
    fun `repository relative paths are accepted`() {
        assertTrue(GitHubPaths.isSafe("android/OllamaHybrid/app/src/main/java/Foo.kt"))
        assertTrue(GitHubPaths.isSafe("README.md"))
    }

    @Test
    fun `traversal and absolute paths are refused`() {
        assertTrue(!GitHubPaths.isSafe("../../etc/passwd"))
        assertTrue(!GitHubPaths.isSafe("/etc/passwd"))
        assertTrue(!GitHubPaths.isSafe("./secret"))
        assertTrue(!GitHubPaths.isSafe("app/../../out"))
        assertTrue(!GitHubPaths.isSafe(""))
        assertTrue(!GitHubPaths.isSafe("dir/"))
        assertTrue(!GitHubPaths.isSafe("a b.kt"))
    }

    @Test
    fun `protected branches are recognised whatever the case`() {
        for (branch in listOf("main", "MAIN", " master ", "release", "production")) {
            assertTrue("$branch должна считаться защищённой", GitHubPaths.isProtected(branch))
        }
        assertTrue(!GitHubPaths.isProtected("nexus/auto-fix/foo-1"))
    }

    @Test
    fun `proposal branch is always fresh and never protected`() {
        val branch = GitHubPaths.proposalBranch("CouncilScreen.kt", 1_700_000_000_000)
        assertTrue(branch.startsWith("nexus/auto-fix/"))
        assertTrue(!GitHubPaths.isProtected(branch))
        assertTrue(branch.contains("councilscreen"))
        assertTrue(
            GitHubPaths.proposalBranch("x", 1L) != GitHubPaths.proposalBranch("x", 2L)
        )
    }

    @Test
    fun `odd seeds still yield a valid branch`() {
        val branch = GitHubPaths.proposalBranch("!!!", 5L)
        assertTrue(branch.startsWith("nexus/auto-fix/fix-"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GitHubClientTest {

    private val repo = GitHubRepo("gar725908-afk", "AI-Council", "main")

    private fun server(onRequest: (RecordedRequest) -> MockResponse): MockWebServer =
        MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = onRequest(request)
            }
            start()
        }

    @Test
    fun `file content is decoded from base64`() = runBlocking {
        val encoded = Base64.encodeToString("fun main() {}".toByteArray(), Base64.NO_WRAP)
        val mock = server {
            MockResponse().setBody(JSONObject().put("content", encoded).put("sha", "abc123").toString())
        }
        try {
            val file = GitHubClient("token", apiBase = mock.url("").toString().trimEnd('/'))
                .readFile(repo, "app/Main.kt").getOrThrow()
            assertEquals("fun main() {}", file.content)
            assertEquals("abc123", file.sha)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun `unsafe path never reaches the network`() = runBlocking {
        val mock = server { MockResponse().setResponseCode(500) }
        try {
            val result = GitHubClient("token", apiBase = mock.url("").toString().trimEnd('/'))
                .readFile(repo, "../../../etc/passwd")
            assertTrue(result.isFailure)
            assertEquals(0, mock.requestCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun `writing to a protected branch is refused before any request`() = runBlocking {
        val mock = server { MockResponse().setResponseCode(500) }
        try {
            val result = GitHubClient("token", apiBase = mock.url("").toString().trimEnd('/'))
                .proposeFix(repo, "app/Main.kt", "new", "sha", "title", "body", branch = "main")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("защищённую ветку"))
            assertEquals(0, mock.requestCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun `propose creates a branch, commits and opens a pull request`() = runBlocking {
        val seen = mutableListOf<String>()
        val mock = server { request ->
            val path = request.path.orEmpty()
            seen += request.method + " " + path.substringBefore('?')
            when {
                path.contains("/git/ref/heads/main") -> MockResponse().setBody(
                    JSONObject().put("object", JSONObject().put("sha", "base-sha")).toString()
                )
                path.endsWith("/git/refs") -> MockResponse().setBody("{}")
                path.contains("/contents/") -> MockResponse().setBody("{}")
                path.endsWith("/pulls") -> MockResponse().setBody(
                    JSONObject().put("number", 42).put("html_url", "https://github.com/x/y/pull/42").toString()
                )
                else -> MockResponse().setResponseCode(404)
            }
        }
        try {
            val pull = GitHubClient("token", apiBase = mock.url("").toString().trimEnd('/'))
                .proposeFix(repo, "app/Main.kt", "fixed", "file-sha", "fix", "body", "nexus/auto-fix/main-1")
                .getOrThrow()
            assertEquals(42, pull.number)
            assertTrue(seen.any { it.startsWith("POST") && it.endsWith("/git/refs") })
            assertTrue(seen.any { it.startsWith("PUT") && it.contains("/contents/") })
            assertTrue(seen.any { it.startsWith("POST") && it.endsWith("/pulls") })
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun `empty content is refused`() = runBlocking {
        val mock = server { MockResponse().setResponseCode(500) }
        try {
            val result = GitHubClient("token", apiBase = mock.url("").toString().trimEnd('/'))
                .proposeFix(repo, "app/Main.kt", "   ", "sha", "t", "b", "nexus/auto-fix/x-1")
            assertTrue(result.isFailure)
            assertEquals(0, mock.requestCount)
        } finally {
            mock.shutdown()
        }
    }

    @Test
    fun `github errors surface their message`() = runBlocking {
        val mock = server {
            MockResponse().setResponseCode(401).setBody(JSONObject().put("message", "Bad credentials").toString())
        }
        try {
            val result = GitHubClient("bad", apiBase = mock.url("").toString().trimEnd('/'))
                .readFile(repo, "app/Main.kt")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("Bad credentials"))
        } finally {
            mock.shutdown()
        }
    }
}
