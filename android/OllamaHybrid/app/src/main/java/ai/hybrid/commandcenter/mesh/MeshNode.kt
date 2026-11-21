package ai.hybrid.commandcenter.mesh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Executes the closed set of operations on this phone; implemented by the app over its real AI core. */
interface MeshWorker {
    fun status(): JSONObject
    fun deviceState(running: Int): MeshPolicy.DeviceState
    suspend fun run(op: MeshOp, params: JSONObject, progress: (String) -> Unit): String
}

data class PairingState(
    val inviteId: String,
    val incoming: Boolean,
    val peerName: String,
    val peerDeviceId: String,
    val peerFingerprint: String,
    val sas: String,
    val localConfirmed: Boolean = false,
    val remoteConfirmed: Boolean = false,
    val status: Status = Status.WAITING,
    val error: String? = null
) {
    enum class Status { WAITING, PAIRED, FAILED }
}

data class SessionInfo(
    val fingerprint: String,
    val deviceId: String,
    val name: String,
    val address: String,
    val outgoing: Boolean,
    val tls: String,
    val since: Long,
    val rttMs: Long? = null,
    val capabilities: JSONObject? = null
)

data class MeshTaskRecord(
    val id: String,
    val outgoing: Boolean,
    val peerName: String,
    val op: MeshOp,
    val status: Status,
    val note: String = "",
    val startedAt: Long,
    val finishedAt: Long? = null,
    val output: String? = null
) {
    enum class Status(val title: String) {
        SENT("Отправлена"), ACCEPTED("Принята"), RUNNING("Выполняется"), DONE("Готово"),
        REJECTED("Отклонена"), FAILED("Сбой"), CANCELLED("Отменена")
    }
}

data class MeshTaskOutcome(val ok: Boolean, val output: String?, val error: String?, val millis: Long)

/** Address rules: by default the worker only talks to private networks (LAN, Tailscale/CGNAT, ULA). */
object MeshAddresses {
    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val bytes = address.address
        return when (address) {
            is Inet4Address -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64 // 100.64.0.0/10
            is Inet6Address -> (bytes[0].toInt() and 0xFE) == 0xFC // fc00::/7
            else -> false
        }
    }

    /** «192.168.1.23:47811», «100.101.7.8:47811» — what goes into an invite. */
    fun local(port: Int): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { isPrivate(it) }
            .map { it.hostAddress + ":" + port }
            .distinct()
    }.getOrDefault(emptyList())

    fun parse(hostPort: String): InetSocketAddress? {
        val index = hostPort.lastIndexOf(':')
        if (index <= 0) return null
        val port = hostPort.substring(index + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return InetSocketAddress(hostPort.substring(0, index).trim('[', ']'), port)
    }
}

/**
 * One phone in the private mesh: a TLS server for trusted peers and pairing,
 * a client for reaching them, and a worker for the typed operations. Every
 * status shown to the owner comes from a real handshake or a real message.
 */
