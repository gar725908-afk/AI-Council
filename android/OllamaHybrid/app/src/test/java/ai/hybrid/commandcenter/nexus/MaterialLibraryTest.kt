package ai.hybrid.commandcenter.nexus

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pure extractor and search tests. */
class MaterialTextExtractorTest {

    @Test
    fun `unsupported extension is refused with the supported list`() {
        val result = MaterialTextExtractor.extract("photo.jpg", byteArrayOf(1, 2, 3))
        assertTrue(result is MaterialTextExtractor.Extraction.Unsupported)
        assertTrue(
            (result as MaterialTextExtractor.Extraction.Unsupported).reason.contains("TXT")
        )
    }

    @Test
    fun `binary content with nul bytes is refused even with txt extension`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x00, 0x01, 0x02)
        val result = MaterialTextExtractor.extract("fake.txt", bytes)
        assertTrue(result is MaterialTextExtractor.Extraction.Unsupported)
    }

    @Test
    fun `empty and blank files are refused`() {
        assertTrue(
            MaterialTextExtractor.extract("a.txt", ByteArray(0))
                is MaterialTextExtractor.Extraction.Unsupported
        )
        assertTrue(
            MaterialTextExtractor.extract("a.md", "   \n ".toByteArray())
                is MaterialTextExtractor.Extraction.Unsupported
        )
    }

    @Test
    fun `markdown text is extracted verbatim`() {
        val source = "# Заголовок\n\nТекст про NEXUS и Council."
        val result = MaterialTextExtractor.extract("notes.md", source.toByteArray())
        val text = result as MaterialTextExtractor.Extraction.Text
        assertEquals(source, text.text)
        assertEquals("md", text.format)
    }
}

class LibrarySearchTest {

    private val docs = listOf(
        LibrarySearch.Doc("1", "contract.md", "Контракт поставки. Срок поставки 30 дней. Поставка в Назрань."),
        LibrarySearch.Doc("2", "notes.txt", "Заметки о проекте NEXUS: срок запуска не определён.")
    )

    @Test
    fun `query terms are tokenized lowercased and deduplicated`() {
        assertEquals(listOf("срок", "поставки"), LibrarySearch.terms("Срок, ПОСТАВКИ срок!"))
    }

    @Test
    fun `blank query returns nothing`() {
        assertTrue(LibrarySearch.search(docs, "  ").isEmpty())
        assertTrue(LibrarySearch.search(docs, "я").isEmpty())
    }

    @Test
    fun `ranking follows occurrence count`() {
        val matches = LibrarySearch.search(docs, "поставки")
        assertEquals("1", matches.single().itemId)
        assertTrue(matches.single().score >= 2)
    }

    @Test
    fun `match carries exact source name and character position`() {
        val match = LibrarySearch.search(docs, "NEXUS").single()
        assertEquals("notes.txt", match.itemName)
        assertEquals(docs[1].text.indexOf("NEXUS"), match.positionChars)
        assertTrue(match.snippet.contains("NEXUS"))
    }

    @Test
    fun `snippet is an exact quote with ellipses only at real cuts`() {
        val longText = "начало " + "x".repeat(300) + " ИСКОМОЕ " + "y".repeat(300) + " конец"
        val snippet = LibrarySearch.snippetAt(longText, longText.indexOf("ИСКОМОЕ"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.contains("ИСКОМОЕ"))
    }

    @Test
    fun `multiple terms accumulate score across the document`() {
        val matches = LibrarySearch.search(docs, "срок поставки")
        assertEquals(2, matches.size)
        assertEquals("1", matches.first().itemId)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MaterialLibraryTest {

    private fun library() = MaterialLibrary(ApplicationProvider.getApplicationContext())

    @Test
    fun `imported text file is listed with hash and searchable`() {
        val lib = library()
        val result = lib.import("spec.md", "NEXUS — локальная AI-экосистема.".toByteArray())
        assertTrue(result is LibraryImportResult.Imported)
        val item = lib.listItems().single()
        assertEquals("spec.md", item.displayName)
        assertEquals(64, item.sha256.length)
        val match = lib.search("экосистема").single()
        assertEquals(item.id, match.itemId)
    }

    @Test
    fun `duplicate content is rejected by hash`() {
        val lib = library()
        val bytes = "одно и то же содержимое".toByteArray()
        assertTrue(lib.import("a.txt", bytes) is LibraryImportResult.Imported)
        val second = lib.import("b.txt", bytes)
        assertTrue(second is LibraryImportResult.Rejected)
        assertTrue((second as LibraryImportResult.Rejected).reason.contains("SHA-256"))
        assertEquals(1, lib.listItems().size)
    }

    @Test
    fun `unsupported file is rejected and not stored`() {
        val lib = library()
        assertTrue(lib.import("binary.apk", byteArrayOf(1, 2)) is LibraryImportResult.Rejected)
        assertTrue(lib.listItems().isEmpty())
    }

    @Test
    fun `delete removes the item and its text`() {
        val lib = library()
        val imported = lib.import("x.txt", "текст для удаления".toByteArray())
        val id = (imported as LibraryImportResult.Imported).item.id
        lib.delete(id)
        assertTrue(lib.listItems().isEmpty())
        assertNull(lib.getText(id))
    }

    @Test
    fun `original file is kept and removed together with the item`() {
        val lib = library()
        val imported = lib.import("keep.txt", "оригинал".toByteArray()) as LibraryImportResult.Imported
        val path = imported.item.originalPath!!
        assertTrue(java.io.File(path).exists())
        assertEquals(path, lib.listItems().single().originalPath)
        lib.delete(imported.item.id)
        assertTrue(!java.io.File(path).exists())
    }

    @Test
    fun `stored text is returned verbatim`() {
        val lib = library()
        val source = "точный текст с позицией"
        val id = (lib.import("v.txt", source.toByteArray()) as LibraryImportResult.Imported).item.id
        assertEquals(source, lib.getText(id))
    }
}
