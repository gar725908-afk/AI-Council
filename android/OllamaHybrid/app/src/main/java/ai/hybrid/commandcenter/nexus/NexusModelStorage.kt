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

data class InstalledNexusModel(
    val id: String,
    val displayName: String,
    val path: String,
    val runtime: ModelRuntimeKind,
    val sizeBytes: Long,
    val sha256: String,
    val imported: Boolean
)

/**
 * Owns app-private model files and the installed-model registry.
 * Downloads are streamed to a temporary file, size/hash checked, then atomically promoted.
 */
class NexusModelStorage(context: Context) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "nexus-models").apply { mkdirs() }
    private val prefs = appContext.getSharedPreferences("nexus_model_registry", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    fun listInstalled(): List<InstalledNexusModel> =
        prefs.all.entries
            .filter { it.key.startsWith("model.") && it.value is String }
            .mapNotNull { (_, value) -> runCatching { decodeRecord(value as String) }.getOrNull() }
            .filter { File(it.path).isFile }
            .sortedBy { it.displayName.lowercase(Locale.ROOT) }

    fun installed(id: String): InstalledNexusModel? =
        prefs.getString(recordKey(id), null)?.let { runCatching { decodeRecord(it) }.getOrNull() }
            ?.takeIf { File(it.path).isFile }

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

    fun activate(id: String): InstalledNexusModel {
        val record = installed(id) ?: throw IllegalArgumentException("Модель не установлена или файл отсутствует.")
        check(prefs.edit().putString(ACTIVE_ID, id).commit()) { "Не удалось сохранить активную модель." }
        return record
    }

    fun deactivate() {
        prefs.edit().remove(ACTIVE_ID).apply()
    }

    suspend fun download(
        model: NexusModelDescriptor,
        acceptedLicenseId: String?,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): InstalledNexusModel = withContext(Dispatchers.IO) {
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

        val destination = File(root, safeId(model.id) + extensionFor(manifest.artifactFileName))
        val partial = File(root, safeId(model.id) + ".download")
        partial.delete()
        val request = Request.Builder()
            .url(verified.downloadUrl)
            .header("Accept", "application/octet-stream")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                require(response.request.url.isHttps) {
                    "Источник перенаправил загрузку на незашифрованный HTTP. Файл не будет сохранён."
                }
                if (!response.isSuccessful) {
                    throw IOException("Сервер загрузки вернул HTTP " + response.code + ".")
                }
                val body = response.body ?: throw IOException("Источник вернул пустое тело файла.")
                val declaredLength = body.contentLength()
                if (declaredLength >= 0L && declaredLength != verified.sizeBytes) {
                    throw IOException("Размер источника не совпадает с манифестом.")
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                FileOutputStream(partial).use { output ->
                    val input = body.byteStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > verified.sizeBytes) {
                            throw IOException("Файл больше указанного размера; загрузка остановлена.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                        onProgress(received, verified.sizeBytes)
                    }
                    output.fd.sync()
                }
                if (received != verified.sizeBytes) {
                    throw IOException("Неполная загрузка: получено " + received + " из " + verified.sizeBytes + " байт.")
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualHash.equals(verified.sha256, ignoreCase = true)) {
                    throw IOException("SHA-256 не совпал. Файл удалён и не будет активирован.")
                }
                promote(partial, destination)
                val record = InstalledNexusModel(
                    id = model.id,
                    displayName = model.displayName,
                    path = destination.absolutePath,
                    runtime = verified.runtime,
                    sizeBytes = received,
                    sha256 = actualHash,
                    imported = false
                )
                saveRecord(record)
                record
            }
        } catch (error: Throwable) {
            partial.delete()
            throw error
        }
    }

    suspend fun importArtifact(uri: Uri): InstalledNexusModel = withContext(Dispatchers.IO) {
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
        val targetName = UUID.randomUUID().toString() + if (runtime == ModelRuntimeKind.LLAMA_CPP) ".gguf" else ".litertlm"
        val target = File(root, targetName)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val availableAtStart = StatFs(root.absolutePath).availableBytes
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть выбранный файл.")
            input.use { source ->
                FileOutputStream(target).use { output ->
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
            if (runtime == ModelRuntimeKind.LLAMA_CPP) {
                val magic = ByteArray(4)
                FileInputStream(target).use { check(it.read(magic) == 4 && String(magic, Charsets.US_ASCII) == "GGUF") {
                    "Файл имеет расширение GGUF, но заголовок формата не распознан."
                } }
            }
            val id = "imported-" + UUID.randomUUID().toString().take(8)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val record = InstalledNexusModel(
                id = id,
                displayName = displayName,
                path = target.absolutePath,
                runtime = runtime,
                sizeBytes = total,
                sha256 = hash,
                imported = true
            )
            saveRecord(record)
            record
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    fun remove(id: String) {
        val record = installed(id) ?: return
        check(prefs.getString(ACTIVE_ID, null) != id) {
            "Сначала переключите активную модель, затем удаляйте эту."
        }
        File(record.path).delete()
        prefs.edit().remove(recordKey(id)).apply()
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
            imported = json.optBoolean("imported", false)
        )
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
        private const val ACTIVE_ID = "active_model_id"
    }
}
