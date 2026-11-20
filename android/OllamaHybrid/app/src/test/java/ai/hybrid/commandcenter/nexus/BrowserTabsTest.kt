package ai.hybrid.commandcenter.nexus

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class BrowserTabsTest {

    @Test
    fun `starts with one empty selected tab`() {
        val tabs = BrowserTabs()
        assertEquals(1, tabs.tabs.size)
        assertEquals(tabs.tabs.first().id, tabs.selectedId)
    }

    @Test
    fun `open adds and selects a new tab up to the limit`() {
        var tabs = BrowserTabs()
        repeat(20) { tabs = tabs.open() }
        assertEquals(BrowserTabs.MAX_TABS, tabs.tabs.size)
        assertEquals(tabs.tabs.last().id, tabs.selectedId)
    }

    @Test
    fun `closing the selected tab selects its left neighbour`() {
        var tabs = BrowserTabs().open().open()
        val middle = tabs.tabs[1].id
        tabs = tabs.select(middle).close(middle)
        assertEquals(2, tabs.tabs.size)
        assertEquals(tabs.tabs[0].id, tabs.selectedId)
    }

    @Test
    fun `closing the last remaining tab yields a fresh empty tab`() {
        val tabs = BrowserTabs()
        val after = tabs.close(tabs.selectedId)
        assertEquals(1, after.tabs.size)
        assertTrue(after.selected.url == null)
    }

    @Test
    fun `update changes only the addressed tab`() {
        val tabs = BrowserTabs().open()
        val first = tabs.tabs[0].id
        val updated = tabs.update(first, url = "https://a.b", title = "A")
        assertEquals("https://a.b", updated.tabs[0].url)
        assertEquals(null, updated.tabs[1].url)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserHistoryStoreTest {

    private fun store() = BrowserHistoryStore(ApplicationProvider.getApplicationContext())

    /** One database per test class: start from a known-empty state, not from test order. */
    @Before
    fun startFromEmptyHistory() {
        store().clear()
    }

    @Test
    fun `visits are recorded newest first and clearable`() {
        val s = store()
        s.record("https://first.example", "First")
        s.record("https://second.example", "Second")
        assertEquals(listOf("https://second.example", "https://first.example"), s.recent().map { it.url })
        s.clear()
        assertEquals(0, s.count())
    }

    @Test
    fun `non http navigations are never stored`() {
        val s = store()
        s.record("javascript:alert(1)", "x")
        s.record("intent://x", "y")
        assertEquals(0, s.count())
    }
}
