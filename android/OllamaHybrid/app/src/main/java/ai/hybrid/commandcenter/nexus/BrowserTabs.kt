package ai.hybrid.commandcenter.nexus

import java.util.UUID

/** Pure tab model for the Cyber Browser: deterministic, unit-tested, UI-agnostic. */
data class BrowserTab(
    val id: String = UUID.randomUUID().toString(),
    val url: String? = null,
    val title: String = ""
)

data class BrowserTabs(
    val tabs: List<BrowserTab> = listOf(BrowserTab()),
    val selectedId: String = tabs.first().id
) {
    val selected: BrowserTab get() = tabs.first { it.id == selectedId }

    fun open(): BrowserTabs {
        if (tabs.size >= MAX_TABS) return this
        val tab = BrowserTab()
        return copy(tabs = tabs + tab, selectedId = tab.id)
    }

    fun select(id: String): BrowserTabs = if (tabs.any { it.id == id }) copy(selectedId = id) else this

    fun close(id: String): BrowserTabs {
        val index = tabs.indexOfFirst { it.id == id }
        if (index < 0) return this
        if (tabs.size == 1) return BrowserTabs()
        val remaining = tabs.filterNot { it.id == id }
        val nextSelected = if (selectedId == id) remaining[(index - 1).coerceAtLeast(0)].id else selectedId
        return copy(tabs = remaining, selectedId = nextSelected)
    }

    fun update(id: String, url: String? = null, title: String? = null): BrowserTabs = copy(
        tabs = tabs.map { tab ->
            if (tab.id != id) tab else tab.copy(url = url ?: tab.url, title = title ?: tab.title)
        }
    )

    companion object {
        const val MAX_TABS = 12
    }
}
