package sk.mirecky.skener.scanner

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/** Ručné úpravy strany po filtri (−50…+50, 0 = bez úpravy). */
data class Adjust(val brightness: Int = 0, val contrast: Int = 0, val sharpness: Int = 0) {
    val isNeutral: Boolean get() = brightness == 0 && contrast == 0 && sharpness == 0

    companion object {
        val RANGE = -50..50
        val NONE = Adjust()
    }
}

/** Obrazový filter strany (špecifikácia F5). */
enum class PageFilter {
    /** Čiernobiely dokument: odstránenie tieňa, prahovanie, odstránenie drobných škvŕn. */
    BLACK_WHITE,
    /** Odtiene šedej s vyrovnaným (bielym) pozadím. */
    GRAYSCALE,
    /** Farebný dokument: biely papier bez tieňa, výraznejší kontrast a sýtejšie farby. */
    COLOR,
    /** Jemný farebný: len odstránenie tieňa a vyváženie bielej, farby a tóny ostanú prirodzené. */
    NATURAL,
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

    /** Rozsah parametra filtra (−/+) nastaviteľného používateľom. */
    val LEVELS = -5..5

    /**
     * Použije [filter] s parametrom [level] (−5…+5, 0 = predvolené):
     * čiernobiely – sýtosť písma, šedý – kontrast, farebné filtre a tabuľa – sýtosť farieb, originál – jas.
     */
    fun apply(bgr: Mat, filter: PageFilter, level: Int = 0): Mat {
        val l = level.coerceIn(LEVELS)
        return when (filter) {
            PageFilter.ORIGINAL -> Mat().also { bgr.convertTo(it, -1, 1.0, l * 8.0) }
            PageFilter.GRAYSCALE -> grayscale(bgr, l)
            PageFilter.BLACK_WHITE -> blackWhite(bgr, l)
            PageFilter.COLOR -> color(bgr, lowPercent = 0.6, white = 238.0, saturation = 1.3 + 0.12 * l)
            PageFilter.NATURAL -> color(bgr, lowPercent = 0.0, white = 250.0, saturation = 1.0 + 0.1 * l)
            PageFilter.WHITEBOARD -> color(bgr, lowPercent = 0.3, white = 225.0, saturation = 1.7 + 0.15 * l)
        }
    }

    /** Filter a následne ručné úpravy (jas, kontrast, ostrosť). */
    fun render(bgr: Mat, filter: PageFilter, level: Int, adjust: Adjust): Mat {
        val out = apply(bgr, filter, level)
        if (adjust.isNeutral) return out
        return adjust(out, adjust).also { out.release() }
    }

    /**
     * Jas posúva hodnoty, kontrast ich rozťahuje okolo strednej šedej, ostrosť je
     * maska neostrosti (kladná) alebo jemné rozmazanie (záporná).
     */
    fun adjust(src: Mat, a: Adjust): Mat {
        val alpha = 1.0 + a.contrast.coerceIn(Adjust.RANGE) / 100.0
        val beta = 128.0 * (1 - alpha) + a.brightness.coerceIn(Adjust.RANGE) * 1.6
        val out = Mat()
        src.convertTo(out, -1, alpha, beta)
        val s = a.sharpness.coerceIn(Adjust.RANGE)
        if (s != 0) {
            val sigma = max(out.cols(), out.rows()) / 1500.0 + 0.8
            val blur = Mat()
            Imgproc.GaussianBlur(out, blur, Size(0.0, 0.0), sigma)
            if (s > 0) {
                val k = s / 50.0 * 1.5
                Core.addWeighted(out, 1 + k, blur, -k, 0.0, out)
            } else {
                val k = -s / 50.0
                Core.addWeighted(out, 1 - k, blur, k, 0.0, out)
            }
            blur.release()
        }
        return out
    }

