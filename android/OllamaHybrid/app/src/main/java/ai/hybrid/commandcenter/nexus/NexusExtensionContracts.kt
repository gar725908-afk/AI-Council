package ai.hybrid.commandcenter.nexus

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/** NEXUS Extension API v1 is declarative; it never loads remote executable code. */
data class NexusExtensionAction(val id: String, val title: String, val type: String, val payload: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("type", type).put("payload", payload)
}

data class NexusExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val apiVersion: Int,
    val capabilities: List<String>,
    val actions: List<NexusExtensionAction>
) {
    companion object {
        const val CURRENT_API_VERSION = 1
        const val MAX_MANIFEST_BYTES = 64 * 1024
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
        private val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?$")
        private val ALLOWED_CAPABILITIES = setOf("open_url", "clipboard_write", "show_message")
        private val ACTION_TYPES = setOf("open_url", "copy_text", "show_message")

        fun parse(json: String): NexusExtensionManifest {
            require(json.toByteArray(Charsets.UTF_8).size <= MAX_MANIFEST_BYTES) {
                "Манифест расширения превышает 64 КБ."
            }
            val root = JSONObject(json)
            val id = root.optString("id").trim()
            val name = root.optString("name").trim()
            val version = root.optString("version").trim()
            val description = root.optString("description", "").trim()
            val apiVersion = root.optInt("apiVersion", -1)
            require(ID_PATTERN.matches(id)) { "Некорректный идентификатор расширения." }
            require(name.isNotBlank() && name.length <= 80) { "Название расширения обязательно (до 80 символов)." }
            require(VERSION_PATTERN.matches(version)) { "Версия должна иметь формат SemVer." }
            require(apiVersion == CURRENT_API_VERSION) { "Неподдерживаемая версия Extension API: " + apiVersion }
            require(description.length <= 500) { "Описание расширения слишком длинное." }

            val capabilitiesJson = root.optJSONArray("capabilities") ?: JSONArray()
            val capabilities = buildList {
                for (index in 0 until capabilitiesJson.length()) {
                    val capability = capabilitiesJson.optString(index).trim()
                    require(capability in ALLOWED_CAPABILITIES) { "Неподдерживаемое разрешение: " + capability }
                    if (capability !in this) add(capability)
                }
            }
            val actionJson = root.optJSONArray("actions") ?: JSONArray()
            require(actionJson.length() in 0..20) { "Расширение может содержать не более 20 действий." }
            val seenActionIds = mutableSetOf<String>()
            val actions = buildList {
                for (index in 0 until actionJson.length()) {
                    val item = actionJson.optJSONObject(index) ?: throw IllegalArgumentException("Некорректное действие.")
                    val actionId = item.optString("id").trim()
                    val title = item.optString("title").trim()
                    val type = item.optString("type").trim()
                    val payload = item.optString("payload").trim()
                    require(ID_PATTERN.matches(actionId)) { "Некорректный ID действия." }
                    require(title.isNotBlank() && title.length <= 80) { "Название действия обязательно (до 80 символов)." }
                    require(type in ACTION_TYPES) { "Тип действия не поддерживается в Extension API v1: " + type }
                    require(payload.isNotBlank() && payload.length <= 4_096) { "Некорректные данные действия." }
                    val capability = when (type) {
                        "open_url" -> "open_url"
                        "copy_text" -> "clipboard_write"
                        else -> "show_message"
                    }
                    require(capability in capabilities) { "Для действия требуется разрешение " + capability }
                    if (type == "open_url") {
                        val uri = runCatching { java.net.URI(payload) }
                            .getOrElse { throw IllegalArgumentException("Некорректная HTTPS-ссылка.", it) }
                        require(uri.scheme.equals("https", ignoreCase = true) &&
                            !uri.host.isNullOrBlank() && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443)) {
                            "Разрешены только HTTPS-ссылки без логина и нестандартного порта."
                        }
                    }
                    if (type == "show_message") require(payload.length <= 240) { "Сообщение длиннее 240 символов." }
                    require(seenActionIds.add(actionId)) { "ID действий должны быть уникальными." }
                    add(NexusExtensionAction(actionId, title, type, payload))
                }
            }
            return NexusExtensionManifest(id, name, version, description, apiVersion, capabilities, actions)
        }

        fun fromStoredJson(json: String): NexusExtensionManifest? = runCatching { parse(json) }.getOrNull()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("version", version).put("description", description)
        .put("apiVersion", apiVersion).put("capabilities", JSONArray(capabilities))
        .put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
}

interface NexusConnector {
    val id: String
    val displayName: String
    val requestedCapabilities: Set<String>
    suspend fun healthCheck(): ConnectorHealth
}

data class ConnectorHealth(val connected: Boolean, val message: String)

class NexusExtensionStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("nexus_extensions_v1", Context.MODE_PRIVATE)

    fun installed(): List<NexusExtensionManifest> {
        val array = runCatching { JSONArray(preferences.getString("manifests", "[]") ?: "[]") }.getOrNull() ?: JSONArray()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                NexusExtensionManifest.fromStoredJson(item.toString())?.let(::add)
            }
        }
    }

    @Synchronized
    fun installManifest(json: String): NexusExtensionManifest {
        val manifest = NexusExtensionManifest.parse(json)
        val previous = installed().firstOrNull { it.id == manifest.id }
        if (previous != null && previous != manifest) {
            NexusExtensionPermissionStore(appContext).revokeAll(manifest.id)
        }
        val updated = installed().filterNot { it.id == manifest.id } + manifest
        val array = JSONArray().apply { updated.forEach { put(it.toJson()) } }
        check(preferences.edit().putString("manifests", array.toString()).commit()) {
            "Не удалось сохранить манифест расширения."
        }
        return manifest
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val updated = installed().filterNot { it.id == id }
        val array = JSONArray().apply { updated.forEach { put(it.toJson()) } }
        val removed = preferences.edit().putString("manifests", array.toString()).commit()
        if (removed) NexusExtensionPermissionStore(appContext).revokeAll(id)
        return removed
    }
}

class NexusExtensionPermissionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("nexus_extension_permissions_v1", Context.MODE_PRIVATE)

    fun isGranted(extensionId: String, capability: String): Boolean =
        preferences.getBoolean(permissionKey(extensionId, capability), false)

    fun setGranted(extensionId: String, capability: String, granted: Boolean) {
        require(capability in setOf("open_url", "clipboard_write", "show_message"))
        check(preferences.edit().putBoolean(permissionKey(extensionId, capability), granted).commit()) {
            "Не удалось сохранить разрешение."
        }
    }

    fun revokeAll(extensionId: String) {
        val prefix = "grant." + extensionId + "."
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.commit()
    }

    private fun permissionKey(extensionId: String, capability: String): String {
        require(extensionId.matches(Regex("^[a-z0-9][a-z0-9._-]{1,63}$")))
        require(capability.matches(Regex("^[a-z_]{2,40}$")))
        return "grant." + extensionId + "." + capability
    }
}

/** Only host-owned v1 actions can run, and only after an explicit per-extension grant. */
class NexusExtensionRuntime(
    private val store: NexusExtensionStore,
    private val permissions: NexusExtensionPermissionStore
) {
    fun execute(context: Context, manifest: NexusExtensionManifest, action: NexusExtensionAction): String {
        val storedManifest = store.installed().firstOrNull { it.id == manifest.id }
            ?: throw IllegalStateException("Расширение больше не установлено.")
        require(storedManifest == manifest) { "Манифест расширения изменился. Обновите карточку расширения." }
        require(storedManifest.actions.any { it.id == action.id && it == action }) {
            "Действие не принадлежит установленному расширению."
        }
        val capability = when (action.type) {
            "open_url" -> "open_url"
            "copy_text" -> "clipboard_write"
            "show_message" -> "show_message"
            else -> throw IllegalArgumentException("Неизвестное действие.")
        }
        check(capability in manifest.capabilities && permissions.isGranted(manifest.id, capability)) {
            "Не выдано разрешение " + capability
        }
        when (action.type) {
            "open_url" -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(action.payload)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(Intent.createChooser(intent, manifest.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return "Ссылка открыта во внешнем браузере."
            }
            "copy_text" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(manifest.name, action.payload))
                return "Текст скопирован."
            }
            "show_message" -> {
                Toast.makeText(context, action.payload, Toast.LENGTH_LONG).show()
                return "Сообщение показано."
            }
        }
        error("Недостижимый тип действия.")
    }
}

/** .nex is a ZIP containing only manifest.json and an optional README.md. */
object NexusExtensionPackageReader {
    const val MAX_ARCHIVE_BYTES = 256 * 1024
    private const val MAX_ENTRIES = 8

    fun readManifest(input: InputStream): String {
        val manifestBytes = ByteArrayOutputStream()
        var entryCount = 0
        var manifestFound = false
        var totalBytes = 0
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                require(entryCount <= MAX_ENTRIES) { "В архиве слишком много файлов." }
                require(!entry.isDirectory) { "Каталоги внутри пакета не поддерживаются." }
                require(entry.name == "manifest.json" || entry.name == "README.md") {
                    "Пакет v1 может содержать только manifest.json и README.md. Исполняемые файлы запрещены."
                }
                require(entry.name != "manifest.json" || !manifestFound) { "В архиве дублируется manifest.json." }
                val sink = if (entry.name == "manifest.json") manifestBytes else ByteArrayOutputStream()
                val buffer = ByteArray(4 * 1024)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    totalBytes += count
                    require(totalBytes <= MAX_ARCHIVE_BYTES) { "Распакованный пакет превышает 256 КБ." }
                    if (entry.name == "manifest.json") {
                        require(manifestBytes.size() + count <= NexusExtensionManifest.MAX_MANIFEST_BYTES) {
                            "Манифест расширения превышает 64 КБ."
                        }
                    }
                    sink.write(buffer, 0, count)
                }
                if (entry.name == "manifest.json") manifestFound = true
                zip.closeEntry()
            }
        }
        require(manifestFound) { "В пакете отсутствует корневой manifest.json." }
        return manifestBytes.toString(Charsets.UTF_8.name())
    }
}
