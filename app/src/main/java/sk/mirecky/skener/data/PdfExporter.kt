package sk.mirecky.skener.data

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.scanner.PageFilter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Zostavenie PDF zo strán dokumentu.
 *
 * Čiernobiele strany sa ukladajú bezstratovo ako 1-bitový obraz (300 dpi pre A4, Flate),
 * ostatné ako JPEG (200 dpi pre A4). Šírka strany PDF zodpovedá A4 (210 mm), výška pomeru obrazu.
 */
internal object PdfExporter {

    private const val PAGE_WIDTH_PT = 595.28f
    private const val BW_MAX_WIDTH = 2480 // 300 dpi na šírku A4
    private const val JPEG_MAX_WIDTH = 1654 // 200 dpi na šírku A4
    private const val JPEG_QUALITY = 80

    fun export(pages: List<PageItem>, target: File) {
        PDDocument().use { doc ->
            for (page in pages) {
                val image = if (page.filter == PageFilter.BLACK_WHITE) bilevel(doc, page.displayFile) else jpeg(doc, page.displayFile)
                val height = PAGE_WIDTH_PT * image.height / image.width
                val pdfPage = PDPage(PDRectangle(PAGE_WIDTH_PT, height))
                doc.addPage(pdfPage)
                PDPageContentStream(doc, pdfPage).use { it.drawImage(image, 0f, 0f, PAGE_WIDTH_PT, height) }
            }
            doc.save(target)
        }
    }

    private fun jpeg(doc: PDDocument, file: File): PDImageXObject {
        val src = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR)
        val scaled = downscale(src, JPEG_MAX_WIDTH)
        val buf = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, JPEG_QUALITY)
        Imgcodecs.imencode(".jpg", scaled, buf, params)
        val bytes = buf.toArray()
        listOf(src, scaled, buf, params).forEach { it.release() }
        return JPEGFactory.createFromByteArray(doc, bytes)
    }

    /** 1-bitový obraz: bit 1 = biela, 0 = čierna (DeviceGray). */
    private fun bilevel(doc: PDDocument, file: File): PDImageXObject {
        val src = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_GRAYSCALE)
        val scaled = downscale(src, BW_MAX_WIDTH)
        Imgproc.threshold(scaled, scaled, 128.0, 255.0, Imgproc.THRESH_BINARY)
        val w = scaled.cols()
        val h = scaled.rows()
        val rowBytes = (w + 7) / 8
        val row = ByteArray(w)
        val packed = ByteArray(rowBytes)
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use { z ->
            for (y in 0 until h) {
                scaled.get(y, 0, row)
                packed.fill(0)
                for (x in 0 until w) {
                    if (row[x].toInt() != 0) packed[x shr 3] = (packed[x shr 3].toInt() or (0x80 ushr (x and 7))).toByte()
                }
                z.write(packed)
            }
        }
        src.release()
        if (scaled !== src) scaled.release()
        return PDImageXObject(doc, ByteArrayInputStream(out.toByteArray()), COSName.FLATE_DECODE, w, h, 1, PDDeviceGray.INSTANCE)
    }

    private fun downscale(src: Mat, maxWidth: Int): Mat {
        if (src.cols() <= maxWidth) return src
        val scale = maxWidth.toDouble() / src.cols()
        val dst = Mat()
        Imgproc.resize(src, dst, Size(maxWidth.toDouble(), src.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        return dst
    }
}
