package ai.hybrid.commandcenter.nexus

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Builds small, valid GGUF headers for tests. */
internal object GgufFixture {
    fun header(
        architecture: String? = "qwen35",
        name: String? = "Qwen3.5 2B",
        template: String? = "{% for m in messages %}<|im_start|>{{ m.role }}<think></think>{% endfor %}",
        tokens: Int = 2_000,
        fileType: Int = 15,
        extra: Map<String, String> = emptyMap()
    ): ByteArray {
        val kv = ByteArrayOutputStream()
        var count = 0L
        fun str(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            kv.write(le64(bytes.size.toLong())); kv.write(bytes)
        }
        fun key(k: String, type: Int) { str(k); kv.write(le32(type)) }
        if (architecture != null) { key("general.architecture", 8); str(architecture); count++ }
        if (name != null) { key("general.name", 8); str(name); count++ }
        key("general.file_type", 4); kv.write(le32(fileType)); count++
        key("general.alignment", 4); kv.write(le32(32)); count++
        if (architecture != null) { key("$architecture.context_length", 4); kv.write(le32(32768)); count++ }
        // A vocabulary-sized string array that the reader must skip, not store.
        key("tokenizer.ggml.tokens", 9); kv.write(le32(8)); kv.write(le64(tokens.toLong()))
        repeat(tokens) { str("tok$it") }
        count++
        key("tokenizer.ggml.scores", 9); kv.write(le32(6)); kv.write(le64(tokens.toLong()))
        repeat(tokens) { kv.write(le32(0)) }
        count++
        extra.forEach { (k, v) -> key(k, 8); str(v); count++ }
        if (template != null) { key("tokenizer.chat_template", 8); str(template); count++ }
        val out = ByteArrayOutputStream()
        out.write("GGUF".toByteArray(Charsets.US_ASCII))
        out.write(le32(3))
        out.write(le64(0))
        out.write(le64(count))
        out.write(kv.toByteArray())
        return out.toByteArray()
    }

    /** A sparse file of [size] bytes that starts with [header]: instant to create, nearly free on disk. */
    fun sparseFile(file: File, header: ByteArray, size: Long): File {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.write(header)
            raf.setLength(size)
        }
        return file
    }

    private fun le32(value: Int) = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    private fun le64(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
}

class ModelCatalogTiersTest {
    @Test fun twelvePinnedPhoneModelsInThreeTiersNoneAboveNineBillion() {
        val phone = NexusModelCatalog.onDevice
        assertEquals(12, phone.size)
        assertEquals(4, NexusModelCatalog.byTier(ModelTier.LIGHT).size)
        assertEquals(3, NexusModelCatalog.byTier(ModelTier.MEDIUM).size)
        assertEquals(5, NexusModelCatalog.byTier(ModelTier.MAX).size)
        assertEquals(phone.size, phone.map { it.id }.toSet().size)
        phone.forEach { model ->
            val artifact = model.artifact!!
            assertTrue(model.id, artifact.validationErrors().isEmpty())
            assertTrue(model.id, artifact.downloadUrl.contains("/resolve/" + artifact.immutableRevision + "/"))
            assertTrue(model.id, artifact.downloadUrl.endsWith("/" + artifact.artifactFileName))
            val billions = Regex("(\\d+(?:\\.\\d+)?)").find(model.approximateParameterLabel)!!.value.toDouble()
            assertTrue(model.id + " is above 9B", billions <= 9.0)
            if (artifact.runtime == ModelRuntimeKind.LLAMA_CPP) assertNotNull(model.id, model.promptStyle)
            assertTrue(model.id, model.summary.isNotBlank())
        }
        // Light models really are light.
        NexusModelCatalog.byTier(ModelTier.LIGHT).forEach { assertTrue(it.artifact!!.sizeBytes < 1_500_000_000L) }
    }

    @Test fun newNvidiaModelAndRussianModelsAreInTheCatalog() {
        val nemotron = NexusModelCatalog.find("nvidia-nemotron-3.5-bolt-e4b")!!
        assertTrue(nemotron.isNew)
        assertEquals(PromptStyle.GEMMA4, nemotron.promptStyle)
        assertEquals("nemotron-3.5-bolt:e4b", nemotron.ollamaTag)
        assertEquals(PromptStyle.NATIVE, NexusModelCatalog.find("yandexgpt-5-lite-8b")!!.promptStyle)
        assertTrue(NexusModelCatalog.onDevice.count { it.russianTuned } >= 4)
    }

    @Test fun foundFileMatchesCatalogBySize() {
        assertEquals("qwen3.5-2b", NexusModelCatalog.matchFile(1_280_835_840L, "Qwen3.5-2B-Q4_K_M.gguf")?.id)
        assertEquals("qwen3-0.6b", NexusModelCatalog.matchFile(428_970_080L)?.id)
        assertNull(NexusModelCatalog.matchFile(123_456L))
    }

