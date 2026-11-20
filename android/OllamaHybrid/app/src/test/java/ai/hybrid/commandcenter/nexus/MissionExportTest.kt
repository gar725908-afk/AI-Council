package ai.hybrid.commandcenter.nexus

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionExportTest {

    private val mission = MissionRecord(
        id = "m1", goal = "Проверить экспорт",
        status = MissionStatus.COMPLETED, createdAt = 1L, updatedAt = 2L,
        result = "итог", error = null
    )
    private val agent = AgentJournalRecord(
        id = "r1", missionId = "m1", agentId = "a1", agentName = "Аналитик", role = "ANALYST",
        status = "COMPLETED", output = "вывод", error = null, createdAt = 2L
    )

    @Test
    fun `export carries missions with nested agent results`() {
        val json = JSONObject(MissionExport.toJson(listOf(mission), mapOf("m1" to listOf(agent)), 10L))
        assertEquals("nexus-missions", json.getString("format"))
        assertEquals(1, json.getInt("missionCount"))
        val exported = json.getJSONArray("missions").getJSONObject(0)
        assertEquals("Проверить экспорт", exported.getString("goal"))
        assertEquals("COMPLETED", exported.getString("status"))
        assertEquals("вывод", exported.getJSONArray("agents").getJSONObject(0).getString("output"))
    }

    @Test
    fun `export never carries credential-looking fields`() {
        val json = MissionExport.toJson(listOf(mission), mapOf("m1" to listOf(agent)), 10L)
        assertTrue(!json.contains("apiKey") && !json.contains("Bearer") && !json.contains("token"))
    }

    @Test
    fun `empty journal exports a valid envelope`() {
        val json = JSONObject(MissionExport.toJson(emptyList(), emptyMap(), 10L))
        assertEquals(0, json.getJSONArray("missions").length())
    }
}
