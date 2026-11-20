package ai.hybrid.commandcenter.androidbridge

import android.graphics.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiTreeMatcherTest {

    private fun node(
        label: String,
        clickable: Boolean = true,
        depth: Int = 1,
        className: String = "Button",
        bounds: Rect = Rect(0, 0, 100, 50),
        description: String = ""
    ) = UiNode(depth, className, label, description, "", clickable, bounds)

    private val screen = listOf(
        node("Настройки", depth = 0, className = "FrameLayout", clickable = false),
        node("Отправить"),
        node("Отправить отчёт"),
        node("Отмена"),
        node("", description = "Поиск", className = "ImageButton")
    )

    @Test
    fun `exact label wins over prefix and substring`() {
        assertEquals("Отправить", UiTreeMatcher.findClickable(screen, "Отправить")?.label)
    }

    @Test
    fun `prefix match is used when no exact label exists`() {
        assertEquals("Отправить отчёт", UiTreeMatcher.findClickable(screen, "Отправить от")?.label)
    }

    @Test
    fun `content description is matched when text is empty`() {
        assertEquals("Поиск", UiTreeMatcher.findClickable(screen, "поиск")?.label)
    }

    @Test
    fun `matching ignores case and surrounding spaces`() {
        assertEquals("Отмена", UiTreeMatcher.findClickable(screen, "  оТМЕНА ")?.label)
    }

    @Test
    fun `empty query and unknown text match nothing`() {
        assertNull(UiTreeMatcher.findClickable(screen, "   "))
        assertNull(UiTreeMatcher.findClickable(screen, "такого элемента нет"))
    }

    @Test
    fun `zero sized nodes are never tap targets`() {
        val invisible = listOf(node("Кнопка", bounds = Rect(0, 0, 0, 0)))
        assertNull(UiTreeMatcher.findClickable(invisible, "Кнопка"))
    }

    @Test
    fun `render keeps meaningful nodes with depth and markers`() {
        val rendered = UiTreeMatcher.render(screen)
        assertTrue(rendered.contains("«Отправить»"))
        assertTrue(rendered.contains("[нажимаемый]"))
        assertTrue(rendered.lines().first().startsWith("FrameLayout"))
        assertTrue(rendered.lines()[1].startsWith("  "))
    }

    @Test
    fun `render is bounded`() {
        val many = (1..500).map { node("Элемент $it") }
        assertEquals(10, UiTreeMatcher.render(many, maxLines = 10).lines().size)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityToolsContractTest {

    @Test
    fun `read is read-only and tap is sensitive`() {
        assertEquals(ActionRisk.READ_ONLY, ReadScreenTool().risk)
        assertEquals(ActionRisk.SENSITIVE, TapElementTool().risk)
        assertEquals(setOf(BridgeTransport.ACCESSIBILITY), ReadScreenTool().supportedTransports)
        assertEquals(BridgeCapability.UI_GESTURES, TapElementTool().capability)
    }

    @Test
    fun `tools refuse honestly while the service is disabled`() = runBlocking {
        val read = ReadScreenTool().execute(BridgeRequest("r1", "ui.read_screen"), BridgeTransport.ACCESSIBILITY)
        assertTrue(!read.success)
        assertEquals("E_SERVICE_OFF", read.errorCode)

        val tap = TapElementTool().execute(
            BridgeRequest("r2", "ui.tap", mapOf("target" to "Кнопка")),
            BridgeTransport.ACCESSIBILITY
        )
        assertTrue(!tap.success)
        assertEquals("E_SERVICE_OFF", tap.errorCode)
    }

    @Test
    fun `tap without a target never reaches the service`() = runBlocking {
        val result = TapElementTool().execute(BridgeRequest("r1", "ui.tap"), BridgeTransport.ACCESSIBILITY)
        assertEquals("E_BAD_TARGET", result.errorCode)
    }

    @Test
    fun `tap verification rejects a mismatched element`() = runBlocking {
        val tool = TapElementTool()
        val request = BridgeRequest("r1", "ui.tap", mapOf("target" to "Отправить"))
        val wrong = BridgeResult(
            true, BridgeTransport.ACCESSIBILITY, "ok", mapOf("matched_label" to "Удалить")
        )
        assertTrue(tool.verify(request, wrong) is VerifiableBridgeTool.Verification.Failed)

        val right = BridgeResult(
            true, BridgeTransport.ACCESSIBILITY, "ok", mapOf("matched_label" to "Отправить отчёт")
        )
        assertEquals(VerifiableBridgeTool.Verification.Passed, tool.verify(request, right))
    }

    @Test
    fun `service reports honestly that it is not running`() {
        assertTrue(!NexusAccessibilityService.isRunning())
        assertNull(NexusAccessibilityService.current())
    }
}
