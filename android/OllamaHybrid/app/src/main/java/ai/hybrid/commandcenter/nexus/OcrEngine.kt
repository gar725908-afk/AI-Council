package ai.hybrid.commandcenter.nexus

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * On-device OCR for scanned pages (handoff 4.7).
 *
 * Honest scope, stated everywhere it is offered: the bundled model reads the
 * **Latin script and digits only** — Cyrillic is not recognised, because no
 * Cyrillic model is published for this engine. So it is useful for amounts,
 * dates, contract and article numbers, emails and Latin names inside a scan,
 * and it never pretends to have read Russian prose. Nothing is uploaded:
 * recognition runs entirely on the device.
 */
object OcrEngine {

    const val SCOPE_NOTE =
        "Распознаётся латиница и цифры (суммы, даты, номера, артикулы, email). " +
            "Кириллица этой моделью не читается."

    sealed class Result {
        data class Text(val text: String, val blocks: Int, val meanConfidence: Float?) : Result()
        data class Empty(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun recognize(bitmap: Bitmap): Result = suspendCancellableCoroutine { continuation ->
        val image = runCatching { InputImage.fromBitmap(bitmap, 0) }.getOrElse { error ->
            continuation.resume(Result.Failed("Не удалось подготовить изображение: " + (error.message ?: "ошибка")))
            return@suspendCancellableCoroutine
        }
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text.trim()
                if (text.isBlank()) {
                    continuation.resume(Result.Empty("На странице не найден распознаваемый текст. " + SCOPE_NOTE))
                } else {
                    val confidences = visionText.textBlocks.mapNotNull { block ->
                        block.lines.mapNotNull { line -> runCatching { line.confidence }.getOrNull() }
                            .takeIf { it.isNotEmpty() }?.average()?.toFloat()
                    }
                    continuation.resume(
                        Result.Text(
                            text = text.take(MaterialTextExtractor.MAX_TEXT_CHARS),
                            blocks = visionText.textBlocks.size,
                            meanConfidence = confidences.takeIf { it.isNotEmpty() }?.average()?.toFloat()
                        )
                    )
                }
            }
            .addOnFailureListener { error ->
                continuation.resume(Result.Failed("Распознавание не выполнено: " + (error.message ?: "ошибка")))
            }
        continuation.invokeOnCancellation { }
    }

    /** Assembles recognised pages into one material text with page markers. */
    fun assemblePages(pages: List<Pair<Int, String>>): String = buildString {
        appendLine("[Распознано на устройстве. " + SCOPE_NOTE + "]")
        appendLine()
        pages.sortedBy { it.first }.forEach { (number, text) ->
            appendLine("## Страница $number")
            appendLine(text.trim())
            appendLine()
        }
    }.trim()
}