    /**
     * Vstup pre OCR: šedý obraz s odstráneným tieňom a nerovnomerným osvetlením.
     * Tesseract si ho sám binarizuje; vyrovnané pozadie mu výrazne znižuje chybovosť.
     */
    fun ocrInput(bgr: Mat): Mat {
        val norm = normalizedGray(bgr)
        stretch(norm, lowPercent = 0.5, highValue = 250.0)
        return norm
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

    private fun grayscale(bgr: Mat, level: Int): Mat {
        val norm = normalizedGray(bgr)
        // Vyšší kontrast: viac tmavých pixelov do čiernej, svetlé tóny skôr do bielej.
        stretch(norm, lowPercent = (1.0 + 0.6 * level).coerceIn(0.0, 6.0), highValue = 245.0 - 5.0 * level)
        val out = Mat()
        Imgproc.cvtColor(norm, out, Imgproc.COLOR_GRAY2BGR)
        norm.release()
        return out
    }

    private fun blackWhite(bgr: Mat, level: Int): Mat {
        val norm = normalizedGray(bgr)
        // Po odstránení tieňa je papier ≈ 255; text je výrazne tmavší. Adaptívny prah
        // si poradí aj so zvyškovými prechodmi, pevná časť (C) potláča šum papiera.
        val block = (max(norm.cols(), norm.rows()) / 40).let { if (it % 2 == 0) it + 1 else it }.coerceAtLeast(15)
        val bw = Mat()
        // Vyššia úroveň = menšia odchýlka od okolia stačí na čiernu (sýtejšie, hrubšie písmo).
        val offset = (18.0 - 3.0 * level).coerceAtLeast(2.0)
        Imgproc.adaptiveThreshold(norm, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, block, offset)
        // Veľmi svetlé miesta sú vždy papier (zabraňuje zrnitosti v prázdnych plochách).
        val paper = Mat()
        Imgproc.threshold(norm, paper, (225.0 + 3.0 * level).coerceIn(200.0, 250.0), 255.0, Imgproc.THRESH_BINARY)
        Core.bitwise_or(bw, paper, bw)
        removeSpecks(bw, maxArea = (norm.cols() * norm.rows() * 1e-5).roundToInt().coerceAtLeast(6))
        clearEdges(bw)
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

    /**
     * Farebné filtre. Osvetlenie (tieň, prechod jasu) sa odhadne z jasu a vydelí rovnako vo všetkých
     * kanáloch, takže odtieň farieb ostane zachovaný; farebný nádych svetla sa odstráni vyvážením
     * bielej podľa farby papiera. Potom sa nastaví čierny a biely bod a sýtosť.
     */
    private fun color(bgr: Mat, lowPercent: Double, white: Double, saturation: Double): Mat {
        val balanced = balancedColor(bgr)
        // Úrovne podľa jasu, rovnaké pre všetky kanály (nemenia odtieň).
        val gray = Mat()
        Imgproc.cvtColor(balanced, gray, Imgproc.COLOR_BGR2GRAY)
        val low = if (lowPercent > 0) percentile(gray, lowPercent).coerceAtMost(white - 60) else 0.0
        gray.release()
        val range = (white - low).coerceAtLeast(1.0)
        balanced.convertTo(balanced, -1, 255.0 / range, -low * 255.0 / range)

        val sat = saturation.coerceIn(0.0, 3.0)
        if (kotlin.math.abs(sat - 1.0) < 1e-3) return balanced
        val hsv = Mat()
        Imgproc.cvtColor(balanced, hsv, Imgproc.COLOR_BGR2HSV)
        val hsvCh = ArrayList<Mat>()
        Core.split(hsv, hsvCh)
        hsvCh[1].convertTo(hsvCh[1], -1, sat, 0.0)
        Core.merge(hsvCh, hsv)
        hsvCh.forEach { it.release() }
        Imgproc.cvtColor(hsv, balanced, Imgproc.COLOR_HSV2BGR)
        hsv.release()
        return balanced
    }

    /** Farebný obraz bez tieňa a farebného nádychu: papier ≈ neutrálna biela (255). */
    private fun balancedColor(bgr: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val background = paperBackground(gray)

        // Farba papiera = priemer pixelov blízkych pozadiu; z nej zosilnenie kanálov na neutrálnu bielu.
        val norm = Mat()
        Core.divide(gray, background, norm, 255.0)
        val paperMask = Mat()
        Imgproc.threshold(norm, paperMask, 225.0, 255.0, Imgproc.THRESH_BINARY)
        val gains = if (Core.countNonZero(paperMask) > paperMask.total() / 50) {
            val c = Core.mean(bgr, paperMask).`val`
            val g = Core.mean(gray, paperMask).`val`[0]
            DoubleArray(3) { i -> (g / c[i].coerceAtLeast(1.0)).coerceIn(0.6, 1.6) }
        } else {
            doubleArrayOf(1.0, 1.0, 1.0)
        }

        val f = Mat()
        bgr.convertTo(f, CvType.CV_32FC3)
        val bgF = Mat()
        background.convertTo(bgF, CvType.CV_32F)
        val bg3 = Mat()
        Core.merge(listOf(bgF, bgF, bgF), bg3)
        Core.divide(f, bg3, f, 255.0)
        Core.multiply(f, Scalar(gains[0], gains[1], gains[2]), f)
        val out = Mat()
        f.convertTo(out, CvType.CV_8UC3)
        listOf(gray, background, norm, paperMask, f, bgF, bg3).forEach { it.release() }
        return out
    }

    /**
     * Odhad osvetlenia papiera pre farebné filtre. Na rozdiel od [estimateBackground] nepovažuje
     * veľké tmavé plochy (fotografie, farebné bloky) za tieň: miesta výrazne tmavšie ako papier
     * sa vyplnia osvetlením z okolitého papiera (normalizované rozmazanie s maskou).
     */
    private fun paperBackground(gray: Mat): Mat {
        val scale = WORK_SIZE / max(gray.cols(), gray.rows()).toDouble()
        val small = Mat()
        Imgproc.resize(gray, small, Size(), scale, scale, Imgproc.INTER_AREA)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(CLOSE_KERNEL, CLOSE_KERNEL))
        Imgproc.morphologyEx(small, small, Imgproc.MORPH_CLOSE, kernel)
        Imgproc.medianBlur(small, small, MEDIAN_KERNEL)
        val paperLevel = percentile(small, 90.0)

        val mask = Mat()
        Imgproc.threshold(small, mask, paperLevel * PAPER_FRACTION, 1.0, Imgproc.THRESH_BINARY)
        val maskF = Mat()
        mask.convertTo(maskF, CvType.CV_32F)
        val smallF = Mat()
        small.convertTo(smallF, CvType.CV_32F)
        val num = Mat()
        Core.multiply(smallF, maskF, num)
        val den = Mat()
        val sigma = WORK_SIZE / 12
        Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sigma)
        Imgproc.GaussianBlur(maskF, den, Size(0.0, 0.0), sigma)
        Core.max(den, Scalar(1e-3), den)
        val filled = Mat()
        Core.divide(num, den, filled)
        // Kde v okolí nie je papier vôbec, použije sa jas papiera.
        val noPaper = Mat()
        Core.compare(den, Scalar(0.02), noPaper, Core.CMP_LT)
        filled.setTo(Scalar(paperLevel), noPaper)
        // Papier si ponechá vlastný (presný) odhad, ostatné miesta vyplnený.
        smallF.copyTo(filled, mask)
        Core.max(filled, Scalar(paperLevel * PAPER_FRACTION), filled)
        Imgproc.GaussianBlur(filled, filled, Size(0.0, 0.0), 3.0)
        val out8 = Mat()
        filled.convertTo(out8, CvType.CV_8U)
        val background = Mat()
        Imgproc.resize(out8, background, gray.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.max(background, Scalar(1.0), background)
        listOf(small, kernel, mask, maskF, smallF, num, den, filled, noPaper, out8).forEach { it.release() }
        return background
    }

    /** Hodnota jasu, pod ktorou leží [percent] % pixelov (8-bitový jednokanálový obraz). */
    private fun percentile(gray: Mat, percent: Double): Double {
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), org.opencv.core.MatOfInt(0), Mat(), hist, org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        val total = gray.total().toDouble()
        var acc = 0.0
        var result = 255
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc / total * 100 >= percent) { result = i; break }
        }
        hist.release()
        return result.toDouble()
    }

    /**
     * Odstráni čierne útvary pri okraji strany, ktoré ležia celé v úzkom páse pri hrane
     * (zvyšok podkladu, tieň hrany papiera). Text a čiary zasahujúce ďalej do strany ostanú.
     */
    private fun clearEdges(bw: Mat) {
        val band = (minOf(bw.cols(), bw.rows()) * EDGE_BAND).roundToInt().coerceAtLeast(2)
        val inv = Mat()
        Core.bitwise_not(bw, inv)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val n = Imgproc.connectedComponentsWithStats(inv, labels, stats, centroids, 8, CvType.CV_32S)
        val w = bw.cols()
        val h = bw.rows()
        val mask = Mat()
        for (i in 1 until n) {
            val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val cw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val ch = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val edge = (x == 0 && x + cw <= band) || (x + cw == w && x >= w - band) ||
                (y == 0 && y + ch <= band) || (y + ch == h && y >= h - band)
            if (!edge) continue
            val roiLabels = labels.submat(y, y + ch, x, x + cw)
            Core.compare(roiLabels, Scalar(i.toDouble()), mask, Core.CMP_EQ)
            bw.submat(y, y + ch, x, x + cw).setTo(Scalar(255.0), mask)
            roiLabels.release()
        }
        listOf(inv, labels, stats, centroids, mask).forEach { it.release() }
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
    /** Miesta tmavšie ako tento podiel jasu papiera nie sú tieň, ale obsah (farebné filtre). */
    private const val PAPER_FRACTION = 0.6
    /** Šírka pásu pri hrane strany (podiel kratšej strany), v ktorom sa odstraňujú zvyšky podkladu. */
    private const val EDGE_BAND = 0.015
}
