package sk.mirecky.skener.data

import android.graphics.ImageDecoder
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.scanner.Filters
import sk.mirecky.skener.scanner.ImageOps
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode
import java.io.File

/** Spracovanie obrazu strán: orezanie a vyrovnanie (F4), filtre (F5), otočenie. Volať mimo hlavného vlákna. */
internal object PageRenderer {

    private const val JPEG_QUALITY = 92

    /** Z pôvodnej fotografie vytvorí farebný orez a výslednú stranu s filtrom. */
    fun renderNew(original: File, crop: File, display: File, quad: Quad?, mode: ScanMode, filter: PageFilter) {
        // ImageDecoder uplatní orientáciu z EXIF, takže obraz je vzpriamený rovnako ako pri analýze.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()

        val cropped = if (quad != null) {
            val snap = if (mode == ScanMode.DOCUMENT) ImageOps.A4_RATIO else null
            ImageOps.cropDocument(bgr, quad, snap, inset = EDGE_INSET).also { bgr.release() }
        } else {
            bgr
        }
        write(cropped, crop)
        renderFilter(cropped, filter, display)
        cropped.release()
    }

    /** Znovu vytvorí výslednú stranu z farebného orezu s iným filtrom. */
    fun applyFilter(crop: File, display: File, filter: PageFilter) {
        val src = Imgcodecs.imread(crop.path, Imgcodecs.IMREAD_COLOR)
        renderFilter(src, filter, display)
        src.release()
    }

    /** Otočí farebný orez aj výslednú stranu o 90° v smere hodinových ručičiek. */
    fun rotateClockwise(vararg files: File) {
        for (f in files) {
            val src = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_UNCHANGED)
            val dst = Mat()
            Core.rotate(src, dst, Core.ROTATE_90_CLOCKWISE)
            write(dst, f)
            src.release()
            dst.release()
        }
    }

    private fun renderFilter(crop: Mat, filter: PageFilter, display: File) {
        val out = Filters.apply(crop, filter)
        write(out, display)
        out.release()
    }

    private fun write(mat: Mat, target: File) {
        val tmp = File(target.parentFile, target.name + ".tmp.jpg")
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        val ok = Imgcodecs.imwrite(tmp.path, mat, params)
        params.release()
        if (!ok) error("Zápis ${target.name} zlyhal")
        if (!tmp.renameTo(target)) error("Premenovanie ${target.name} zlyhalo")
    }

    /** Odrezanie okraja strany (podiel), aby na hrane neostal tmavý prúžok podkladu. */
    private const val EDGE_INSET = 0.004
}
