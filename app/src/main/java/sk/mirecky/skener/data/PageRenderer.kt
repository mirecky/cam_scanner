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
    /** Názov súboru masky vyčistených miest v priečinku strany. */
    const val CLEAN_FILE = "clean.png"
    /** Šírka obrazu pri dopočítaní vyčistených miest originálu. */
    private const val INPAINT_WIDTH = 1200.0
    /** Dlhšia strana obrazu pri hľadaní dokumentu (zodpovedá analýze z kamery). */
    private const val ANALYSIS_LONG_SIDE = 640
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

        // Nový orez: staré vyčistené miesta by už nesedeli.
        File(display.parentFile, CLEAN_FILE).delete()
        val cropped = if (quad != null) {
            val snap = if (mode == ScanMode.DOCUMENT) ImageOps.A4_RATIO else null
            val margin = if (mode == ScanMode.DOCUMENT) DOCUMENT_MARGIN else WHITEBOARD_MARGIN
            ImageOps.cropDocument(bgr, quad, snap, margin).also {
                bgr.release()
                // Zahnutý roh listu (kramlička): tmavý podklad v rohu sa nahradí papierom.
                if (mode == ScanMode.DOCUMENT) ImageOps.fillDarkCorners(it)
            }
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
    fun detect(original: File, mode: ScanMode = ScanMode.DOCUMENT): Quad? {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(original)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            // Rovnaké rozlíšenie ako živá analýza z kamery (dlhšia strana 640 px).
            val scale = ANALYSIS_LONG_SIDE.toDouble() / maxOf(w, h)
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
            detector.detect(gray, mode)?.quad
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
        loadMask(File(display.parentFile, CLEAN_FILE))?.let { mask ->
            applyClean(out, mask, filter)
            mask.release()
        }
        write(out, display)
        out.release()
    }

    /** Načíta masku vyčistených miest, ak existuje. */
    fun loadMask(file: File): Mat? {
        if (!file.exists()) return null
        val m = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_GRAYSCALE)
        return if (m.empty()) { m.release(); null } else m
    }

    /**
     * Vyčistí miesta podľa [mask] (prispôsobí sa veľkosti obrazu): pri filtroch s vybieleným
     * papierom ich vyplní bielou, pri origináli ich dopočíta z okolia (inpainting).
     */
    fun applyClean(image: Mat, mask: Mat, filter: PageFilter) {
        val m = if (mask.size() == image.size()) mask else Mat().also {
            Imgproc.resize(mask, it, image.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        }
        if (filter == PageFilter.ORIGINAL) {
            // Inpainting na zmenšenom obraze je rýchly; výsledok sa vloží len do vyčistených miest.
            val scale = minOf(1.0, INPAINT_WIDTH / image.cols())
            val small = Mat()
            val smallMask = Mat()
            Imgproc.resize(image, small, org.opencv.core.Size(), scale, scale, Imgproc.INTER_AREA)
            Imgproc.resize(m, smallMask, small.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            val filled = Mat()
            org.opencv.photo.Photo.inpaint(small, smallMask, filled, 7.0, org.opencv.photo.Photo.INPAINT_TELEA)
            val big = Mat()
            Imgproc.resize(filled, big, image.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            big.copyTo(image, m)
            listOf(small, smallMask, filled, big).forEach { it.release() }
        } else {
            image.setTo(org.opencv.core.Scalar.all(255.0), m)
        }
        if (m !== mask) m.release()
    }

    /**
     * Dokreslí ťahy štetca do masky vyčistených miest strany. Súradnice ťahov sú pomerné (0–1)
     * k orezu strany, [radius] je polomer štetca ako podiel šírky strany.
     */
    fun paintMask(crop: File, maskFile: File, strokes: List<List<Pair<Double, Double>>>, radius: Double) {
        val ref = Imgcodecs.imread(crop.path, Imgcodecs.IMREAD_GRAYSCALE)
        val w = ref.cols()
        val h = ref.rows()
        ref.release()
        val mask = loadMask(maskFile)?.takeIf { it.cols() == w && it.rows() == h } ?: Mat.zeros(h, w, org.opencv.core.CvType.CV_8UC1)
        val thickness = (radius * 2 * w).toInt().coerceAtLeast(2)
        val white = org.opencv.core.Scalar(255.0)
        for (stroke in strokes) {
            val pts = stroke.map { (x, y) -> org.opencv.core.Point(x * w, y * h) }
            if (pts.size == 1) Imgproc.circle(mask, pts[0], thickness / 2, white, -1)
            for (i in 1 until pts.size) Imgproc.line(mask, pts[i - 1], pts[i], white, thickness)
            pts.forEach { Imgproc.circle(mask, it, thickness / 2, white, -1) }
        }
        write(mask, maskFile)
        mask.release()
    }

    private fun write(mat: Mat, target: File) {
        // Prípona dočasného súboru určuje formát (maska je bezstratové PNG, strany JPEG).
        val tmp = File(target.parentFile, target.name + ".tmp." + target.extension)
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
