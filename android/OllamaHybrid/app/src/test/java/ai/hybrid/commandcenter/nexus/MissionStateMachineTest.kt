package ai.hybrid.commandcenter.nexus

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionStateMachineTest {

    @Test
    fun supportsNormalMissionLifecycle() {
        assertTrue(MissionStateMachine.canTransition(MissionStatus.DRAFT, MissionStatus.QUEUED))
        assertTrue(MissionStateMachine.canTransition(MissionStatus.QUEUED, MissionStatus.RUNNING))
        assertTrue(MissionStateMachine.canTransition(MissionStatus.RUNNING, MissionStatus.VERIFYING))
        assertTrue(MissionStateMachine.canTransition(MissionStatus.VERIFYING, MissionStatus.COMPLETED))
    }

    @Test
    fun refusesUnsafeOrInvalidTransitions() {
        assertFalse(MissionStateMachine.canTransition(MissionStatus.DRAFT, MissionStatus.COMPLETED))
        assertFalse(MissionStateMachine.canTransition(MissionStatus.RUNNING, MissionStatus.COMPLETED))
        assertFalse(MissionStateMachine.canTransition(MissionStatus.COMPLETED, MissionStatus.QUEUED))
        assertFalse(MissionStateMachine.canTransition(MissionStatus.CANCELLED, MissionStatus.RUNNING))
    }

    @Test
    fun interruptedMissionRequiresExplicitResume() {
        assertTrue(MissionStateMachine.canTransition(MissionStatus.INTERRUPTED, MissionStatus.QUEUED))
        assertTrue(MissionStateMachine.canTransition(MissionStatus.INTERRUPTED, MissionStatus.CANCELLED))
        assertFalse(MissionStateMachine.canTransition(MissionStatus.INTERRUPTED, MissionStatus.RUNNING))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requireTransitionThrowsForInvalidTransition() {
        MissionStateMachine.requireTransition(MissionStatus.DRAFT, MissionStatus.COMPLETED)
    }
}
