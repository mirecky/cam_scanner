package sk.mirecky.skener.data

import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceGray
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.util.Matrix
import org.opencv.core.Core
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
import java.io.InputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Zostavenie PDF zo strán dokumentu.
 *
 * Čiernobiele strany sa ukladajú bezstratovo ako 1-bitový obraz (300 dpi pre A4, Flate),
 * ostatné ako JPEG (200 dpi pre A4); strany bez farieb (odtiene šedej, farebný filter na
 * čiernobielej predlohe) ako jednokanálový JPEG, ktorý je približne o polovicu menší. Šírka strany PDF zodpovedá A4 (210 mm), výška pomeru obrazu.
 * Ak je k strane k dispozícii OCR, pridá sa neviditeľná textová vrstva (vyhľadávanie, kopírovanie).
 */
internal object PdfExporter {

    private const val PAGE_WIDTH_PT = 595.28f

    /** Rozlíšenie a kompresia obrázkov v PDF. */
    private class Quality(val bwMaxWidth: Int, val jpegMaxWidth: Int, val jpegQuality: Int, val grayQuality: Int)

    /** 300 dpi pre čiernobiele strany, 200 dpi pre ostatné (šírka A4). */
    private val NORMAL = Quality(bwMaxWidth = 2480, jpegMaxWidth = 1654, jpegQuality = 76, grayQuality = 80)
    /** Zmenšené PDF: 200 dpi čiernobiele, 150 dpi ostatné, silnejšia kompresia. */
    private val COMPACT = Quality(bwMaxWidth = 1654, jpegMaxWidth = 1240, jpegQuality = 58, grayQuality = 62)
    /** Strana s takou nízkou sýtosťou (99. percentil, 0–255) sa uloží v odtieňoch šedej. */
    private const val GRAY_SATURATION = 28

    fun export(pages: List<PageItem>, target: File, ocr: (PageItem) -> OcrResult?, font: () -> InputStream, compact: Boolean = false) {
        val q = if (compact) COMPACT else NORMAL
        PDDocument().use { doc ->
            // Písmo na textovú vrstvu sa načíta len raz a vloží sa len podmnožina použitých znakov.
            val textFont: PDFont? by lazy { runCatching { font().use { PDType0Font.load(doc, it, true) } }.getOrNull() }
            for (page in pages) {
                val image = when (page.filter) {
                    PageFilter.BLACK_WHITE -> bilevel(doc, page.displayFile, q)
                    PageFilter.GRAYSCALE -> jpeg(doc, page.displayFile, gray = true, q)
                    PageFilter.ORIGINAL -> jpeg(doc, page.displayFile, gray = false, q)
                    else -> jpeg(doc, page.displayFile, gray = null, q)
                }
                val height = PAGE_WIDTH_PT * image.height / image.width
                val pdfPage = PDPage(PDRectangle(PAGE_WIDTH_PT, height))
                doc.addPage(pdfPage)
                PDPageContentStream(doc, pdfPage).use { cs ->
                    cs.drawImage(image, 0f, 0f, PAGE_WIDTH_PT, height)
                    val result = ocr(page)
                    val f = textFont
                    if (result != null && result.words.isNotEmpty() && f != null) drawTextLayer(cs, f, result, height)
                }
            }
            doc.save(target)
        }
    }

