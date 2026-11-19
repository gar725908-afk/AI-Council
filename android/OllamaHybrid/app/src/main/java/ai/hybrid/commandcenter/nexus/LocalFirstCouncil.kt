package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Control modes for the local-first Council.
 *
 * The local model supplies decisions; this application-level controller owns execution,
 * durable mission state, participant allowlists and approval gates.
 */
enum class CouncilControlMode {
    LOCAL_AUTONOMOUS,
    LOCAL_ASSISTED,
    MANUAL
}

/** A transport is an implementation detail; the Manager does not depend on a provider API. */
enum class CouncilParticipantTransport {
    ON_DEVICE_LOCAL_MODEL,
    ANDROID_WEBVIEW_SESSION,
    OPTIONAL_API,
    DESKTOP_LINK,
    MANUAL_EXTERNAL
}

data class CouncilParticipantDescriptor(
    val id: String,
    val displayName: String,
    val transport: CouncilParticipantTransport,
    val enabled: Boolean = true,
    val sessionReady: Boolean = true,
    val requiresUserApproval: Boolean = false
)

data class LocalCouncilProfile(
    val mode: CouncilControlMode,
    /** Local/on-device model identifier used as the Council Manager. Null until configured. */
    val managerModelId: String? = null,
    val participants: List<CouncilParticipantDescriptor>,
    val maxManagerTurns: Int = 8,
    val maxParallelParticipants: Int = 3,
    /** Paid/API-backed participants are opt-in and disabled by default. */
    val allowOptionalApiParticipants: Boolean = false,
    /** Desktop execution is a future transport and remains disabled until paired explicitly. */
    val allowDesktopLink: Boolean = false
) {
    fun validationErrors(): List<String> = buildList {
        if (mode != CouncilControlMode.MANUAL && managerModelId.isNullOrBlank()) {
            add("Для автоматического режима выберите локальную модель-координатор.")
        }
        if (maxManagerTurns !in 1..32) add("Число ходов Manager должно быть от 1 до 32.")
        if (maxParallelParticipants !in 1..8) add("Параллелизм должен быть от 1 до 8.")
        if (participants.any { it.id.isBlank() }) add("У каждого участника должен быть непустой ID.")
        if (participants.map { it.id }.distinct().size != participants.size) {
            add("ID участников должны быть уникальными.")
        }
        if (!allowOptionalApiParticipants && participants.any {
                it.enabled && it.transport == CouncilParticipantTransport.OPTIONAL_API
            }
        ) {
            add("API-участники отключены политикой. Включите их отдельно, если действительно хотите их использовать.")
        }
        if (!allowDesktopLink && participants.any {
                it.enabled && it.transport == CouncilParticipantTransport.DESKTOP_LINK
            }
        ) {
            add("Desktop Link пока отключён. Подключение ПК будет добавлено отдельным этапом.")
        }
    }
}

data class CouncilAssignment(
    val participantId: String,
    val task: String
)

data class CouncilParticipantResult(
    val participantId: String,
    val displayName: String,
    val task: String,
    val output: String,
    val succeeded: Boolean,
    val error: String? = null
)

data class LocalCouncilContext(
    val goal: String,
    val managerTurn: Int,
    val maxManagerTurns: Int,
    val availableParticipants: List<CouncilParticipantDescriptor>,
    val previousResults: List<CouncilParticipantResult>
)

/**
 * Structured actions returned by the on-device Manager model.
 * The model cannot call a browser, shell or Android API directly.
 */
sealed interface LocalCouncilDecision {
    data class Delegate(val assignments: List<CouncilAssignment>) : LocalCouncilDecision
    data class AskUser(val question: String) : LocalCouncilDecision
    data class Finish(val summary: String) : LocalCouncilDecision
    data class YieldControl(val reason: String) : LocalCouncilDecision
}

interface LocalCouncilManager {
    suspend fun decide(context: LocalCouncilContext): LocalCouncilDecision
}

interface CouncilParticipantAdapter {
    val descriptor: CouncilParticipantDescriptor

    suspend fun execute(
        assignment: CouncilAssignment,
        previousResults: List<CouncilParticipantResult>
    ): String
}

/** Approval is supplied by the app UI; the default implementation denies protected dispatches. */
fun interface CouncilDispatchApproval {
    suspend fun approve(
        assignments: List<CouncilAssignment>,
        participants: List<CouncilParticipantDescriptor>,
        reason: String
    ): Boolean
}