class MeshNode(
    val identity: MeshIdentity,
    private val trust: MeshTrustStore,
    private val worker: MeshWorker,
    private val name: () -> String,
    private val policy: () -> MeshPolicy,
    private val allowPublicAddresses: () -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    /** Fresh addresses for a peer (mDNS), tried before the stored ones. */
    private val hints: (TrustedPeer) -> List<String> = { emptyList() }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val random = SecureRandom()
    @Volatile private var server: SSLServerSocket? = null

    private val _port = MutableStateFlow<Int?>(null)
    val port: StateFlow<Int?> = _port
    private val _pairing = MutableStateFlow<PairingState?>(null)
    val pairing: StateFlow<PairingState?> = _pairing
    private val _sessions = MutableStateFlow<Map<String, SessionInfo>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionInfo>> = _sessions
    private val _tasks = MutableStateFlow(
        trust.tasks().map { task ->
            // A task cannot outlive the process that ran it: say so instead of showing it «running» forever.
            if (task.status in UNFINISHED) task.copy(status = MeshTaskRecord.Status.FAILED, note = "Прервано: приложение было закрыто", finishedAt = task.finishedAt ?: task.startedAt)
            else task
        }
    )
    val tasks: StateFlow<List<MeshTaskRecord>> = _tasks
    private val _peers = MutableStateFlow(trust.peers())
    val peers: StateFlow<List<TrustedPeer>> = _peers

    private val live = ConcurrentHashMap<String, Session>()
    private val invites = ConcurrentHashMap<String, InviteState>()
    private val runningIncoming = AtomicInteger(0)
    private val recentUntrusted = ArrayDeque<Long>()

    /** [claimed]: a phone proved the secret and its pairing is in progress — one per invite. */
    private class InviteState(
        val invite: MeshInvite, var used: Boolean = false, var failures: Int = 0, var claimed: Boolean = false
    )

    private inner class Session(val socket: SSLSocket, val address: String, val outgoing: Boolean) {
        val fingerprint: String = MeshFingerprint.of(socket.session.peerCertificates[0] as X509Certificate)
        val input = DataInputStream(BufferedInputStream(socket.inputStream))
        val output = DataOutputStream(BufferedOutputStream(socket.outputStream))
        @Volatile var peerName = "?"
        @Volatile var peerDeviceId = "?"
        @Volatile var trusted = false
        @Volatile var pairingInvite: String? = null
        /** Where this peer listens: the dialled address, or its address plus the port from its hello. */
        @Volatile var callback: String? = if (outgoing) address else null
        @Volatile var rttMs: Long? = null
        @Volatile var capabilities: JSONObject? = null
        val since = clock()
        val waiting = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
        val pongs = ConcurrentHashMap<Long, CompletableDeferred<Long>>()
        val running = ConcurrentHashMap<String, Job>()
        val readyToTrust = CompletableDeferred<Unit>()

        /**
         * Frames are written by this session's own thread, in order. Buttons call into the node
         * from Android's main thread, where a socket write is refused (NetworkOnMainThreadException):
         * the emulator E2E caught a «Код совпадает» tap whose confirmation never left the phone.
         */
        private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "nexus-mesh-writer").apply { isDaemon = true }
        }
        private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Queues one frame. Never blocks and never touches the network on the caller's thread. */
        fun send(message: JSONObject) {
            if (closed.get()) throw java.io.IOException("Соединение закрыто.")
            try {
                writer.execute {
                    try {
                        MeshProtocol.write(output, message)
                    } catch (error: Exception) {
                        close(error.message ?: "Ошибка записи в канал.")
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                throw java.io.IOException("Соединение закрыто.")
            }
        }

        fun close(reason: String? = null) {
            if (!closed.compareAndSet(false, true)) return
            waiting.values.forEach { it.completeExceptionally(java.io.IOException(reason ?: "Соединение закрыто.")) }
            running.values.forEach { it.cancel() }
            if (live[fingerprint] === this) {
                live.remove(fingerprint)
                publishSessions()
            }
            // «bye» goes out after everything already queued; the socket is closed off the caller's thread too.
            val finish = Runnable {
                runCatching { if (reason != null) MeshProtocol.write(output, MeshProtocol.message("bye", "reason" to reason.take(200))) }
                runCatching { socket.close() }
            }
            try {
                writer.execute(finish)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                Thread(finish, "nexus-mesh-close").start()
            }
            writer.shutdown()
        }

        fun info() = SessionInfo(
            fingerprint, peerDeviceId, peerName, address, outgoing,
            socket.session.protocol + " · " + socket.session.cipherSuite.removePrefix("TLS_"), since, rttMs, capabilities
        )
    }

    // ------------------------------------------------------------------ lifecycle

    /** Opens the TLS listener; port 0 picks a free one (tests). Returns the bound port. */
    fun start(port: Int = MeshProtocol.DEFAULT_PORT): Int {
        server?.let { return it.localPort }
        val context = tlsContext(AnyPeerTrustManager())
        val socket = context.serverSocketFactory.createServerSocket() as SSLServerSocket
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(port), 16)
        socket.needClientAuth = true
        harden(socket)
        server = socket
        _port.value = socket.localPort
        scope.launch {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() as SSLSocket }.getOrNull() ?: break
                launch { handleIncoming(client) }
            }
        }
        audit("start", "Узел слушает порт ${socket.localPort}")
        return socket.localPort
    }

    fun stop() {
        live.values.toList().forEach { it.close("Узел остановлен владельцем.") }
        runCatching { server?.close() }
        server = null
        _port.value = null
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    // ------------------------------------------------------------------ pairing

    fun createInvite(addresses: List<String>): MeshInvite {
        invites.entries.removeIf { it.value.invite.expired(clock()) || it.value.used || it.value.claimed }
        val invite = MeshInvite.create(identity, name(), addresses, clock())
        invites[invite.id] = InviteState(invite)
        audit("invite", "Создано приглашение ${invite.id.take(6)} на ${MeshInvite.TTL_SEC / 60} мин")
        return invite
    }

    fun cancelInvites() = invites.clear()

    /** Joins the phone that created [invite]; the inviter's key is pinned from the link, so a relay cannot pose as it. */
    suspend fun join(invite: MeshInvite): PairingState = withContext(Dispatchers.IO) {
        require(!invite.expired(clock())) { "Срок приглашения истёк — создай новое на том телефоне." }
        require(invite.fingerprint != identity.fingerprint) { "Это приглашение этого же телефона." }
        val session = openTo(invite.addresses, invite.fingerprint)
        val inviterNonce: String
        val joinerNonce = MeshProtocol.newNonce()
        try {
            val peer = exchangeHello(session)
            require(peer.optString("device") == MeshFingerprint.deviceId(invite.fingerprint)) { "Устройство представилось чужим именем." }
            // Commit to our nonce first; the inviter answers with its own, then ours is revealed.
            val commit = MeshProtocol.commitment(invite.id, invite.fingerprint, identity.fingerprint, joinerNonce)
            val proof = MeshProtocol.pairingProof(invite.secret, invite.id, invite.fingerprint, identity.fingerprint, commit)
            session.send(MeshProtocol.message("pair_request", "invite" to invite.id, "proof" to proof, "commit" to commit))
            val answer = MeshProtocol.read(session.input)
            when (answer.optString("t")) {
                "pair_nonce" -> inviterNonce = answer.getString("nonce")
                "pair_reject" -> {
                    val reason = answer.optString("reason").ifBlank { "Другой телефон отклонил сопряжение." }
                    session.close(reason)
                    audit("pair-fail", reason)
                    val failed = PairingState(
                        invite.id, incoming = false, peerName = session.peerName, peerDeviceId = session.peerDeviceId,
                        peerFingerprint = session.fingerprint, sas = "", status = PairingState.Status.FAILED, error = reason
                    )
                    _pairing.value = failed
                    return@withContext failed
                }
                else -> throw MeshProtocol.ProtocolException("Ожидался ответ на запрос сопряжения.")
            }
            session.send(MeshProtocol.message("pair_reveal", "nonce" to joinerNonce))
        } catch (error: Exception) {
            session.close(error.message)
            throw error
        }
        session.pairingInvite = invite.id
        session.socket.soTimeout = (PAIRING_TIMEOUT_MS + 10_000).toInt()
        val state = PairingState(
            invite.id, incoming = false, peerName = session.peerName, peerDeviceId = session.peerDeviceId,
            peerFingerprint = session.fingerprint,
            sas = MeshProtocol.sas(invite.fingerprint, identity.fingerprint, invite.id, inviterNonce, joinerNonce)
        )
        _pairing.value = state
        pendingPairings[invite.id] = session
        audit("pair", "Запрос сопряжения отправлен: ${session.peerName}")
        scope.launch { readLoop(session) }
        armPairingTimeout(invite.id, session)
        state
    }

    private val pendingPairings = ConcurrentHashMap<String, Session>()

    fun confirmPairing() {
        val state = _pairing.value ?: return
        if (state.status != PairingState.Status.WAITING || state.localConfirmed) return
        val session = pendingPairings[state.inviteId] ?: return failPairing(state.inviteId, "Соединение для сопряжения уже закрыто.")
        runCatching { session.send(MeshProtocol.message("pair_confirm")) }
        // Atomic: the other phone's confirmation may land on the network thread at this very moment.
        _pairing.update { current -> if (current?.inviteId == state.inviteId) current.copy(localConfirmed = true) else current }
        maybeCompletePairing(state.inviteId)
    }

    fun rejectPairing(reason: String = "Владелец отклонил сопряжение.") {
        val state = _pairing.value ?: return
        pendingPairings[state.inviteId]?.let { runCatching { it.send(MeshProtocol.message("pair_reject", "reason" to reason)) } }
        failPairing(state.inviteId, reason)
    }

    fun clearPairing() {
        if (_pairing.value?.status != PairingState.Status.WAITING) _pairing.value = null
    }

    private fun maybeCompletePairing(inviteId: String) {
        val state = _pairing.value?.takeIf { it.inviteId == inviteId } ?: return
        if (!state.localConfirmed || !state.remoteConfirmed) return
        val session = pendingPairings.remove(inviteId) ?: return
        val peer = TrustedPeer(
            fingerprint = session.fingerprint,
            deviceId = session.peerDeviceId,
            name = session.peerName,
            pairedAt = clock(),
            grants = MeshOp.DEFAULT_GRANTS,
            addresses = listOfNotNull(session.callback),
            lastSeenAt = clock()
        )
        trust.save(peer)
        invites[inviteId]?.used = true
        _peers.value = trust.peers()
        _pairing.update { current -> if (current?.inviteId == inviteId) current.copy(status = PairingState.Status.PAIRED) else current }
        audit("paired", "Сопряжено: ${peer.name} (${peer.deviceId}), код ${state.sas}")
        establish(session)
    }

    private fun failPairing(inviteId: String, reason: String) {
        pendingPairings.remove(inviteId)?.close(reason)
        _pairing.update { current ->
            if (current != null && current.inviteId == inviteId && current.status == PairingState.Status.WAITING) {
                current.copy(status = PairingState.Status.FAILED, error = reason)
            } else current
        }
        audit("pair-fail", reason)
    }

    private fun armPairingTimeout(inviteId: String, session: Session) {
        scope.launch {
            delay(PAIRING_TIMEOUT_MS)
            if (pendingPairings[inviteId] === session) failPairing(inviteId, "Сопряжение не подтверждено за 2 минуты.")
        }
    }

    // ------------------------------------------------------------------ trusted connections

    /** Connects to a paired phone: tries every known address, pinning its key. */
    suspend fun connect(peer: TrustedPeer, extraAddresses: List<String> = emptyList()): SessionInfo = withContext(Dispatchers.IO) {
        live[peer.fingerprint]?.let { return@withContext it.info() }
        check(peer.active) { "Доверие к этому устройству отозвано." }
        val addresses = (extraAddresses + hints(peer) + peer.addresses).distinct()
        require(addresses.isNotEmpty()) { "Неизвестен адрес устройства — оно должно быть в сети NEXUS." }
        val session = openTo(addresses, peer.fingerprint)
        exchangeHello(session)
        establish(session)
        scope.launch { readLoop(session) }
        session.info()
    }

    fun disconnect(fingerprint: String) {
        live[fingerprint]?.close("Отключено владельцем.")
    }

    fun revoke(fingerprint: String) {
        trust.revoke(fingerprint, clock())
        live[fingerprint]?.close("Доверие отозвано.")
        _peers.value = trust.peers()
    }

    /** Measures a real round trip on the encrypted channel. */
    suspend fun ping(fingerprint: String): Long? {
        val session = live[fingerprint] ?: return null
        val n = random.nextLong()
        val deferred = CompletableDeferred<Long>()
        session.pongs[n] = deferred
        val sent = System.nanoTime()
        runCatching { session.send(MeshProtocol.message("ping", "n" to n)) }.onFailure { return null }
        val back = withTimeoutOrNull(10_000) { deferred.await() } ?: return null
        val rtt = (back - sent) / 1_000_000
        session.rttMs = rtt
        publishSessions()
        return rtt
    }

    /** Sends one typed task to a trusted phone and waits for its real result. */
    suspend fun runRemote(fingerprint: String, op: MeshOp, params: JSONObject, timeoutMs: Long = 300_000): MeshTaskOutcome {
        val started = clock()
        val peer = trust.trusted(fingerprint) ?: return MeshTaskOutcome(false, null, "Устройство не сопряжено или доверие отозвано.", 0)
        val session = live[fingerprint] ?: runCatching { connect(peer); live[fingerprint] }.getOrElse {
            return MeshTaskOutcome(false, null, "Не удалось соединиться: " + (it.message ?: "сеть недоступна"), clock() - started)
        } ?: return MeshTaskOutcome(false, null, "Соединение не установлено.", clock() - started)
        val id = MeshProtocol.randomId()
        val deferred = CompletableDeferred<JSONObject>()
        session.waiting[id] = deferred
        record(MeshTaskRecord(id, true, peer.name, op, MeshTaskRecord.Status.SENT, startedAt = started))
        return try {
            session.send(MeshProtocol.message("task", "id" to id, "op" to op.wire, "params" to params))
            val final = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (final == null) {
                runCatching { session.send(MeshProtocol.message("task_cancel", "id" to id)) }
                update(id) { it.copy(status = MeshTaskRecord.Status.FAILED, note = "Нет ответа за ${timeoutMs / 1000} с", finishedAt = clock()) }
                MeshTaskOutcome(false, null, "Узел не ответил вовремя.", clock() - started)
            } else if (final.optString("t") == "task_result") {
                val output = final.optString("output")
                update(id) { it.copy(status = MeshTaskRecord.Status.DONE, output = output.take(4_000), finishedAt = clock()) }
                MeshTaskOutcome(true, output, null, clock() - started)
            } else {
                val reason = final.optString("reason", "Отклонено узлом.")
                MeshTaskOutcome(false, null, reason, clock() - started)
            }
        } catch (cancel: CancellationException) {
            runCatching { session.send(MeshProtocol.message("task_cancel", "id" to id)) }
            update(id) { it.copy(status = MeshTaskRecord.Status.CANCELLED, finishedAt = clock()) }
            throw cancel
        } catch (error: Exception) {
            update(id) { it.copy(status = MeshTaskRecord.Status.FAILED, note = error.message ?: "обрыв связи", finishedAt = clock()) }
            MeshTaskOutcome(false, null, error.message ?: "Соединение оборвалось.", clock() - started)
        } finally {
            session.waiting.remove(id)
        }
    }

    // ------------------------------------------------------------------ internals

    private fun handleIncoming(socket: SSLSocket) {
        val remote = socket.inetAddress
        if (!allowPublicAddresses() && !MeshAddresses.isPrivate(remote)) {
            audit("blocked", "Отклонено подключение с публичного адреса ${remote.hostAddress}")
            runCatching { socket.close() }
            return
        }
        var session: Session? = null
        try {
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            harden(socket)
            socket.startHandshake()
            val current = Session(socket, remote.hostAddress + ":" + socket.port, outgoing = false)
            session = current
            val hello = MeshProtocol.read(current.input)
            if (hello.optString("t") != "hello") throw MeshProtocol.ProtocolException("Первым должно быть приветствие.")
            acceptHello(current, hello)
            current.send(helloMessage())
            val peer = trust.trusted(current.fingerprint)
            if (peer != null) {
                establish(current)
                readLoop(current)
                return
            }
            if (!allowUntrusted()) throw MeshProtocol.ProtocolException("Слишком много попыток подключения — пауза.")
            // Not trusted: the only thing allowed is a pairing request for a live invite.
            val request = MeshProtocol.read(current.input)
            if (request.optString("t") != "pair_request") throw MeshProtocol.ProtocolException("Устройство не сопряжено.")
            val inviteId = request.getString("invite")
            val commit = request.getString("commit")
            val state = invites[inviteId]
            val reason = if (state == null) "Приглашение не найдено — создай новое." else synchronized(state) {
                when {
                    state.used -> "Приглашение уже использовано."
                    // One pairing per invite: a copied link cannot take over a pairing in progress.
                    state.claimed -> "Приглашение уже использует другое устройство — создай новое."
                    state.invite.expired(clock()) -> "Срок приглашения истёк."
                    !MeshProtocol.proofMatches(
                        MeshProtocol.pairingProof(state.invite.secret, inviteId, identity.fingerprint, current.fingerprint, commit),
                        request.getString("proof")
                    ) -> {
                        state.failures++
                        if (state.failures >= 3) invites.remove(inviteId)
                        "Неверная ссылка сопряжения."
                    }
                    else -> {
                        state.claimed = true
                        null
                    }
                }
            }
            if (reason != null) {
                audit("pair-denied", reason + " (" + current.peerName + ", " + current.address + ")")
                current.send(MeshProtocol.message("pair_reject", "reason" to reason))
                current.close()
                return
            }
            // Our nonce goes out only now, after the joiner is bound to its own by the commitment.
            val inviterNonce = MeshProtocol.newNonce()
            current.send(MeshProtocol.message("pair_nonce", "nonce" to inviterNonce))
            val reveal = MeshProtocol.read(current.input)
            if (reveal.optString("t") != "pair_reveal") throw MeshProtocol.ProtocolException("Ожидалось раскрытие кода сопряжения.")
            val joinerNonce = reveal.getString("nonce")
            if (!MeshProtocol.commitmentMatches(commit, inviteId, identity.fingerprint, current.fingerprint, joinerNonce)) {
                val refused = "Проверка кода сопряжения не прошла."
                audit("pair-denied", refused + " (" + current.peerName + ", " + current.address + ")")
                current.send(MeshProtocol.message("pair_reject", "reason" to refused))
                current.close()
                return
            }
            current.pairingInvite = inviteId
            current.socket.soTimeout = (PAIRING_TIMEOUT_MS + 10_000).toInt()
            pendingPairings[inviteId] = current
            _pairing.value = PairingState(
                inviteId, incoming = true, peerName = current.peerName, peerDeviceId = current.peerDeviceId,
                peerFingerprint = current.fingerprint,
                sas = MeshProtocol.sas(identity.fingerprint, current.fingerprint, inviteId, inviterNonce, joinerNonce)
            )
            audit("pair", "Запрос сопряжения от ${current.peerName} (${current.peerDeviceId})")
            armPairingTimeout(inviteId, current)
            readLoop(current)
        } catch (error: Exception) {
            if (session == null) audit("tls-fail", "Рукопожатие не прошло: " + (error.message ?: error.javaClass.simpleName).take(120))
            session?.close(error.message) ?: runCatching { socket.close() }
        }
    }

    @Synchronized
    private fun allowUntrusted(): Boolean {
        val now = clock()
        while (recentUntrusted.isNotEmpty() && now - recentUntrusted.first() > 60_000) recentUntrusted.removeFirst()
        if (recentUntrusted.size >= 10) return false
        recentUntrusted.addLast(now)
        return true
    }

    private fun openTo(addresses: List<String>, pinnedFingerprint: String): Session {
        val context = tlsContext(PinnedTrustManager(pinnedFingerprint))
        var last: Exception? = null
        for (hostPort in addresses) {
            val target = MeshAddresses.parse(hostPort) ?: continue
            try {
                val plain = java.net.Socket()
                plain.connect(target, CONNECT_TIMEOUT_MS)
                val socket = context.socketFactory.createSocket(plain, target.hostString, target.port, true) as SSLSocket
                harden(socket)
                socket.useClientMode = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                socket.startHandshake()
                return Session(socket, hostPort, outgoing = true)
            } catch (error: Exception) {
                last = error
            }
        }
        throw java.io.IOException(
            when {
                last is javax.net.ssl.SSLHandshakeException -> "Ключ устройства не совпал — соединение прервано (возможна подмена)."
                last != null -> "Устройство недоступно по известным адресам: " + (last.message ?: last.javaClass.simpleName)
                else -> "Нет адреса устройства."
            }
        )
    }

    private fun exchangeHello(session: Session): JSONObject {
        session.send(helloMessage())
        val hello = MeshProtocol.read(session.input)
        if (hello.optString("t") != "hello") throw MeshProtocol.ProtocolException("Ожидалось приветствие.")
        acceptHello(session, hello)
        return hello
    }

    private fun acceptHello(session: Session, hello: JSONObject) {
        val claimed = hello.getString("device")
        if (claimed != MeshFingerprint.deviceId(session.fingerprint)) {
            throw MeshProtocol.ProtocolException("ID устройства не соответствует его ключу.")
        }
        session.peerDeviceId = claimed
        session.peerName = hello.getString("name").ifBlank { claimed }
        val port = hello.optInt("port", 0)
        if (!session.outgoing && port in 1..65535) {
            // An incoming socket shows an ephemeral source port; the hello says where the peer really listens.
            val host = session.socket.inetAddress
            session.callback = (if (host is Inet6Address) "[" + host.hostAddress + "]" else host.hostAddress) + ":" + port
        }
    }

    private fun helloMessage() = MeshProtocol.message(
        "hello", "device" to identity.deviceId, "name" to name().take(40), "app" to "nexus", "port" to (server?.localPort ?: 0)
    )

    private fun establish(session: Session) {
        session.trusted = true
        session.socket.soTimeout = IDLE_TIMEOUT_MS
        if (session.outgoing) {
            // The connecting side keeps the channel alive and asks for the peer's capability profile.
            scope.launch { heartbeat(session) }
            scope.launch { runCatching { session.send(MeshProtocol.message("status_request")) } }
        }
        live.put(session.fingerprint, session)?.takeIf { it !== session }?.close("Новое соединение заменило старое.")
        trust.peer(session.fingerprint)?.let { peer ->
            val addresses = (listOfNotNull(session.callback) + peer.addresses).distinct().take(6)
            trust.save(peer.copy(lastSeenAt = clock(), addresses = addresses, name = session.peerName))
            _peers.value = trust.peers()
        }
        publishSessions()
    }

    /** One snapshot of the live sessions; synchronized so an older snapshot never overwrites a newer one. */
    @Synchronized
    private fun publishSessions() {
        _sessions.value = live.values.associate { it.fingerprint to it.info() }
    }

    private suspend fun heartbeat(session: Session) {
        while (scope.isActive && !session.socket.isClosed && live[session.fingerprint] === session) {
            delay(HEARTBEAT_MS)
            if (ping(session.fingerprint) == null && live[session.fingerprint] === session) {
                session.close("Нет ответа на heartbeat.")
            }
        }
    }

    private fun readLoop(session: Session) {
        try {
            while (!session.socket.isClosed) {
                val message = MeshProtocol.read(session.input)
                handle(session, message)
            }
        } catch (_: Exception) {
            // closed or timed out
        } finally {
            session.pairingInvite?.let { invite ->
                if (pendingPairings[invite] === session) failPairing(invite, "Соединение оборвалось до подтверждения.")
            }
            session.close()
        }
    }

    private fun handle(session: Session, message: JSONObject) {
        when (message.getString("t")) {
            "ping" -> session.send(MeshProtocol.message("pong", "n" to message.optLong("n")))
            "pong" -> session.pongs.remove(message.optLong("n"))?.complete(System.nanoTime())
            "bye" -> session.close()
            "pair_confirm" -> {
                val invite = session.pairingInvite ?: return
                _pairing.update { current -> if (current?.inviteId == invite) current.copy(remoteConfirmed = true) else current }
                maybeCompletePairing(invite)
            }
            "pair_reject" -> session.pairingInvite?.let { failPairing(it, message.optString("reason", "Другой телефон отклонил сопряжение.")) }
            else -> {
                if (!session.trusted) throw MeshProtocol.ProtocolException("Устройство не сопряжено.")
                handleTrusted(session, message)
            }
        }
    }

    private fun handleTrusted(session: Session, message: JSONObject) {
        val id = message.optString("id")
        when (message.getString("t")) {
            "status_request" -> session.send(MeshProtocol.message("status", "profile" to worker.status()))
            "status" -> message.optJSONObject("profile")?.let { profile ->
                session.capabilities = profile
                publishSessions()
            }
            "task" -> acceptTask(session, message)
            "task_cancel" -> session.running.remove(id)?.cancel()
            "task_accept" -> update(id) { it.copy(status = MeshTaskRecord.Status.ACCEPTED) }
            "task_progress" -> update(id) { it.copy(status = MeshTaskRecord.Status.RUNNING, note = message.optString("note")) }
            "task_result" -> session.waiting[id]?.complete(message)
            "task_reject" -> {
                update(id) {
                    it.copy(
                        status = if (message.optString("code") == "failed") MeshTaskRecord.Status.FAILED else MeshTaskRecord.Status.REJECTED,
                        note = message.optString("reason"), finishedAt = clock()
                    )
                }
                session.waiting[id]?.complete(message)
            }
            "error" -> session.close(message.optString("reason"))
            else -> throw MeshProtocol.ProtocolException("Неожиданное сообщение.")
        }
    }

    private fun acceptTask(session: Session, message: JSONObject) {
        val id = message.getString("id")
        val op = MeshOp.byWire(message.getString("op")) ?: return
        val params = message.optJSONObject("params") ?: JSONObject()
        val peer = trust.trusted(session.fingerprint)
        val promptChars = params.optString("prompt").length
        val denial = when {
            peer == null -> "Устройство больше не доверенное."
            op !in peer.grants -> "У этого устройства нет разрешения на «${op.title}»."
            else -> (policy().evaluate(op, promptChars, worker.deviceState(runningIncoming.get())) as? MeshPolicy.Verdict.Deny)?.reason
        }
        if (denial != null) {
            session.send(MeshProtocol.message("task_reject", "id" to id, "reason" to denial, "code" to "denied"))
            record(MeshTaskRecord(id, false, session.peerName, op, MeshTaskRecord.Status.REJECTED, denial, clock(), clock()))
            audit("task-denied", "${session.peerName}: ${op.wire} — $denial")
            return
        }
        session.send(MeshProtocol.message("task_accept", "id" to id))
        record(MeshTaskRecord(id, false, session.peerName, op, MeshTaskRecord.Status.RUNNING, startedAt = clock()))
        runningIncoming.incrementAndGet()
        val job = scope.launch {
            try {
                val output = worker.run(op, params) { note ->
                    runCatching { session.send(MeshProtocol.message("task_progress", "id" to id, "note" to note.take(200))) }
                }
                session.send(MeshProtocol.message("task_result", "id" to id, "output" to output.take(64_000)))
                update(id) { it.copy(status = MeshTaskRecord.Status.DONE, output = output.take(4_000), finishedAt = clock()) }
            } catch (cancel: CancellationException) {
                runCatching { session.send(MeshProtocol.message("task_reject", "id" to id, "reason" to "Задача отменена.", "code" to "cancelled")) }
                update(id) { it.copy(status = MeshTaskRecord.Status.CANCELLED, finishedAt = clock()) }
            } catch (error: Exception) {
                val reason = (error.message ?: error.javaClass.simpleName).take(280)
                runCatching { session.send(MeshProtocol.message("task_reject", "id" to id, "reason" to reason, "code" to "failed")) }
                update(id) { it.copy(status = MeshTaskRecord.Status.FAILED, note = reason, finishedAt = clock()) }
            } finally {
                runningIncoming.decrementAndGet()
                session.running.remove(id)
            }
        }
        session.running[id] = job
    }

    private fun record(task: MeshTaskRecord) {
        val next = _tasks.updateAndGet { list -> (listOf(task) + list.filterNot { it.id == task.id && it.outgoing == task.outgoing }).take(HISTORY_SIZE) }
        trust.saveTasks(next)
    }

    private fun update(id: String, change: (MeshTaskRecord) -> MeshTaskRecord) {
        var statusChanged = false
        val next = _tasks.updateAndGet { list ->
            list.map { task ->
                if (task.id != id) task else change(task).also { statusChanged = statusChanged || it.status != task.status }
            }
        }
        // Progress notes arrive often; the stored history only needs status changes.
        if (statusChanged) trust.saveTasks(next)
    }

    /** Forget finished and failed tasks (the security log stays). */
    fun clearHistory() {
        val next = _tasks.updateAndGet { list -> list.filter { it.status in UNFINISHED } }
        trust.saveTasks(next)
    }

    private fun audit(kind: String, text: String) = trust.audit(MeshAuditEvent(clock(), kind, text))

    private fun tlsContext(trustManager: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(identity.keyManagers(), arrayOf(trustManager), random) }

    private fun harden(socket: javax.net.ssl.SSLSocket) {
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket.enabledCipherSuites = socket.supportedCipherSuites.filter(::strongSuite).toTypedArray()
    }

    private fun harden(socket: SSLServerSocket) {
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.3" || it == "TLSv1.2" }.toTypedArray()
        socket.enabledCipherSuites = socket.supportedCipherSuites.filter(::strongSuite).toTypedArray()
    }

    /** TLS 1.3 suites, or TLS 1.2 with ephemeral ECDHE + ECDSA + AEAD (forward secrecy). */
    private fun strongSuite(suite: String): Boolean =
        suite.startsWith("TLS_AES_") || suite.startsWith("TLS_CHACHA20_") ||
            (suite.startsWith("TLS_ECDHE_ECDSA_WITH_") && (suite.contains("_GCM_") || suite.contains("CHACHA20")))

    /** Server side: any client may complete TLS; authorisation happens by fingerprint right after. */
    private class AnyPeerTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            if (chain.isNullOrEmpty()) throw CertificateException("Нет сертификата клиента.")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Серверная проверка здесь не используется.")
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /** Client side: the server must present exactly the key we expect. */
    private class PinnedTrustManager(private val expected: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Клиентская проверка здесь не используется.")
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("Нет сертификата узла.")
            if (MeshFingerprint.of(leaf) != expected) throw CertificateException("Ключ узла не совпадает с ожидаемым.")
        }
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HANDSHAKE_TIMEOUT_MS = 20_000
        const val IDLE_TIMEOUT_MS = 50_000
        const val HEARTBEAT_MS = 15_000L
        const val HISTORY_SIZE = 60
        val UNFINISHED = setOf(MeshTaskRecord.Status.SENT, MeshTaskRecord.Status.ACCEPTED, MeshTaskRecord.Status.RUNNING)
        const val PAIRING_TIMEOUT_MS = 120_000L
    }
}
