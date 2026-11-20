package ai.hybrid.commandcenter.nexus

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ErrorReportTest {

    private fun entry(
        summary: String = "IllegalStateException: boom",
        details: String = "at ai.hybrid.commandcenter.Foo.bar(Foo.kt:42)"
    ) = ErrorEntry(1, "crash", summary, details, "1.0 (1)", "15 (API 35)", "Pixel 8", 1_700_000_000_000)

    @Test
    fun `credentials are redacted from any report text`() {
        val dirty = "Authorization: Bearer abcdef1234567890 key sk-ABCDEFGHIJKLMNOP " +
            "api_key=supersecret123 token: 0123456789abcdef"
        val clean = ErrorReport.redact(dirty)
        assertTrue(!clean.contains("abcdef1234567890"))
        assertTrue(!clean.contains("sk-ABCDEFGHIJKLMNOP"))
        assertTrue(!clean.contains("supersecret123"))
        assertTrue(clean.contains("[скрыто]"))
    }

    @Test
    fun `guid-looking values are redacted too`() {
        val clean = ErrorReport.redact("token 01a15957-8128-778d-8ab2-b4fa24569565 end")
        assertTrue(!clean.contains("01a15957"))
    }

    @Test
    fun `report body carries error, environment and trace`() {
        val body = ErrorReport.buildBody(entry(), note = "нажал Council")
        assertTrue(body.contains("нажал Council"))
        assertTrue(body.contains("IllegalStateException: boom"))
        assertTrue(body.contains("Версия NEXUS: 1.0 (1)"))
        assertTrue(body.contains("Pixel 8"))
        assertTrue(body.contains("Foo.kt:42"))
        assertTrue(body.contains("отправлен пользователем вручную"))
    }

    @Test
    fun `missing note leaves a visible placeholder instead of pretending`() {
        assertTrue(ErrorReport.buildBody(entry()).contains("опишите, что делали"))
    }

    @Test
    fun `title is bounded and carries the kind`() {
        val title = ErrorReport.buildTitle(entry(summary = "X".repeat(400)))
        assertTrue(title.startsWith("[crash]"))
        assertTrue(title.length <= 120)
    }

    @Test
    fun `issue url is bounded even for a huge trace`() {
        val url = ErrorReport.issueUrl(entry(details = "stack frame line\n".repeat(5_000)))
        assertTrue(url.startsWith("https://github.com/gar725908-afk/AI-Council/issues/new"))
        assertTrue("URL слишком длинный: " + url.length, url.length <= 7_200)
        assertTrue(url.contains("labels=bug"))
    }

    @Test
    fun `secrets never reach the issue url`() {
        val url = ErrorReport.issueUrl(entry(details = "api_key=verysecretvalue123"))
        assertTrue(!url.contains("verysecretvalue123"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ErrorJournalTest {

    private fun journal() = ErrorJournal(ApplicationProvider.getApplicationContext())

    @Test
    fun `errors are stored newest first with environment`() {
        val j = journal()
        j.record("tool", "первая", "детали 1", "1.0")
        j.record("crash", "вторая", "детали 2", "1.0")
        val entries = j.recent()
        assertEquals(listOf("вторая", "первая"), entries.map { it.summary })
        assertTrue(entries.first().device.isNotBlank())
        assertTrue(entries.first().androidRelease.contains("API"))
    }

    @Test
    fun `throwables are stored with their stack trace`() {
        val j = journal()
        j.recordThrowable("crash", IllegalStateException("boom"), "1.0")
        val entry = j.recent().single()
        assertTrue(entry.summary.contains("IllegalStateException"))
        assertTrue(entry.details.contains("IllegalStateException"))
    }

    @Test
    fun `secrets are redacted before storage`() {
        val j = journal()
        j.record("tool", "ошибка sk-SECRETKEY123456", "api_key=hidden987654", "1.0")
        val entry = j.recent().single()
        assertTrue(!entry.summary.contains("SECRETKEY123456"))
        assertTrue(!entry.details.contains("hidden987654"))
    }

    @Test
    fun `journal can be cleared by the user`() {
        val j = journal()
        j.record("tool", "x", "y", "1.0")
        assertEquals(1, j.count())
        j.clear()
        assertEquals(0, j.count())
    }
}
