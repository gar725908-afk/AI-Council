package ai.hybrid.commandcenter.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** A worker that answers like a real one would, so tasks travel the real wire. */
private class EchoWorker(private val label: String) : MeshWorker {
    @Volatile var charging = true
    override fun status(): JSONObject = JSONObject().put("name", label).put("runtimes", "test")
    override fun deviceState(running: Int) = MeshPolicy.DeviceState(80, charging, true, 0, running)
    override suspend fun run(op: MeshOp, params: JSONObject, progress: (String) -> Unit): String {
        progress("думаю")
        return when (op) {
            MeshOp.INFERENCE_RUN -> "$label ответил на: " + params.optString("prompt")
            MeshOp.NODE_STATUS -> status().toString()
            MeshOp.RUNTIME_BENCHMARK -> "ok"
        }
    }
}

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshNodeTest {
    private val nodes = mutableListOf<MeshNode>()

    private fun node(name: String, profile: MeshProfile = MeshProfile.PERFORMANCE, worker: MeshWorker = EchoWorker(name)): Pair<MeshNode, InMemoryTrustStore> {
        val trust = InMemoryTrustStore()
        var policy = MeshPolicy.of(profile)
        val node = MeshNode(SoftwareIdentity.generate(), trust, worker, { name }, { policy })
        nodes += node
        return node to trust
    }

    @After fun tearDown() = nodes.forEach { it.shutdown() }

    private suspend fun <T> await(timeoutMs: Long = 15_000, read: () -> T?): T = withTimeout(timeoutMs) {
        var value = read()
        while (value == null) {
            delay(20)
            value = read()
        }
        value
    }

    private fun pair(a: MeshNode, b: MeshNode): Unit = runBlocking {
        val port = a.start(0)
        val invite = MeshInvite.parse(a.createInvite(listOf("127.0.0.1:$port")).toLink())
        val joining = b.join(invite)
        val incoming = await { a.pairing.value }
        assertEquals("both screens show the same code", joining.sas, incoming.sas)
        assertTrue(incoming.incoming)
        a.confirmPairing()
        b.confirmPairing()
        await { a.pairing.value?.takeIf { it.status == PairingState.Status.PAIRED } }
        await { b.pairing.value?.takeIf { it.status == PairingState.Status.PAIRED } }
        Unit
    }

    @Test fun twoPhonesPairOverMutualTlsAndRunARealTask() = runBlocking {
        val (a, trustA) = node("Телефон A")
        val (b, trustB) = node("Телефон B")
        pair(a, b)
        assertNotNull(trustA.trusted(b.identity.fingerprint))
        assertNotNull(trustB.trusted(a.identity.fingerprint))
        val session = await { b.sessions.value[a.identity.fingerprint] }
        assertTrue(session.tls, session.tls.startsWith("TLSv1.3"))

        val outcome = b.runRemote(a.identity.fingerprint, MeshOp.INFERENCE_RUN, JSONObject().put("prompt", "2+2"))
        assertTrue(outcome.error, outcome.ok)
        assertEquals("Телефон A ответил на: 2+2", outcome.output)
        assertEquals(MeshTaskRecord.Status.DONE, b.tasks.value.first().status)
        assertEquals(MeshTaskRecord.Status.DONE, await { a.tasks.value.firstOrNull { !it.outgoing && it.status == MeshTaskRecord.Status.DONE } }.status)
        assertNotNull("heartbeat ping measures a real round trip", b.ping(a.identity.fingerprint))
        assertNotNull(await { b.sessions.value[a.identity.fingerprint]?.capabilities })
    }

    @Test fun theCiHostFlowWorksAgainstALocalPhone() = runBlocking {
        // the same code the emulator E2E runs on the CI host, against a «phone» whose owner taps the button
        val (phone, _) = node("Телефон", MeshProfile.BALANCED)
        val port = phone.start(0)
        val link = phone.createInvite(listOf("127.0.0.1:$port")).toLink()
        val owner = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            await(30_000) { phone.pairing.value?.takeIf { it.status == PairingState.Status.WAITING } }
            delay(300)
            phone.confirmPairing()
        }
        val out = java.io.File.createTempFile("mesh-result", ".txt")
        hostPeerFlow(link, "127.0.0.1:$port", out)
        owner.join()
        val log = out.readText()
        assertTrue(log, log.contains("paired; channel TLSv1.3"))
        assertTrue(log, log.contains("status ok=true"))
        assertTrue(log, log.contains("benchmark ok=true"))
        assertTrue(log, log.contains("answer: Телефон ответил на: Ответь одним предложением"))
        val done = phone.tasks.value.filter { !it.outgoing && it.status == MeshTaskRecord.Status.DONE }.map { it.op }.toSet()
        assertEquals(setOf(MeshOp.NODE_STATUS, MeshOp.RUNTIME_BENCHMARK, MeshOp.INFERENCE_RUN), done)
    }

    @Test fun simultaneousConfirmationsNeverLoseEachOther() = runBlocking {
        // Both owners press «Код совпадает» at the same moment, many times over: no confirmation may be lost.
        repeat(12) { round ->
            val (a, _) = node("A$round")
            val (b, _) = node("B$round")
            val port = a.start(0)
            b.join(MeshInvite.parse(a.createInvite(listOf("127.0.0.1:$port")).toLink()))
            await { a.pairing.value }
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val jobs = listOf(a, b).map { n ->
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) { gate.await(); n.confirmPairing() }
            }
            gate.complete(Unit)
            jobs.forEach { it.join() }
            await(10_000) { a.pairing.value?.takeIf { it.status == PairingState.Status.PAIRED } }
            await(10_000) { b.pairing.value?.takeIf { it.status == PairingState.Status.PAIRED } }
            a.shutdown(); b.shutdown()
        }
    }

    @Test fun theInviterCallsTheJoinerBackOnItsListeningPort() = runBlocking {
        val (a, trustA) = node("A")
        val (b, _) = node("B")
        val bPort = b.start(0)
        pair(a, b)
        // not the joiner's ephemeral source port: the port it announced in hello
        assertEquals(listOf("127.0.0.1:$bPort"), trustA.trusted(b.identity.fingerprint)!!.addresses)
        a.disconnect(b.identity.fingerprint)
        await { if (a.sessions.value.containsKey(b.identity.fingerprint)) null else Unit }
        // roles are not fixed: the phone that invited now dials the other one and gives it work
        val outcome = a.runRemote(b.identity.fingerprint, MeshOp.INFERENCE_RUN, JSONObject().put("prompt", "обратно"))
        assertTrue(outcome.error, outcome.ok)
        assertEquals("B ответил на: обратно", outcome.output)
        assertTrue(await { a.sessions.value[b.identity.fingerprint] }.outgoing)
    }

    @Test fun taskHistorySurvivesARestartAndUnfinishedTasksAreMarkedInterrupted() = runBlocking {
        val (a, _) = node("Телефон A")
        val (b, trustB) = node("Телефон B")
        pair(a, b)
        assertTrue(b.runRemote(a.identity.fingerprint, MeshOp.INFERENCE_RUN, JSONObject().put("prompt", "привет")).ok)
        // simulate a task that was still running when the app died
        val running = MeshTaskRecord("dead1", true, "Телефон A", MeshOp.RUNTIME_BENCHMARK, MeshTaskRecord.Status.RUNNING, startedAt = 1_000)
        trustB.saveTasks(listOf(running) + trustB.tasks())

        val restarted = MeshNode(b.identity, trustB, EchoWorker("B"), { "Телефон B" }, { MeshPolicy.of(MeshProfile.BALANCED) })
        nodes += restarted
        val history = restarted.tasks.value
        val done = history.first { it.op == MeshOp.INFERENCE_RUN }
        assertEquals(MeshTaskRecord.Status.DONE, done.status)
        assertEquals("Телефон A ответил на: привет", done.output)
        val dead = history.first { it.id == "dead1" }
        assertEquals("a task cannot keep «running» after a restart", MeshTaskRecord.Status.FAILED, dead.status)
        assertTrue(dead.note, dead.note.contains("Прервано"))

        restarted.clearHistory()
        assertTrue(restarted.tasks.value.isEmpty())
        assertTrue(trustB.tasks().isEmpty())
    }

    @Test fun onlyLocalCoresServeOtherPhonesByDefault() {
        assertTrue(MeshPolicy.isLocalCore("ON_DEVICE_LLAMA_CPP", ""))
        assertTrue(MeshPolicy.isLocalCore("OLLAMA", "http://192.168.1.20:11434"))
        assertTrue(MeshPolicy.isLocalCore("OLLAMA", "http://100.101.7.8:11434"))
        assertTrue(MeshPolicy.isLocalCore("OPENAI_COMPATIBLE", "http://localhost:1234/v1"))
        assertTrue(MeshPolicy.isLocalCore("OLLAMA", "http://[fd7a:115c:a1e0::1]:11434"))
        assertFalse("a public server is a cloud", MeshPolicy.isLocalCore("OPENAI_COMPATIBLE", "https://8.8.8.8/v1"))
        assertFalse("a DNS name could point anywhere", MeshPolicy.isLocalCore("OLLAMA", "https://ollama.example.com"))
        assertFalse(MeshPolicy.isLocalCore("OPENAI", "https://api.openai.com/v1"))
        assertFalse(MeshPolicy.of(MeshProfile.BALANCED).allowCloudCore)
    }

    @Test fun storedHistoryIsTrimmedAndRoundTrips() {
        val long = "я".repeat(5_000)
        val task = MeshTaskRecord("t1", false, "Pixel", MeshOp.NODE_STATUS, MeshTaskRecord.Status.DONE, "заметка", 10, 20, long)
        val back = TaskHistoryCodec.decode(TaskHistoryCodec.encode(listOf(task))).single()
        assertEquals(TaskHistoryCodec.MAX_OUTPUT, back.output!!.length)
        assertEquals(task.copy(output = long.take(TaskHistoryCodec.MAX_OUTPUT)), back)
        assertTrue("garbage is ignored, not a crash", TaskHistoryCodec.decode("{oops").isEmpty())
    }

    @Test fun resourceGovernorRejectsWithAnHonestReason() = runBlocking {
        val worker = EchoWorker("A").apply { charging = false }
        val (a, _) = node("A", MeshProfile.MINIMAL, worker)
        val (b, _) = node("B")
        pair(a, b)
        val outcome = b.runRemote(a.identity.fingerprint, MeshOp.INFERENCE_RUN, JSONObject().put("prompt", "hi"))
        assertFalse(outcome.ok)
        assertTrue(outcome.error, outcome.error!!.contains("зарядке"))
        assertEquals(MeshTaskRecord.Status.REJECTED, b.tasks.value.first().status)
    }

    @Test fun revokedPhoneCanNoLongerRunTasks() = runBlocking {
        val (a, _) = node("A")
        val (b, _) = node("B")
        pair(a, b)
        assertTrue(b.runRemote(a.identity.fingerprint, MeshOp.RUNTIME_BENCHMARK, JSONObject()).ok)
        a.revoke(b.identity.fingerprint)
        await { b.sessions.value[a.identity.fingerprint]?.let { null } ?: Unit }
        val after = b.runRemote(a.identity.fingerprint, MeshOp.RUNTIME_BENCHMARK, JSONObject(), timeoutMs = 10_000)
        assertFalse("a revoked device must be refused", after.ok)
    }

    @Test fun wrongSecretAndSwappedKeyAreRefused() = runBlocking {
        val (a, trustA) = node("A")
        val (c, _) = node("C")
        val port = a.start(0)
        val real = a.createInvite(listOf("127.0.0.1:$port"))
        // C forged the secret: the proof does not match, the inviter rejects.
        val forged = real.copy(secret = ByteArray(16) { 7 })
        c.join(forged)
        val failed = await { c.pairing.value?.takeIf { it.status == PairingState.Status.FAILED } }
        assertTrue(failed.error, failed.error!!.contains("Неверная ссылка"))
        assertNull(a.pairing.value?.takeIf { it.status == PairingState.Status.WAITING })
        assertTrue(trustA.auditLog().any { it.kind == "pair-denied" })
        // A link whose key was swapped (someone in the middle) fails at the TLS pin.
        val (d, _) = node("D")
        try {
            d.join(real.copy(fingerprint = d.identity.fingerprint.reversed()))
            fail("pinned key mismatch must abort")
        } catch (e: Exception) {
            assertTrue(e.message, e.message!!.contains("Ключ") || e.message!!.contains("недоступно"))
        }
    }

    @Test fun ownerMustConfirmOnBothPhones() = runBlocking {
        val (a, trustA) = node("A")
        val (b, trustB) = node("B")
        val port = a.start(0)
        val invite = a.createInvite(listOf("127.0.0.1:$port"))
        b.join(invite)
        await { a.pairing.value }
        a.confirmPairing()
        delay(300)
        assertNull("no trust until the second phone confirms too", trustA.trusted(b.identity.fingerprint))
        b.rejectPairing()
        await { a.pairing.value?.takeIf { it.status == PairingState.Status.FAILED } }
        assertNull(trustA.trusted(b.identity.fingerprint))
        assertNull(trustB.trusted(a.identity.fingerprint))
    }

    @Test fun aRevealThatBreaksItsCommitmentIsRefused() = runBlocking {
        val (a, trustA) = node("A")
        val port = a.start(0)
        val invite = a.createInvite(listOf("127.0.0.1:$port"))
        RawPeer(port).use { evil ->
            val promised = MeshProtocol.newNonce()
            val commit = MeshProtocol.commitment(invite.id, invite.fingerprint, evil.identity.fingerprint, promised)
            val proof = MeshProtocol.pairingProof(invite.secret, invite.id, invite.fingerprint, evil.identity.fingerprint, commit)
            evil.send(MeshProtocol.message("pair_request", "invite" to invite.id, "proof" to proof, "commit" to commit))
            assertEquals("pair_nonce", evil.read().getString("t"))
            // having seen the inviter's nonce, it tries to swap its own
            evil.send(MeshProtocol.message("pair_reveal", "nonce" to MeshProtocol.newNonce()))
            val answer = evil.read()
            assertEquals("pair_reject", answer.getString("t"))
            assertTrue(answer.optString("reason"), answer.optString("reason").contains("Проверка кода"))
        }
        assertNull("no code is ever shown for a broken commitment", a.pairing.value)
        assertTrue(trustA.auditLog().any { it.kind == "pair-denied" })
    }

    @Test fun aCopiedInviteCannotTakeOverAPairingInProgress() = runBlocking {
        val (a, _) = node("A")
        val (b, trustB) = node("B")
        val port = a.start(0)
        val invite = MeshInvite.parse(a.createInvite(listOf("127.0.0.1:$port")).toLink())
        val joining = b.join(invite)
        assertEquals(joining.sas, await { a.pairing.value }.sas)
        // someone else has a copy of the link (a photo of the QR) and the secret in it
        RawPeer(port).use { copy ->
            val nonce = MeshProtocol.newNonce()
            val commit = MeshProtocol.commitment(invite.id, invite.fingerprint, copy.identity.fingerprint, nonce)
            val proof = MeshProtocol.pairingProof(invite.secret, invite.id, invite.fingerprint, copy.identity.fingerprint, commit)
            copy.send(MeshProtocol.message("pair_request", "invite" to invite.id, "proof" to proof, "commit" to commit))
            val answer = copy.read()
            assertEquals("pair_reject", answer.getString("t"))
            assertTrue(answer.optString("reason"), answer.optString("reason").contains("другое устройство"))
        }
        assertEquals("the pairing in progress is untouched", b.identity.fingerprint, a.pairing.value!!.peerFingerprint)
        a.confirmPairing()
        b.confirmPairing()
        await { a.pairing.value?.takeIf { it.status == PairingState.Status.PAIRED } }
        assertNotNull(await { trustB.trusted(a.identity.fingerprint) })
    }
}

