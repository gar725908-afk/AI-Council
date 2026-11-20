package ai.hybrid.commandcenter.nexus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** User-configured MCP servers (names and URLs). Tokens live in the credential vault, never here. */
class McpServerStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nexus_mcp_servers", Context.MODE_PRIVATE)

    fun list(): List<McpServer> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isNotEmpty() && url.isNotEmpty()) add(McpServer(name, url))
            }
        }
    }

    /** Returns a reason when the server cannot be added. */
    fun add(name: String, url: String): String? {
        val cleanName = name.trim().take(60)
        val cleanUrl = url.trim()
        if (cleanName.isEmpty()) return "Укажите имя сервера."
        if (!(cleanUrl.startsWith("https://") || cleanUrl.startsWith("http://"))) return "URL должен начинаться с https:// (или http:// для локальной сети)."
        if (cleanUrl.length > 2048 || cleanUrl.any { it.code < 0x20 || it == ' ' }) return "Некорректный URL."
        val current = list()
        if (current.any { it.name.equals(cleanName, ignoreCase = true) }) return "Сервер с таким именем уже есть."
        save(current + McpServer(cleanName, cleanUrl))
        return null
    }

    fun remove(name: String) = save(list().filterNot { it.name == name })

    private fun save(servers: List<McpServer>) {
        val array = JSONArray()
        servers.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "servers"
    }
}
