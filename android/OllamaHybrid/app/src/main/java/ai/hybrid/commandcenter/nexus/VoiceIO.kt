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

/** A system voice the owner can pick; network voices are labelled because they send text out. */
data class VoiceOption(val name: String, val label: String, val offline: Boolean, val quality: Int)

/**
 * Splits a streaming answer into finished sentences so speech can start after
 * the first sentence instead of after the whole answer. Decimals («3.5»),
 * abbreviations without a following space and unfinished tails are held back.
 */
class SentenceChunker {
    private val buffer = StringBuilder()

    fun add(chunk: String): List<String> {
        buffer.append(chunk)
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            val end = c == '\n' || ((c == '.' || c == '!' || c == '?' || c == '…') && i + 1 < buffer.length && buffer[i + 1].isWhitespace())
            if (end) {
                val sentence = buffer.substring(start, i + 1).trim()
                if (sentence.length >= 2) out += sentence
                start = i + 1
            }
            i++
        }
        buffer.delete(0, start)
        return out
    }

    fun flush(): String? {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return rest.takeIf { it.isNotEmpty() }
    }
}

class VoiceOutput(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nexus_profile", Context.MODE_PRIVATE)
    @Volatile private var ready = false
    @Volatile private var languageSupported = false
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    /** Real speaking state from the synthesiser (start/done/error), for the hologram's mouth. */
    @Volatile var onSpeakingChanged: ((Boolean) -> Unit)? = null

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            val result = runCatching { ttsSetLanguage() }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
            languageSupported = result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
            runCatching { applyPreferences() }
        }
    }

    init {
        runCatching {
            tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { onSpeakingChanged?.invoke(true) }
                override fun onDone(utteranceId: String?) { finished() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finished() }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { pending.set(0); onSpeakingChanged?.invoke(false) }
            })
        }
    }

    private fun finished() {
        if (pending.decrementAndGet() <= 0) {
            pending.set(0)
            onSpeakingChanged?.invoke(false)
        }
    }

    private fun ttsSetLanguage(): Int = tts.setLanguage(Locale("ru", "RU"))

    private fun applyPreferences() {
        prefs.getString(VOICE_KEY, null)?.let { wanted -> tts.voices?.firstOrNull { it.name == wanted }?.let { tts.voice = it } }
        tts.setSpeechRate(prefs.getFloat(RATE_KEY, 1.0f))
    }

    /** Russian voices installed in the system synthesiser, offline first. */
    fun voices(): List<VoiceOption> = runCatching {
        tts.voices.orEmpty()
            .filter { it.locale.language == "ru" && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) }
            .map { voice ->
                val offline = !voice.isNetworkConnectionRequired
                VoiceOption(voice.name, voiceLabel(voice.name), offline, voice.quality)
            }
            .sortedWith(compareByDescending<VoiceOption> { it.offline }.thenByDescending { it.quality }.thenBy { it.name })
    }.getOrDefault(emptyList())

    val selectedVoice: String? get() = prefs.getString(VOICE_KEY, null)
    val speechRate: Float get() = prefs.getFloat(RATE_KEY, 1.0f)

    fun selectVoice(name: String?) {
        prefs.edit().apply { if (name == null) remove(VOICE_KEY) else putString(VOICE_KEY, name) }.apply()
        runCatching {
            if (name == null) ttsSetLanguage() else tts.voices?.firstOrNull { it.name == name }?.let { tts.voice = it }
        }
    }

    fun setRate(rate: Float) {
        val value = rate.coerceIn(0.6f, 1.6f)
        prefs.edit().putFloat(RATE_KEY, value).apply()
        runCatching { tts.setSpeechRate(value) }
    }

    /** Returns null on success, otherwise the honest reason speaking did not start. */
    fun speak(text: String, queue: Boolean = false): String? {
        if (!ready) return "Синтез речи ещё инициализируется или недоступен."
        if (!languageSupported) return "Русский голос не установлен в системном синтезаторе."
        val chunk = text.take(3_900)
        if (chunk.isBlank()) return null
        if (!queue) pending.set(0)
        pending.incrementAndGet()
        val result = tts.speak(chunk, if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH, null,
            "nexus-voice-" + System.nanoTime())
        if (result != TextToSpeech.SUCCESS) finished()
        return if (result == TextToSpeech.SUCCESS) null else "Синтезатор отклонил запрос."
    }

    companion object {
        private const val VOICE_KEY = "tts_voice"
        private const val RATE_KEY = "tts_rate"

        /** «ru-ru-x-ruc-local» → «Голос RUC · на телефоне». */
        fun voiceLabel(name: String): String {
            val code = name.split('-', '#', '_').firstOrNull { it.length == 3 && it.startsWith("r") && it != "rus" }
                ?: name.substringAfterLast('-')
            val place = when {
                name.contains("network", true) -> "через интернет"
                name.contains("local", true) -> "на телефоне"
                else -> ""
            }
            return ("Голос " + code.uppercase() + if (place.isNotEmpty()) " · $place" else "").trim()
        }
    }

    fun stop() {
        runCatching { tts.stop() }
        onSpeakingChanged?.invoke(false)
    }

    fun shutdown() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}
