package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class BrowserHistoryEntry(val url: String, val title: String, val visitedAtEpochMillis: Long)

/** Persistent, app-private browsing history (handoff 4.4) — user-clearable. */
class BrowserHistoryStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "nexus_browser.db", null, 1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL, " +
                "title TEXT NOT NULL, visited_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_history_visited ON history(visited_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(url: String, title: String) {
        if (!BrowserUrlPolicy.allowsNavigation(url)) return
        writableDatabase.execSQL(
            "INSERT INTO history (url, title, visited_at) VALUES (?,?,?)",
            arrayOf(url.take(2048), title.take(200), System.currentTimeMillis())
        )
        writableDatabase.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY visited_at DESC LIMIT $MAX_ROWS)"
        )
    }

    fun recent(limit: Int = 50): List<BrowserHistoryEntry> =
        readableDatabase.rawQuery(
            "SELECT url, title, visited_at FROM history ORDER BY visited_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(BrowserHistoryEntry(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
                }
            }
        }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM history")
    }

    private companion object {
        const val MAX_ROWS = 2000
    }
}
