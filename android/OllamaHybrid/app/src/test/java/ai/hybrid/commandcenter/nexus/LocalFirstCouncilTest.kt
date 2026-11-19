package ai.hybrid.commandcenter.nexus

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFirstCouncilTest {

    @Test
    fun localManagerCanDelegateAndThenReviewAnotherRound() = runBlocking {
        val web = CouncilParticipantDescriptor(
            id = "web-session-a",
            displayName = "Free AI Web Session",
            transport = CouncilParticipantTransport.ANDROID_WEBVIEW_SESSION,
            externalContextConsentGranted = true
        )
        val adapter = RecordingAdapter(web)
        val manager = QueueManager(
            LocalCouncilDecision.Delegate(
                listOf(CouncilAssignment(web.id, "Find an approach to the task"))
            ),
            LocalCouncilDecision.Delegate(
                listOf(CouncilAssignment(web.id, "Critically review your previous answer"))
            ),
            LocalCouncilDecision.Finish("Final result after two rounds")
        )
        val profile = LocalCouncilProfile(
            mode = CouncilControlMode.LOCAL_AUTONOMOUS,
            managerModelId = "on-device-model",
            participants = listOf(web)
        )
        val orchestrator = LocalFirstCouncilOrchestrator(
            manager = manager,
            participantAdapters = listOf(adapter),
            approval = CouncilDispatchApproval { _, _, _ -> false }
        )

        val result = orchestrator.runManaged(profile, "Design a solution")

        assertEquals(LocalCouncilExecutionStatus.COMPLETED, result.status)
        assertEquals(3, result.managerTurns)
        assertEquals(2, result.results.size)
        assertEquals("Final result after two rounds", result.summary)
        assertEquals(1, manager.contexts[1].previousResults.size)
        assertEquals("Critically review your previous answer", adapter.tasks[1])
    }

    @Test
    fun localManagerIsRequiredOutsideManualMode() {
        val profile = LocalCouncilProfile(
            mode = CouncilControlMode.LOCAL_AUTONOMOUS,
            managerModelId = null,
            participants = listOf(
                CouncilParticipantDescriptor(
                    "web",
                    "Web session",
                    CouncilParticipantTransport.ANDROID_WEBVIEW_SESSION
                )
            )
        )

        assertTrue(profile.validationErrors().any { it.contains("локальную модель-координатор") })
    }

    @Test
    fun optionalApiParticipantsRequireExplicitOptIn() {
        val api = CouncilParticipantDescriptor(
            "api-agent",
            "Optional API",
            CouncilParticipantTransport.OPTIONAL_API
        )
        val profile = LocalCouncilProfile(
            mode = CouncilControlMode.LOCAL_AUTONOMOUS,
            managerModelId = "local",
            participants = listOf(api)
        )

        assertTrue(profile.validationErrors().any { it.contains("API-участники отключены") })
        assertFalse(
            profile.copy(allowOptionalApiParticipants = true)
                .validationErrors().any { it.contains("API-участники отключены") }
        )
    }

    @Test
    fun assistedModeStopsBeforeDispatchWhenApprovalIsDenied() = runBlocking {
        val web = CouncilParticipantDescriptor(
            "web",
            "Web session",
            CouncilParticipantTransport.ANDROID_WEBVIEW_SESSION
        )
        val adapter = RecordingAdapter(web)
        val manager = QueueManager(
            LocalCouncilDecision.Delegate(listOf(CouncilAssignment("web", "Analyze this")))
        )
        val profile = LocalCouncilProfile(
            mode = CouncilControlMode.LOCAL_ASSISTED,
            managerModelId = "local",
            participants = listOf(web)
        )
        val orchestrator = LocalFirstCouncilOrchestrator(
            manager,
            listOf(adapter),
            CouncilDispatchApproval { _, _, _ -> false }
        )

        val result = orchestrator.runManaged(profile, "Test")

        assertEquals(LocalCouncilExecutionStatus.WAITING_FOR_APPROVAL, result.status)
        assertTrue(adapter.tasks.isEmpty())
    }

    @Test
    fun manualModeCanDispatchWithoutConfiguringLocalManager() = runBlocking {
        val web = CouncilParticipantDescriptor(
            "web",
            "Web session",
            CouncilParticipantTransport.ANDROID_WEBVIEW_SESSION
        )
        val adapter = RecordingAdapter(web)
        val profile = LocalCouncilProfile(
            mode = CouncilControlMode.MANUAL,
            managerModelId = null,
            participants = listOf(web)
        )
        val orchestrator = LocalFirstCouncilOrchestrator(
            QueueManager(LocalCouncilDecision.Finish("unused")),
            listOf(adapter),
            CouncilDispatchApproval { _, _, _ -> false }
        )

        val result = orchestrator.dispatchManually(profile, "web", "Manual request")

        assertEquals(LocalCouncilExecutionStatus.COMPLETED, result.status)
        assertEquals("Manual request", adapter.tasks.single())
    }

    private class QueueManager(
        private vararg val decisions: LocalCouncilDecision
    ) : LocalCouncilManager {
        val contexts = mutableListOf<LocalCouncilContext>()
        private var index = 0

        override suspend fun decide(context: LocalCouncilContext): LocalCouncilDecision {
            contexts += context
            return decisions.getOrElse(index++) {
                LocalCouncilDecision.Finish("No more decisions")
            }
        }
    }

    private class RecordingAdapter(
        override val descriptor: CouncilParticipantDescriptor
    ) : CouncilParticipantAdapter {
        val tasks = mutableListOf<String>()

        override suspend fun execute(
            assignment: CouncilAssignment,
            previousResults: List<CouncilParticipantResult>
        ): String {
            tasks += assignment.task
            return "Result: ${assignment.task}"
        }
    }
}
