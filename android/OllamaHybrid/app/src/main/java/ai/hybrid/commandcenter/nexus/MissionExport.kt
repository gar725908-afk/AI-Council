package ai.hybrid.commandcenter.nexus

import org.json.JSONArray
import org.json.JSONObject

/**
 * User-owned data export (handoff 4.7 / 6.1): missions with their agent
 * results as plain JSON. Contains no credentials — the journal never stores
 * them — and every field is the stored value, not a summary.
 */
object MissionExport {

    const val FORMAT_VERSION = 1

    fun toJson(
        missions: List<MissionRecord>,
        agentResults: Map<String, List<AgentJournalRecord>>,
        exportedAtEpochMillis: Long
    ): String {
        val root = JSONObject()
            .put("format", "nexus-missions")
            .put("version", FORMAT_VERSION)
            .put("exportedAt", exportedAtEpochMillis)
            .put("missionCount", missions.size)
        val array = JSONArray()
        missions.forEach { mission ->
            val agents = JSONArray()
            agentResults[mission.id].orEmpty().forEach { record ->
                agents.put(
                    JSONObject()
                        .put("agentId", record.agentId)
                        .put("agentName", record.agentName)
                        .put("role", record.role)
                        .put("status", record.status)
                        .put("output", record.output ?: JSONObject.NULL)
                        .put("error", record.error ?: JSONObject.NULL)
                        .put("createdAt", record.createdAt)
                )
            }
            array.put(
                JSONObject()
                    .put("id", mission.id)
                    .put("goal", mission.goal)
                    .put("status", mission.status.name)
                    .put("createdAt", mission.createdAt)
                    .put("updatedAt", mission.updatedAt)
                    .put("result", mission.result ?: JSONObject.NULL)
                    .put("error", mission.error ?: JSONObject.NULL)
                    .put("agents", agents)
            )
        }
        root.put("missions", array)
        return root.toString(2)
    }
}
