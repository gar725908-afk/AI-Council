package ai.hybrid.commandcenter.androidbridge

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ScreenCaptureTest {

    private val context: android.content.Context = ApplicationProvider.getApplicationContext()

    @After
    fun clearGrant() {
        ScreenCaptureGrant.clear()
    }

    @Test
    fun `no standing grant exists by default`() {
        assertTrue(!ScreenCaptureGrant.hasGrant())
    }

    @Test
    fun `a stored grant is single use`() {
        ScreenCaptureGrant.store(-1, Intent())
        assertTrue(ScreenCaptureGrant.hasGrant())
        ScreenCapture.captureOnce(context)
        assertTrue("Разрешение обязано сгорать после одного кадра", !ScreenCaptureGrant.hasGrant())
    }

    @Test
    fun `capture without consent fails honestly`() {
        val outcome = ScreenCapture.captureOnce(context)
        assertTrue(!outcome.success)
        assertTrue(outcome.detail.contains("согласия"))
    }

    @Test
    fun `tool is sensitive and refuses without consent`() = runBlocking {
        val tool = ScreenCaptureTool(context)
        assertEquals(ActionRisk.SENSITIVE, tool.risk)
        assertEquals(BridgeCapability.SCREEN_CAPTURE, tool.capability)
        val result = tool.execute(BridgeRequest("r1", tool.id), BridgeTransport.NATIVE)
        assertTrue(!result.success)
        assertEquals("E_NO_CONSENT", result.errorCode)
    }

    @Test
    fun `verification rejects a missing or empty file`() = runBlocking {
        val tool = ScreenCaptureTool(context)
        val request = BridgeRequest("r1", tool.id)

        val noPath = BridgeResult(true, BridgeTransport.NATIVE, "ok")
        assertTrue(tool.verify(request, noPath) is VerifiableBridgeTool.Verification.Failed)

        val missing = BridgeResult(
            true, BridgeTransport.NATIVE, "ok", mapOf("path" to "/does/not/exist.png")
        )
        assertTrue(tool.verify(request, missing) is VerifiableBridgeTool.Verification.Failed)

        val empty = File(context.filesDir, "empty.png").apply { writeBytes(ByteArray(0)) }
        val emptyResult = BridgeResult(
            true, BridgeTransport.NATIVE, "ok", mapOf("path" to empty.absolutePath)
        )
        assertTrue(tool.verify(request, emptyResult) is VerifiableBridgeTool.Verification.Failed)

        val real = File(context.filesDir, "frame.png").apply { writeBytes(ByteArray(64) { 1 }) }
        val goodResult = BridgeResult(
            true, BridgeTransport.NATIVE, "ok", mapOf("path" to real.absolutePath)
        )
        assertEquals(VerifiableBridgeTool.Verification.Passed, tool.verify(request, goodResult))
    }
}
