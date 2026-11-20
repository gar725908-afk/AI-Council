package ai.hybrid.commandcenter.nexus

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.util.Locale

/** A model file that already lies somewhere on the phone. */
data class FoundModelFile(
    val path: String,
    val fileName: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val runtime: ModelRuntimeKind,
    val gguf: GgufInfo?,
    val headerError: String?,
    val catalogMatch: NexusModelDescriptor?,
    val parts: Int = 1
) {
    /** Why this file cannot be launched as a chat model, or null when it can. */
    val problem: String?
        get() = when {
            headerError != null -> headerError
            runtime == ModelRuntimeKind.LLAMA_CPP && gguf == null -> "заголовок GGUF не прочитан"
            gguf != null && !gguf.kind.runnable -> gguf.kind.note
            else -> null
        }

    val runnable: Boolean get() = problem == null

    val title: String
        get() = catalogMatch?.displayName ?: gguf?.title ?: fileName.substringBeforeLast('.').replace('_', ' ').take(60)

    /** Short technical line: "qwen35 · Q4_K_M · 2 части". */
    val details: String
        get() = listOfNotNull(
            if (runtime == ModelRuntimeKind.LITERT_LM) "LiteRT-LM" else gguf?.architecture,
            gguf?.quantization,
            if (parts > 1) "$parts части" else null
        ).joinToString(" · ")

    /** Folder shown to the owner: "Download", "Documents/models". */
    val folderLabel: String
        get() {
            val parent = File(path).parent ?: return ""
            val storage = runCatching { Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            return if (storage != null && parent.startsWith(storage)) parent.removePrefix(storage).trim('/').ifEmpty { "Память телефона" }
            else parent.substringAfterLast("/Android/data/").ifEmpty { parent }
        }
}

/**
 * Finds model weights the owner already downloaded (browser «Загрузки»,
 * «Документы», a copy from the PC), so NEXUS can say «скачивать не надо».
 * Reads only file names and GGUF headers — never the weights themselves.
 */
object LocalModelScanner {
    private val EXTENSIONS = listOf(".gguf", ".litertlm")
    private const val MIN_SIZE_BYTES = 32L * 1024 * 1024
    private val SKIP_TOP_LEVEL = setOf("android", "dcim")
    private val SPLIT = Regex("^(.*)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)

    /** Folder that never needs a permission: Android/data/<app>/files/models (reachable over USB from a PC). */
    fun appModelsDir(context: Context): File? =
        context.getExternalFilesDir(null)?.let { File(it, "models") }?.also { it.mkdirs() }

    fun roots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        appModelsDir(context)?.let { roots += it }
        if (StorageAccess.hasFullRead(context)) {
            runCatching { Environment.getExternalStorageDirectory() }.getOrNull()?.let { roots += it }
            // Removable cards: /storage/XXXX-XXXX derived from the app's per-volume folders.
            context.getExternalFilesDirs(null).drop(1).filterNotNull().forEach { dir ->
                val volume = dir.absolutePath.substringBefore("/Android/data/")
                if (volume != dir.absolutePath) roots += File(volume)
            }
        }
        return roots.filter { it.isDirectory && it.canRead() }
    }

    /** Walks [roots] breadth-first with hard limits, returning candidate model files. */
    fun findFiles(roots: List<File>, maxDepth: Int = 6, maxEntries: Int = 80_000): List<File> {
        val result = ArrayList<File>()
        val seenDirs = HashSet<String>()
        var visited = 0
        for (root in roots) {
            val queue = ArrayDeque<Pair<File, Int>>()
            queue.add(root to 0)
            while (queue.isNotEmpty() && visited < maxEntries) {
                val (dir, depth) = queue.removeFirst()
                val canonical = runCatching { dir.canonicalPath }.getOrNull() ?: continue
                if (!seenDirs.add(canonical)) continue
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    visited++
                    val name = child.name
                    if (name.startsWith(".")) continue
                    if (child.isDirectory) {
                        if (depth == 0 && name.lowercase(Locale.ROOT) in SKIP_TOP_LEVEL) continue
                        if (depth + 1 <= maxDepth) queue.add(child to depth + 1)
                    } else if (EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } && child.length() >= MIN_SIZE_BYTES) {
                        result += child
                    }
                }
            }
        }
        return result.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }
    }

    /** Reads what a file is: GGUF header, catalog match, split parts. */
    fun inspect(file: File): FoundModelFile? {
        val name = file.name
        val split = SPLIT.matchEntire(name)
        if (split != null && split.groupValues[2] != "00001") return null // only the first part represents a split model
        val runtime = if (name.endsWith(".litertlm", ignoreCase = true)) ModelRuntimeKind.LITERT_LM else ModelRuntimeKind.LLAMA_CPP
        var gguf: GgufInfo? = null
        var error: String? = null
        if (runtime == ModelRuntimeKind.LLAMA_CPP) {
            runCatching { FileInputStream(file).use { GgufHeader.read(it) } }
                .onSuccess { gguf = it }
                .onFailure { error = "файл повреждён или недокачан" }
        }
        var size = file.length()
        var parts = 1
        if (split != null) {
            val total = split.groupValues[3].toInt()
            val prefix = split.groupValues[1]
            val siblings = (1..total).map { File(file.parentFile, String.format(Locale.ROOT, "%s-%05d-of-%05d.gguf", prefix, it, total)) }
            if (siblings.all { it.isFile }) {
                size = siblings.sumOf { it.length() }
                parts = total
            } else {
                error = "не хватает частей модели (нужно $total)"
            }
        }
        val catalog = if (parts == 1) NexusModelCatalog.matchFile(size, name)?.takeIf { it.artifact?.runtime == runtime } else null
        return FoundModelFile(
            path = file.absolutePath,
            fileName = name,
            sizeBytes = size,
            modifiedAt = file.lastModified(),
            runtime = runtime,
            gguf = gguf,
            headerError = error,
            catalogMatch = catalog,
            parts = parts
        )
    }

    /** Full scan: newest-first, catalog matches and runnable files on top. */
    fun scan(context: Context): List<FoundModelFile> =
        findFiles(roots(context))
            .mapNotNull { runCatching { inspect(it) }.getOrNull() }
            .sortedWith(
                compareByDescending<FoundModelFile> { it.catalogMatch != null }
                    .thenByDescending { it.runnable }
                    .thenByDescending { it.modifiedAt }
            )
}

/** Read access to shared storage, the one permission model discovery needs. */
object StorageAccess {
    /** Android 11+ hides other apps' files unless the owner allows «Доступ ко всем файлам». */
    val usesAllFilesAccess: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    fun hasFullRead(context: Context): Boolean =
        if (usesAllFilesAccess) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Settings screen where the owner flips «Доступ ко всем файлам» for NEXUS (Android 11+). */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + context.packageName))

    fun fallbackSettingsIntent(): Intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
}
