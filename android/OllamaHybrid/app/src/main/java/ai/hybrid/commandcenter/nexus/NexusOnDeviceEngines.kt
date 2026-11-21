package ai.hybrid.commandcenter.nexus

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One serialized owner for LiteRT-LM's Engine and Conversation lifecycle.
 * All SDK operations are serialized. Model initialization is performed off the main thread.
 */
/** Engines that can load their model ahead of the first question. */
interface Prewarmable {
    suspend fun prewarm()
}

class LiteRtLmGateway(context: Context) : ModelGateway, Closeable, Prewarmable {
    private val appContext = context.applicationContext
    private val storage = NexusModelStorage(appContext)
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-litert-engine").apply { isDaemon = true }
    }
    private val dispatcher = executor.asCoroutineDispatcher()
    private val lifecycle = Mutex()
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    @Volatile private var loadedPath: String? = null
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0)

    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        check(!closed.get()) { "LiteRT-LM gateway уже закрыт." }
        require(request.apiKey.isBlank()) { "Локальный LiteRT-LM не использует API-ключ." }
        require(request.messages.any { it.role == "user" }) {
            "Для локальной модели необходимо user-сообщение."
        }
        val requestGeneration = generation.incrementAndGet()
        val producer = this
        val job = launch {
            try {
                withContext(dispatcher) {
                    lifecycle.withLock {
                        check(!closed.get()) { "LiteRT-LM gateway уже закрыт." }
                        val record = storage.activeModel()
                            ?: throw IllegalStateException("Локальная модель не выбрана. Открой «Подключение ядра».")
                        check(record.runtime == ModelRuntimeKind.LITERT_LM) {
                            "Активная модель работает через другой движок. Переподключи ядро."
                        }
                        check(record.location != ModelLocation.EXTERNAL_URI) { "LiteRT-LM нужен файл, а не ссылка." }
                        ensureEngine(record)
                        // Each gateway request carries complete chat history. Recreate the conversation
                        // when the history changes so old model state cannot leak between AI Studio turns.
                        replaceConversation(request)
                        val current = checkNotNull(conversation)
                        val lastUser = request.messages.last { it.role == "user" }
                        current.sendMessageAsync(lastUser.content).collect { chunk ->
                            if (generation.get() == requestGeneration) producer.send(chunk.toString())
                        }
                    }
                }
                close()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose {
            generation.incrementAndGet()
            job.cancel()
            // Schedule cancellation behind any in-progress SDK operation on the owner executor.
            runCatching {
                executor.execute { runCatching { conversation?.cancelProcess() } }
            }
        }
    }

    override suspend fun prewarm() {
        withContext(dispatcher) {
            lifecycle.withLock {
                if (closed.get()) return@withLock
                val record = storage.activeModel() ?: return@withLock
                if (record.runtime != ModelRuntimeKind.LITERT_LM || record.location == ModelLocation.EXTERNAL_URI) return@withLock
                ensureEngine(record)
            }
        }
    }

    private fun ensureEngine(record: InstalledNexusModel) {
        val modelPath = record.path
        if (engine != null && loadedPath == modelPath) return
        storage.verifyIntegrity(record)
        releaseOwnedObjects()
        check(File(modelPath).isFile) { "Файл активной LiteRT-LM модели не найден." }
        val candidate = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = appContext.cacheDir.absolutePath
            )
        )
        // Per LiteRT-LM Android guidance, do not close an Engine if initialize() throws.
        candidate.initialize()
        engine = candidate
        loadedPath = modelPath
    }

    private fun replaceConversation(request: CompletionRequest) {
        val previous = conversation
        conversation = null
        previous?.close()
        val lastUserIndex = request.messages.indexOfLast { it.role == "user" }
        val systemText = request.messages.filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        val initial = request.messages.take(lastUserIndex).mapNotNull { message ->
            when (message.role) {
                "user" -> Message.user(message.content)
                "assistant" -> Message.model(message.content)
                else -> null
            }
        }
        val config = ConversationConfig(
            systemInstruction = Contents.of(systemText),
            initialMessages = initial,
            samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = request.temperature)
        )
        conversation = checkNotNull(engine).createConversation(config)
    }

    private fun releaseOwnedObjects() {
        val oldConversation = conversation
        conversation = null
        runCatching { oldConversation?.close() }
        val oldEngine = engine
        engine = null
        loadedPath = null
        runCatching { oldEngine?.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        runCatching {
            executor.execute {
                runCatching {
                    val oldConversation = conversation
                    conversation = null
                    runCatching { oldConversation?.close() }
                    val oldEngine = engine
                    engine = null
                    loadedPath = null
                    runCatching { oldEngine?.close() }
                }
                dispatcher.close()
            }
        }
    }
}

