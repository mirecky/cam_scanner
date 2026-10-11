package sk.mirecky.skener.scanner

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/** Obrazový filter strany (špecifikácia F5). */
enum class PageFilter {
    /** Čiernobiely dokument: odstránenie tieňa, prahovanie, odstránenie drobných škvŕn. */
    BLACK_WHITE,
    /** Odtiene šedej s vyrovnaným (bielym) pozadím. */
    GRAYSCALE,
    /** Farebný dokument s vybieleným pozadím. */
    COLOR,
    /** Tabuľa: silnejšie potlačenie pozadia a odleskov, sýtejšie fixky. */
    WHITEBOARD,
    /** Bez úprav (len orezanie a vyrovnanie). */
    ORIGINAL;

    companion object {
        fun defaultFor(mode: ScanMode) = when (mode) {
            ScanMode.DOCUMENT -> BLACK_WHITE
            ScanMode.WHITEBOARD -> WHITEBOARD
        }
    }
}

/**
 * Implementácia filtrov nad obrazom BGR (8UC3). Základom všetkých filtrov okrem originálu je
 * odhad pozadia (papiera) vrátane tieňov a nerovnomerného osvetlenia a jeho „vydelenie“,
 * po ktorom je papier rovnomerne biely a text zachová kontrast.
 */
object Filters {

    fun apply(bgr: Mat, filter: PageFilter): Mat = when (filter) {
        PageFilter.ORIGINAL -> bgr.clone()
        PageFilter.GRAYSCALE -> grayscale(bgr)
        PageFilter.BLACK_WHITE -> blackWhite(bgr)
        PageFilter.COLOR -> color(bgr, strength = 1.0, saturation = 1.15)
        PageFilter.WHITEBOARD -> color(bgr, strength = 1.0, saturation = 1.6)
    }

    /** Šedý obraz s odstráneným tieňom (pozadie ≈ 255). */
    private fun normalizedGray(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val background = estimateBackground(gray)
        val out = Mat()
        Core.divide(gray, background, out, 255.0)
        gray.release()
        background.release()
        return out
    }

    /**
     * Odhad pozadia: zmenšenie, morfologické uzavretie (odstráni tmavý text a čiary),
     * mediánové rozmazanie a zväčšenie späť. Výsledok obsahuje tiene a prechody osvetlenia,
     * nie však obsah dokumentu.
     */
    private fun estimateBackground(gray: Mat): Mat {
        val scale = WORK_SIZE / max(gray.cols(), gray.rows()).toDouble()
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(CLOSE_KERNEL, CLOSE_KERNEL))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.medianBlur(small, small, MEDIAN_KERNEL)
        val background = Mat()
        Imgproc.resize(small, background, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        // Ochrana pred delením nulou na úplne tmavých miestach.
        Core.max(background, Scalar(1.0), background)
        small.release()
        kernel.release()
        return background
    }

    private fun grayscale(bgr: Mat): Mat {
        val norm = normalizedGray(bgr)
        stretch(norm, lowPercent = 1.0, highValue = 245.0)
        val out = Mat()
        Imgproc.cvtColor(norm, out, Imgproc.COLOR_GRAY2BGR)
        norm.release()
        return out
    }

    private fun blackWhite(bgr: Mat): Mat {
        val norm = normalizedGray(bgr)
        // Po odstránení tieňa je papier ≈ 255; text je výrazne tmavší. Adaptívny prah
        // si poradí aj so zvyškovými prechodmi, pevná časť (C) potláča šum papiera.
        val block = (max(norm.cols(), norm.rows()) / 40).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15)
        val bw = Mat()
        Imgproc.adaptiveThreshold(norm, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, 18.0)
        // Veľmi svetlé miesta sú vždy papier (zabraňuje zrnitosti v prázdnych plochách).
        val paper = Mat()
        Imgproc.threshold(norm, paper, 225.0, 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(bw, paper, bw)
        removeSpecks(bw, maxArea = (norm.cols() * norm.rows() * 1e-5).roundToInt().coerceAtLeast(6))
        norm.release()
        paper.release()
        val out = Mat()
        Imgproc.cvtColor(bw, out, Imgproc.COLOR_GRAY2BGR)
        bw.release()
        return out
    }

