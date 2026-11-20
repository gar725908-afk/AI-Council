package ai.hybrid.commandcenter.androidbridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One node of the on-screen UI tree, flattened for tools and tests. */
data class UiNode(
    val depth: Int,
    val className: String,
    val text: String,
    val contentDescription: String,
    val viewId: String,
    val clickable: Boolean,
    val bounds: Rect
) {
    val label: String get() = text.ifBlank { contentDescription }.ifBlank { viewId.substringAfterLast('/') }
}

/**
 * Accessibility transport for NEXUS Bridge (handoff section 7).
 *
 * The service does nothing on its own: it never reacts to events, never
 * records the screen and performs no action unless a Gateway-approved tool
 * asks for one. The user enables it in system settings and can revoke it
 * there at any time; `isRunning` reflects that real state.
 */
class NexusAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun readTree(maxNodes: Int = 300): List<UiNode> {
        val root = rootInActiveWindow ?: return emptyList()
        val collected = ArrayList<UiNode>()
        collect(root, 0, collected, maxNodes.coerceIn(1, 2_000))
        return collected
    }

    private fun collect(node: AccessibilityNodeInfo?, depth: Int, into: MutableList<UiNode>, max: Int) {
        if (node == null || into.size >= max || depth > MAX_DEPTH) return
        val bounds = Rect().also(node::getBoundsInScreen)
        into.add(
            UiNode(
                depth = depth,
                className = node.className?.toString()?.substringAfterLast('.').orEmpty().take(48),
                text = node.text?.toString().orEmpty().take(200),
                contentDescription = node.contentDescription?.toString().orEmpty().take(200),
                viewId = node.viewIdResourceName.orEmpty().take(160),
                clickable = node.isClickable,
                bounds = bounds
            )
        )
        for (index in 0 until node.childCount) {
            collect(node.getChild(index), depth + 1, into, max)
        }
    }

    /** Taps the centre of [bounds]; returns null on success, otherwise the reason. */
    fun tap(bounds: Rect, durationMillis: Long = 60): String? {
        if (bounds.width() <= 0 || bounds.height() <= 0) return "Элемент не имеет видимых границ."
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMillis.coerceIn(10, 1_000)))
            .build()
        val latch = CountDownLatch(1)
        var failure: String? = "Жест не был выполнен системой."
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(description: GestureDescription?) {
                    failure = null
                    latch.countDown()
                }

                override fun onCancelled(description: GestureDescription?) {
                    failure = "Система отменила жест."
                    latch.countDown()
                }
            },
            null
        )
        if (!dispatched) return "Система отклонила отправку жеста."
        if (!latch.await(GESTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return "Жест не завершился за ${GESTURE_TIMEOUT_SECONDS} с."
        return failure
    }

    companion object {
        private const val MAX_DEPTH = 40
        private const val GESTURE_TIMEOUT_SECONDS = 5L

        @Volatile
        private var instance: NexusAccessibilityService? = null

        /** True only while the user keeps the service enabled in system settings. */
        fun isRunning(): Boolean = instance != null

        fun current(): NexusAccessibilityService? = instance
    }
}

/** Pure selection logic over a flattened UI tree, so matching is testable. */
object UiTreeMatcher {

    /** Best clickable node for [query]: exact label first, then prefix, then substring. */
    fun findClickable(nodes: List<UiNode>, query: String): UiNode? {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return null
        val clickable = nodes.filter { it.clickable && it.bounds.width() > 0 && it.bounds.height() > 0 }
        val candidates = clickable.ifEmpty { nodes.filter { it.bounds.width() > 0 && it.bounds.height() > 0 } }
        return candidates.firstOrNull { it.label.lowercase() == needle }
            ?: candidates.firstOrNull { it.label.lowercase().startsWith(needle) }
            ?: candidates.firstOrNull { it.label.lowercase().contains(needle) }
            ?: candidates.firstOrNull { node ->
                node.text.lowercase().contains(needle) || node.contentDescription.lowercase().contains(needle)
            }
    }

    /** Compact, model-friendly rendering of the tree: only nodes that carry meaning. */
    fun render(nodes: List<UiNode>, maxLines: Int = 120): String = nodes
        .filter { it.label.isNotBlank() || it.clickable }
        .take(maxLines.coerceIn(1, 1_000))
        .joinToString("\n") { node ->
            buildString {
                repeat(node.depth.coerceAtMost(8)) { append("  ") }
                append(node.className.ifBlank { "View" })
                if (node.label.isNotBlank()) append(" «").append(node.label).append("»")
                if (node.clickable) append(" [нажимаемый]")
            }
        }
}
