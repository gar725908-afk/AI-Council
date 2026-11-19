package ai.hybrid.commandcenter.nexus

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A device inference adapter. It never represents a LAN or cloud endpoint as on-device execution.
 */
class LiteRtLmGateway(context: Context) : ModelGateway, Closeable {
    private val appContext = context.applicationContext
    private val storage = NexusModelStorage(appContext)
    private val engineMutex = Mutex()
    @Volatile private var engine: Engine? = null

    override fun stream(request: CompletionRequest): Flow<String> = flow {
        require(request.apiKey.isBlank()) { "Локальный LiteRT-LM не использует API-ключ." }
        val activeEngine = getOrCreateEngine()
        val lastUserIndex = request.messages.indexOfLast { it.role == "user" }
        require(lastUserIndex >= 0) { "Для локальной модели необходимо user-сообщение." }
        val systemText = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
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
            samplerConfig = SamplerConfig(temperature = request.temperature.toFloat())
        )
        activeEngine.createConversation(config).use { conversation ->
            conversation.sendMessageAsync(request.messages[lastUserIndex].content).collect { message ->
                emit(message.toString())
            }
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun getOrCreateEngine(): Engine = engineMutex.withLock {
        engine?.let { return@withLock it }
        val modelPath = storage.activeModelPath(ModelRuntimeKind.LITERT_LM)
        check(File(modelPath).isFile) { "Файл активной LiteRT-LM модели не найден." }
        val created = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                cacheDir = appContext.cacheDir.absolutePath
            )
        )
        try {
            created.initialize()
            engine = created
            created
        } catch (error: Throwable) {
            runCatching { created.close() }
            throw error
        }
    }

    override fun close() {
        val current = engine
        engine = null
        runCatching { current?.close() }
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
    @Volatile private var handle: Long = 0L

    override fun stream(request: CompletionRequest): Flow<String> = callbackFlow {
        require(request.apiKey.isBlank()) { "Локальный llama.cpp не использует API-ключ." }
        val nativeHandle = getOrCreateHandle()
        val prompt = buildQwenStylePrompt(request.messages)
        val job = launch(Dispatchers.IO) {
            try {
                NativeLlamaBridge.nativeGenerate(
                    nativeHandle,
                    prompt,
                    512,
                    request.temperature.toFloat(),
                    object : LlamaTokenSink {
                        override fun onToken(value: String) {
                            trySend(value)
                        }
                    }
                )
                close()
            } catch (error: Throwable) {
                close(error)
            }
        }
        awaitClose {
            NativeLlamaBridge.nativeCancel(nativeHandle)
            job.cancel()
        }
    }

    private suspend fun getOrCreateHandle(): Long = mutex.withLock {
        if (handle != 0L) return@withLock handle
        val modelPath = storage.activeModelPath(ModelRuntimeKind.LLAMA_CPP)
        check(File(modelPath).isFile) { "Файл активной GGUF-модели не найден." }
        val created = NativeLlamaBridge.nativeCreate(
            modelPath,
            4096,
            (Runtime.getRuntime().availableProcessors() - 1).coerceIn(2, 6)
        )
        check(created != 0L) { "llama.cpp не смог открыть GGUF-модель." }
        handle = created
        created
    }

    override fun close() {
        val current = handle
        handle = 0L
        if (current != 0L) runCatching { NativeLlamaBridge.nativeDestroy(current) }
    }

    private fun buildQwenStylePrompt(messages: List<ProviderMessage>): String {
        val system = messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val conversation = messages.filter { it.role == "user" || it.role == "assistant" }
        return buildString {
            if (system.isNotBlank()) {
                append("<|im_start|>system\n")
                append(system)
                append("<|im_end|>\n")
            }
            conversation.forEach { message ->
                append(if (message.role == "assistant") "<|im_start|>assistant\n" else "<|im_start|>user\n")
                append(message.content)
                append("<|im_end|>\n")
            }
            append("<|im_start|>assistant\n")
        }
    }
}

private object NativeLlamaBridge {
    init { System.loadLibrary("nexus_llama_jni") }

    external fun nativeCreate(modelPath: String, contextSize: Int, threads: Int): Long
    external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        sink: LlamaTokenSink
    )
    external fun nativeCancel(handle: Long)
    external fun nativeDestroy(handle: Long)
}
