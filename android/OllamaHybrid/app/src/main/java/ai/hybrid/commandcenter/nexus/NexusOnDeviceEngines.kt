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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One serialized owner for LiteRT-LM's Engine and Conversation lifecycle.
 * All blocking SDK calls (initialize, stream collection, cancel, close) share one executor.
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

    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        check(!closed.get()) { "LiteRT-LM gateway уже закрыт." }
        require(request.apiKey.isBlank()) { "Локальный LiteRT-LM не использует API-ключ." }
        val lastUserIndex = request.messages.indexOfLast { it.role == "user" }
        require(lastUserIndex >= 0) { "Для локальной модели необходимо user-сообщение." }

        val cancelled = AtomicBoolean(false)
        val job = launch {
            try {
                withContext(dispatcher) {
                    lifecycle.withLock {
                        check(!closed.get()) { "LiteRT-LM gateway уже закрыт." }
                        val modelPath = storage.activeModelPath(ModelRuntimeKind.LITERT_LM)
                        ensureEngine(modelPath)
                        val currentConversation = ensureConversation(request, lastUserIndex)
                        currentConversation.sendMessageAsync(request.messages[lastUserIndex].content).collect { chunk ->
                            if (!cancelled.get()) trySend(chunk.toString())
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
            cancelled.set(true)
            job.cancel()
            // cancelProcess is serialized with the active stream to avoid concurrent SDK calls.
            executor.execute {
                runCatching { conversation?.cancelProcess() }
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
        // Do not call close() on an Engine whose initialize() failed.
        candidate.initialize()
        engine = candidate
        loadedPath = modelPath
    }

    private fun ensureConversation(request: CompletionRequest, lastUserIndex: Int): Conversation {
        conversation?.let { return it }
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
            samplerConfig = SamplerConfig(temperature = request.temperature)
        )
        return checkNotNull(engine).createConversation(config).also { conversation = it }
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
        executor.execute {
            lifecycle.withLockBlocking {
                releaseOwnedObjects()
            }
            dispatcher.close()
        }
    }

    private fun Mutex.withLockBlocking(block: () -> Unit) {
        // This helper runs only on the single owner executor; no coroutine is holding this mutex.
        block()
    }
}

private fun interface LlamaTokenSink {
    fun onToken(value: String)
}

/**
 * llama.cpp JNI runtime built from the pinned upstream source tag.
 * The first Android slice targets arm64-v8a CPU inference; GPU offload is not claimed.
 */
class LlamaCppGateway(context: Context) : ModelGateway, Closeable {
    private val storage = NexusModelStorage(context.applicationContext)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    @Volatile private var handle: Long = 0L
    private val generation = AtomicLong(0L)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        check(!closed.get()) { "llama.cpp gateway уже закрыт." }
        require(request.apiKey.isBlank()) { "Локальный llama.cpp не использует API-ключ." }
        val nativeHandle = getOrCreateHandle()
        val prompt = buildQwenStylePrompt(request.messages)
        val requestGeneration = generation.incrementAndGet()
        val job = launch(Dispatchers.IO) {
            try {
                NativeLlamaBridge.nativeGenerate(
                    nativeHandle, prompt, 512, request.temperature.toFloat(),
                    object : LlamaTokenSink {
                        override fun onToken(value: String) {
                            if (generation.get() == requestGeneration) trySend(value)
                        }
                    }
                )
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose {
            generation.incrementAndGet()
            NativeLlamaBridge.nativeCancel(nativeHandle)
            job.cancel()
        }
    }

    private suspend fun getOrCreateHandle(): Long = mutex.withLock {
        check(!closed.get()) { "llama.cpp gateway уже закрыт." }
        if (handle != 0L) return@withLock handle
        val modelPath = storage.activeModelPath(ModelRuntimeKind.LLAMA_CPP)
        check(File(modelPath).isFile) { "Файл активной GGUF-модели не найден." }
        val created = withContext(Dispatchers.IO) {
            NativeLlamaBridge.nativeCreate(
                modelPath, 4096,
                (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
            )
        }
        check(created != 0L) { "llama.cpp не смог открыть GGUF-модель." }
        handle = created
        created
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val current = handle
        handle = 0L
        if (current != 0L) runCatching { NativeLlamaBridge.nativeDestroy(current) }
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
