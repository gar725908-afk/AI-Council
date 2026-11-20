package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Where an installed model's bytes live. */
enum class ModelLocation {
    /** Downloaded or copied into NEXUS's private storage. */
    MANAGED,
    /** A file the owner already had on the phone (shared storage), used in place. */
    EXTERNAL_FILE,
    /** A document the owner picked through Android's file picker, used in place by descriptor. */
    EXTERNAL_URI
}

data class InstalledNexusModel(
    val id: String,
    val displayName: String,
    /** Absolute path, or a content:// URI for [ModelLocation.EXTERNAL_URI]. */
    val path: String,
    val runtime: ModelRuntimeKind,
    val sizeBytes: Long,
    /** SHA-256 when known; empty for a found file that is not from the catalog. */
    val sha256: String,
    val imported: Boolean,
    val location: ModelLocation = ModelLocation.MANAGED,
    val catalogId: String? = null,
    val promptStyle: PromptStyle? = null,
    val architecture: String? = null,
    /** File modification time when the bytes were last verified; 0 = never. */
    val modifiedAt: Long = 0L,
    val verifiedAt: Long = 0L
) {
    val isExternal: Boolean get() = location != ModelLocation.MANAGED
}

/**
 * Owns app-private model files and the installed-model registry.
 * Downloads are streamed to a temporary file, size/hash checked, then atomically promoted.
 */
