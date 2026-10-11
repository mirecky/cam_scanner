package sk.mirecky.skener.ui.document

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.scanner.Adjust
import sk.mirecky.skener.scanner.Filters
import sk.mirecky.skener.scanner.PageFilter
import kotlin.math.max

/**
 * Rýchly náhľad filtrov na zmenšenom farebnom oreze strany, aby úpravy (−/+) reagovali okamžite.
 * Výsledná strana v plnom rozlíšení sa prepočíta až po dokončení úprav.
 */
class FilterPreview(private val page: PageItem) {

    private val base = CompletableDeferred<Mat>()
    @Volatile private var loaded: Mat? = null
    @Volatile private var released = false

    suspend fun load() = withContext(Dispatchers.Default) {
        if (base.isCompleted) return@withContext
        val src = Imgcodecs.imread(page.cropFile.path, Imgcodecs.IMREAD_COLOR)
        val mat = scaled(src, PREVIEW_SIZE).also { if (it !== src) src.release() }
        loaded = mat
        base.complete(mat)
    }

    suspend fun render(filter: PageFilter, level: Int, adjust: Adjust): ImageBitmap = withContext(Dispatchers.Default) {
        val out = Filters.render(base.await(), filter, level, adjust)
        toImageBitmap(out).also { out.release() }
    }

    /** Malé náhľady všetkých filtrov s predvoleným parametrom (pre rad ikoniek). */
    suspend fun thumbnails(): Map<PageFilter, ImageBitmap> = withContext(Dispatchers.Default) {
        val small = scaled(base.await(), THUMB_SIZE)
        PageFilter.entries.associateWith { f ->
            val out = Filters.apply(small, f, 0)
            toImageBitmap(out).also { out.release() }
        }.also { if (small !== base.await()) small.release() }
    }

    fun release() {
        if (released) return
        released = true
        loaded?.release() ?: base.cancel()
    }

    private fun scaled(src: Mat, maxSide: Int): Mat {
        val longest = max(src.cols(), src.rows())
        if (longest <= maxSide) return src
        val f = maxSide.toDouble() / longest
        return Mat().also { Imgproc.resize(src, it, Size(src.cols() * f, src.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA) }
    }

    private fun toImageBitmap(bgr: Mat): ImageBitmap {
        val rgba = Mat()
        Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp.asImageBitmap()
    }

    private companion object {
        const val PREVIEW_SIZE = 1400
        const val THUMB_SIZE = 220
    }
}
