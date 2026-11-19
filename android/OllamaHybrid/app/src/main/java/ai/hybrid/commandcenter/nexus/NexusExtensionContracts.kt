package ai.hybrid.commandcenter.nexus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable, versioned contract for NEXUS extensions.
 *
 * V1 deliberately accepts metadata-only manifests. It never loads or executes code from an
 * imported extension. Executable extension runtimes will require a separate sandbox and policy.
 */
data class NexusExtensionManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val apiVersion: Int,
    val capabilities: List<String>
) {
    companion object {
        const val CURRENT_API_VERSION = 1
        const val MAX_MANIFEST_BYTES = 64 * 1024
        private val ID_PATTERN = Regex("^[a-z0-9][a-z0-9._-]{1,63}$")
        private val VERSION_PATTERN = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][A-Za-z0-9.-]+)?$")

        private val ALLOWED_CAPABILITIES = setOf(
            "network", "read_repository", "read_notes", "browser_automation",
            "local_files", "notifications", "model_provider"
        )

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
            require(apiVersion == CURRENT_API_VERSION) {
                "Неподдерживаемая версия Extension API: $apiVersion."
            }
            require(description.length <= 500) { "Описание расширения слишком длинное." }

            val capabilitiesJson = root.optJSONArray("capabilities") ?: JSONArray()
            val capabilities = buildList {
                for (index in 0 until capabilitiesJson.length()) {
                    val capability = capabilitiesJson.optString(index).trim()
                    require(capability in ALLOWED_CAPABILITIES) {
                        "Расширение запрашивает неизвестное разрешение: $capability."
                    }
                    if (capability !in this) add(capability)
                }
            }
            return NexusExtensionManifest(id, name, version, description, apiVersion, capabilities)
        }

        fun fromStoredJson(json: String): NexusExtensionManifest? =
            runCatching { parse(json) }.getOrNull()
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("version", version)
        .put("description", description)
        .put("apiVersion", apiVersion)
        .put("capabilities", JSONArray(capabilities))
}

/** A connector can only act through capabilities granted by the host application. */
interface NexusConnector {
    val id: String
    val displayName: String
    val requestedCapabilities: Set<String>
    suspend fun healthCheck(): ConnectorHealth
}

data class ConnectorHealth(
    val connected: Boolean,
    val message: String
)

class NexusExtensionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "nexus_extensions_v1",
        Context.MODE_PRIVATE
    )

    fun installed(): List<NexusExtensionManifest> {
        val stored = preferences.getString(KEY_MANIFESTS, "[]") ?: "[]"
        val array = runCatching { JSONArray(stored) }.getOrNull() ?: JSONArray()
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
        val existing = installed().filterNot { it.id == manifest.id }
        val updated = existing + manifest
        val array = JSONArray().apply { updated.forEach { put(it.toJson()) } }
        check(preferences.edit().putString(KEY_MANIFESTS, array.toString()).commit()) {
            "Не удалось сохранить манифест расширения."
        }
        return manifest
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val updated = installed().filterNot { it.id == id }
        val array = JSONArray().apply { updated.forEach { put(it.toJson()) } }
        return preferences.edit().putString(KEY_MANIFESTS, array.toString()).commit()
    }

    private companion object {
        const val KEY_MANIFESTS = "manifests"
    }
}
