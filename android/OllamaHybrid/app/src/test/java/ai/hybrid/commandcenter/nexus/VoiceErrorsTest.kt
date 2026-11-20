package ai.hybrid.commandcenter.nexus

import android.speech.SpeechRecognizer
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceErrorsTest {

    @Test
    fun `known recognizer errors map to human reasons`() {
        assertTrue(VoiceErrors.describe(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS).contains("микрофон"))
        assertTrue(VoiceErrors.describe(SpeechRecognizer.ERROR_NO_MATCH).contains("не распознана"))
        assertTrue(VoiceErrors.describe(SpeechRecognizer.ERROR_NETWORK).contains("сеть"))
    }

    @Test
    fun `unknown codes keep the code visible`() {
        assertTrue(VoiceErrors.describe(999).contains("999"))
    }
}