enum class LocalCouncilExecutionStatus {
    COMPLETED,
    NEEDS_USER_INPUT,
    WAITING_FOR_APPROVAL,
    PAUSED,
    MANAGER_TURN_LIMIT,
    NO_AVAILABLE_PARTICIPANTS,
    INVALID_PROFILE,
    FAILED
}

data class LocalCouncilExecution(
    val status: LocalCouncilExecutionStatus,
    val managerTurns: Int = 0,
    val results: List<CouncilParticipantResult> = emptyList(),
    val summary: String? = null,
    val question: String? = null,
    val message: String? = null
)

/**
 * Provider-neutral Council control plane.
 *
 * LOCAL_AUTONOMOUS: the local model chooses which enabled participants to consult.
 * LOCAL_ASSISTED: the local model proposes dispatches; the user approves each batch.
 * MANUAL: the user chooses the participant and sends the task directly.
 *
 * WebView, embedded local inference, API and desktop adapters can be added independently.
 */
class LocalFirstCouncilOrchestrator(
    private val manager: LocalCouncilManager,
    participantAdapters: List<CouncilParticipantAdapter>,
    private val approval: CouncilDispatchApproval,
    maxParallelism: Int = 3
) {
    private val adapters = participantAdapters.associateBy { it.descriptor.id }
    private val parallelism = Semaphore(maxParallelism.coerceIn(1, 8))

    suspend fun runManaged(
        profile: LocalCouncilProfile,
        goal: String
    ): LocalCouncilExecution {
        if (profile.mode == CouncilControlMode.MANUAL) {
            return LocalCouncilExecution(
                status = LocalCouncilExecutionStatus.INVALID_PROFILE,
                message = "В режиме MANUAL используйте dispatchManually: выбор участника должен сделать пользователь."
            )
        }
        if (goal.isBlank()) {
            return LocalCouncilExecution(LocalCouncilExecutionStatus.FAILED, message = "Задача пуста.")
        }
        val errors = profile.validationErrors()
        if (errors.isNotEmpty()) {
            return LocalCouncilExecution(
                status = LocalCouncilExecutionStatus.INVALID_PROFILE,
                message = errors.joinToString(" ")
            )
        }

        val available = profile.participants.filter { descriptor ->
            descriptor.enabled && descriptor.sessionReady &&
                adapters[descriptor.id]?.descriptor == descriptor
        }
        if (available.isEmpty()) {
            return LocalCouncilExecution(
                status = LocalCouncilExecutionStatus.NO_AVAILABLE_PARTICIPANTS,
                message = "Нет подключённых участников. Добавьте локальную модель или войдите в выбранную WebView-сессию."
            )
        }

        val results = mutableListOf<CouncilParticipantResult>()
        var turns = 0
        while (turns < profile.maxManagerTurns) {
            val decision = try {
                manager.decide(
                    LocalCouncilContext(
                        goal = goal,
                        managerTurn = turns + 1,
                        maxManagerTurns = profile.maxManagerTurns,
                        availableParticipants = available,
                        previousResults = results.toList()
                    )
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                return LocalCouncilExecution(
                    LocalCouncilExecutionStatus.FAILED,
                    managerTurns = turns,
                    results = results.toList(),
                    message = "Локальный Manager завершился с ошибкой: ${error.message ?: error::class.java.simpleName}"
                )
            }
            turns++

            when (decision) {
                is LocalCouncilDecision.Delegate -> {
                    val assignments = decision.assignments
                    if (assignments.isEmpty() || assignments.size > 8 ||
                        assignments.any { it.task.isBlank() } ||
                        assignments.map { it.participantId }.distinct().size != assignments.size
                    ) {
                        return LocalCouncilExecution(
                            LocalCouncilExecutionStatus.FAILED,
                            turns,
                            results.toList(),
                            message = "Manager вернул некорректный список поручений."
                        )
                    }

                    val targets = assignments.mapNotNull { assignment ->
                        available.firstOrNull { it.id == assignment.participantId }
                    }
                    if (targets.size != assignments.size) {
                        return LocalCouncilExecution(
                            LocalCouncilExecutionStatus.FAILED,
                            turns,
                            results.toList(),
                            message = "Manager попытался обратиться к неизвестному или отключённому участнику."
                        )
                    }

                    val requiresApproval = profile.mode == CouncilControlMode.LOCAL_ASSISTED ||
                        targets.any { it.requiresUserApproval }
                    if (requiresApproval && !approval.approve(
                            assignments,
                            targets,
                            if (profile.mode == CouncilControlMode.LOCAL_ASSISTED) {
                                "Подтвердите отправку поручений участникам Council."
                            } else {
                                "Для выбранного участника требуется отдельное подтверждение."
                            }
                        )
                    ) {
                        return LocalCouncilExecution(
                            LocalCouncilExecutionStatus.WAITING_FOR_APPROVAL,
                            turns,
                            results.toList(),
                            message = "Ожидается подтверждение пользователя; поручения не отправлены."
                        )
                    }

                    val batchResults = coroutineScope {
                        assignments.map { assignment ->
                            async(Dispatchers.IO) {
                                parallelism.withPermit {
                                    val adapter = adapters.getValue(assignment.participantId)
                                    try {
                                        val output = adapter.execute(assignment, results.toList())
                                        CouncilParticipantResult(
                                            participantId = adapter.descriptor.id,
                                            displayName = adapter.descriptor.displayName,
                                            task = assignment.task,
                                            output = output,
                                            succeeded = output.isNotBlank(),
                                            error = if (output.isBlank()) "Участник вернул пустой ответ." else null
                                        )
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        CouncilParticipantResult(
                                            participantId = adapter.descriptor.id,
                                            displayName = adapter.descriptor.displayName,
                                            task = assignment.task,
                                            output = "",
                                            succeeded = false,
                                            error = error.message ?: error::class.java.simpleName
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    results += batchResults
                }

                is LocalCouncilDecision.AskUser -> return LocalCouncilExecution(
                    LocalCouncilExecutionStatus.NEEDS_USER_INPUT,
                    turns,
                    results.toList(),
                    question = decision.question
                )

                is LocalCouncilDecision.Finish -> return LocalCouncilExecution(
                    LocalCouncilExecutionStatus.COMPLETED,
                    turns,
                    results.toList(),
                    summary = decision.summary
                )

                is LocalCouncilDecision.YieldControl -> return LocalCouncilExecution(
                    LocalCouncilExecutionStatus.PAUSED,
                    turns,
                    results.toList(),
                    message = decision.reason
                )
            }
        }

        return LocalCouncilExecution(
            LocalCouncilExecutionStatus.MANAGER_TURN_LIMIT,
            turns,
            results.toList(),
            message = "Достигнут лимит ходов локального Manager. Миссия сохранена для продолжения или ручного управления."
        )
    }

    /** Explicit user-directed dispatch; no local Manager is required in MANUAL mode. */
    suspend fun dispatchManually(
        profile: LocalCouncilProfile,
        participantId: String,
        task: String
    ): LocalCouncilExecution {
        if (profile.mode != CouncilControlMode.MANUAL) {
            return LocalCouncilExecution(
                LocalCouncilExecutionStatus.INVALID_PROFILE,
                message = "Ручная отправка доступна только в режиме MANUAL."
            )
        }
        if (task.isBlank()) {
            return LocalCouncilExecution(LocalCouncilExecutionStatus.FAILED, message = "Задача пуста.")
        }
        val errors = profile.validationErrors()
        if (errors.isNotEmpty()) {
            return LocalCouncilExecution(
                LocalCouncilExecutionStatus.INVALID_PROFILE,
                message = errors.joinToString(" ")
            )
        }
        val descriptor = profile.participants.firstOrNull {
            it.id == participantId && it.enabled && it.sessionReady
        } ?: return LocalCouncilExecution(
            LocalCouncilExecutionStatus.NO_AVAILABLE_PARTICIPANTS,
            message = "Участник не включён или его сессия не готова."
        )
        val adapter = adapters[participantId]
            ?: return LocalCouncilExecution(
                LocalCouncilExecutionStatus.NO_AVAILABLE_PARTICIPANTS,
                message = "Для участника ещё не подключён транспорт."
            )
        if (adapter.descriptor != descriptor) {
            return LocalCouncilExecution(
                LocalCouncilExecutionStatus.INVALID_PROFILE,
                message = "Описание участника не совпадает с зарегистрированным адаптером."
            )
        }
        return try {
            val output = adapter.execute(CouncilAssignment(participantId, task), emptyList())
            LocalCouncilExecution(
                LocalCouncilExecutionStatus.COMPLETED,
                results = listOf(
                    CouncilParticipantResult(
                        participantId = participantId,
                        displayName = descriptor.displayName,
                        task = task,
                        output = output,
                        succeeded = output.isNotBlank(),
                        error = if (output.isBlank()) "Участник вернул пустой ответ." else null
                    )
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            LocalCouncilExecution(
                LocalCouncilExecutionStatus.FAILED,
                message = error.message ?: error::class.java.simpleName
            )
        }
    }
}
