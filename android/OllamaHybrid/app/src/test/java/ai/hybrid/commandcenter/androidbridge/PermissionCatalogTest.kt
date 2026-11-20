package ai.hybrid.commandcenter.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCatalogTest {

    private val manifestPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.RECORD_AUDIO"
    )

    @Test
    fun `catalog mirrors the manifest exactly`() {
        assertEquals(manifestPermissions, PermissionCatalog.declared.map { it.permission }.toSet())
    }

    @Test
    fun `every declared permission states a purpose`() {
        assertTrue(PermissionCatalog.declared.all { it.purpose.length > 20 })
    }

    @Test
    fun `microphone is the only runtime permission and is tied to voice`() {
        val runtime = PermissionCatalog.declared.filter { it.grant == PermissionCatalog.Grant.RUNTIME }
        assertEquals(listOf("android.permission.RECORD_AUDIO"), runtime.map { it.permission })
        assertTrue(runtime.single().purpose.contains("Voice"))
    }

    @Test
    fun `not requested list still names storage and accessibility`() {
        assertTrue(PermissionCatalog.notRequested.any { it.first.contains("Хранилище") })
        assertTrue(PermissionCatalog.notRequested.any { it.first.contains("Захват экрана") })
    }
}
