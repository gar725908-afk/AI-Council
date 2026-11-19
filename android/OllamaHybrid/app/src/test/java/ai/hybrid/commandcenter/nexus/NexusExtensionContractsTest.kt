package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusExtensionContractsTest {
    @Test
    fun parsesSupportedDeclarativeActions() {
        val manifest = NexusExtensionManifest.parse(
            """{"id":"sample.hello","name":"Hello","version":"1.0.0","apiVersion":1,"capabilities":["show_message"],"actions":[{"id":"hello","title":"Say hello","type":"show_message","payload":"Hello from an extension"}]}"""
        )
        assertEquals("sample.hello", manifest.id)
        assertEquals("show_message", manifest.actions.single().type)
    }

    @Test
    fun rejectsUnknownCapabilityAndExecutableActionTypes() {
        assertThrows(IllegalArgumentException::class.java) {
            NexusExtensionManifest.parse(
                """{"id":"sample.hello","name":"Hello","version":"1.0.0","apiVersion":1,"capabilities":["root_access"]}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusExtensionManifest.parse(
                """{"id":"sample.hello","name":"Hello","version":"1.0.0","apiVersion":1,"capabilities":[],"actions":[{"id":"run","title":"Run script","type":"javascript","payload":"alert(1)"}]}"""
            )
        }
    }

    @Test
    fun actionsRequireExplicitCapabilitiesAndHttpsUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            NexusExtensionManifest.parse(
                """{"id":"sample.hello","name":"Hello","version":"1.0.0","apiVersion":1,"capabilities":[],"actions":[{"id":"copy","title":"Copy","type":"copy_text","payload":"secret"}]}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusExtensionManifest.parse(
                """{"id":"sample.hello","name":"Hello","version":"1.0.0","apiVersion":1,"capabilities":["open_url"],"actions":[{"id":"open","title":"Open","type":"open_url","payload":"javascript:alert(1)"}]}"""
            )
        }
    }

    @Test
    fun serializesActionsForPersistentStore() {
        val original = NexusExtensionManifest.parse(
            """{"id":"sample.copy","name":"Copy","version":"1.2.3","apiVersion":1,"capabilities":["clipboard_write"],"actions":[{"id":"copy","title":"Copy code","type":"copy_text","payload":"NEXUS"}]}"""
        )
        val restored = NexusExtensionManifest.fromStoredJson(original.toJson().toString())
        assertTrue(restored != null)
        assertEquals(original, restored)
    }
}
