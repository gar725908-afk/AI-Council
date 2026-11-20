package ai.hybrid.commandcenter

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * JVM render smoke tests: every primary NEXUS screen must compose without
 * crashing against the real application class, stores and journal. This is
 * not a substitute for physical-device QA — it catches render-time crashes
 * and broken wiring before an APK ever reaches a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NexusApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderSmokeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun commandDeckRendersAgainstRealStores() {
        compose.setContent { CommandDeckScreen(onNavigate = {}) }
        compose.onRoot().assertExists()
    }

    @Test
    fun deviceCoreRendersWithRealInspector() {
        compose.setContent { DeviceCoreScreen() }
        compose.onRoot().assertExists()
    }

    @Test
    fun systemSpaceRendersDeviceTab() {
        compose.setContent { SystemScreen(initialTab = SystemTab.DEVICE.ordinal) }
        compose.onRoot().assertExists()
    }

    @Test
    fun settingsPanelRendersRealCounts() {
        compose.setContent { SettingsPanel() }
        compose.onRoot().assertExists()
    }

    @Test
    fun archiveRendersWithEmptyLibrary() {
        compose.setContent { ArchiveScreen() }
        compose.onRoot().assertExists()
    }

    @Test
    fun councilCommandCenterRendersFromJournal() {
        compose.setContent { CouncilScreen() }
        compose.onRoot().assertExists()
    }
}
