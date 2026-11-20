package ai.hybrid.commandcenter.nexus

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * NEXUS Voice (handoff 4.8) on platform services only: SpeechRecognizer for
 * input and TextToSpeech for output. No cloud SDKs, no always-on listening —
 * recognition starts only from an explicit tap and every failure is shown
 * with its reason. Availability depends on the device's installed engines
 * and is reported honestly instead of assumed.
 */
object VoiceErrors {
    fun describe(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Ошибка записи звука."
        SpeechRecognizer.ERROR_CLIENT -> "Ошибка клиента распознавания."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет разрешения на микрофон."
        SpeechRecognizer.ERROR_NETWORK -> "Распознаватель требует сеть, а она недоступна."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Таймаут сети распознавателя."
        SpeechRecognizer.ERROR_NO_MATCH -> "Речь не распознана."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознаватель занят."
        SpeechRecognizer.ERROR_SERVER -> "Ошибка сервера распознавания."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Речь не началась — время ожидания вышло."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Язык не поддерживается установленным распознавателем."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "Языковой пакет не загружен на устройстве."
        else -> "Ошибка распознавания (код $code)."
    }
}

class VoiceInput(private val context: Context) {

    sealed class Event {
        object Listening : Event()
        data class Partial(val text: String) : Event()
        /** Microphone level from the recogniser, in dB (roughly -2…10). */
        data class Level(val rmsDb: Float) : Event()
        data class Final(val text: String) : Event()
        data class Error(val reason: String) : Event()
        object Ended : Event()
    }

    private var recognizer: SpeechRecognizer? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /** Main thread only. */
    fun start(language: String = "ru-RU", onEvent: (Event) -> Unit) {
        if (!isAvailable()) {
            onEvent(Event.Error("На устройстве нет службы распознавания речи."))
            return
        }
        stop()
        val speech = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = speech
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = onEvent(Event.Listening)
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = onEvent(Event.Level(rmsdB))
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = onEvent(Event.Ended)
            override fun onError(error: Int) = onEvent(Event.Error(VoiceErrors.describe(error)))
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onEvent(if (text.isBlank()) Event.Error(VoiceErrors.describe(SpeechRecognizer.ERROR_NO_MATCH)) else Event.Final(text))
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!text.isNullOrBlank()) onEvent(Event.Partial(text))
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        speech.startListening(intent)
    }

    fun stop() {
        recognizer?.let { runCatching { it.stopListening() }; runCatching { it.destroy() } }
        recognizer = null
    }
}

class VoiceOutput(context: Context) {
    @Volatile private var ready = false
    @Volatile private var languageSupported = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val result = runCatching { ttsSetLanguage() }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            languageSupported = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    private fun ttsSetLanguage(): Int = tts.setLanguage(Locale("ru", "RU"))

    /** Returns null on success, otherwise the honest reason speaking did not start. */
    fun speak(text: String): String? {
        if (!ready) return "Синтез речи ещё инициализируется или недоступен."
        if (!languageSupported) return "Русский голос не установлен в системном синтезаторе."
        val chunk = text.take(3_900)
        val result = tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "nexus-voice")
        return if (result == TextToSpeech.SUCCESS) null else "Синтезатор отклонил запрос."
    }

    fun stop() {
        runCatching { tts.stop() }
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