internal fun interface LlamaTokenSink {
    /** Receives complete UTF-8 characters; returning false stops generation. */
    fun onToken(value: ByteArray): Boolean
}

/**
 * llama.cpp JNI runtime built from the pinned upstream source tag.
 * Runs any chat GGUF the pinned build supports: the prompt is written in the
 * model's own format (catalog style, or the template stored in the file).
 * CPU inference only; GPU offload is not claimed.
 */
class LlamaCppGateway(context: Context) : ModelGateway, Closeable, Prewarmable {
    private val storage = NexusModelStorage(context.applicationContext)
    private val nativeLibraryDir: String = context.applicationContext.applicationInfo.nativeLibraryDir.orEmpty()
    private val mutex = Mutex()
    private val handleLock = ReentrantReadWriteLock()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    @Volatile private var handle: Long = 0L
    @Volatile private var loadedKey: String? = null
    @Volatile private var loadedStyle: PromptStyle = PromptStyle.CHATML

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        check(!closed.get()) { "llama.cpp gateway уже закрыт." }
        require(request.apiKey.isBlank()) { "Локальный llama.cpp не использует API-ключ." }
        val nativeHandle = getOrCreateHandle()
        val history = PromptFormats.fitHistory(request.messages)
        // The template is read from the engine, so the engine must not be freed
        // meanwhile: a model switch or close() takes the write lock first.
        val prompt = withContext(Dispatchers.IO) {
            withHandle(nativeHandle) { buildPrompt(it, loadedStyle, history) }
        }
        val requestGeneration = generation.incrementAndGet()
        val sanitizer = StreamSanitizer()
        val job = launch(Dispatchers.IO) {
            val readLock = handleLock.readLock()
            readLock.lock()
            try {
                check(!closed.get() && handle == nativeHandle) {
                    "llama.cpp gateway closed before generation started."
                }
                NativeLlamaBridge.nativeGenerate(
                    nativeHandle, prompt.toByteArray(Charsets.UTF_8), 1024, request.temperature.toFloat(),
                    object : LlamaTokenSink {
                        override fun onToken(value: ByteArray): Boolean {
                            if (generation.get() != requestGeneration) return false
                            val text = sanitizer.accept(String(value, Charsets.UTF_8))
                            if (text.isNotEmpty()) {
                                val sent = channel.trySendBlocking(text)
                                if (sent.isFailure) return false
                            }
                            return !sanitizer.reachedStop
                        }
                    }
                )
                val tail = sanitizer.finish()
                if (tail.isNotEmpty() && generation.get() == requestGeneration) channel.trySendBlocking(tail)
                close()
            } catch (error: Throwable) {
                close(error)
            } finally {
                readLock.unlock()
            }
        }
        awaitClose {
            generation.incrementAndGet()
            val readLock = handleLock.readLock()
            readLock.lock()
            try {
                if (handle == nativeHandle) NativeLlamaBridge.nativeCancel(nativeHandle)
            } finally {
                readLock.unlock()
            }
            job.cancel()
        }
    }

    /** Runs [block] while [nativeHandle] is guaranteed to stay loaded. */
    private inline fun <T> withHandle(nativeHandle: Long, block: (Long) -> T): T {
        val readLock = handleLock.readLock()
        readLock.lock()
        try {
            check(!closed.get() && handle == nativeHandle) { "llama.cpp gateway closed before generation started." }
            return block(nativeHandle)
        } finally {
            readLock.unlock()
        }
    }

    /** Maps the model before the first question so the owner does not wait for loading. */
    override suspend fun prewarm() {
        if (storage.activeModel()?.runtime == ModelRuntimeKind.LLAMA_CPP) getOrCreateHandle()
    }

    private fun buildPrompt(nativeHandle: Long, style: PromptStyle, history: List<ProviderMessage>): String {
        PromptFormats.build(style, history)?.let { return it }
        // NATIVE: llama.cpp applies the template stored in the GGUF; ChatML if it does not know it.
        val messages = PromptFormats.nativeMessages(history)
        val formatted = NativeLlamaBridge.nativeFormatChat(
            nativeHandle,
            messages.map { it.role.toByteArray(Charsets.UTF_8) }.toTypedArray(),
            messages.map { it.content.toByteArray(Charsets.UTF_8) }.toTypedArray()
        )
        return formatted?.toString(Charsets.UTF_8) ?: PromptFormats.build(PromptStyle.CHATML, history)!!
    }

    private suspend fun getOrCreateHandle(): Long = mutex.withLock {
        withContext(Dispatchers.IO) {
            val writeLock = handleLock.writeLock()
            writeLock.lock()
            try {
                check(!closed.get()) { "llama.cpp gateway уже закрыт." }
                val record = storage.activeModel()
                    ?: throw IllegalStateException("Локальная модель не выбрана. Открой «Подключение ядра».")
                check(record.runtime == ModelRuntimeKind.LLAMA_CPP) {
                    "Активная модель работает через другой движок. Переподключи ядро."
                }
                val key = record.id + "|" + record.path
                if (handle != 0L && loadedKey == key) return@withContext handle
                val verified = storage.verifyIntegrity(record)

                val previous = handle
                handle = 0L
                loadedKey = null
                if (previous != 0L) NativeLlamaBridge.nativeDestroy(previous)

                NativeLlamaBridge.backends(nativeLibraryDir)
                val threads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
                val created = if (verified.location == ModelLocation.EXTERNAL_URI) {
                    storage.openReadDescriptor(verified).use { descriptor ->
                        NativeLlamaBridge.nativeCreateFromFd(descriptor.fd, CONTEXT_SIZE, threads)
                    }
                } else {
                    check(File(verified.path).isFile) { "Файл GGUF-модели не найден." }
                    NativeLlamaBridge.nativeCreate(verified.path.toByteArray(Charsets.UTF_8), CONTEXT_SIZE, threads)
                }
                check(created != 0L) { "llama.cpp не смог открыть GGUF-модель." }
                if (closed.get()) {
                    NativeLlamaBridge.nativeDestroy(created)
                    throw IllegalStateException("llama.cpp gateway закрыт во время инициализации.")
                }
                loadedStyle = verified.promptStyle
                    ?: NexusModelCatalog.find(verified.catalogId ?: verified.id)?.promptStyle
                    ?: PromptFormats.detect(
                        NativeLlamaBridge.nativeMetadata(created, "general.architecture")?.toString(Charsets.UTF_8),
                        NativeLlamaBridge.nativeChatTemplate(created)?.toString(Charsets.UTF_8)
                    )
                handle = created
                loadedKey = key
                created
            } finally {
                writeLock.unlock()
            }
        }
    }

    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-llama-cleanup").apply { isDaemon = true }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        runCatching {
            cleanupExecutor.execute {
                val readLock = handleLock.readLock()
                readLock.lock()
                try {
                    val current = handle
                    if (current != 0L) runCatching { NativeLlamaBridge.nativeCancel(current) }
                } finally {
                    readLock.unlock()
                }
                val writeLock = handleLock.writeLock()
                writeLock.lock()
                try {
                    val ownedHandle = handle
                    handle = 0L
                    loadedKey = null
                    if (ownedHandle != 0L) NativeLlamaBridge.nativeDestroy(ownedHandle)
                } finally {
                    writeLock.unlock()
                    cleanupExecutor.shutdown()
                }
            }
        }.onFailure { cleanupExecutor.shutdown() }
    }

    private companion object {
        const val CONTEXT_SIZE = 4096
    }
}