    /**
     * Neviditeľný text presne nad slovami v obraze. Slová jedného riadku tvoria jeden textový objekt
     * so spoločnou základnou čiarou a medzery sú skutočné znaky roztiahnuté cez medzeru v obraze,
     * takže vyhľadávanie aj kopírovanie z PDF vráti slová oddelené a v správnom poradí.
     */
    private fun drawTextLayer(cs: PDPageContentStream, font: PDFont, ocr: OcrResult, pageHeight: Float) {
        val scale = PAGE_WIDTH_PT / ocr.width
        val descriptor = font.fontDescriptor
        val ascent = (descriptor?.ascent ?: 900f) / 1000f
        val descent = (descriptor?.descent ?: -200f) / 1000f
        val lineHeight = (ascent - descent).coerceAtLeast(0.5f)
        val spaceUnits = runCatching { font.getStringWidth(" ") / 1000f }.getOrDefault(0.25f).coerceAtLeast(0.05f)
        for (line in OcrLines.group(ocr.words)) {
            val top = line.minOf { it.y }
            val bottom = line.maxOf { it.y + it.h }
            val size = ((bottom - top) * scale / lineHeight).coerceIn(2f, 200f)
            val baseline = pageHeight - bottom * scale - descent * size
            cs.beginText()
            try {
                cs.setRenderingMode(RenderingMode.NEITHER)
                cs.setFont(font, size)
                for ((i, word) in line.withIndex()) {
                    val textWidth = runCatching { font.getStringWidth(word.text) / 1000f * size }.getOrDefault(0f)
                    if (textWidth <= 0f) continue
                    try {
                        cs.setTextMatrix(Matrix.getTranslateInstance(word.x * scale, baseline))
                        cs.setHorizontalScaling(word.w * scale / textWidth * 100f)
                        cs.showText(word.text)
                    } catch (e: Exception) {
                        // Znak, ktorý písmo nepozná – slovo sa vynechá, ostatné ostanú vyhľadávateľné.
                        continue
                    }
                    val next = line.getOrNull(i + 1) ?: continue
                    val gapStart = (word.x + word.w) * scale
                    val gap = (next.x * scale - gapStart).coerceAtLeast(0.5f)
                    cs.setTextMatrix(Matrix.getTranslateInstance(gapStart, baseline))
                    cs.setHorizontalScaling(gap / (spaceUnits * size) * 100f)
                    cs.showText(" ")
                }
            } finally {
                cs.endText()
            }
        }
    }

    /**
     * Strana ako JPEG. [gray] = true vždy jednokanálový, false vždy farebný,
     * null podľa obsahu (strana bez farieb sa uloží v odtieňoch šedej).
     */
    private fun jpeg(doc: PDDocument, file: File, gray: Boolean?, q: Quality): PDImageXObject {
        val src = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR)
        val scaled = downscale(src, q.jpegMaxWidth)
        val asGray = gray ?: isColorless(scaled)
        val image = if (asGray) Mat().also { Imgproc.cvtColor(scaled, it, Imgproc.COLOR_BGR2GRAY) } else scaled
        val buf = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, if (asGray) q.grayQuality else q.jpegQuality)
        Imgcodecs.imencode(".jpg", image, buf, params)
        val bytes = buf.toArray()
        if (image !== scaled) image.release()
        if (scaled !== src) scaled.release()
        listOf(src, buf, params).forEach { it.release() }
        return JPEGFactory.createFromByteArray(doc, bytes)
    }

    /** Takmer žiadne farebné pixely (sýtosť 99. percentilu pod [GRAY_SATURATION]). */
    private fun isColorless(bgr: Mat): Boolean {
        val small = Mat()
        Imgproc.resize(bgr, small, Size(bgr.cols() / 4.0, bgr.rows() / 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val hsv = Mat()
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)
        val sat = Mat()
        Core.extractChannel(hsv, sat, 1)
        val colored = Mat()
        Imgproc.threshold(sat, colored, GRAY_SATURATION.toDouble(), 255.0, Imgproc.THRESH_BINARY)
        val share = Core.countNonZero(colored).toDouble() / colored.total()
        listOf(small, hsv, sat, colored).forEach { it.release() }
        return share < 0.01
    }

    /** 1-bitový obraz: bit 1 = biela, 0 = čierna (DeviceGray). */
    private fun bilevel(doc: PDDocument, file: File, q: Quality): PDImageXObject {
        val src = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_GRAYSCALE)
        val scaled = downscale(src, q.bwMaxWidth)
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
