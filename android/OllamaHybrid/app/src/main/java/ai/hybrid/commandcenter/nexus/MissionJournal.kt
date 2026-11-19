package ai.hybrid.commandcenter.nexus

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

enum class MissionStatus { DRAFT, QUEUED, RUNNING, VERIFYING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

object MissionStateMachine {
    private val transitions = mapOf(
        MissionStatus.DRAFT to setOf(MissionStatus.QUEUED, MissionStatus.CANCELLED),
        MissionStatus.QUEUED to setOf(MissionStatus.RUNNING, MissionStatus.FAILED, MissionStatus.CANCELLED, MissionStatus.INTERRUPTED),
        MissionStatus.RUNNING to setOf(MissionStatus.VERIFYING, MissionStatus.FAILED, MissionStatus.CANCELLED, MissionStatus.INTERRUPTED),
        MissionStatus.VERIFYING to setOf(MissionStatus.COMPLETED, MissionStatus.FAILED, MissionStatus.INTERRUPTED),
        MissionStatus.INTERRUPTED to setOf(MissionStatus.QUEUED, MissionStatus.CANCELLED),
        MissionStatus.FAILED to setOf(MissionStatus.QUEUED, MissionStatus.CANCELLED),
        MissionStatus.COMPLETED to emptySet(),
        MissionStatus.CANCELLED to emptySet()
    )
    fun canTransition(from: MissionStatus, to: MissionStatus) = transitions[from]?.contains(to) == true
    fun requireTransition(from: MissionStatus, to: MissionStatus) {
        require(canTransition(from, to)) { "Недопустимый переход миссии: $from → $to" }
    }
}

data class MissionRecord(
    val id: String, val goal: String, val status: MissionStatus,
    val createdAt: Long, val updatedAt: Long, val result: String?, val error: String?
)

data class AgentJournalRecord(
    val id: String, val missionId: String, val agentId: String, val agentName: String,
    val role: String, val status: String, val output: String?, val error: String?, val createdAt: Long
)

data class ChatConversationRecord(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class ChatMessageRecord(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val createdAt: Long
)

/** Local app-private journal. Provider credentials are never stored in this database. */
class MissionJournal(context: Context) : SQLiteOpenHelper(
    context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION
) {
    private val contentCipher = MissionDataCipher()
    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE missions (
                id TEXT PRIMARY KEY NOT NULL, goal TEXT NOT NULL,
                endpoint TEXT NOT NULL, model TEXT NOT NULL, state TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                result TEXT, error TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE agent_results (
                id TEXT PRIMARY KEY NOT NULL, mission_id TEXT NOT NULL,
                agent_id TEXT NOT NULL, agent_name TEXT NOT NULL, role TEXT NOT NULL,
                status TEXT NOT NULL, output TEXT, error TEXT, created_at INTEGER NOT NULL,
                FOREIGN KEY(mission_id) REFERENCES missions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mission_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, mission_id TEXT NOT NULL,
                event TEXT NOT NULL, detail TEXT, created_at INTEGER NOT NULL,
                FOREIGN KEY(mission_id) REFERENCES missions(id) ON DELETE CASCADE
            )
        """.trimIndent())
        createChatTables(db)
        db.execSQL("CREATE INDEX idx_missions_updated ON missions(updated_at DESC)")
        db.execSQL("CREATE INDEX idx_agent_results_mission ON agent_results(mission_id, created_at)")
        db.execSQL("CREATE INDEX idx_mission_events_mission ON mission_events(mission_id, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Additive migrations preserve all mission and conversation history.
        if (oldVersion < 2) createChatTables(db)
    }

    private fun createChatTables(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chat_conversations (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS chat_messages (
                id TEXT PRIMARY KEY NOT NULL,
                conversation_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(conversation_id) REFERENCES chat_conversations(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_messages_conversation ON chat_messages(conversation_id, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chat_conversations_updated ON chat_conversations(updated_at DESC)")
    }

    fun createConversation(title: String = "Новый чат"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        writableDatabase.insertOrThrow("chat_conversations", null, ContentValues().apply {
            put("id", id)
            put("title", contentCipher.encrypt(title))
            put("created_at", now)
            put("updated_at", now)
        })
        return id
    }

    fun getLatestConversationId(): String? =
        readableDatabase.rawQuery(
            "SELECT id FROM chat_conversations ORDER BY updated_at DESC LIMIT 1",
            null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun appendChatMessage(conversationId: String, role: String, content: String) {
        require(role in setOf("system", "user", "assistant")) { "Unsupported chat role." }
        require(content.isNotBlank()) { "Chat message cannot be blank." }
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(db.rawQuery(
                "SELECT id FROM chat_conversations WHERE id = ?",
                arrayOf(conversationId)
            ).use { it.moveToFirst() }) { "Conversation not found: $conversationId" }
            db.insertOrThrow("chat_messages", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString())
                put("conversation_id", conversationId)
                put("role", role)
                put("content", contentCipher.encrypt(content))
                put("created_at", now)
            })
            val update = ContentValues().apply { put("updated_at", now) }
            if (role == "user") {
                db.rawQuery(
                    "SELECT title FROM chat_conversations WHERE id = ?",
                    arrayOf(conversationId)
                ).use { cursor ->
                    if (cursor.moveToFirst() &&
                        contentCipher.decrypt(cursor.getString(0)) == "Новый чат"
                    ) {
                        update.put("title", contentCipher.encrypt(content.take(80)))
                    }
                }
            }
            db.update("chat_conversations", update, "id = ?", arrayOf(conversationId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getChatMessages(conversationId: String, limit: Int = 100): List<ChatMessageRecord> {
        val safeLimit = limit.coerceIn(1, 500)
        return readableDatabase.rawQuery(
            "SELECT id, conversation_id, role, content, created_at FROM (" +
                "SELECT id, conversation_id, role, content, created_at FROM chat_messages " +
                "WHERE conversation_id = ? ORDER BY created_at DESC LIMIT ?" +
                ") ORDER BY created_at ASC",
            arrayOf(conversationId, safeLimit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ChatMessageRecord(
                    id = cursor.getString(0),
                    conversationId = cursor.getString(1),
                    role = cursor.getString(2),
                    content = contentCipher.decrypt(cursor.getString(3)).orEmpty(),
                    createdAt = cursor.getLong(4)
                ))
            }
        }
    }

    fun getRecentConversations(limit: Int = 20): List<ChatConversationRecord> {
        val safeLimit = limit.coerceIn(1, 100)
        return readableDatabase.rawQuery(
            "SELECT id, title, created_at, updated_at FROM chat_conversations " +
                "ORDER BY updated_at DESC LIMIT ?",
            arrayOf(safeLimit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(ChatConversationRecord(
                    id = cursor.getString(0),
                    title = contentCipher.decrypt(cursor.getString(1)).orEmpty(),
                    createdAt = cursor.getLong(2),
                    updatedAt = cursor.getLong(3)
                ))
            }
        }
    }

    fun createMission(goal: String, endpoint: String, model: String): String {
        require(goal.isNotBlank()) { "Цель миссии не может быть пустой." }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("missions", null, ContentValues().apply {
                put("id", id); put("goal", contentCipher.encrypt(goal)); put("endpoint", endpoint); put("model", model)
                put("state", MissionStatus.DRAFT.name); put("created_at", now); put("updated_at", now)
            })
            db.insertOrThrow("mission_events", null, ContentValues().apply {
                put("mission_id", id); put("event", "MISSION_CREATED")
                put("detail", "Миссия создана"); put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return id
    }

    fun transition(
        missionId: String, next: MissionStatus, detail: String? = null,
        result: String? = null, error: String? = null
    ) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = db.rawQuery("SELECT state FROM missions WHERE id = ?", arrayOf(missionId)).use {
                check(it.moveToFirst()) { "Миссия не найдена: $missionId" }
                MissionStatus.valueOf(it.getString(0))
            }
            MissionStateMachine.requireTransition(current, next)
            val values = ContentValues().apply {
                put("state", next.name); put("updated_at", System.currentTimeMillis())
                if (result != null) put("result", contentCipher.encrypt(result))
                if (error != null) put("error", contentCipher.encrypt(error))
            }
            check(db.update("missions", values, "id = ?", arrayOf(missionId)) == 1)
            db.insertOrThrow("mission_events", null, ContentValues().apply {
                put("mission_id", missionId); put("event", "STATE_${next.name}")
                put("detail", contentCipher.encrypt(detail)); put("created_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun saveAgentResult(missionId: String, result: AgentRunResult) {
        val now = System.currentTimeMillis()
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("agent_results", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("mission_id", missionId)
                put("agent_id", result.agent.id); put("agent_name", result.agent.name)
                put("role", result.agent.role.name); put("status", result.status.name)
                put("output", contentCipher.encrypt(result.output)); put("error", contentCipher.encrypt(result.error)); put("created_at", now)
            })
            db.insertOrThrow("mission_events", null, ContentValues().apply {
                put("mission_id", missionId); put("event", "AGENT_${result.status.name}")
                put("detail", contentCipher.encrypt("${result.agent.name}: ${result.error ?: "результат сохранён"}"))
                put("created_at", now)
            })
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun getRecentMissions(limit: Int = 30): List<MissionRecord> {
        val safeLimit = limit.coerceIn(1, 200)
        return readableDatabase.rawQuery(
            "SELECT id, goal, state, created_at, updated_at, result, error FROM missions ORDER BY updated_at DESC LIMIT ?",
            arrayOf(safeLimit.toString())
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(MissionRecord(
                    cursor.getString(0), contentCipher.decrypt(cursor.getString(1)).orEmpty(), MissionStatus.valueOf(cursor.getString(2)),
                    cursor.getLong(3), cursor.getLong(4), contentCipher.decrypt(cursor.getString(5)), contentCipher.decrypt(cursor.getString(6))
                ))
            }
        }
    }

    fun getAgentResults(missionId: String): List<AgentJournalRecord> =
        readableDatabase.rawQuery(
            "SELECT id, mission_id, agent_id, agent_name, role, status, output, error, created_at " +
                "FROM agent_results WHERE mission_id = ? ORDER BY created_at ASC",
            arrayOf(missionId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(AgentJournalRecord(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3),
                    cursor.getString(4), cursor.getString(5), contentCipher.decrypt(cursor.getString(6)),
                    contentCipher.decrypt(cursor.getString(7)), cursor.getLong(8)
                ))
            }
        }

    /** Mark abandoned work; do not replay side effects automatically after process death. */
    fun markAbandonedMissionsInterrupted(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = db.rawQuery(
                "SELECT id FROM missions WHERE state IN (?, ?)",
                arrayOf(MissionStatus.RUNNING.name, MissionStatus.VERIFYING.name)
            ).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            ids.forEach { missionId ->
                val now = System.currentTimeMillis()
                db.update("missions", ContentValues().apply {
                    put("state", MissionStatus.INTERRUPTED.name); put("updated_at", now)
                    put("error", contentCipher.encrypt("Процесс приложения завершился до подтверждения результата."))
                }, "id = ?", arrayOf(missionId))
                db.insertOrThrow("mission_events", null, ContentValues().apply {
                    put("mission_id", missionId); put("event", "STATE_INTERRUPTED")
                    put("detail", "Обнаружена незавершённая миссия при старте приложения")
                    put("created_at", now)
                })
            }
            db.setTransactionSuccessful()
            return ids.size
        } finally {
            db.endTransaction()
        }
    }

    private fun addEvent(missionId: String, event: String, detail: String?) {
        writableDatabase.insertOrThrow("mission_events", null, ContentValues().apply {
            put("mission_id", missionId); put("event", event); put("detail", detail)
            put("created_at", System.currentTimeMillis())
        })
    }

    companion object {
        private const val DATABASE_NAME = "nexus_missions.db"
        private const val DATABASE_VERSION = 2
    }
}
