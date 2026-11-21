package ai.hybrid.commandcenter.mesh

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NEXUS Mesh protocol v2. Every message is a small JSON object inside a
 * length-prefixed frame on a mutually authenticated TLS connection. The
 * schema is closed: unknown types, unknown operations and oversized fields are
 * rejected, nothing is deserialised into arbitrary objects.
 *
 * v2 changed pairing only: the six-digit code now mixes in a fresh nonce from
 * each phone, and the joining phone commits to its nonce before it sees the
 * inviter's (commit–reveal, as in Bluetooth numeric comparison and ZRTP). In v1
 * the code depended on the two keys and the invite alone, so someone holding a
 * leaked invite who saw the code could try certificates until one gave the same
 * code. In v2 the code does not exist until both nonces are out, and the nonce
 * that comes last was fixed in advance: one blind guess in a million per attempt.
 */
object MeshProtocol {
    const val VERSION = 2
    const val DEFAULT_PORT = 47811
    const val SERVICE_TYPE = "_nexus-mesh._tcp."
    const val MAX_FRAME = 256 * 1024

    val TYPES = setOf(
        "hello", "pair_request", "pair_nonce", "pair_reveal", "pair_confirm", "pair_reject", "ping", "pong",
        "status_request", "status", "task", "task_accept", "task_reject", "task_progress",
        "task_result", "task_cancel", "error", "bye"
    )

    class ProtocolException(message: String) : IOException(message)

    fun write(out: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FRAME) throw ProtocolException("Сообщение больше лимита кадра.")
        synchronized(out) {
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    fun read(input: DataInputStream): JSONObject {
        val size = input.readInt()
        if (size <= 0 || size > MAX_FRAME) throw ProtocolException("Неверный размер кадра: $size.")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }
            .getOrElse { throw ProtocolException("Кадр не является JSON-объектом.") }
        validate(json)
        return json
    }

