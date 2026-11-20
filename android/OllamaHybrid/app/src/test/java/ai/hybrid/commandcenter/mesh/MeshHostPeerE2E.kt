package ai.hybrid.commandcenter.mesh

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The second Mesh node for the emulator E2E: runs on the CI host, joins the
 * phone from its invite link (key pinned from the link), confirms pairing and
 * sends one real task. Skipped unless the workflow provides MESH_LINK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MeshHostPeerE2E {
    @Test fun hostPeerPairsWithThePhoneAndRunsATask() {
        val linkText = System.getenv("MESH_LINK")
        assumeTrue("set MESH_LINK to run", !linkText.isNullOrBlank())
        runBlocking {
            hostPeerFlow(linkText!!, System.getenv("MESH_TARGET") ?: "127.0.0.1:47811", File(System.getenv("MESH_OUT") ?: "build/mesh-result.txt"))
        }
    }
}

/** What the CI host node does against the phone; also run in-process against a local node by [MeshNodeTest]. */
internal suspend fun hostPeerFlow(linkText: String, target: String, out: File) {
        val worker = object : MeshWorker {
            override fun status() = JSONObject().put("core", "CI host peer").put("profile", "OFF")
            override fun deviceState(running: Int) = MeshPolicy.DeviceState(100, true, true, 0, running)
            override suspend fun run(op: MeshOp, params: JSONObject, progress: (String) -> Unit) = "host"
        }
        val trust = InMemoryTrustStore()
        val node = MeshNode(SoftwareIdentity.generate(), trust, worker, { "ПК-узел CI" }, { MeshPolicy.of(MeshProfile.OFF) })
        val log = StringBuilder()
        try {
            log.appendLine("link: " + linkText.take(24) + "… (" + linkText.length + " chars)")
            val invite = MeshInvite.parse(linkText)
            log.appendLine("invite from ${invite.name} ${invite.deviceId}, pinned key ${invite.fingerprint.take(16)}")
            val state = node.join(invite.copy(addresses = listOf(target)))
            log.appendLine("pairing started, SAS ${state.sas}")
            node.confirmPairing()
            withTimeout(300_000) {
                while (node.pairing.value?.status != PairingState.Status.PAIRED) {
                    if (node.pairing.value?.status == PairingState.Status.FAILED) error("pairing failed: " + node.pairing.value?.error)
                    delay(200)
                }
            }
            val session = node.sessions.value[invite.fingerprint]
            log.appendLine("paired; channel ${session?.tls}")
            val rtts = (1..5).mapNotNull { node.ping(invite.fingerprint) }
            log.appendLine("ping ${rtts.joinToString()} ms")
            val status = node.runRemote(invite.fingerprint, MeshOp.NODE_STATUS, JSONObject(), timeoutMs = 60_000)
            log.appendLine("status ok=${status.ok} in ${status.millis} ms: " + (status.output ?: status.error)?.replace('\n', ' ')?.take(600))
            val bench = node.runRemote(invite.fingerprint, MeshOp.RUNTIME_BENCHMARK, JSONObject(), timeoutMs = 300_000)
            log.appendLine("benchmark ok=${bench.ok} in ${bench.millis} ms: " + (bench.output ?: bench.error))
            // the model answer goes last: the phone's journey finishes when it sees this task done
            val outcome = node.runRemote(
                invite.fingerprint, MeshOp.INFERENCE_RUN,
                JSONObject().put("prompt", "Ответь одним предложением: что такое NEXUS Mesh?"), timeoutMs = 540_000
            )
            log.appendLine("task ok=${outcome.ok} in ${outcome.millis} ms")
            log.appendLine("answer: " + (outcome.output ?: outcome.error))
            assertTrue(status.error ?: "", status.ok)
            assertTrue(outcome.error ?: "", outcome.ok)
        } catch (error: Throwable) {
            log.appendLine("FAILED: " + error.stackTraceToString().take(4_000))
            throw error
        } finally {
            out.parentFile?.mkdirs()
            out.writeText(log.toString())
            println(log)
            node.shutdown()
        }
}
