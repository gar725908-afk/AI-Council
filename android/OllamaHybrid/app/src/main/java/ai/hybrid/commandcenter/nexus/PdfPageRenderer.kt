package ai.hybrid.commandcenter.nexus

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Page rendering with the platform PdfRenderer: zero dependencies, offline,
 * exact rendering of the stored original. Text comes from PDFBox; pixels
 * come from here. Each call opens and closes the document, so no file
 * descriptor outlives a page render.
 */
object PdfPageRenderer {

    fun pageCount(path: String): Int = open(path)?.use { it.pageCount } ?: 0

    fun render(path: String, pageIndex: Int, targetWidthPx: Int): Bitmap? {
        val renderer = open(path) ?: return null
        renderer.use { doc ->
            if (pageIndex !in 0 until doc.pageCount) return null
            doc.openPage(pageIndex).use { page ->
                val width = targetWidthPx.coerceIn(200, 2400)
                val height = (width.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                return bitmap
            }
        }
    }

    private fun open(path: String): PdfRenderer? = try {
        val file = File(path)
        if (!file.exists()) null
        else PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
    } catch (_: Exception) {
        null
    }
}
