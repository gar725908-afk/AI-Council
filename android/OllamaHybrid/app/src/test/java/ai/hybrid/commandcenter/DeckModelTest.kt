package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceDiagnostics
import ai.hybrid.commandcenter.nexus.EndpointReachability
import ai.hybrid.commandcenter.nexus.ErrorEntry
import ai.hybrid.commandcenter.nexus.HealthCheckResult
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.ui.NexMind
import ai.hybrid.commandcenter.ui.NexState
import ai.hybrid.commandcenter.ui.SegmentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckModelTest {

    private fun diag(
        battery: Int? = 80,
        charging: Boolean? = false,
        thermal: Int? = 0,
        network: String = "Wi-Fi"
    ) = DeviceDiagnostics(
        batteryPercent = battery,
        charging = charging,
        ramFreeBytes = 2_000_000_000,
        ramTotalBytes = 8_000_000_000,
        storageFreeBytes = 10_000_000_000,
        storageTotalBytes = 100_000_000_000,
        thermalLabel = "Норма",
        thermalStatus = thermal,
        networkLabel = network,
        measuredAtEpochMillis = 1_000
    )

    private fun mission(id: String, status: MissionStatus, updated: Long, error: String? = null) =
        MissionRecord(id, "цель $id", status, createdAt = 0, updatedAt = updated, result = null, error = error)

    private fun health(reachable: Boolean) = HealthCheckResult(
        reachability = if (reachable) EndpointReachability.REACHABLE else EndpointReachability.UNREACHABLE,
        httpCode = if (reachable) 200 else null,
        detail = "",
        latencyMillis = 120,
        checkedAtEpochMillis = 5
    )

    @Test
    fun localModelIsAlwaysOk() {
        val s = DeckModel.modelSegment("Gemma 2B", "OpenAI", networkAvailable = false, health = null)
        assertEquals(SegmentStatus.OK, s.status)
        assertEquals("Gemma 2B", s.value)
    }

    @Test
    fun cloudOnlyNeverCheckedIsAWarningNotOk() {
        val s = DeckModel.modelSegment(null, "OpenAI", networkAvailable = true, health = null)
        assertEquals(SegmentStatus.WARN, s.status)
        assertTrue(s.value.contains("не проверено"))
    }

    @Test
    fun cloudReachableAndUnreachable() {
        assertEquals(SegmentStatus.OK, DeckModel.modelSegment(null, "X", true, health(true)).status)
        assertEquals(SegmentStatus.ERROR, DeckModel.modelSegment(null, "X", true, health(false)).status)
        assertEquals(SegmentStatus.ERROR, DeckModel.modelSegment(null, "X", false, health(true)).status)
    }

    @Test
    fun noModelWarns() {
        assertEquals(SegmentStatus.WARN, DeckModel.modelSegment(null, null, true, null).status)
    }

    @Test
    fun deviceSegmentFollowsThermalAndBattery() {
        assertEquals(SegmentStatus.OK, DeckModel.deviceSegment(diag()).status)
        assertEquals(SegmentStatus.WARN, DeckModel.deviceSegment(diag(thermal = 2)).status)
        assertEquals(SegmentStatus.ERROR, DeckModel.deviceSegment(diag(thermal = 4)).status)
        assertEquals(SegmentStatus.WARN, DeckModel.deviceSegment(diag(battery = 9)).status)
        assertEquals(SegmentStatus.OK, DeckModel.deviceSegment(diag(battery = 9, charging = true)).status)
        assertEquals(SegmentStatus.UNKNOWN, DeckModel.deviceSegment(diag(battery = null, thermal = null)).status)
    }

    @Test
    fun networkSegment() {
        assertEquals(SegmentStatus.OK, DeckModel.networkSegment(diag()).status)
        assertEquals(SegmentStatus.WARN, DeckModel.networkSegment(diag(network = "Нет соединения")).status)
    }

    @Test
    fun councilSegmentPriorities() {
        assertEquals(SegmentStatus.UNKNOWN, DeckModel.councilSegment(emptyList()).status)
        assertEquals(
            SegmentStatus.ACTIVE,
            DeckModel.councilSegment(listOf(mission("a", MissionStatus.FAILED, 9), mission("b", MissionStatus.RUNNING, 1))).status
        )
        assertEquals(SegmentStatus.WARN, DeckModel.councilSegment(listOf(mission("a", MissionStatus.AWAITING_INPUT, 1))).status)
        assertEquals(
            SegmentStatus.ERROR,
            DeckModel.councilSegment(listOf(mission("a", MissionStatus.COMPLETED, 1), mission("b", MissionStatus.FAILED, 2))).status
        )
        assertEquals(SegmentStatus.OK, DeckModel.councilSegment(listOf(mission("a", MissionStatus.COMPLETED, 3))).status)
    }

    @Test
    fun segmentsAreFourInFixedOrder() {
        val titles = DeckModel.segments("M", null, diag(), null, emptyList()).map { it.title }
        assertEquals(listOf("Модель", "Сеть", "Устройство", "Council"), titles)
    }

    @Test
    fun factsPickNewerErrorSource() {
        val journalError = ErrorEntry(1, "crash", "журнальная ошибка", "", "1", "15", "Pixel", occurredAtEpochMillis = 500)
        val missions = listOf(mission("a", MissionStatus.FAILED, 900, error = "таймаут провайдера"))
        val newerMission = DeckModel.facts(1_000, false, "M", null, diag(), missions, journalError)
        assertEquals("таймаут провайдера", newerMission.lastErrorSummary)
        assertEquals(900L, newerMission.lastErrorAtMillis)

        val newerJournal = DeckModel.facts(1_000, false, "M", null, diag(), missions, journalError.copy(occurredAtEpochMillis = 950))
        assertEquals("журнальная ошибка", newerJournal.lastErrorSummary)
    }

    @Test
    fun runningMissionMakesNexThink() {
        val facts = DeckModel.facts(
            10_000, false, "M", null, diag(),
            listOf(mission("a", MissionStatus.RUNNING, 9_000)), null
        )
        assertEquals(NexState.THINKING, NexMind.derive(facts))
    }

    @Test
    fun offlineWithoutLocalModelIsOffline() {
        val facts = DeckModel.facts(10_000, false, null, "OpenAI", diag(network = "Нет соединения"), emptyList(), null)
        assertEquals(NexState.OFFLINE, NexMind.derive(facts))
        assertNull(facts.lastErrorSummary)
    }

    @Test
    fun routeLineIsHonest() {
        assertEquals("Маршрута нет: модель не подключена", DeckModel.routeLine(null, null, null, true))
        assertTrue(DeckModel.routeLine(null, "OpenAI", "gpt", false).contains("нет сети"))
        assertTrue(DeckModel.routeLine("Gemma", null, null, false).startsWith("На устройстве"))
    }
}
