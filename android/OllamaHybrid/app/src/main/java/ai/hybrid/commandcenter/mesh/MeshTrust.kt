package ai.hybrid.commandcenter.mesh

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** A phone the owner paired with. Only public data: the key fingerprint, never a secret. */
data class TrustedPeer(
    val fingerprint: String,
    val deviceId: String,
    val name: String,
    val pairedAt: Long,
    val grants: Set<MeshOp>,
    val addresses: List<String> = emptyList(),
    val lastSeenAt: Long = 0L,
    val revoked: Boolean = false
) {
    val active: Boolean get() = !revoked
}

data class MeshAuditEvent(val at: Long, val kind: String, val text: String)

/** Where trust lives. The Android store keeps it in app-private preferences; tests keep it in memory. */
interface MeshTrustStore {
    fun peers(): List<TrustedPeer>
    fun peer(fingerprint: String): TrustedPeer?
    fun save(peer: TrustedPeer)
    fun audit(event: MeshAuditEvent)
    fun auditLog(): List<MeshAuditEvent>

    /** Task history survives restarts; outputs are trimmed so the store stays small. */
    fun tasks(): List<MeshTaskRecord>
    fun saveTasks(tasks: List<MeshTaskRecord>)

    fun trusted(fingerprint: String): TrustedPeer? = peer(fingerprint)?.takeIf { it.active }

    fun revoke(fingerprint: String, at: Long = System.currentTimeMillis()) {
        val peer = peer(fingerprint) ?: return
        save(peer.copy(revoked = true))
        audit(MeshAuditEvent(at, "revoke", "Доверие отозвано: " + peer.name + " (" + peer.deviceId + ")"))
    }
}

class InMemoryTrustStore : MeshTrustStore {
    private val map = ConcurrentHashMap<String, TrustedPeer>()
    private val log = ArrayList<MeshAuditEvent>()
    override fun peers(): List<TrustedPeer> = map.values.sortedBy { it.name }
    override fun peer(fingerprint: String): TrustedPeer? = map[fingerprint]
    override fun save(peer: TrustedPeer) { map[peer.fingerprint] = peer }
    @Synchronized override fun audit(event: MeshAuditEvent) { log += event }
    @Synchronized override fun auditLog(): List<MeshAuditEvent> = log.toList()
    @Volatile private var history: List<MeshTaskRecord> = emptyList()
    override fun tasks(): List<MeshTaskRecord> = history
    override fun saveTasks(tasks: List<MeshTaskRecord>) { history = tasks.map(TaskHistoryCodec::trim) }
}

/** JSON form of the task history (no secrets: ids, op, status, short notes and trimmed outputs). */
object TaskHistoryCodec {
    const val MAX_OUTPUT = 2_000
    const val MAX_NOTE = 300

    fun trim(task: MeshTaskRecord) = task.copy(note = task.note.take(MAX_NOTE), output = task.output?.take(MAX_OUTPUT))

    fun encode(tasks: List<MeshTaskRecord>): String = JSONArray().also { array ->
        tasks.forEach { t ->
            array.put(
                JSONObject().put("id", t.id).put("out", t.outgoing).put("peer", t.peerName.take(60)).put("op", t.op.wire)
                    .put("st", t.status.name).put("note", t.note.take(MAX_NOTE)).put("at", t.startedAt)
                    .apply { t.finishedAt?.let { put("end", it) }; t.output?.let { put("res", it.take(MAX_OUTPUT)) } }
            )
        }
    }.toString()

    fun decode(raw: String?): List<MeshTaskRecord> = runCatching {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val op = MeshOp.byWire(o.optString("op")) ?: return@mapNotNull null
            val status = runCatching { MeshTaskRecord.Status.valueOf(o.optString("st")) }.getOrNull() ?: return@mapNotNull null
            MeshTaskRecord(
                id = o.getString("id"), outgoing = o.optBoolean("out"), peerName = o.optString("peer"), op = op, status = status,
                note = o.optString("note"), startedAt = o.optLong("at"),
                finishedAt = if (o.has("end")) o.getLong("end") else null,
                output = if (o.has("res")) o.getString("res") else null
            )
        }
    }.getOrDefault(emptyList())
}

class AndroidTrustStore(context: Context) : MeshTrustStore {
    private val prefs = context.applicationContext.getSharedPreferences("nexus_mesh_trust", Context.MODE_PRIVATE)

    @Synchronized
    override fun peers(): List<TrustedPeer> =
        prefs.all.filterKeys { it.startsWith("peer.") }.values
            .mapNotNull { (it as? String)?.let { raw -> runCatching { decode(JSONObject(raw)) }.getOrNull() } }
            .sortedBy { it.name.lowercase() }

    @Synchronized
    override fun peer(fingerprint: String): TrustedPeer? =
        prefs.getString("peer.$fingerprint", null)?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }

    @Synchronized
    override fun save(peer: TrustedPeer) {
        prefs.edit().putString("peer." + peer.fingerprint, encode(peer).toString()).apply()
    }

    @Synchronized
    override fun audit(event: MeshAuditEvent) {
        val list = JSONArray(prefs.getString("audit", "[]"))
        list.put(JSONObject().put("at", event.at).put("k", event.kind).put("x", event.text.take(300)))
        val trimmed = JSONArray()
        val from = (list.length() - 200).coerceAtLeast(0)
        for (i in from until list.length()) trimmed.put(list.get(i))
        prefs.edit().putString("audit", trimmed.toString()).apply()
    }

    @Synchronized
    override fun auditLog(): List<MeshAuditEvent> = runCatching {
        val list = JSONArray(prefs.getString("audit", "[]"))
        (0 until list.length()).map { i ->
            val o = list.getJSONObject(i)
            MeshAuditEvent(o.getLong("at"), o.getString("k"), o.getString("x"))
        }
    }.getOrDefault(emptyList())

    @Synchronized
    override fun tasks(): List<MeshTaskRecord> = TaskHistoryCodec.decode(prefs.getString("tasks", null))

    @Synchronized
    override fun saveTasks(tasks: List<MeshTaskRecord>) {
        prefs.edit().putString("tasks", TaskHistoryCodec.encode(tasks)).apply()
    }

    private fun encode(peer: TrustedPeer) = JSONObject()
        .put("fp", peer.fingerprint).put("id", peer.deviceId).put("name", peer.name)
        .put("pairedAt", peer.pairedAt).put("grants", JSONArray(peer.grants.map { it.wire }))
        .put("addr", JSONArray(peer.addresses)).put("seen", peer.lastSeenAt).put("revoked", peer.revoked)

    private fun decode(o: JSONObject) = TrustedPeer(
        fingerprint = o.getString("fp"),
        deviceId = o.getString("id"),
        name = o.getString("name"),
        pairedAt = o.getLong("pairedAt"),
        grants = o.optJSONArray("grants")?.let { a -> (0 until a.length()).mapNotNull { MeshOp.byWire(a.getString(it)) }.toSet() }
            ?: MeshOp.DEFAULT_GRANTS,
        addresses = o.optJSONArray("addr")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
        lastSeenAt = o.optLong("seen"),
        revoked = o.optBoolean("revoked")
    )
}
