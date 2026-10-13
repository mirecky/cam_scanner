package sk.mirecky.skener.data

import android.graphics.ImageDecoder
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.scanner.Adjust
import sk.mirecky.skener.scanner.DocumentDetector
import sk.mirecky.skener.scanner.Filters
import sk.mirecky.skener.scanner.ImageOps
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode
import java.io.File

/** Spracovanie obrazu strán: orezanie a vyrovnanie (F4), filtre (F5), otočenie. Volať mimo hlavného vlákna. */
internal object PageRenderer {

    private const val JPEG_QUALITY = 92
    /** Kratšia strana obrazu pri hľadaní dokumentu (zodpovedá analýze z kamery). */
    private const val ANALYSIS_WIDTH = 480
    /** Šírka vykreslenej strany staršieho PDF (300 dpi pre A4). */
    private const val MAX_PDF_WIDTH = 2480

    /**
     * Z pôvodnej fotografie vytvorí farebný orez a výslednú stranu s filtrom a úpravami.
     * [rotation] (násobok 90°) sa uplatní po orezaní, aby opätovný orez zachoval otočenie strany.
     */
    fun renderNew(
        original: File,
        crop: File,
        display: File,
        quad: Quad?,
        mode: ScanMode,
        filter: PageFilter,
        level: Int = 0,
        adjust: Adjust = Adjust.NONE,
        rotation: Int = 0,
    ) {
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
            val margin = if (mode == ScanMode.DOCUMENT) DOCUMENT_MARGIN else WHITEBOARD_MARGIN
            ImageOps.cropDocument(bgr, quad, snap, margin).also { bgr.release() }
        } else {
            bgr
        }
        val rotated = rotate(cropped, rotation)
        write(rotated, crop)
        renderFilter(rotated, filter, display, level, adjust)
        rotated.release()
        if (rotated !== cropped) cropped.release()
    }

    /**
     * Nájde dokument na fotografii (import z galérie). Fotografia sa zmenší na rozlíšenie
     * analýzy z kamery, na ktoré je detektor nastavený. Vráti null, ak sa dokument nenašiel.
     */
    fun detect(original: File): Quad? {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val scale = ANALYSIS_WIDTH.toDouble() / minOf(w, h)
            if (scale < 1) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
        }
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()
        val gray = Mat()
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        val detector = DocumentDetector()
        return try {
            detector.detect(gray, ScanMode.DOCUMENT)?.quad
        } finally {
            detector.release()
            gray.release()
        }
    }

    /**
     * Vykreslí stranu PDF (300 dpi, najviac šírka A4) do JPEG [target] a odhadne, aký filter
     * strana má: čiernobiela (takmer len čierne a biele pixely), odtiene šedej alebo farebná.
     */
    fun renderPdfPage(page: android.graphics.pdf.PdfRenderer.Page, target: File): PageFilter {
        val w = (page.width * 300 / 72).coerceIn(1, MAX_PDF_WIDTH)
        val h = (w.toLong() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
        val rgba = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        bitmap.recycle()
        val bgr = Mat()
        Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
        rgba.release()
        val filter = classify(bgr)
        write(bgr, target)
        bgr.release()
        return filter
    }

    private fun classify(bgr: Mat): PageFilter {
        val small = Mat()
        Imgproc.resize(bgr, small, org.opencv.core.Size(bgr.cols() / 4.0, bgr.rows() / 4.0), 0.0, 0.0, Imgproc.INTER_AREA)
        val hsv = Mat()
        Imgproc.cvtColor(small, hsv, Imgproc.COLOR_BGR2HSV)
        val sat = Mat()
        Core.extractChannel(hsv, sat, 1)
        val colored = Mat()
        Imgproc.threshold(sat, colored, 40.0, 255.0, Imgproc.THRESH_BINARY)
        val colorShare = Core.countNonZero(colored).toDouble() / colored.total()
        // Pri zmenšení vzniknú na hranách písmen sivé prechody, preto sa meria na plnom rozlíšení.
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val mid = Mat()
        Core.inRange(gray, org.opencv.core.Scalar(40.0), org.opencv.core.Scalar(215.0), mid)
        val midShare = Core.countNonZero(mid).toDouble() / mid.total()
        listOf(small, hsv, sat, colored, gray, mid).forEach { it.release() }
        return when {
            colorShare > 0.01 -> PageFilter.ORIGINAL
            midShare < 0.01 -> PageFilter.BLACK_WHITE
            else -> PageFilter.GRAYSCALE
        }
    }

    private fun rotate(src: Mat, rotation: Int): Mat {
        val code = when (((rotation % 360) + 360) % 360) {
            90 -> Core.ROTATE_90_CLOCKWISE
            180 -> Core.ROTATE_180
            270 -> Core.ROTATE_90_COUNTERCLOCKWISE
            else -> return src
        }
        return Mat().also { Core.rotate(src, it, code) }
    }

    /** Znovu vytvorí výslednú stranu z farebného orezu s iným filtrom. */
    fun applyFilter(crop: File, display: File, filter: PageFilter, level: Int, adjust: Adjust) {
        val src = Imgcodecs.imread(crop.path, Imgcodecs.IMREAD_COLOR)
        renderFilter(src, filter, display, level, adjust)
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

    private fun renderFilter(crop: Mat, filter: PageFilter, display: File, level: Int = 0, adjust: Adjust = Adjust.NONE) {
        val out = Filters.render(crop, filter, level, adjust)
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

    /**
     * Odrezanie okraja strany (podiel kratšej strany), aby na hrane neostal tmavý prúžok podkladu.
     * Dokument: 2 mm z 210 mm šírky A4 – tlačiarne aj tak netlačia bližšie ako 3–5 mm od okraja.
     */
    private const val DOCUMENT_MARGIN = 2.0 / 210.0
    private const val WHITEBOARD_MARGIN = 0.004
}