    @Test fun fitEstimateFollowsPhoneMemory() {
        val gb = 1024L * 1024 * 1024
        val qwen9 = NexusModelCatalog.find("qwen3.5-9b")!!.artifact!!.sizeBytes
        val qwen06 = NexusModelCatalog.find("qwen3-0.6b")!!.artifact!!.sizeBytes
        assertEquals(ModelFit.TOO_BIG, ModelFit.evaluate(qwen9, (7.4 * gb).toLong()))
        assertEquals(ModelFit.OK, ModelFit.evaluate(qwen9, (11.2 * gb).toLong()))
        assertEquals(ModelFit.EASY, ModelFit.evaluate(qwen06, (3.6 * gb).toLong()))
        assertEquals(ModelFit.UNKNOWN, ModelFit.evaluate(qwen06, 0L))
    }
}

class PromptFormatsTest {
    private val chat = listOf(
        ProviderMessage("system", "Ты NEX."),
        ProviderMessage("user", "Привет"),
        ProviderMessage("assistant", "Здравствуй!"),
        ProviderMessage("user", "Сколько будет 2+2?")
    )

    @Test fun qwenStyleDisablesThinkingWithAnEmptyBlock() {
        val prompt = PromptFormats.build(PromptStyle.QWEN_NOTHINK, chat)!!
        assertTrue(prompt.startsWith("<|im_start|>system\nТы NEX.<|im_end|>\n"))
        assertTrue(prompt.contains("<|im_start|>assistant\nЗдравствуй!<|im_end|>\n"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertTrue(PromptFormats.build(PromptStyle.CHATML, chat)!!.endsWith("<|im_start|>assistant\n"))
    }

    @Test fun gemma4UsesTurnMarkersAndModelRole() {
        val prompt = PromptFormats.build(PromptStyle.GEMMA4, chat)!!
        assertEquals(
            "<|turn>system\nТы NEX.<turn|>\n<|turn>user\nПривет<turn|>\n<|turn>model\nЗдравствуй!<turn|>\n" +
                "<|turn>user\nСколько будет 2+2?<turn|>\n<|turn>model\n",
            prompt
        )
        assertNull(PromptFormats.build(PromptStyle.NATIVE, chat))
    }

    @Test fun nativeTemplatesGetThePersonaInsideTheFirstUserTurn() {
        val messages = PromptFormats.nativeMessages(chat)
        assertEquals(listOf("user", "assistant", "user"), messages.map { it.role })
        assertEquals("Ты NEX.\n\nПривет", messages.first().content)
    }

    @Test fun unknownFilesGetTheirStyleFromArchitectureAndTemplate() {
        assertEquals(PromptStyle.GEMMA4, PromptFormats.detect("gemma4", "{{ '<|turn>' + role }}"))
        assertEquals(PromptStyle.QWEN_NOTHINK, PromptFormats.detect("qwen35", "<|im_start|>{{ x }}<think>"))
        assertEquals(PromptStyle.CHATML, PromptFormats.detect("qwen2", "<|im_start|>{{ x }}"))
        assertEquals(PromptStyle.NATIVE, PromptFormats.detect("llama", "{{ ' Ассистент:' }}"))
        assertEquals(PromptStyle.QWEN_NOTHINK, PromptFormats.detect("qwen3", null))
    }

    @Test fun historyIsTrimmedFromTheOldEndButKeepsPersonaAndQuestion() {
        val long = buildList {
            add(ProviderMessage("system", "Ты NEX."))
            repeat(40) { i ->
                add(ProviderMessage("user", "вопрос $i " + "х".repeat(300)))
                add(ProviderMessage("assistant", "ответ $i " + "у".repeat(300)))
            }
            add(ProviderMessage("user", "последний вопрос"))
        }
        val fitted = PromptFormats.fitHistory(long, 3_000)
        assertEquals("system", fitted.first().role)
        assertEquals("последний вопрос", fitted.last().content)
        assertTrue(fitted.sumOf { it.content.length } <= 3_000)
        assertEquals("user", fitted[1].role)
        assertTrue(fitted.size < long.size)
    }
}

class StreamSanitizerTest {
    private fun run(vararg chunks: String): Pair<String, Boolean> {
        val sanitizer = StreamSanitizer()
        val out = StringBuilder()
        for (chunk in chunks) {
            out.append(sanitizer.accept(chunk))
            if (sanitizer.reachedStop) break
        }
        out.append(sanitizer.finish())
        return out.toString() to sanitizer.reachedStop
    }

    @Test fun reasoningIsHiddenEvenWhenTagsAreSplitAcrossTokens() {
        val (text, stopped) = run("<thi", "nk>\nдумаю про ", "задачу</th", "ink>\n\nОтвет: ", "4")
        assertEquals("Ответ: 4", text)
        assertFalse(stopped)
    }

    @Test fun endOfTurnMarkersStopGeneration() {
        val (text, stopped) = run("Привет", "!<tu", "rn|>\n<|turn>user\nещё")
        assertEquals("Привет!", text)
        assertTrue(stopped)
        assertEquals("Да" to true, run("Да", "<|im_end|>", "мусор"))
    }

    @Test fun gemmaThoughtChannelIsDroppedAndOrdinaryAngleBracketsSurvive() {
        assertEquals("ok" to false, run("<|channel>thought\n", "<channel|>", "ok"))
        assertEquals("a < b и c > d" to false, run("a <", " b и c > d"))
        assertEquals("x <" to false, run("x <"))
    }
}

class GgufHeaderTest {
    @Test fun readsMetadataAndSkipsTheVocabulary() {
        val info = GgufHeader.read(ByteArrayInputStream(GgufFixture.header(tokens = 50_000)))
        assertEquals(3, info.version)
        assertEquals("qwen35", info.architecture)
        assertEquals("Qwen3.5 2B", info.title)
        assertEquals("Q4_K_M", info.quantization)
        assertEquals(32768L, info.contextLength)
        assertTrue(info.chatTemplate!!.contains("<|im_start|>"))
        assertEquals(GgufKind.CHAT, info.kind)
    }

    @Test fun classifiesFilesThatCannotChat() {
        assertEquals(GgufKind.PROJECTOR, GgufHeader.classify("clip"))
        assertEquals(GgufKind.EMBEDDING, GgufHeader.classify("nomic-bert"))
        assertEquals(GgufKind.UNSUPPORTED, GgufHeader.classify("totally-new-arch"))
        assertEquals(GgufKind.CHAT, GgufHeader.classify("gemma4"))
        assertEquals(GgufKind.CHAT, GgufHeader.classify("nemotron_h"))
        assertEquals(GgufKind.UNKNOWN, GgufHeader.classify(null))
    }

    @Test fun rejectsGarbageAndTruncatedHeaders() {
        try {
            GgufHeader.read(ByteArrayInputStream("NOPE-not-a-gguf".toByteArray()))
            fail("garbage accepted")
        } catch (_: IOException) {
        }
        val full = GgufFixture.header()
        try {
            GgufHeader.read(ByteArrayInputStream(full.copyOf(full.size / 2)))
            fail("truncated header accepted")
        } catch (_: IOException) {
        }
    }
}

class OllamaModelHubTest {
    @Test fun offersStayWithinNineBillionAndMatchInstalledTags() {
        assertTrue(OllamaModelHub.offers.size >= 9)
        assertTrue(OllamaModelHub.offers.any { it.tag == "nemotron-3.5-bolt:e4b" && it.isNew })
        val installed = listOf("qwen3.5:2b", "hf.co/yandex/YandexGPT-5-Lite-8B-instruct-GGUF:Q4_K_M", "llama3")
        assertTrue(OllamaModelHub.isInstalled(OllamaModelHub.offers.first { it.tag == "qwen3.5:2b" }, installed))
        assertTrue(OllamaModelHub.isInstalled(OllamaModelHub.offers.first { it.tag.startsWith("hf.co/yandex") }, installed))
        assertFalse(OllamaModelHub.isInstalled(OllamaModelHub.offers.first { it.tag == "qwen3.5:9b" }, installed))
    }

    @Test fun pullTrackerAddsUpLayersAndReportsErrors() {
        val tracker = OllamaModelHub.PullTracker()
        tracker.accept("""{"status":"pulling manifest"}""")
        tracker.accept("""{"status":"pulling a","digest":"sha256:a","total":100,"completed":50}""")
        val p = tracker.accept("""{"status":"pulling b","digest":"sha256:b","total":300,"completed":100}""")!!
        assertEquals(150L, p.completed)
        assertEquals(400L, p.total)
        tracker.accept("""{"status":"success"}""")
        assertTrue(tracker.success)
        try {
            OllamaModelHub.PullTracker().accept("""{"error":"pull model manifest: file does not exist"}""")
            fail("error line ignored")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("нет в библиотеке"))
        }
    }

    @Test fun pullAsksOllamaOnThePcAndStreamsProgress() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse().setBody(
                """{"status":"pulling manifest"}
{"status":"pulling x","digest":"sha256:x","total":1000,"completed":400}
{"status":"pulling x","digest":"sha256:x","total":1000,"completed":1000}
{"status":"verifying sha256 digest"}
{"status":"success"}
"""
            )
        )
        server.start()
        try {
            val seen = mutableListOf<OllamaModelHub.PullProgress>()
            OllamaModelHub.pull(server.url("/").toString().trimEnd('/'), "qwen3.5:2b", onProgress = { seen += it })
            val request = server.takeRequest()
            assertEquals("/api/pull", request.path)
            val body = JSONObject(request.body.readUtf8())
            assertEquals("qwen3.5:2b", body.getString("model"))
            assertTrue(body.getBoolean("stream"))
            assertEquals(1f, seen.last().fraction)
        } finally {
            server.shutdown()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FoundModelsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun scannerFindsModelsSkipsJunkAndRecognisesCatalogSizes() {
        val root = File(context.cacheDir, "scan-" + System.nanoTime())
        val unknown = GgufFixture.sparseFile(File(root, "Download/my-model.gguf"), GgufFixture.header(name = "Моя модель"), 64L * 1024 * 1024)
        GgufFixture.sparseFile(File(root, "Download/tiny.gguf"), GgufFixture.header(), 4L * 1024 * 1024)
        GgufFixture.sparseFile(File(root, "Documents/models/Qwen3-0.6B-Q4_0.gguf"), GgufFixture.header(architecture = "qwen3"), 428_970_080L)
        GgufFixture.sparseFile(File(root, "Download/mmproj.gguf"), GgufFixture.header(architecture = "clip"), 40L * 1024 * 1024)
        GgufFixture.sparseFile(File(root, ".hidden/secret.gguf"), GgufFixture.header(), 64L * 1024 * 1024)
        GgufFixture.sparseFile(File(root, "Download/broken.gguf"), "nope".toByteArray(), 64L * 1024 * 1024)

        val files = LocalModelScanner.findFiles(listOf(root)).map { it.name }.toSet()
        assertEquals(setOf("my-model.gguf", "Qwen3-0.6B-Q4_0.gguf", "mmproj.gguf", "broken.gguf"), files)

        val inspected = LocalModelScanner.findFiles(listOf(root)).mapNotNull { LocalModelScanner.inspect(it) }.associateBy { it.fileName }
        assertEquals("qwen3-0.6b", inspected.getValue("Qwen3-0.6B-Q4_0.gguf").catalogMatch?.id)
        assertTrue(inspected.getValue("my-model.gguf").runnable)
        assertEquals("Моя модель", inspected.getValue("my-model.gguf").title)
        assertFalse(inspected.getValue("mmproj.gguf").runnable)
        assertFalse(inspected.getValue("broken.gguf").runnable)
        assertTrue(unknown.exists())
    }

    @Test fun splitModelsAreShownOnceWithTheirFullSize() {
        val root = File(context.cacheDir, "split-" + System.nanoTime())
        val part = 40L * 1024 * 1024
        GgufFixture.sparseFile(File(root, "big-00001-of-00002.gguf"), GgufFixture.header(), part)
        GgufFixture.sparseFile(File(root, "big-00002-of-00002.gguf"), ByteArray(0), part)
        val found = LocalModelScanner.findFiles(listOf(root)).mapNotNull { LocalModelScanner.inspect(it) }
        assertEquals(1, found.size)
        assertEquals(2, found.single().parts)
        assertEquals(2 * part, found.single().sizeBytes)
    }

    @Test fun foundFileRunsInPlaceAndForgettingNeverDeletesIt() = runBlocking {
        val file = GgufFixture.sparseFile(
            File(context.cacheDir, "owner-files/gemma-like.gguf"),
            GgufFixture.header(architecture = "gemma4", name = "Gemma Like", template = "{{ '<|turn>' }}"),
            48L * 1024 * 1024
        )
        val found = LocalModelScanner.inspect(file)!!
        val storage = NexusModelStorage(context)
        val record = storage.registerFound(found)
        assertEquals(ModelLocation.EXTERNAL_FILE, record.location)
        assertEquals(PromptStyle.GEMMA4, record.promptStyle)
        assertEquals(file.absolutePath, record.path)
        assertTrue(storage.listInstalled().any { it.id == record.id })

        val active = storage.activate(record.id)
        assertEquals(record.id, storage.activeModel()?.id)
        assertTrue(active.verifiedAt > 0L)

        storage.deactivate()
        storage.remove(record.id)
        assertTrue("the owner's file must stay", file.exists())
        assertNull(storage.installed(record.id))
    }

    @Test fun aFileThatChangedAfterRegistrationIsNotTrusted() = runBlocking {
        val file = GgufFixture.sparseFile(File(context.cacheDir, "owner-files/changing.gguf"), GgufFixture.header(), 48L * 1024 * 1024)
        val storage = NexusModelStorage(context)
        val record = storage.registerFound(LocalModelScanner.inspect(file)!!)
        RandomAccessFile(file, "rw").use { it.setLength(50L * 1024 * 1024) }
        assertNull("size changed: record must not be offered", storage.installed(record.id))
    }
}
