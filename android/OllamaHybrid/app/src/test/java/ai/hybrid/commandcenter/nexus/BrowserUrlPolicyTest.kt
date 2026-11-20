package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserUrlPolicyTest {

    @Test
    fun `bare domain becomes https`() {
        assertEquals("https://zakupki.gov.ru/epz", BrowserUrlPolicy.normalize("zakupki.gov.ru/epz"))
    }

    @Test
    fun `explicit http and https are kept as typed`() {
        assertEquals("http://192.168.1.10:11434", BrowserUrlPolicy.normalize("http://192.168.1.10:11434"))
        assertEquals("https://example.com", BrowserUrlPolicy.normalize("https://example.com"))
    }

    @Test
    fun `dangerous schemes are refused`() {
        assertNull(BrowserUrlPolicy.normalize("javascript:alert(1)"))
        assertNull(BrowserUrlPolicy.normalize("file:///etc/hosts"))
        assertNull(BrowserUrlPolicy.normalize("content://media/1"))
        assertNull(BrowserUrlPolicy.normalize("intent://x#Intent;end"))
        assertNull(BrowserUrlPolicy.normalize("ftp://host"))
    }

    @Test
    fun `free text is not silently turned into a search`() {
        assertNull(BrowserUrlPolicy.normalize("как купить томограф"))
        assertNull(BrowserUrlPolicy.normalize("nexus"))
    }

    @Test
    fun `localhost gets plain http`() {
        assertEquals("http://localhost:8080", BrowserUrlPolicy.normalize("localhost:8080"))
    }

    @Test
    fun `navigation allow list is http and https only`() {
        assertTrue(BrowserUrlPolicy.allowsNavigation("https://a.b/c"))
        assertFalse(BrowserUrlPolicy.allowsNavigation("intent://x"))
        assertFalse(BrowserUrlPolicy.allowsNavigation("javascript:void(0)"))
    }

    @Test
    fun `display host strips scheme and path`() {
        assertEquals("zakupki.gov.ru", BrowserUrlPolicy.displayHost("https://zakupki.gov.ru/epz/main"))
    }
}
