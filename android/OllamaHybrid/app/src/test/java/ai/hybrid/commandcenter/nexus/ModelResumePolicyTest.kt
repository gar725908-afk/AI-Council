package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelResumePolicyTest {

    private val meta = ModelResumePolicy.Meta(
        url = "https://host/model.gguf",
        sha256 = "abc123",
        sizeBytes = 1_000L
    )

    @Test
    fun `no partial file means start over`() {
        assertTrue(ModelResumePolicy.plan(meta, meta, 0L) is ModelResumePolicy.Plan.StartOver)
    }

    @Test
    fun `missing metadata means start over even with bytes on disk`() {
        assertTrue(ModelResumePolicy.plan(null, meta, 500L) is ModelResumePolicy.Plan.StartOver)
    }

    @Test
    fun `changed url or hash or size invalidates the partial`() {
        assertTrue(
            ModelResumePolicy.plan(meta.copy(url = "https://other/x"), meta, 500L)
                is ModelResumePolicy.Plan.StartOver
        )
        assertTrue(
            ModelResumePolicy.plan(meta.copy(sha256 = "def456"), meta, 500L)
                is ModelResumePolicy.Plan.StartOver
        )
        assertTrue(
            ModelResumePolicy.plan(meta.copy(sizeBytes = 2_000L), meta, 500L)
                is ModelResumePolicy.Plan.StartOver
        )
    }

    @Test
    fun `hash comparison ignores case`() {
        val plan = ModelResumePolicy.plan(meta.copy(sha256 = "ABC123"), meta, 500L)
        assertEquals(ModelResumePolicy.Plan.Resume(500L), plan)
    }

    @Test
    fun `matching metadata resumes from the partial length`() {
        assertEquals(ModelResumePolicy.Plan.Resume(500L), ModelResumePolicy.plan(meta, meta, 500L))
    }

    @Test
    fun `complete partial only needs verification`() {
        assertEquals(ModelResumePolicy.Plan.FinalizeOnly, ModelResumePolicy.plan(meta, meta, 1_000L))
    }

    @Test
    fun `oversized partial is discarded`() {
        assertTrue(ModelResumePolicy.plan(meta, meta, 1_500L) is ModelResumePolicy.Plan.StartOver)
    }

    @Test
    fun `206 with matching content range appends`() {
        assertEquals(
            ModelResumePolicy.RangeVerdict.Append,
            ModelResumePolicy.rangeVerdict(206, "bytes 500-999/1000", 500L, 1_000L)
        )
    }

    @Test
    fun `206 with wrong start offset fails instead of corrupting the file`() {
        assertTrue(
            ModelResumePolicy.rangeVerdict(206, "bytes 400-999/1000", 500L, 1_000L)
                is ModelResumePolicy.RangeVerdict.Fail
        )
    }

    @Test
    fun `206 with mismatched total fails`() {
        assertTrue(
            ModelResumePolicy.rangeVerdict(206, "bytes 500-1999/2000", 500L, 1_000L)
                is ModelResumePolicy.RangeVerdict.Fail
        )
    }

    @Test
    fun `206 without content range fails`() {
        assertTrue(
            ModelResumePolicy.rangeVerdict(206, null, 500L, 1_000L)
                is ModelResumePolicy.RangeVerdict.Fail
        )
    }

    @Test
    fun `200 restarts cleanly when the server ignores range`() {
        assertTrue(
            ModelResumePolicy.rangeVerdict(200, null, 500L, 1_000L)
                is ModelResumePolicy.RangeVerdict.Restart
        )
    }

    @Test
    fun `416 restarts cleanly`() {
        assertTrue(
            ModelResumePolicy.rangeVerdict(416, null, 500L, 1_000L)
                is ModelResumePolicy.RangeVerdict.Restart
        )
    }

    @Test
    fun `other codes fail with the code in the reason`() {
        val verdict = ModelResumePolicy.rangeVerdict(503, null, 500L, 1_000L)
        assertTrue(verdict is ModelResumePolicy.RangeVerdict.Fail)
        assertTrue((verdict as ModelResumePolicy.RangeVerdict.Fail).reason.contains("503"))
    }

    @Test
    fun `meta survives a json round trip`() {
        assertEquals(meta, ModelResumePolicy.parseMeta(ModelResumePolicy.metaJson(meta)))
    }

    @Test
    fun `broken or incomplete meta parses to null`() {
        assertNull(ModelResumePolicy.parseMeta(null))
        assertNull(ModelResumePolicy.parseMeta(""))
        assertNull(ModelResumePolicy.parseMeta("not json"))
        assertNull(ModelResumePolicy.parseMeta("""{"url":"x"}"""))
        assertNull(ModelResumePolicy.parseMeta("""{"url":"x","sha256":"y","sizeBytes":0}"""))
    }
}
