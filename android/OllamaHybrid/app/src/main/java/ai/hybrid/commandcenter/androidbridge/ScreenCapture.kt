package ai.hybrid.commandcenter.androidbridge

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Screen capture for NEXUS Bridge (handoff section 7).
 *
 * Android grants this only through a system consent dialog the user must
 * accept, and the grant is one-shot: NEXUS keeps no standing capture
 * permission, takes exactly one frame per approved request and tears the
 * projection down immediately afterwards. Nothing is captured in the
 * background and nothing leaves the device by itself.
 */
object ScreenCaptureGrant {

    @Volatile
    private var pendingResultCode: Int = 0

    @Volatile
    private var pendingData: Intent? = null

    /** Intent for the system consent dialog. */
    fun consentIntent(context: Context): Intent? =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager)
            ?.createScreenCaptureIntent()

    /** Stores a grant the user just gave; it is consumed by the next capture. */
    fun store(resultCode: Int, data: Intent?) {
        pendingResultCode = resultCode
        pendingData = data
    }

    fun hasGrant(): Boolean = pendingData != null

    fun clear() {
        pendingResultCode = 0
        pendingData = null
    }

    internal fun consume(): Pair<Int, Intent>? {
        val data = pendingData ?: return null
        val code = pendingResultCode
        clear()
        return code to data
    }
}

object ScreenCapture {

    data class CaptureOutcome(
        val success: Boolean,
        val filePath: String?,
        val widthPx: Int,
        val heightPx: Int,
        val detail: String
    )

    private const val CAPTURE_TIMEOUT_SECONDS = 8L

    /**
     * Takes exactly one frame using a grant the user has just approved and
     * writes it into app-private storage. The projection is always stopped.
     */
    fun captureOnce(context: Context): CaptureOutcome {
        val grant = ScreenCaptureGrant.consume()
            ?: return CaptureOutcome(false, null, 0, 0, "Нет разрешения на захват: система не выдавала согласия.")
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return CaptureOutcome(false, null, 0, 0, "Сервис захвата экрана недоступен.")

        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return CaptureOutcome(false, null, 0, 0, "Оконный сервис недоступен.")
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        if (width <= 0 || height <= 0) {
            return CaptureOutcome(false, null, 0, 0, "Не удалось определить размер экрана.")
        }

        var projection: MediaProjection? = null
        var reader: ImageReader? = null
        return try {
            projection = manager.getMediaProjection(grant.first, grant.second)
                ?: return CaptureOutcome(false, null, 0, 0, "Система не выдала сессию захвата.")
            reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
            val latch = CountDownLatch(1)
            val handler = Handler(Looper.getMainLooper())
            var bitmap: Bitmap? = null

            reader.setOnImageAvailableListener({ source ->
                if (bitmap != null) return@setOnImageAvailableListener
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val full = Bitmap.createBitmap(
                        width + rowPadding / plane.pixelStride, height, Bitmap.Config.ARGB_8888
                    )
                    full.copyPixelsFromBuffer(plane.buffer)
                    bitmap = if (full.width != width) Bitmap.createBitmap(full, 0, 0, width, height) else full
                } finally {
                    image.close()
                    latch.countDown()
                }
            }, handler)

            val display = projection.createVirtualDisplay(
                "nexus-capture",
                width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
            val captured = latch.await(CAPTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            display?.release()

            val frame = bitmap
            when {
                !captured || frame == null ->
                    CaptureOutcome(false, null, width, height, "Кадр не пришёл за ${CAPTURE_TIMEOUT_SECONDS} с.")
                else -> {
                    val directory = File(context.filesDir, "captures").apply { mkdirs() }
                    val file = File(directory, "screen-" + System.currentTimeMillis() + ".png")
                    FileOutputStream(file).use { out -> frame.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    frame.recycle()
                    CaptureOutcome(true, file.absolutePath, width, height, "Снимок сохранён во внутренней памяти приложения.")
                }
            }
        } catch (error: Exception) {
            CaptureOutcome(false, null, width, height, "Захват не выполнен: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            runCatching { reader?.close() }
            runCatching { projection?.stop() }
        }
    }
}

/**
 * Bridge tool for a single screen capture. SENSITIVE: the Gateway requires a
 * one-shot approval, and the system consent dialog must have been accepted
 * immediately before.
 */
class ScreenCaptureTool(private val context: Context) : VerifiableBridgeTool {
    override val id: String = "ui.capture_screen"
    override val capability: BridgeCapability = BridgeCapability.SCREEN_CAPTURE
    override val risk: ActionRisk = ActionRisk.SENSITIVE
    override val supportedTransports: Set<BridgeTransport> = setOf(BridgeTransport.NATIVE)

    override suspend fun execute(request: BridgeRequest, transport: BridgeTransport): BridgeResult {
        if (!ScreenCaptureGrant.hasGrant()) {
            return BridgeResult(
                false, transport,
                "Система не выдала согласие на захват экрана. Нажмите «Разрешить снимок» и подтвердите в системном окне.",
                errorCode = "E_NO_CONSENT"
            )
        }
        val outcome = ScreenCapture.captureOnce(context)
        return if (outcome.success && outcome.filePath != null) {
            BridgeResult(
                true, transport, outcome.detail,
                data = mapOf(
                    "path" to outcome.filePath,
                    "width" to outcome.widthPx.toString(),
                    "height" to outcome.heightPx.toString()
                )
            )
        } else {
            BridgeResult(false, transport, outcome.detail, errorCode = "E_CAPTURE_FAILED")
        }
    }

    /** Postcondition: the file really exists and is a non-empty image. */
    override suspend fun verify(request: BridgeRequest, result: BridgeResult): VerifiableBridgeTool.Verification {
        val path = result.data["path"].orEmpty()
        if (path.isBlank()) return VerifiableBridgeTool.Verification.Failed("Путь к снимку не возвращён.")
        val file = File(path)
        if (!file.exists() || file.length() == 0L) {
            return VerifiableBridgeTool.Verification.Failed("Файл снимка отсутствует или пуст.")
        }
        return VerifiableBridgeTool.Verification.Passed
    }
}
