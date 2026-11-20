package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.androidbridge.DeviceDiagnostics
import ai.hybrid.commandcenter.nexus.EndpointReachability
import ai.hybrid.commandcenter.nexus.ErrorEntry
import ai.hybrid.commandcenter.nexus.HealthCheckResult
import ai.hybrid.commandcenter.nexus.MissionRecord
import ai.hybrid.commandcenter.nexus.MissionStatus
import ai.hybrid.commandcenter.ui.NexFacts
import ai.hybrid.commandcenter.ui.OrbitalSegment
import ai.hybrid.commandcenter.ui.SegmentStatus
import java.util.Locale

/**
 * Pure mapping from measured state to what the Command Deck draws
 * (handoff 5.1). No Android calls here, so every rule is unit-tested and
 * the Core Orbital can never show a colour the data does not justify.
 */
object DeckModel {

    /** PowerManager.THERMAL_STATUS_MODERATE / SEVERE without an Android import. */
    private const val THERMAL_MODERATE = 2
    private const val THERMAL_SEVERE = 3
    private const val LOW_BATTERY_PERCENT = 15

    private val activeStates = setOf(MissionStatus.QUEUED, MissionStatus.RUNNING, MissionStatus.VERIFYING)

    fun runningMission(missions: List<MissionRecord>): MissionRecord? =
        missions.firstOrNull { it.status in activeStates }

    fun awaitingMission(missions: List<MissionRecord>): MissionRecord? =
        missions.firstOrNull { it.status == MissionStatus.AWAITING_INPUT }

    fun lastCompleted(missions: List<MissionRecord>): MissionRecord? =
        missions.filter { it.status == MissionStatus.COMPLETED }.maxByOrNull { it.updatedAt }

    fun networkAvailable(diagnostics: DeviceDiagnostics): Boolean =
        diagnostics.networkLabel != "Нет соединения"

    fun modelSegment(
        localModelName: String?,
        cloudLabel: String?,
        networkAvailable: Boolean,
        health: HealthCheckResult?
    ): OrbitalSegment = when {
        localModelName != null -> OrbitalSegment("Модель", localModelName, SegmentStatus.OK)
        cloudLabel == null -> OrbitalSegment("Модель", "не подключена", SegmentStatus.WARN)
        !networkAvailable -> OrbitalSegment("Модель", "$cloudLabel · нет сети", SegmentStatus.ERROR)
        health == null -> OrbitalSegment("Модель", "$cloudLabel · не проверено", SegmentStatus.WARN)
        health.reachability == EndpointReachability.REACHABLE ->
            OrbitalSegment("Модель", "$cloudLabel · ${health.latencyMillis} мс", SegmentStatus.OK)
        else -> OrbitalSegment("Модель", "$cloudLabel · недоступен", SegmentStatus.ERROR)
    }

    fun networkSegment(diagnostics: DeviceDiagnostics): OrbitalSegment =
        if (networkAvailable(diagnostics)) {
            OrbitalSegment("Сеть", diagnostics.networkLabel, SegmentStatus.OK)
        } else {
            OrbitalSegment("Сеть", "нет соединения", SegmentStatus.WARN)
        }

    fun deviceSegment(diagnostics: DeviceDiagnostics): OrbitalSegment {
        val battery = diagnostics.batteryPercent
        val thermal = diagnostics.thermalStatus
        val value = buildList {
            if (battery != null) add("$battery%")
            add(diagnostics.thermalLabel.lowercase(Locale.ROOT))
        }.joinToString(" · ")
        val lowBattery = battery != null && battery < LOW_BATTERY_PERCENT && diagnostics.charging != true
        val status = when {
            thermal != null && thermal >= THERMAL_SEVERE -> SegmentStatus.ERROR
            thermal != null && thermal >= THERMAL_MODERATE -> SegmentStatus.WARN
            lowBattery -> SegmentStatus.WARN
            battery == null && thermal == null -> SegmentStatus.UNKNOWN
            else -> SegmentStatus.OK
        }
        return OrbitalSegment("Устройство", value, status)
    }

    fun councilSegment(missions: List<MissionRecord>): OrbitalSegment {
        val running = runningMission(missions)
        if (running != null) return OrbitalSegment("Council", "работает", SegmentStatus.ACTIVE)
        if (awaitingMission(missions) != null) return OrbitalSegment("Council", "ждёт ответа", SegmentStatus.WARN)
        val latest = missions.maxByOrNull { it.updatedAt }
            ?: return OrbitalSegment("Council", "миссий не было", SegmentStatus.UNKNOWN)
        return when (latest.status) {
            MissionStatus.COMPLETED -> OrbitalSegment("Council", "последняя завершена", SegmentStatus.OK)
            MissionStatus.FAILED, MissionStatus.INTERRUPTED ->
                OrbitalSegment("Council", "последняя с ошибкой", SegmentStatus.ERROR)
            MissionStatus.CANCELLED -> OrbitalSegment("Council", "последняя отменена", SegmentStatus.OK)
            else -> OrbitalSegment("Council", "черновик", SegmentStatus.UNKNOWN)
        }
    }

    fun segments(
        localModelName: String?,
        cloudLabel: String?,
        diagnostics: DeviceDiagnostics,
        health: HealthCheckResult?,
        missions: List<MissionRecord>
    ): List<OrbitalSegment> = listOf(
        modelSegment(localModelName, cloudLabel, networkAvailable(diagnostics), health),
        networkSegment(diagnostics),
        deviceSegment(diagnostics),
        councilSegment(missions)
    )

    /**
     * Facts for NEX. A failed mission counts as an error event, as does a
     * journal error; the newer one wins.
     */
    fun facts(
        nowMillis: Long,
        listening: Boolean,
        localModelName: String?,
        cloudLabel: String?,
        diagnostics: DeviceDiagnostics,
        missions: List<MissionRecord>,
        lastError: ErrorEntry?
    ): NexFacts {
        val completed = lastCompleted(missions)
        val failedMission = missions
            .filter { it.status == MissionStatus.FAILED }
            .maxByOrNull { it.updatedAt }
        val journalErrorAt = lastError?.occurredAtEpochMillis
        val useMissionError = failedMission != null &&
            (journalErrorAt == null || failedMission.updatedAt > journalErrorAt)
        return NexFacts(
            nowMillis = nowMillis,
            listening = listening,
            networkAvailable = networkAvailable(diagnostics),
            localModelName = localModelName,
            cloudProviderName = cloudLabel,
            runningMissionGoal = runningMission(missions)?.goal,
            awaitingInputGoal = awaitingMission(missions)?.goal,
            lastCompletedGoal = completed?.goal,
            lastCompletedAtMillis = completed?.updatedAt,
            lastErrorSummary = if (useMissionError) {
                failedMission?.error ?: "миссия «${failedMission?.goal}» завершилась с ошибкой"
            } else {
                lastError?.summary
            },
            lastErrorAtMillis = if (useMissionError) failedMission?.updatedAt else journalErrorAt
        )
    }

    /** Active route: where a task typed now would actually go. */
    fun routeLine(localModelName: String?, cloudLabel: String?, cloudModel: String?, networkAvailable: Boolean): String =
        when {
            localModelName != null && cloudLabel != null && networkAvailable ->
                "На устройстве · $localModelName  ·  облако в резерве: $cloudLabel"
            localModelName != null -> "На устройстве · $localModelName"
            cloudLabel != null && networkAvailable ->
                "Облако · $cloudLabel" + (cloudModel?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            cloudLabel != null -> "Облако · $cloudLabel — сейчас нет сети"
            else -> "Маршрута нет: модель не подключена"
        }
}