/** A hand-driven client on the real wire, for moves the real node never makes. */
private class RawPeer(port: Int) : AutoCloseable {
    val identity = SoftwareIdentity.generate()
    private val socket: javax.net.ssl.SSLSocket
    private val input: DataInputStream
    private val output: DataOutputStream

    init {
        val acceptAny = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out java.security.cert.X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
        }
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(identity.keyManagers(), arrayOf(acceptAny), java.security.SecureRandom())
        socket = context.socketFactory.createSocket("127.0.0.1", port) as javax.net.ssl.SSLSocket
        socket.soTimeout = 15_000
        socket.startHandshake()
        input = DataInputStream(socket.inputStream.buffered())
        output = DataOutputStream(socket.outputStream.buffered())
        send(MeshProtocol.message("hello", "device" to identity.deviceId, "name" to "raw", "app" to "test", "port" to 0))
        check(read().getString("t") == "hello")
    }

    fun send(message: JSONObject) = MeshProtocol.write(output, message)
    fun read(): JSONObject = MeshProtocol.read(input)
    override fun close() {
        runCatching { socket.close() }
    }
}

class MeshProtocolTest {
    @Test fun framesRoundTripAndUnknownTypesOrOpsAreRejected() {
        val bytes = ByteArrayOutputStream()
        MeshProtocol.write(DataOutputStream(bytes), MeshProtocol.message("task", "id" to "t1", "op" to "inference.run", "params" to JSONObject().put("prompt", "x")))
        val back = MeshProtocol.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals("inference.run", back.getString("op"))

        fun frame(json: String): DataInputStream {
            val raw = json.toByteArray()
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply { writeInt(raw.size); write(raw) }
            return DataInputStream(ByteArrayInputStream(out.toByteArray()))
        }
        for (bad in listOf(
            """{"v":2,"t":"shell","cmd":"rm -rf /"}""",
            """{"v":2,"t":"task","id":"1","op":"shell.exec"}""",
            """{"v":1,"t":"ping"}""",
            """{"v":3,"t":"ping"}""",
            """{"v":2,"t":"hello","device":"x"}""",
            """{"v":2,"t":"pair_request","invite":"i","proof":"${"z".repeat(64)}","commit":"${"a".repeat(64)}"}""",
            """{"v":2,"t":"pair_request","invite":"i","proof":"${"a".repeat(64)}"}""",
            """{"v":2,"t":"pair_reveal","nonce":"abc"}""",
            """{"v":2,"t":"pair_nonce","nonce":"${"A".repeat(32)}"}"""
        )) {
            try {
                MeshProtocol.read(frame(bad))
                fail("accepted: $bad")
            } catch (_: MeshProtocol.ProtocolException) {
            }
        }
        val huge = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(MeshProtocol.MAX_FRAME + 1) }
        try {
            MeshProtocol.read(DataInputStream(ByteArrayInputStream(huge.toByteArray())))
            fail("oversized frame accepted")
        } catch (_: MeshProtocol.ProtocolException) {
        }
    }

    @Test fun proofBindsKeysAndCommitmentAndTheCodeNeedsBothNonces() {
        val secret = ByteArray(16) { it.toByte() }
        val commit = "c".repeat(64)
        val p1 = MeshProtocol.pairingProof(secret, "inv", "a".repeat(64), "b".repeat(64), commit)
        val p2 = MeshProtocol.pairingProof(secret, "inv", "a".repeat(64), "e".repeat(64), commit)
        val p3 = MeshProtocol.pairingProof(secret, "inv", "a".repeat(64), "b".repeat(64), "d".repeat(64))
        assertNotEquals("another key in the middle changes the proof", p1, p2)
        assertNotEquals("another commitment changes the proof", p1, p3)
        assertTrue(MeshProtocol.proofMatches(p1, p1.uppercase()))
        val ni = MeshProtocol.newNonce()
        val nj = MeshProtocol.newNonce()
        assertTrue(ni.matches(Regex("[0-9a-f]{32}")))
        val code = MeshProtocol.sas("a".repeat(64), "b".repeat(64), "inv", ni, nj)
        assertTrue(code.matches(Regex("\\d{3} \\d{3}")))
        assertEquals("both phones compute the same code", code, MeshProtocol.sas("a".repeat(64), "b".repeat(64), "inv", ni, nj))
    }

    /**
     * Why protocol v2. With the v1 formula the six digits depended on the two keys
     * and the invite only, so someone holding a leaked invite who saw the joiner's
     * code could try certificates until one gave the same digits — about a million
     * tries, seconds on one core. In v2 the code also needs both phones' fresh
     * nonces, and the joiner's nonce is fixed by a commitment before the
     * inviter's is sent: nothing can be tried in advance.
     */
    @Test fun v1CodesCouldBeSteeredByTryingKeysV2CodesCannot() {
        val hex = "0123456789abcdef".toCharArray()
        fun fingerprint(random: java.util.Random): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            val out = CharArray(64)
            bytes.forEachIndexed { i, b -> out[2 * i] = hex[(b.toInt() shr 4) and 15]; out[2 * i + 1] = hex[b.toInt() and 15] }
            return String(out)
        }
        val sha = java.security.MessageDigest.getInstance("SHA-256")
        fun v1(a: String, b: String, invite: String): Int {
            val (low, high) = if (a < b) a to b else b to a
            val d = sha.digest("nexus-mesh-sas-v1|$low|$high|$invite".toByteArray())
            return (((d[0].toLong() and 0xFF) shl 24 or ((d[1].toLong() and 0xFF) shl 16) or
                ((d[2].toLong() and 0xFF) shl 8) or (d[3].toLong() and 0xFF)) % 1_000_000).toInt()
        }
        val random = java.util.Random(20261121)
        val inviter = fingerprint(random)
        val joiner = fingerprint(random)
        val shownOnTheJoiner = v1(inviter, joiner, "invite")
        val started = System.nanoTime()
        var tries = 0
        var forged: String? = null
        while (forged == null && tries < 8_000_000) {
            tries++
            val candidate = fingerprint(random)
            if (v1(inviter, candidate, "invite") == shownOnTheJoiner) forged = candidate
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        println("v1: a key giving the joiner's code found after $tries tries in $millis ms")
        assertNotNull("v1 codes can be steered: a matching key exists within reach", forged)

        // v2: the very same key yields a different code for every fresh pair of nonces…
        val codes = (1..2_000).map { MeshProtocol.sas(inviter, forged!!, "invite", MeshProtocol.newNonce(), MeshProtocol.newNonce()) }.toSet()
        assertTrue("v2 codes are not tied to the key (${codes.size} distinct of 2000)", codes.size > 1_950)
        // …and the joiner cannot change its nonce after seeing the inviter's: the commitment binds it.
        val nonce = MeshProtocol.newNonce()
        val commit = MeshProtocol.commitment("invite", inviter, joiner, nonce)
        assertTrue(MeshProtocol.commitmentMatches(commit, "invite", inviter, joiner, nonce))
        assertFalse(MeshProtocol.commitmentMatches(commit, "invite", inviter, joiner, MeshProtocol.newNonce()))
        assertFalse("bound to the keys", MeshProtocol.commitmentMatches(commit, "invite", inviter, forged!!, nonce))
    }

    @Test fun invitesSurviveMessengersAndExpire() {
        val identity = SoftwareIdentity.generate()
        val invite = MeshInvite.create(identity, "Мой телефон", listOf("192.168.1.5:47811"), nowMs = 1_000_000L)
        val pasted = "Вот ссылка:\n" + invite.toLink().chunked(30).joinToString("\n")
        assertEquals(invite, MeshInvite.parse(pasted))
        assertTrue(invite.expired(1_000_000L + (MeshInvite.TTL_SEC + 1) * 1000))
        assertFalse(invite.expired(1_000_000L + 60_000))
        assertEquals(identity.deviceId, MeshFingerprint.deviceId(identity.fingerprint))
        assertTrue(identity.deviceId.matches(Regex("NX-[0-9A-F]{4}-[0-9A-F]{4}")))
    }

    @Test fun privateAddressRules() {
        fun ip(s: String) = java.net.InetAddress.getByName(s)
        assertTrue(MeshAddresses.isPrivate(ip("192.168.0.10")))
        assertTrue(MeshAddresses.isPrivate(ip("10.1.2.3")))
        assertTrue("Tailscale/CGNAT", MeshAddresses.isPrivate(ip("100.101.7.8")))
        assertTrue(MeshAddresses.isPrivate(ip("127.0.0.1")))
        assertFalse(MeshAddresses.isPrivate(ip("8.8.8.8")))
        assertFalse(MeshAddresses.isPrivate(ip("100.200.1.1")))
        assertEquals(47811, MeshAddresses.parse("192.168.1.5:47811")!!.port)
        assertNull(MeshAddresses.parse("nonsense"))
    }

    @Test fun governorProfiles() {
        val state = MeshPolicy.DeviceState(batteryPercent = 20, charging = false, onWifi = false, thermalStatus = 0, running = 0)
        assertTrue(MeshPolicy.of(MeshProfile.OFF).evaluate(MeshOp.NODE_STATUS, 0, state) is MeshPolicy.Verdict.Deny)
        assertTrue(MeshPolicy.of(MeshProfile.BALANCED).evaluate(MeshOp.INFERENCE_RUN, 10, state) is MeshPolicy.Verdict.Deny)
        assertTrue(MeshPolicy.of(MeshProfile.PERFORMANCE).evaluate(MeshOp.INFERENCE_RUN, 10, state) is MeshPolicy.Verdict.Allow)
        assertTrue(MeshPolicy.of(MeshProfile.PERFORMANCE).evaluate(MeshOp.INFERENCE_RUN, 10, state.copy(running = 3)) is MeshPolicy.Verdict.Deny)
        assertTrue(MeshPolicy.of(MeshProfile.PERFORMANCE).evaluate(MeshOp.INFERENCE_RUN, 9_000, state) is MeshPolicy.Verdict.Deny)
    }
}
