package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Material Library storage (handoff section 4.7): imported user-selected
 * files as searchable text with integrity hashes. App-private SQLite; the
 * original file never leaves the device and credentials are never stored
 * here. Duplicate imports (same SHA-256) are rejected honestly.
 */
data class LibraryItem(
    val id: String,
    val displayName: String,
    val format: String,
    val sizeBytes: Long,
    val sha256: String,
    val textChars: Int,
    val importedAtEpochMillis: Long,
    /** App-private copy of the original file (viewer, future PDF operations); null for legacy rows. */
    val originalPath: String? = null
)

sealed class LibraryImportResult {
    data class Imported(val item: LibraryItem) : LibraryImportResult()
    data class Rejected(val reason: String) : LibraryImportResult()
}

class MaterialLibrary(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    private val originalsDir: File = File(context.applicationContext.filesDir, "library").apply { mkdirs() }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE library_items (
                id TEXT PRIMARY KEY NOT NULL,
                display_name TEXT NOT NULL,
                format TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                sha256 TEXT NOT NULL UNIQUE,
                text TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                original_path TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_library_imported ON library_items(imported_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive: legacy rows keep working without an original file.
        if (oldVersion < 2) db.execSQL("ALTER TABLE library_items ADD COLUMN original_path TEXT")
    }

    fun import(displayName: String, bytes: ByteArray): LibraryImportResult {
        val safeName = displayName.trim().take(200).ifBlank { "без имени" }
        val extraction = MaterialTextExtractor.extract(safeName, bytes)
        val text = when (extraction) {
            is MaterialTextExtractor.Extraction.Unsupported ->
                return LibraryImportResult.Rejected(extraction.reason)
            is MaterialTextExtractor.Extraction.Text -> extraction
        }
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
        if (findBySha(sha256) != null) {
            return LibraryImportResult.Rejected("Этот файл уже импортирован (совпал SHA-256).")
        }
        val id = UUID.randomUUID().toString()
        val original = File(originalsDir, id + "." + text.format)
        try {
            original.writeBytes(bytes)
        } catch (error: Exception) {
            return LibraryImportResult.Rejected("Не удалось сохранить копию файла: " + (error.message ?: "ошибка записи"))
        }
        val item = LibraryItem(
            id = id,
            displayName = safeName,
            format = text.format,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256,
            textChars = text.text.length,
            importedAtEpochMillis = System.currentTimeMillis(),
            originalPath = original.absolutePath
        )
        writableDatabase.execSQL(
            "INSERT INTO library_items (id, display_name, format, size_bytes, sha256, text, imported_at, original_path) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            arrayOf(
                item.id, item.displayName, item.format, item.sizeBytes,
                item.sha256, text.text, item.importedAtEpochMillis, item.originalPath
            )
        )
        return LibraryImportResult.Imported(item)
    }

    fun listItems(limit: Int = 200): List<LibraryItem> =
        readableDatabase.rawQuery(
            "SELECT id, display_name, format, size_bytes, sha256, length(text), imported_at, original_path " +
                "FROM library_items ORDER BY imported_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 1000).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LibraryItem(
                            id = cursor.getString(0),
                            displayName = cursor.getString(1),
                            format = cursor.getString(2),
                            sizeBytes = cursor.getLong(3),
                            sha256 = cursor.getString(4),
                            textChars = cursor.getInt(5),
                            importedAtEpochMillis = cursor.getLong(6),
                            originalPath = if (cursor.isNull(7)) null else cursor.getString(7)
                        )
                    )
                }
            }
        }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM library_items")
        originalsDir.listFiles()?.forEach { it.delete() }
    }

    fun delete(itemId: String) {
        originalPath(itemId)?.let { File(it).delete() }
        writableDatabase.execSQL("DELETE FROM library_items WHERE id = ?", arrayOf(itemId))
    }

    fun originalPath(itemId: String): String? =
        readableDatabase.rawQuery(
            "SELECT original_path FROM library_items WHERE id = ?", arrayOf(itemId)
        ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }

    fun search(query: String, limit: Int = 20): List<LibrarySearch.Match> =
        LibrarySearch.search(loadDocs(), query, limit)

    fun getText(itemId: String): String? =
        readableDatabase.rawQuery(
            "SELECT text FROM library_items WHERE id = ?", arrayOf(itemId)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun loadDocs(): List<LibrarySearch.Doc> =
        readableDatabase.rawQuery(
            "SELECT id, display_name, text FROM library_items ORDER BY imported_at DESC LIMIT 500",
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(LibrarySearch.Doc(cursor.getString(0), cursor.getString(1), cursor.getString(2)))
                }
            }
        }

    private fun findBySha(sha256: String): String? =
        readableDatabase.rawQuery(
            "SELECT id FROM library_items WHERE sha256 = ?", arrayOf(sha256)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private companion object {
        const val DATABASE_NAME = "nexus_library.db"
        const val DATABASE_VERSION = 2
    }
}
