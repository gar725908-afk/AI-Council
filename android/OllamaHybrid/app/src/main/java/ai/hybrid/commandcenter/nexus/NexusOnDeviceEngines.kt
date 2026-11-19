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
class LiteRtLmGateway(context: Context) : ModelGateway, Closeable {
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
                        val modelPath = storage.activeModelPath(ModelRuntimeKind.LITERT_LM)
                        ensureEngine(modelPath)
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

    private fun ensureEngine(modelPath: String) {
        if (engine != null && loadedPath == modelPath) return
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

private fun interface LlamaTokenSink {
    fun onToken(value: String)
}

/**
 * llama.cpp JNI runtime built from the pinned upstream source tag.
 * The initial Android target is arm64-v8a CPU inference. GPU offload is not claimed.
 */
class LlamaCppGateway(context: Context) : ModelGateway, Closeable {
    private val storage = NexusModelStorage(context.applicationContext)
    private val mutex = Mutex()
    private val handleLock = ReentrantReadWriteLock()
    private val closed = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    @Volatile private var handle: Long = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        check(!closed.get()) { "llama.cpp gateway уже закрыт." }
        require(request.apiKey.isBlank()) { "Локальный llama.cpp не использует API-ключ." }
        val nativeHandle = getOrCreateHandle()
        val prompt = buildQwenStylePrompt(request.messages)
        val requestGeneration = generation.incrementAndGet()
        val job = launch(Dispatchers.IO) {
            val readLock = handleLock.readLock()
            readLock.lock()
            try {
                check(!closed.get() && handle == nativeHandle) {
                    "llama.cpp gateway closed before generation started."
                }
                NativeLlamaBridge.nativeGenerate(
                    nativeHandle, prompt, 512, request.temperature.toFloat(),
                    object : LlamaTokenSink {
                        override fun onToken(value: String) {
                            if (generation.get() == requestGeneration) {
                                val sent = channel.trySendBlocking(value)
                                if (sent.isFailure && !channel.isClosedForSend) {
                                    NativeLlamaBridge.nativeCancel(nativeHandle)
                                }
                            }
                        }
                    }
                )
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

    private suspend fun getOrCreateHandle(): Long = mutex.withLock {
        withContext(Dispatchers.IO) {
            val writeLock = handleLock.writeLock()
            writeLock.lock()
            try {
                check(!closed.get()) { "llama.cpp gateway уже закрыт." }
                val modelPath = storage.activeModelPath(ModelRuntimeKind.LLAMA_CPP)
                check(File(modelPath).isFile) { "Файл активной GGUF-модели не найден." }
                if (handle != 0L && loadedModelPath == modelPath) return@withContext handle

                val previous = handle
                handle = 0L
                loadedModelPath = null
                if (previous != 0L) NativeLlamaBridge.nativeDestroy(previous)

                val created = NativeLlamaBridge.nativeCreate(
                    modelPath, 4096,
                    (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
                )
                check(created != 0L) { "llama.cpp не смог открыть GGUF-модель." }
                if (closed.get()) {
                    NativeLlamaBridge.nativeDestroy(created)
                    throw IllegalStateException("llama.cpp gateway закрыт во время инициализации.")
                }
                handle = created
                loadedModelPath = modelPath
                created
            } finally {
                writeLock.unlock()
            }
        }
    }

    @Volatile private var loadedModelPath: String? = null
    private val cleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-llama-cleanup").apply { isDaemon = true }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        generation.incrementAndGet()
        val current = handle
        if (current != 0L) runCatching { NativeLlamaBridge.nativeCancel(current) }
        runCatching {
            cleanupExecutor.execute {
                val writeLock = handleLock.writeLock()
                writeLock.lock()
                try {
                    val ownedHandle = handle
                    handle = 0L
                    loadedModelPath = null
                    if (ownedHandle != 0L) NativeLlamaBridge.nativeDestroy(ownedHandle)
                } finally {
                    writeLock.unlock()
                    cleanupExecutor.shutdown()
                }
            }
        }.onFailure { cleanupExecutor.shutdown() }
    }

    private fun buildQwenStylePrompt(messages: List<ProviderMessage>): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val conversation = messages.filter { it.role == "user" || it.role == "assistant" }
        return buildString {
            if (system.isNotBlank()) {
                append("<|im_start|>system\n"); append(system); append("<|im_end|>\n")
            }
            conversation.forEach { message ->
                append(if (message.role == "assistant") "<|im_start|>assistant\n" else "<|im_start|>user\n")
                append(message.content); append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n<think>\n\n</think>\n")
        }
    }
}

private object NativeLlamaBridge {
    init { System.loadLibrary("nexus_llama_jni") }

    external fun nativeCreate(modelPath: String, contextSize: Int, threads: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float, sink: LlamaTokenSink)
    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)
}