/** Which llama.cpp CPU build this phone uses, e.g. «CPU: NEON ARM_FMA FP16_VA DOTPROD»; null if the engine cannot load. */
fun llamaCpuBackends(context: Context): String? =
    runCatching { NativeLlamaBridge.backends(context.applicationContext.applicationInfo.nativeLibraryDir.orEmpty()) }.getOrNull()

/**
 * The JNI surface of llama.cpp. Handles are opaque ids, not pointers: a stale or
 * unknown id is refused by the native side instead of being dereferenced, and
 * [nativeDestroy] waits for calls in flight before it frees the engine.
 * Internal only so the instrumented lifecycle tests can drive it directly.
 */
internal object NativeLlamaBridge {
    init { System.loadLibrary("nexus_llama_jni") }

    @Volatile private var loaded: String? = null

    /** Loads the CPU variant for this processor once; returns e.g. «CPU: NEON ARM_FMA FP16_VA DOTPROD». */
    @Synchronized
    fun backends(nativeLibraryDir: String): String = loaded ?: nativeInitBackends(nativeLibraryDir).also { loaded = it }

    private external fun nativeInitBackends(libDir: String): String

    /** [modelPath] is UTF-8 bytes, so any file name reaches fopen intact. */
    external fun nativeCreate(modelPath: ByteArray, contextSize: Int, threads: Int): Long
    external fun nativeCreateFromFd(fd: Int, contextSize: Int, threads: Int): Long
    external fun nativeMetadata(handle: Long, key: String): ByteArray?
    external fun nativeChatTemplate(handle: Long): ByteArray?
    external fun nativeFormatChat(handle: Long, roles: Array<ByteArray>, contents: Array<ByteArray>): ByteArray?
    external fun nativeGenerate(handle: Long, prompt: ByteArray, maxTokens: Int, temperature: Float, sink: LlamaTokenSink)
    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)

    /** Exists only in the CI AddressSanitizer build: a planted use-after-free the sanitizer must report. */
    external fun nativeAsanCanary(): Int
}