    /**
     * Odstráni drobné čierne bodky (prach, šum, škvrny) do [maxArea] pixelov, ktoré sú osamotené,
     * t. j. v ich blízkosti nie je väčší objekt (písmeno, čiara). Diakritika (čiarky, mäkčene,
     * bodky nad i) leží vždy pri písmene, preto sa zachová.
     */
    private fun removeSpecks(bw: Mat, maxArea: Int) {
        val inv = Mat()
        Core.bitwise_not(bw, inv)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S)
        val isSmall = BooleanArray(n)
        var anySmall = false
        for (i in 1 until n) {
            if (stats.get(i, Imgproc.CC_STAT_AREA)[0] <= maxArea) {
                isSmall[i] = true
                anySmall = true
            }
        }
        if (anySmall) {
            val cols = labels.cols()
            val row = IntArray(cols)
            val bigRow = ByteArray(cols)
            val smallRow = ByteArray(cols)
            val big = Mat.zeros(labels.size(), CvType.CV_8UC1)
            val small = Mat.zeros(labels.size(), CvType.CV_8UC1)
            for (y in 0 until labels.rows()) {
                labels.get(y, 0, row)
                for (x in 0 until cols) {
                    val l = row[x]
                    bigRow[x] = if (l != 0 && !isSmall[l]) 255.toByte() else 0
                    smallRow[x] = if (l != 0 && isSmall[l]) 255.toByte() else 0
                }
                big.put(y, 0, bigRow)
                small.put(y, 0, smallRow)
            }
            // Okolie väčších objektov; malé bodky v ňom sú diakritika alebo interpunkcia.
            val radius = (max(bw.cols(), bw.rows()) / 100.0).coerceAtLeast(4.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(radius * 2 + 1, radius * 2 + 1))
            Imgproc.dilate(big, big, kernel)
            val isolated = Mat()
            Core.subtract(small, big, isolated)
            // Bodka je osamotená, len ak z nej v okolí textu neleží nič; čiastočný presah ju ponechá.
            for (i in 1 until n) if (isSmall[i]) {
                val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
                val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
                val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
                val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
                val area = stats.get(i, Imgproc.CC_STAT_AREA)[0]
                val roi = isolated.submat(y, y + h, x, x + w)
                if (Core.countNonZero(roi) >= area) bw.submat(y, y + h, x, x + w).setTo(Scalar(255.0), small.submat(y, y + h, x, x + w))
                roi.release()
            }
            listOf(big, small, isolated, kernel).forEach { it.release() }
        }
        listOf(inv, labels, stats, centroids).forEach { it.release() }
    }

    private fun color(bgr: Mat, strength: Double, saturation: Double): Mat {
        // Každý kanál sa vydelí vlastným odhadom pozadia: papier je potom neutrálne biely
        // aj pri farebnom (teplom) osvetlení a tiene zmiznú; farby obsahu sa zachovajú.
        val channels = ArrayList<Mat>()
        Core.split(bgr, channels)
        for (c in channels) {
            val background = estimateBackground(c)
            Core.divide(c, background, c, 255.0 * strength)
            background.release()
        }
        val balanced = Mat()
        Core.merge(channels, balanced)
        channels.forEach { it.release() }

        val hsv = Mat()
        Imgproc.cvtColor(balanced, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        hsvCh[1].convertTo(hsvCh[1], -1, saturation, 0.0)
        Core.merge(hsvCh, hsv)
        hsvCh.forEach { it.release() }
        val out = Mat()
        Imgproc.cvtColor(hsv, out, Imgproc.COLOR_HSV2BGR)
        hsv.release()
        balanced.release()
        return out
    }

    /** Lineárne roztiahne jas: [lowPercent] % najtmavších pixelov → 0, hodnota [highValue] a viac → 255. */
    private fun stretch(gray: Mat, lowPercent: Double, highValue: Double) {
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), org.opencv.core.MatOfInt(0), Mat(), hist, org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = gray.total().toDouble()
        var acc = 0.0
        var low = 0
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc / total * 100 >= lowPercent) { low = i; break }
        }
        hist.release()
        val range = (highValue - low).coerceAtLeast(1.0)
        gray.convertTo(gray, -1, 255.0 / range, -low * 255.0 / range)
    }

    /** Dlhšia strana obrazu pri odhade pozadia. */
    private const val WORK_SIZE = 600.0
    private const val CLOSE_KERNEL = 15.0
    private const val MEDIAN_KERNEL = 21
}