    fun message(type: String, vararg fields: Pair<String, Any?>): JSONObject {
        require(type in TYPES) { "unknown type $type" }
        return JSONObject().put("v", VERSION).put("t", type).apply { fields.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) } }
    }

    /** Strict schema check shared by both sides. */
    fun validate(json: JSONObject) {
        if (json.optInt("v", -1) != VERSION) throw ProtocolException("Версия протокола не совпадает (нужна $VERSION).")
        val type = json.optString("t")
        if (type !in TYPES) throw ProtocolException("Неизвестный тип сообщения: «${type.take(20)}».")
        fun str(key: String, max: Int, required: Boolean = true) {
            if (!json.has(key) || json.isNull(key)) {
                if (required) throw ProtocolException("Нет поля «$key» в «$type».")
                return
            }
            val value = json.opt(key) as? String ?: throw ProtocolException("Поле «$key» должно быть строкой.")
            if (value.length > max) throw ProtocolException("Поле «$key» длиннее $max символов.")
        }
        fun hex(key: String, length: Int) {
            val value = json.opt(key) as? String ?: throw ProtocolException("Нет поля «$key» в «$type».")
            if (value.length != length || !value.all { it in '0'..'9' || it in 'a'..'f' }) {
                throw ProtocolException("Поле «$key» должно быть $length шестнадцатеричными символами.")
            }
        }
        when (type) {
            "hello" -> {
                str("device", 32); str("name", 40); str("app", 24, required = false)
                if (json.has("port") && (json.opt("port") !is Number || json.getInt("port") !in 0..65535)) {
                    throw ProtocolException("Поле «port» должно быть числом 0…65535.")
                }
            }
            "pair_request" -> { str("invite", 32); hex("proof", 64); hex("commit", 64) }
            "pair_nonce", "pair_reveal" -> hex("nonce", NONCE_BYTES * 2)
            "pair_reject", "error", "bye" -> str("reason", 300, required = false)
            "task" -> {
                str("id", 40); str("op", 40)
                if (MeshOp.byWire(json.optString("op")) == null) throw ProtocolException("Операция «${json.optString("op").take(30)}» не разрешена.")
                if (json.has("params") && json.optJSONObject("params") == null) throw ProtocolException("params должен быть объектом.")
            }
            "task_accept", "task_cancel" -> str("id", 40)
            "task_reject" -> { str("id", 40); str("reason", 300) }
            "task_progress" -> { str("id", 40); str("note", 200) }
            "task_result" -> { str("id", 40); str("output", 64_000) }
        }
    }

    // ---------------------------------------------------------------- pairing

    private val random = SecureRandom()

    fun randomId(bytes: Int = 9): String {
        val data = ByteArray(bytes).also(random::nextBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data)
    }

    const val NONCE_BYTES = 16

    /** 128 fresh random bits, hex: one per phone per pairing attempt. */
    fun newNonce(): String = ByteArray(NONCE_BYTES).also(random::nextBytes).toHex()

    /**
     * Proof that the joining phone knows the invite secret, bound to the two
     * TLS identities actually seen on this connection (a relay in the middle
     * presents different keys, so its proof can never match) and to the
     * joiner's commitment.
     */
    fun pairingProof(
        secret: ByteArray, inviteId: String, inviterFingerprint: String, joinerFingerprint: String, commit: String
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        val data = "nexus-mesh-pair-v2|$inviteId|$inviterFingerprint|$joinerFingerprint|$commit".toByteArray(Charsets.UTF_8)
        return mac.doFinal(data).toHex()
    }

    fun proofMatches(expected: String, actual: String): Boolean =
        MessageDigest.isEqual(expected.toByteArray(), actual.lowercase(Locale.ROOT).toByteArray())

    /** The joiner's commitment to its nonce, sent before it sees the inviter's nonce. */
    fun commitment(inviteId: String, inviterFingerprint: String, joinerFingerprint: String, joinerNonce: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("nexus-mesh-commit-v2|$inviteId|$inviterFingerprint|$joinerFingerprint|$joinerNonce".toByteArray(Charsets.UTF_8))
            .toHex()

    fun commitmentMatches(
        commit: String, inviteId: String, inviterFingerprint: String, joinerFingerprint: String, joinerNonce: String
    ): Boolean = proofMatches(commitment(inviteId, inviterFingerprint, joinerFingerprint, joinerNonce), commit)

    /**
     * Six digits both owners compare on their screens (numeric comparison). Inputs:
     * both keys in their roles, the invite and both phones' nonces — the joiner's
     * one fixed by its commitment before the inviter's was sent.
     */
    fun sas(
        inviterFingerprint: String, joinerFingerprint: String, inviteId: String, inviterNonce: String, joinerNonce: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(
            "nexus-mesh-sas-v2|$inviterFingerprint|$joinerFingerprint|$inviteId|$inviterNonce|$joinerNonce".toByteArray(Charsets.UTF_8)
        )
        val number = ((digest[0].toLong() and 0xFF) shl 24 or ((digest[1].toLong() and 0xFF) shl 16) or
            ((digest[2].toLong() and 0xFF) shl 8) or (digest[3].toLong() and 0xFF)) % 1_000_000
        val text = String.format(Locale.ROOT, "%06d", number)
        return text.substring(0, 3) + " " + text.substring(3)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

/** The closed list of things one phone may ask another to do. There is no shell. */
enum class MeshOp(val wire: String, val title: String) {
    NODE_STATUS("node.status", "Состояние узла"),
    INFERENCE_RUN("inference.run", "Ответ модели"),
    RUNTIME_BENCHMARK("runtime.benchmark", "Замер скорости");

    companion object {
        fun byWire(value: String): MeshOp? = entries.firstOrNull { it.wire == value }
        val DEFAULT_GRANTS: Set<MeshOp> = setOf(NODE_STATUS, INFERENCE_RUN, RUNTIME_BENCHMARK)
    }
}

/**
 * What the inviting phone hands over (as a link or a QR code): where to
 * connect, which key to expect, and a one-time secret. Nothing in it lets
 * anyone run tasks — it only opens a pairing that both owners still confirm.
 */
data class MeshInvite(
    val id: String,
    val secret: ByteArray,
    val fingerprint: String,
    val deviceId: String,
    val name: String,
    val addresses: List<String>,
    val expiresAtEpochSec: Long
) {
    fun expired(nowMs: Long = System.currentTimeMillis()): Boolean = nowMs / 1000 > expiresAtEpochSec

    fun toLink(): String {
        val json = JSONObject()
            .put("i", id)
            .put("s", Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
            .put("f", fingerprint)
            .put("d", deviceId)
            .put("n", name)
            .put("a", JSONArray(addresses))
            .put("e", expiresAtEpochSec)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    override fun equals(other: Any?): Boolean =
        other is MeshInvite && other.id == id && other.secret.contentEquals(secret) && other.fingerprint == fingerprint
    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val PREFIX = "nexus-mesh:1:"
        const val TTL_SEC = 10 * 60L

        fun create(identity: MeshIdentity, name: String, addresses: List<String>, nowMs: Long = System.currentTimeMillis()): MeshInvite =
            MeshInvite(
                id = MeshProtocol.randomId(),
                secret = ByteArray(16).also(SecureRandom()::nextBytes),
                fingerprint = identity.fingerprint,
                deviceId = identity.deviceId,
                name = name,
                addresses = addresses,
                expiresAtEpochSec = nowMs / 1000 + TTL_SEC
            )

        /** Parses a pasted link; whitespace and line breaks from messengers are tolerated. */
        fun parse(raw: String): MeshInvite {
            val text = raw.trim().filterNot { it.isWhitespace() }
            val start = text.indexOf(PREFIX)
            require(start >= 0) { "Это не ссылка сопряжения NEXUS Mesh." }
            val payload = text.substring(start + PREFIX.length)
            val json = runCatching { JSONObject(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)) }
                .getOrElse { throw IllegalArgumentException("Ссылка повреждена — скопируй её целиком.") }
            val fingerprint = json.getString("f")
            require(fingerprint.matches(Regex("[0-9a-f]{64}"))) { "В ссылке неверный отпечаток ключа." }
            val secret = Base64.getUrlDecoder().decode(json.getString("s"))
            require(secret.size >= 16) { "В ссылке слишком короткий секрет." }
            val addresses = json.getJSONArray("a").let { array -> (0 until array.length()).map { array.getString(it) } }
                .filter { it.length <= 64 }.take(8)
            return MeshInvite(
                id = json.getString("i").take(32),
                secret = secret,
                fingerprint = fingerprint,
                deviceId = json.getString("d").take(32),
                name = json.getString("n").take(40),
                addresses = addresses,
                expiresAtEpochSec = json.getLong("e")
            )
        }
    }
}
