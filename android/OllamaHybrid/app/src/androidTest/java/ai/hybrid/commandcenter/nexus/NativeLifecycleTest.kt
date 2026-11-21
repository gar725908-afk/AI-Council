package ai.hybrid.commandcenter.nexus

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The llama.cpp engine's life on a real Android runtime: load, answer, cancel and
 * free, over and over, from several threads, including the race behind
 * CVE-2026-70640 (a context freed while another thread is generating with it).
 *
 * Debuggable apps run with CheckJNI, so any JNI misuse (a pending exception
 * ignored, a leaked local reference table, a bad reference) aborts the process and
 * fails the run. The ASan workflow runs this same class with AddressSanitizer,
 * which turns any use-after-free or overflow into a crash with a report.
 *
 * Argument: model — a small chat GGUF on the device
 * (default /sdcard/Download/gemma-3-270m-it-Q8_0.gguf, pushed by the CI script).
 */
@RunWith(AndroidJUnit4::class)
class NativeLifecycleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val model = File(args.getString("model") ?: "/sdcard/Download/gemma-3-270m-it-Q8_0.gguf")
    private val prompt = (
        "<start_of_turn>user\nCount from one to two hundred in words, separated by commas.<end_of_turn>\n" +
            "<start_of_turn>model\n"
        ).toByteArray(Charsets.UTF_8)

    @Before
    fun engineAndModel() {
        assumeTrue("no test model at $model", model.canRead())
        val backends = NativeLlamaBridge.backends(context.applicationInfo.nativeLibraryDir.orEmpty())
        Log.i(TAG, "backends: $backends")
        assertTrue("no CPU backend was loaded: $backends", backends.contains("CPU"))
    }

    private fun open(): Long =
        NativeLlamaBridge.nativeCreate(model.path.toByteArray(Charsets.UTF_8), 512, 2)
            .also { assertNotEquals("the engine did not open", 0L, it) }

    private fun generate(handle: Long, maxTokens: Int, onPiece: (String) -> Boolean = { true }): String {
        val text = StringBuilder()
        NativeLlamaBridge.nativeGenerate(handle, prompt, maxTokens, 0.0f, LlamaTokenSink { bytes ->
            val piece = String(bytes, Charsets.UTF_8)
            text.append(piece)
            onPiece(piece)
        })
        return text.toString()
    }

    @Test
    fun loadAnswerAndFreeOverAndOver() {
        repeat(4) { round ->
            val handle = open()
            val text = generate(handle, 12)
            assertTrue("round $round produced no text", text.isNotBlank())
            NativeLlamaBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun cancelStopsAnAnswerMidwayAndTheEngineStaysUsable() {
        val handle = open()
        try {
            val firstPiece = CountDownLatch(1)
            val pieces = AtomicInteger()
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "nexus-test-generate") {
                runCatching { generate(handle, 400) { pieces.incrementAndGet(); firstPiece.countDown(); true } }
                    .onFailure { failure.set(it) }
            }
            assertTrue("no token within two minutes", firstPiece.await(120, TimeUnit.SECONDS))
            NativeLlamaBridge.nativeCancel(handle)
            worker.join(30_000)
            assertFalse("the generation ignored the cancel", worker.isAlive)
            assertNull(failure.get())
            assertTrue("the cancel did not shorten the answer (${pieces.get()} pieces)", pieces.get() < 400)
            assertTrue("the engine stopped working after a cancel", generate(handle, 8).isNotBlank())
        } finally {
            NativeLlamaBridge.nativeDestroy(handle)
        }
    }

    /** The CVE-2026-70640 shape: free the engine while another thread is generating with it. */
    @Test
    fun freeingDuringAnAnswerWaitsForItInsteadOfPullingMemoryAway() {
        repeat(3) { round ->
            val handle = open()
            val firstPiece = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            val worker = thread(name = "nexus-test-generate-$round") {
                runCatching { generate(handle, 400) { firstPiece.countDown(); true } }.onFailure { failure.set(it) }
            }
            assertTrue("no token within two minutes", firstPiece.await(120, TimeUnit.SECONDS))
            val started = SystemClock.elapsedRealtime()
            NativeLlamaBridge.nativeDestroy(handle)
            val waited = SystemClock.elapsedRealtime() - started
            worker.join(30_000)
            assertFalse("the generation kept running after the engine was freed", worker.isAlive)
            assertNull("a generation stopped by close must end quietly", failure.get())
            Log.i(TAG, "round $round: close during generation returned after $waited ms")
            // the id is dead now: refused, never dereferenced
            assertThrows(IllegalStateException::class.java) { generate(handle, 4) }
        }
    }

    @Test
    fun staleAndUnknownIdsAreRefusedNotDereferenced() {
        val handle = open()
        NativeLlamaBridge.nativeDestroy(handle)
        NativeLlamaBridge.nativeDestroy(handle)
        NativeLlamaBridge.nativeCancel(handle)
        assertNull(NativeLlamaBridge.nativeMetadata(handle, "general.architecture"))
        assertNull(NativeLlamaBridge.nativeChatTemplate(handle))
        assertNull(NativeLlamaBridge.nativeFormatChat(handle, arrayOf("user".toByteArray()), arrayOf("hi".toByteArray())))
        assertThrows(IllegalStateException::class.java) { generate(handle, 4) }
        val neverIssued = 0x7fff_ffff_ffffL
        NativeLlamaBridge.nativeCancel(neverIssued)
        NativeLlamaBridge.nativeDestroy(neverIssued)
        assertNull(NativeLlamaBridge.nativeMetadata(neverIssued, "general.architecture"))
        // a fresh engine still gets a working id after all that
        val next = open()
        assertNotEquals(handle, next)
        assertTrue(generate(next, 4).isNotBlank())
        NativeLlamaBridge.nativeDestroy(next)
    }

    @Test
    fun twoThreadsShareOneEngineOneAnswerAtATime() {
        val handle = open()
        try {
            val answers = Collections.synchronizedList(mutableListOf<String>())
            val workers = List(2) { index -> thread(name = "nexus-test-share-$index") { answers += generate(handle, 10) } }
            workers.forEach { it.join(180_000) }
            assertEquals(2, answers.size)
            answers.forEach { assertTrue("an answer came back empty", it.isNotBlank()) }
        } finally {
            NativeLlamaBridge.nativeDestroy(handle)
        }
    }

    @Test
    fun cancelAndCloseRacingFromManyThreadsNeverCrash() {
        repeat(5) {
            val handle = open()
            val firstPiece = CountDownLatch(1)
            val worker = thread { runCatching { generate(handle, 400) { firstPiece.countDown(); true } } }
            firstPiece.await(120, TimeUnit.SECONDS)
            val go = CountDownLatch(1)
            val racers = List(6) { index ->
                thread {
                    go.await()
                    when (index % 3) {
                        0 -> NativeLlamaBridge.nativeCancel(handle)
                        1 -> NativeLlamaBridge.nativeDestroy(handle)
                        else -> runCatching { NativeLlamaBridge.nativeMetadata(handle, "general.architecture") }
                    }
                }
            }
            go.countDown()
            racers.forEach { it.join(60_000) }
            worker.join(30_000)
            assertFalse(worker.isAlive)
        }
    }

    @Test
    fun repeatedLoadsDoNotLeakNativeMemory() {
        fun cycle() {
            val handle = open()
            generate(handle, 4)
            NativeLlamaBridge.nativeDestroy(handle)
        }
        repeat(2) { cycle() } // warm-up: allocator pools, backend registry
        val before = Debug.getNativeHeapAllocatedSize()
        val handle = open()
        val withEngine = Debug.getNativeHeapAllocatedSize()
        generate(handle, 4)
        NativeLlamaBridge.nativeDestroy(handle)
        val perEngine = (withEngine - before).coerceAtLeast(1L shl 20)
        repeat(8) { cycle() }
        val growth = Debug.getNativeHeapAllocatedSize() - before
        Log.i(TAG, "native heap: one engine ${perEngine shr 10} KB, growth after 9 loads ${growth shr 10} KB")
        assertTrue(
            "native heap grew by ${growth shr 20} MB over 9 loads (one engine is ${perEngine shr 20} MB)",
            growth < perEngine * 2 + (8L shl 20)
        )
    }

    /** The app's own path: the gateway closed (app leaving, model switch) in the middle of an answer. */
    @Test
    fun gatewayClosedMidAnswerEndsTheStream(): Unit = runBlocking {
        val storage = NexusModelStorage(context)
        assumeTrue("no llama.cpp model is active", storage.activeModel()?.runtime == ModelRuntimeKind.LLAMA_CPP)
        val request = CompletionRequest(
            endpoint = "", model = "local", apiKey = "",
            messages = listOf(ProviderMessage("user", "Count from one to two hundred in words."))
        )
        val gateway = LlamaCppGateway(context)
        val pieces = AtomicInteger()
        val closer = thread(name = "nexus-test-closer") {
            val deadline = SystemClock.elapsedRealtime() + 180_000
            while (pieces.get() == 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20)
            gateway.close()
        }
        val outcome = runCatching {
            withTimeout(180_000) { gateway.stream(request).collect { pieces.incrementAndGet() } }
        }
        closer.join(30_000)
        assertFalse(closer.isAlive)
        assertTrue("the answer never started", pieces.get() > 0)
        assertFalse("the stream did not end after close", outcome.exceptionOrNull() is TimeoutCancellationException)
        Log.i(TAG, "gateway closed after ${pieces.get()} pieces: ${outcome.exceptionOrNull()?.javaClass?.simpleName ?: "stream ended"}")
    }

    /** In the ASan job the sanitizer must really be in the process, or a clean run proves nothing. */
    @Test
    fun sanitizerRuntimeIsLoadedInTheAsanJob() {
        assumeTrue(args.getString("asan") == "true")
        val maps = File("/proc/self/maps").readText()
        assertTrue("the AddressSanitizer runtime is not mapped in the app process", maps.contains("libclang_rt.asan"))
    }

    /** Run alone by the ASan job, which then expects an AddressSanitizer report in logcat. */
    @Test
    fun asanCanary() {
        assumeTrue(args.getString("asanCanary") == "true")
        NativeLlamaBridge.nativeAsanCanary()
        fail("AddressSanitizer did not stop a planted use-after-free")
    }

    private companion object {
        const val TAG = "NexusLifecycle"
    }
}