class NexusModelStorage(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "nexus-models")
    private val prefs = appContext.getSharedPreferences("nexus_model_registry", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()

    fun listInstalled(): List<InstalledNexusModel> =
        prefs.all.entries
            .filter { it.key.startsWith("model.") && it.value is String }
            .mapNotNull { (_, value) -> runCatching { decodeRecord(value as String) }.getOrNull() }
            .filter(::isUsable)
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }

    fun installed(id: String): InstalledNexusModel? =
        prefs.getString(recordKey(id), null)?.let { runCatching { decodeRecord(it) }.getOrNull() }
            ?.takeIf(::isUsable)

    fun activeModel(): InstalledNexusModel? =
        prefs.getString(ACTIVE_ID, null)?.let(::installed)

    fun activeModelPath(runtime: ModelRuntimeKind): String {
        val active = activeModel()
            ?: throw IllegalStateException("В Model Center ещё не выбрана активная локальная модель.")
        require(active.runtime == runtime) {
            "Активная модель работает через " + active.runtime + ", а выбранный движок ожидает " + runtime + "."
        }
        return active.path
    }

    fun verifyActiveModelIntegrity(runtime: ModelRuntimeKind, expectedPath: String) {
        val active = activeModel()
            ?: throw IllegalStateException("В Model Center ещё не выбрана активная локальная модель.")
        require(active.runtime == runtime && active.path == expectedPath) {
            "Активная модель изменилась во время подготовки inference runtime."
        }
        verifyArtifactIntegrity(active)
        Unit
    }

    fun activate(id: String): InstalledNexusModel {
        val record = installed(id) ?: throw IllegalArgumentException("Модель не установлена или файл отсутствует.")
        val verified = verifyArtifactIntegrity(record)
        check(prefs.edit().putString(ACTIVE_ID, id).commit()) { "Не удалось сохранить активную модель." }
        return verified
    }

    /** Verifies the active model before an engine maps it; cheap when nothing changed since the last check. */
    fun verifyIntegrity(record: InstalledNexusModel): InstalledNexusModel = verifyArtifactIntegrity(record)

    /** Opens a picked document for reading; the caller owns (and must close) the descriptor. */
    fun openReadDescriptor(record: InstalledNexusModel): android.os.ParcelFileDescriptor {
        require(record.location == ModelLocation.EXTERNAL_URI) { "Модель хранится как обычный файл." }
        return appContext.contentResolver.openFileDescriptor(Uri.parse(record.path), "r")
            ?: throw IOException("Android не дал открыть выбранный файл модели. Выбери его заново.")
    }

    fun deactivate() {
        prefs.edit().remove(ACTIVE_ID).apply()
    }

    suspend fun download(
        model: NexusModelDescriptor,
        acceptedLicenseId: String?,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onCallCreated: (Call) -> Unit = {}
    ): InstalledNexusModel = withContext(Dispatchers.IO) {
        ensureRoot()
        val manifest = model.artifact
            ?: throw IllegalStateException("Для выбранной модели пока нет проверенного файла для загрузки.")
        val available = StatFs(root.absolutePath).availableBytes
        val decision = ModelDownloadPolicy.evaluate(
            model,
            ModelDownloadRequest(model.id, available, acceptedLicenseId)
        )
        val verified = (decision as? ModelDownloadDecision.Allowed)?.manifest
            ?: throw IllegalArgumentException((decision as ModelDownloadDecision.Rejected).reasons.joinToString(" "))
        require(verified.downloadUrl.startsWith("https://")) {
            "Загрузчик принимает только HTTPS-источники."
        }

        // Deterministic partial name per model enables resume; the final artifact
        // still gets an immutable unique name at promote time, so a re-download
        // can never overwrite a file mapped by the active inference engine.
        val partial = File(root, safeId(model.id) + ".download")
        val metaFile = File(root, safeId(model.id) + ".download.meta")
        val expectedMeta = ModelResumePolicy.Meta(verified.downloadUrl, verified.sha256, verified.sizeBytes)
        val storedMeta = ModelResumePolicy.parseMeta(
            runCatching { if (metaFile.exists()) metaFile.readText() else null }.getOrNull()
        )
        var plan = ModelResumePolicy.plan(
            storedMeta, expectedMeta, if (partial.exists()) partial.length() else 0L
        )
        if (plan is ModelResumePolicy.Plan.StartOver) {
            partial.delete()
            metaFile.delete()
        }
        metaFile.writeText(ModelResumePolicy.metaJson(expectedMeta))

        fun discardPartial() {
            partial.delete()
            metaFile.delete()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L

        // Re-hash already downloaded bytes so a resumed file passes the exact
        // same end-to-end SHA-256 verification as a fresh download.
        if (plan !is ModelResumePolicy.Plan.StartOver) {
            try {
                FileInputStream(partial).use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        received += count
                    }
                }
                onProgress(received, verified.sizeBytes)
            } catch (error: Throwable) {
                discardPartial()
                throw error
            }
        }

        if (received < verified.sizeBytes) {
            val requestBuilder = Request.Builder()
                .url(verified.downloadUrl)
                .header("Accept", "application/octet-stream")
                .get()
            if (received > 0L) {
                requestBuilder.header("Range", "bytes=" + received + "-")
            }
            val call = client.newCall(requestBuilder.build())
            try {
                onCallCreated(call)
                call.execute().use { response ->
                    require(response.request.url.isHttps) {
                        "Источник перенаправил загрузку на незашифрованный HTTP. Файл не будет сохранён."
                    }
                    if (received > 0L) {
                        when (val verdict = ModelResumePolicy.rangeVerdict(
                            response.code, response.header("Content-Range"), received, verified.sizeBytes
                        )) {
                            is ModelResumePolicy.RangeVerdict.Append -> Unit
                            is ModelResumePolicy.RangeVerdict.Restart -> {
                                partial.delete()
                                digest.reset()
                                received = 0L
                                onProgress(0L, verified.sizeBytes)
                            }
                            is ModelResumePolicy.RangeVerdict.Fail ->
                                throw IOException(verdict.reason)
                        }
                    }
                    if (!response.isSuccessful && response.code != 206) {
                        throw IOException("Сервер загрузки вернул HTTP " + response.code + ".")
                    }
                    val body = response.body ?: throw IOException("Источник вернул пустое тело файла.")
                    val declaredLength = body.contentLength()
                    val expectedBody = verified.sizeBytes - received
                    if (declaredLength >= 0L && declaredLength != expectedBody) {
                        throw CorruptDownloadException("Размер источника не совпадает с манифестом.")
                    }
                    var lastProgressReported = received
                    FileOutputStream(partial, received > 0L).use { output ->
                        val input = body.byteStream()
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            received += count
                            if (received > verified.sizeBytes) {
                                throw CorruptDownloadException("Файл больше указанного размера; загрузка остановлена.")
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                            if (received - lastProgressReported >= PROGRESS_REPORT_INTERVAL_BYTES ||
                                received == verified.sizeBytes
                            ) {
                                onProgress(received, verified.sizeBytes)
                                lastProgressReported = received
                            }
                        }
                        output.fd.sync()
                    }
                    if (received != verified.sizeBytes) {
                        throw IOException(
                            "Неполная загрузка: получено " + received + " из " + verified.sizeBytes +
                                " байт. Повторный запуск продолжит с этого места."
                        )
                    }
                }
            } catch (error: Throwable) {
                call.cancel()
                // A corrupt partial can never be resumed; an interrupted one can.
                if (error is CorruptDownloadException) discardPartial()
                throw error
            }
        }

        val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actualHash.equals(verified.sha256, ignoreCase = true)) {
            discardPartial()
            throw IOException("SHA-256 не совпал. Файл удалён и не будет активирован.")
        }
        val destination = File(
            root,
            safeId(model.id) + "-" + UUID.randomUUID().toString() + extensionFor(manifest.artifactFileName)
        )
        promote(partial, destination)
        metaFile.delete()
        val record = InstalledNexusModel(
            id = model.id,
            displayName = model.displayName,
            path = destination.absolutePath,
            runtime = verified.runtime,
            sizeBytes = received,
            sha256 = actualHash,
            imported = false,
            catalogId = model.id,
            promptStyle = model.promptStyle,
            modifiedAt = destination.lastModified(),
            verifiedAt = System.currentTimeMillis()
        )
        saveRecord(record)
        record
    }

    suspend fun importArtifact(uri: Uri): InstalledNexusModel = withContext(Dispatchers.IO) {
        ensureRoot()
        val resolver = appContext.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }?.takeIf { it.isNotBlank() } ?: "imported-model"
        val lower = displayName.lowercase(Locale.ROOT)
        val runtime = when {
            lower.endsWith(".gguf") -> ModelRuntimeKind.LLAMA_CPP
            lower.endsWith(".litertlm") -> ModelRuntimeKind.LITERT_LM
            else -> throw IllegalArgumentException("Поддерживаются только .gguf и .litertlm файлы.")
        }
        val extension = if (runtime == ModelRuntimeKind.LLAMA_CPP) ".gguf" else ".litertlm"
        val target = File(root, UUID.randomUUID().toString() + extension)
        val partial = File(root, UUID.randomUUID().toString() + ".importing")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val availableAtStart = StatFs(root.absolutePath).availableBytes
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть выбранный файл.")
            input.use { source ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > availableAtStart) {
                            throw IOException("Недостаточно места для копирования модели.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(total > 0L) { "Выбран пустой файл." }
            val header = if (runtime == ModelRuntimeKind.LLAMA_CPP) {
                val info = runCatching { FileInputStream(partial).use { GgufHeader.read(it) } }.getOrElse {
                    throw IllegalArgumentException("Файл имеет расширение GGUF, но заголовок формата не распознан.")
                }
                check(info.kind.runnable) { "Этот GGUF не запустится в чате: " + info.kind.note + "." }
                info
            } else null
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val catalog = NexusModelCatalog.matchFile(total, displayName)
                ?.takeIf { it.artifact?.sha256.equals(hash, ignoreCase = true) && installed(it.id) == null }
            val id = catalog?.id ?: ("imported-" + UUID.randomUUID().toString().take(8))
            promote(partial, target)
            val record = InstalledNexusModel(
                id = id,
                displayName = catalog?.displayName ?: header?.title ?: displayName,
                path = target.absolutePath,
                runtime = runtime,
                sizeBytes = total,
                sha256 = hash,
                imported = catalog == null,
                catalogId = catalog?.id,
                promptStyle = catalog?.promptStyle ?: header?.let { PromptFormats.detect(it.architecture, it.chatTemplate) },
                architecture = header?.architecture,
                modifiedAt = target.lastModified(),
                verifiedAt = System.currentTimeMillis()
            )
            saveRecord(record)
            record
        } catch (error: Throwable) {
            partial.delete()
            target.delete()
            throw error
        }
    }

    fun remove(id: String) {
        val record = installed(id) ?: return
        check(prefs.getString(ACTIVE_ID, null) != id) {
            "Сначала переключите активную модель, затем удаляйте эту."
        }
        if (record.location == ModelLocation.MANAGED) {
            val artifact = File(record.path)
            if (artifact.exists()) {
                check(artifact.delete()) {
                    "Не удалось удалить файл модели; запись в каталоге сохранена."
                }
            }
        } else if (record.location == ModelLocation.EXTERNAL_URI) {
            // The owner's file stays where it is; NEXUS only forgets it.
            runCatching {
                appContext.contentResolver.releasePersistableUriPermission(
                    Uri.parse(record.path), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        check(prefs.edit().remove(recordKey(id)).commit()) {
            "Файл удалён, но запись каталога не удалось обновить. Обновите Model Center."
        }
    }

    private fun saveRecord(record: InstalledNexusModel) {
        val json = JSONObject()
            .put("id", record.id)
            .put("displayName", record.displayName)
            .put("path", record.path)
            .put("runtime", record.runtime.name)
            .put("sizeBytes", record.sizeBytes)
            .put("sha256", record.sha256)
            .put("imported", record.imported)
            .put("location", record.location.name)
            .put("catalogId", record.catalogId ?: JSONObject.NULL)
            .put("promptStyle", record.promptStyle?.name ?: JSONObject.NULL)
            .put("architecture", record.architecture ?: JSONObject.NULL)
            .put("modifiedAt", record.modifiedAt)
            .put("verifiedAt", record.verifiedAt)
        check(prefs.edit().putString(recordKey(record.id), json.toString()).commit()) {
            "Не удалось сохранить запись модели."
        }
    }

    private fun decodeRecord(value: String): InstalledNexusModel {
        val json = JSONObject(value)
        return InstalledNexusModel(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            path = json.getString("path"),
            runtime = ModelRuntimeKind.valueOf(json.getString("runtime")),
            sizeBytes = json.getLong("sizeBytes"),
            sha256 = json.getString("sha256"),
            imported = json.optBoolean("imported", false),
            location = runCatching { ModelLocation.valueOf(json.optString("location", "MANAGED")) }
                .getOrDefault(ModelLocation.MANAGED),
            catalogId = json.optStringOrNull("catalogId") ?: NexusModelCatalog.find(json.getString("id"))?.id,
            promptStyle = json.optStringOrNull("promptStyle")?.let { runCatching { PromptStyle.valueOf(it) }.getOrNull() },
            architecture = json.optStringOrNull("architecture"),
            modifiedAt = json.optLong("modifiedAt", 0L),
            verifiedAt = json.optLong("verifiedAt", 0L)
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotBlank() }

    private fun isUsable(record: InstalledNexusModel): Boolean = runCatching {
        if (record.sizeBytes <= 0L) return@runCatching false
        when (record.location) {
            ModelLocation.MANAGED -> {
                val managedRoot = root.canonicalFile
                val artifact = File(record.path).canonicalFile
                artifact.parentFile == managedRoot && artifact.isFile &&
                    artifact.length() == record.sizeBytes && record.sha256.matches(SHA256)
            }
            ModelLocation.EXTERNAL_FILE -> {
                val artifact = File(record.path)
                artifact.isFile && artifact.canRead() && artifact.length() == record.sizeBytes
            }
            ModelLocation.EXTERNAL_URI -> documentSize(Uri.parse(record.path)) == record.sizeBytes
        }
    }.getOrDefault(false)

    private fun documentSize(uri: Uri): Long? =
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun documentModified(uri: Uri): Long =
        runCatching {
            appContext.contentResolver.query(
                uri, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L } ?: 0L
        }.getOrDefault(0L)

    private fun currentModified(record: InstalledNexusModel): Long = when (record.location) {
        ModelLocation.EXTERNAL_URI -> documentModified(Uri.parse(record.path))
        else -> File(record.path).lastModified()
    }

    private fun openStream(record: InstalledNexusModel): java.io.InputStream = when (record.location) {
        ModelLocation.EXTERNAL_URI -> appContext.contentResolver.openInputStream(Uri.parse(record.path))
            ?: throw IOException("Android не дал открыть выбранный файл модели.")
        else -> FileInputStream(record.path)
    }

    /**
     * Proves the bytes before an engine maps them. A full SHA-256 pass runs once
     * after install and again only if the file's size or modification time
     * changed — re-hashing gigabytes on every launch cost the owner a minute.
     */
    private fun verifyArtifactIntegrity(record: InstalledNexusModel): InstalledNexusModel {
        check(isUsable(record)) {
            if (record.isExternal) "Файл модели «" + record.displayName + "» пропал или изменился. Найди модели заново."
            else "Файл модели отсутствует, имеет неверный размер или находится вне хранилища NEXUS."
        }
        val modified = currentModified(record)
        val unchanged = record.verifiedAt > 0L && modified == record.modifiedAt
        if (unchanged) return record
        val expected = record.sha256.takeIf { it.matches(SHA256) }
            ?: run {
                // A found file that is not from the catalog has no reference hash: check that it is still a GGUF.
                if (record.runtime == ModelRuntimeKind.LLAMA_CPP) {
                    openStream(record).use { GgufHeader.read(it) }
                }
                val refreshed = record.copy(modifiedAt = modified, verifiedAt = System.currentTimeMillis())
                saveRecord(refreshed)
                return refreshed
            }
        val actual = openStream(record).use { sha256Of(it) }
        check(actual.equals(expected, ignoreCase = true)) {
            "SHA-256 файла модели не совпадает с реестром. Модель не будет активирована."
        }
        val refreshed = record.copy(modifiedAt = modified, verifiedAt = System.currentTimeMillis())
        saveRecord(refreshed)
        return refreshed
    }

    private fun sha256Of(input: java.io.InputStream, total: Long = 0L, onProgress: (Long, Long) -> Unit = { _, _ -> }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 32)
        var done = 0L
        var reported = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            done += count
            if (done - reported >= 32L * 1024 * 1024) { onProgress(done, total); reported = done }
        }
        onProgress(done, total)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Registers a model file the owner already has, without copying it.
     * A catalog-sized file is hashed first: only a proven copy takes the
     * catalog identity (and its prompt format); anything else is "found".
     */
    suspend fun registerFound(
        found: FoundModelFile,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): InstalledNexusModel = withContext(Dispatchers.IO) {
        check(found.runnable) { found.problem ?: "Этот файл нельзя запустить." }
        val file = File(found.path)
        check(file.isFile && file.canRead()) { "Нет доступа к файлу " + found.fileName + "." }
        val catalog = found.catalogMatch
        val existing = catalog?.let { installed(it.id) }
        if (existing != null) return@withContext existing
        val hash = if (catalog != null) FileInputStream(file).use { sha256Of(it, file.length(), onProgress) } else ""
        val proven = catalog != null && catalog.artifact?.sha256.equals(hash, ignoreCase = true)
        val record = InstalledNexusModel(
            id = if (proven) catalog!!.id else foundId(found.path, found.sizeBytes),
            displayName = if (proven) catalog!!.displayName else found.title,
            path = file.absolutePath,
            runtime = found.runtime,
            sizeBytes = file.length(),
            sha256 = hash,
            imported = !proven,
            location = ModelLocation.EXTERNAL_FILE,
            catalogId = if (proven) catalog!!.id else null,
            promptStyle = if (proven) catalog!!.promptStyle
            else found.gguf?.let { PromptFormats.detect(it.architecture, it.chatTemplate) },
            architecture = found.gguf?.architecture,
            modifiedAt = file.lastModified(),
            verifiedAt = System.currentTimeMillis()
        )
        saveRecord(record)
        record
    }

    /**
     * Uses a document picked in Android's file picker. GGUF files run in place
     * through a file descriptor; LiteRT-LM needs a real path, so those are copied.
     */
    suspend fun registerDocument(
        uri: Uri,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): InstalledNexusModel = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        val (name, size) = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null
                else (cursor.getString(0) ?: "model") to (if (cursor.isNull(1)) -1L else cursor.getLong(1))
            } ?: ("model" to -1L)
        val lower = name.lowercase(Locale.ROOT)
        if (lower.endsWith(".litertlm")) return@withContext importArtifact(uri)
        require(lower.endsWith(".gguf")) { "Поддерживаются файлы .gguf и .litertlm." }
        require(size > 0L) { "Android не сообщил размер файла. Попробуй выбрать его через «Файлы»." }
        val header = (resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть выбранный файл."))
            .use { GgufHeader.read(it) }
        check(header.kind.runnable) { "Этот GGUF не запустится в чате: " + header.kind.note + "." }
        check((header.splitCount ?: 1) <= 1) {
            "Модель разбита на части. Положи все части в одну папку и найди их кнопкой «Найти на телефоне»."
        }
        runCatching { resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val catalog = NexusModelCatalog.matchFile(size, name)
        catalog?.let { installed(it.id) }?.let { return@withContext it }
        val hash = if (catalog != null) {
            (resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть выбранный файл.")).use { sha256Of(it, size, onProgress) }
        } else ""
        val proven = catalog != null && catalog.artifact?.sha256.equals(hash, ignoreCase = true)
        val record = InstalledNexusModel(
            id = if (proven) catalog!!.id else foundId(uri.toString(), size),
            displayName = if (proven) catalog!!.displayName else (header.title ?: name.removeSuffix(".gguf")),
            path = uri.toString(),
            runtime = ModelRuntimeKind.LLAMA_CPP,
            sizeBytes = size,
            sha256 = hash,
            imported = !proven,
            location = ModelLocation.EXTERNAL_URI,
            catalogId = if (proven) catalog!!.id else null,
            promptStyle = if (proven) catalog!!.promptStyle else PromptFormats.detect(header.architecture, header.chatTemplate),
            architecture = header.architecture,
            modifiedAt = documentModified(uri),
            verifiedAt = System.currentTimeMillis()
        )
        saveRecord(record)
        record
    }

    private fun foundId(location: String, size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest((location + "#" + size).toByteArray())
        return "found-" + digest.take(5).joinToString("") { "%02x".format(it) }
    }

    private fun ensureRoot() {
        check(root.mkdirs() || root.isDirectory) {
            "Не удалось создать приватное хранилище моделей NEXUS."
        }
    }

    private fun recordKey(id: String) = "model." + id

    private fun safeId(value: String) = value.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun extensionFor(name: String): String =
        if (name.endsWith(".litertlm", true)) ".litertlm" else if (name.endsWith(".gguf", true)) ".gguf" else ".model"

    private fun promote(source: File, destination: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(), destination.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            if (!source.renameTo(destination)) {
                throw IOException("Не удалось атомарно сохранить проверенную модель.")
            }
        }
    }

    companion object {
        private val SHA256 = Regex("[A-Fa-f0-9]{64}")
        private const val ACTIVE_ID = "active_model_id"
        private const val PROGRESS_REPORT_INTERVAL_BYTES = 1L * 1024L * 1024L
    }
}

private class CorruptDownloadException(message: String) : IOException(message)
