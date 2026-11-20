package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter

data class ErrorEntry(
    val id: Long,
    val kind: String,
    val summary: String,
    val details: String,
    val appVersion: String,
    val androidRelease: String,
    val device: String,
    val occurredAtEpochMillis: Long
)

/**
 * Local error journal (the missing half of the self-repair loop).
 *
 * Everything stays on the device until the user explicitly sends a report.
 * Stack traces are stored as-is, but user content — prompts, answers,
 * material text, endpoints, tokens — is never written here: only the error
 * itself, the app version and the device model.
 */
class ErrorJournal(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "nexus_errors.db", null, 1
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE errors (id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, " +
                "summary TEXT NOT NULL, details TEXT NOT NULL, app_version TEXT NOT NULL, " +
                "android_release TEXT NOT NULL, device TEXT NOT NULL, occurred_at INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_errors_time ON errors(occurred_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(kind: String, summary: String, details: String, appVersion: String) {
        writableDatabase.execSQL(
            "INSERT INTO errors (kind, summary, details, app_version, android_release, device, occurred_at) " +
                "VALUES (?,?,?,?,?,?,?)",
            arrayOf(
                kind.take(40),
                ErrorReport.redact(summary).take(300),
                ErrorReport.redact(details).take(MAX_DETAILS_CHARS),
                appVersion.take(40),
                Build.VERSION.RELEASE.orEmpty() + " (API " + Build.VERSION.SDK_INT + ")",
                (Build.MANUFACTURER + " " + Build.MODEL).take(80),
                System.currentTimeMillis()
            )
        )
        writableDatabase.execSQL(
            "DELETE FROM errors WHERE id NOT IN (SELECT id FROM errors ORDER BY occurred_at DESC LIMIT $MAX_ROWS)"
        )
    }

    fun recordThrowable(kind: String, error: Throwable, appVersion: String) {
        val trace = StringWriter().also { writer -> error.printStackTrace(PrintWriter(writer)) }.toString()
        record(
            kind = kind,
            summary = error.javaClass.simpleName + ": " + (error.message ?: "без сообщения"),
            details = trace,
            appVersion = appVersion
        )
    }

    fun recent(limit: Int = 50): List<ErrorEntry> =
        readableDatabase.rawQuery(
            "SELECT id, kind, summary, details, app_version, android_release, device, occurred_at " +
                "FROM errors ORDER BY occurred_at DESC LIMIT ?",
            arrayOf(limit.coerceIn(1, 500).toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ErrorEntry(
                            id = cursor.getLong(0),
                            kind = cursor.getString(1),
                            summary = cursor.getString(2),
                            details = cursor.getString(3),
                            appVersion = cursor.getString(4),
                            androidRelease = cursor.getString(5),
                            device = cursor.getString(6),
                            occurredAtEpochMillis = cursor.getLong(7)
                        )
                    )
                }
            }
        }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM errors", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun clear() {
        writableDatabase.execSQL("DELETE FROM errors")
    }

    private companion object {
        const val MAX_ROWS = 300
        const val MAX_DETAILS_CHARS = 20_000
    }
}

/** Builds the report text and the GitHub issue link. Pure, so it is fully tested. */
object ErrorReport {

    private const val REPO_ISSUE_URL = "https://github.com/gar725908-afk/AI-Council/issues/new"
    private const val MAX_URL_CHARS = 7_000

    private val secretPatterns = listOf(
        Regex("""(?i)\b(sk|rk|pk)-[A-Za-z0-9_\-]{12,}"""),
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-]{10,}"""),
        Regex("""(?i)\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b"""),
        Regex("""(?i)(api[_-]?key|token|password|secret)["'\s:=]+[A-Za-z0-9._\-]{6,}""")
    )

    /** Removes anything that looks like a credential before it is ever stored or sent. */
    fun redact(text: String): String =
        secretPatterns.fold(text) { acc, pattern -> pattern.replace(acc, "[скрыто]") }

    fun buildBody(entry: ErrorEntry, note: String = ""): String = buildString {
        appendLine("### Что произошло")
        appendLine(note.ifBlank { "(опишите, что делали в приложении)" })
        appendLine()
        appendLine("### Ошибка")
        appendLine("`" + entry.summary + "`")
        appendLine()
        appendLine("### Окружение")
        appendLine("- Версия NEXUS: " + entry.appVersion)
        appendLine("- Android: " + entry.androidRelease)
        appendLine("- Устройство: " + entry.device)
        appendLine("- Тип: " + entry.kind)
        appendLine()
        appendLine("### Трассировка")
        appendLine("```")
        appendLine(redact(entry.details).take(6_000))
        appendLine("```")
        appendLine()
        appendLine("_Отчёт собран в приложении и отправлен пользователем вручную._")
    }

    fun buildTitle(entry: ErrorEntry): String =
        ("[" + entry.kind + "] " + entry.summary).take(120)

    /** Pre-filled GitHub issue link; the user reviews and submits it himself. */
    fun issueUrl(entry: ErrorEntry, note: String = ""): String {
        val title = java.net.URLEncoder.encode(buildTitle(entry), "UTF-8")
        var body = buildBody(entry, note)
        var encoded = java.net.URLEncoder.encode(body, "UTF-8")
        while (REPO_ISSUE_URL.length + title.length + encoded.length > MAX_URL_CHARS && body.length > 500) {
            body = body.take(body.length / 2) + "\n```\n_(трассировка усечена для ссылки)_"
            encoded = java.net.URLEncoder.encode(body, "UTF-8")
        }
        return "$REPO_ISSUE_URL?title=$title&body=$encoded&labels=bug"
    }
}
