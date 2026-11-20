package ai.hybrid.commandcenter

import ai.hybrid.commandcenter.ui.EffectsMode
import ai.hybrid.commandcenter.ui.MotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPolicyTest {

    @Test
    fun `system remove-animations forces static whatever the user picked`() {
        for (choice in EffectsMode.entries) {
            assertEquals(EffectsMode.STATIC, MotionPolicy.resolve(choice, animatorScale = 0f, powerSave = false))
        }
    }

    @Test
    fun `battery saver forces static`() {
        assertEquals(EffectsMode.STATIC, MotionPolicy.resolve(EffectsMode.EXPERIMENTAL, 1f, powerSave = true))
    }

    @Test
    fun `otherwise the user choice stands`() {
        assertEquals(EffectsMode.EXPERIMENTAL, MotionPolicy.resolve(EffectsMode.EXPERIMENTAL, 1f, false))
        assertEquals(EffectsMode.SUBTLE, MotionPolicy.resolve(EffectsMode.SUBTLE, 0.5f, false))
    }

    @Test
    fun `static never animates`() {
        assertTrue(!MotionPolicy.animates(EffectsMode.STATIC))
        assertTrue(MotionPolicy.animates(EffectsMode.SUBTLE))
    }

    @Test
    fun `durations follow the system animator scale`() {
        assertEquals(0, MotionPolicy.scaled(200, 0f))
        assertEquals(100, MotionPolicy.scaled(200, 0.5f))
        assertEquals(400, MotionPolicy.scaled(200, 2f))
    }

    @Test
    fun `unknown stored mode falls back to the bright default`() {
        assertEquals(EffectsMode.EXPERIMENTAL, EffectsMode.byId(null))
        assertEquals(EffectsMode.EXPERIMENTAL, EffectsMode.byId("glitter"))
        assertEquals(EffectsMode.STATIC, EffectsMode.byId("static"))
    }
}
