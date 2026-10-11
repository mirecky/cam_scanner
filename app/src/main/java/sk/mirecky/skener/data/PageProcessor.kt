package sk.mirecky.skener.data

import android.graphics.ImageDecoder
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.scanner.ImageOps
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode
import java.io.File

/** Spracovanie nasnímanej fotografie na stranu dokumentu (orezanie a vyrovnanie perspektívy, F4). */
object PageProcessor {

    private const val JPEG_QUALITY = 92

    /**
     * Zo súboru [original] vytvorí stranu [target]. Ak je známy obrys dokumentu [quad]
     * (normalizované súradnice vzpriameného obrazu), dokument sa vyreže a vyrovná
     * (v režime dokument s prispôsobením na pomer A4, ak je mu blízky); inak sa použije celá fotografia.
     */
    fun process(original: File, target: File, quad: Quad?, mode: ScanMode) {
        if (quad == null) {
            original.copyTo(target, overwrite = true)
            return
        }
        // ImageDecoder uplatní orientáciu z EXIF, takže obraz je vzpriamený rovnako ako pri analýze.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()

        val snap = if (mode == ScanMode.DOCUMENT) ImageOps.A4_RATIO else null
        val cropped = ImageOps.cropDocument(rgba, quad, snap)
        rgba.release()
        val bgr = Mat()
        Imgproc.cvtColor(cropped, bgr, Imgproc.COLOR_RGBA2BGR)
        cropped.release()

        val tmp = File(target.parentFile, target.name + ".tmp.jpg")
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        val ok = Imgcodecs.imwrite(tmp.path, bgr, params)
        bgr.release()
        params.release()
        if (!ok) error("Zápis strany zlyhal")
        tmp.renameTo(target)
    }
}
