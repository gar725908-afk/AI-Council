package ai.hybrid.commandcenter.androidbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionCatalogTest {

    private val manifestPermissions = setOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
        "android.permission.REQUEST_INSTALL_PACKAGES"
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
    fun `no runtime dangerous permission is declared yet`() {
        assertTrue(PermissionCatalog.declared.none { it.grant == PermissionCatalog.Grant.RUNTIME })
    }

    @Test
    fun `not requested list names the microphone until voice exists`() {
        assertTrue(PermissionCatalog.notRequested.any { it.first.contains("Микрофон") })
    }
}
