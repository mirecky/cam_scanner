package sk.mirecky.skener.scanner

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/** Výsledok detekcie v jednom snímku analýzy. */
data class Detection(
    val quad: Quad,
    /** Najmenší kontrast (rozdiel jasu vnútri a vonku) spomedzi štyroch strán. */
    val contrast: Double,
    /** Podiel plochy záberu, ktorý dokument zaberá (0..1). */
    val areaFraction: Double,
    /** Ostrosť: rozptyl Laplaceovho operátora vo vnútri dokumentu. */
    val sharpness: Double,
)

/** Hraničné hodnoty pre overenie, že nájdený štvoruholník je skutočne dokument (F2). */
data class DetectionCriteria(
    val minArea: Double,
    val maxArea: Double,
    val minSideRatio: Double,
    val maxSideRatio: Double,
    /**
     * Prijímať len dokumenty orientované na výšku. List A4 skenovaný telefónom na výšku leží
     * takmer vždy na výšku; obrazovky, televízory a obrazy bývajú na šírku (ochrana pred falošnými nálezmi).
     */
    val portraitOnly: Boolean,
    val minAngle: Double,
    val maxAngle: Double,
    /** Minimálny rozdiel jasu vnútri a vonku pozdĺž každej strany (medián vzoriek). */
    val minSideContrast: Double,
    /** Či musí byť vnútro svetlejšie než okolie (papier); pri tabuli stačí absolútny rozdiel. */
    val insideBrighter: Boolean,
    /** Uprednostniť kandidátov s pomerom strán A4. */
    val preferA4: Boolean,
    /** Minimálny priemerný jas vnútri (papier je svetlý); 0 = nekontroluje sa. */
    val minInsideBrightness: Double,
) {
    companion object {
        fun forMode(mode: ScanMode) = when (mode) {
            ScanMode.DOCUMENT -> DetectionCriteria(
                minArea = 0.15, maxArea = 0.97, minSideRatio = 1.15, maxSideRatio = 1.75, portraitOnly = true,
                minAngle = 50.0, maxAngle = 130.0,
                minSideContrast = 12.0, insideBrighter = true, preferA4 = true,
                minInsideBrightness = 115.0,
            )
            ScanMode.WHITEBOARD -> DetectionCriteria(
                minArea = 0.20, maxArea = 0.98, minSideRatio = 1.0, maxSideRatio = 3.0, portraitOnly = false,
                minAngle = 45.0, maxAngle = 135.0,
                minSideContrast = 10.0, insideBrighter = false, preferA4 = false,
                minInsideBrightness = 80.0,
            )
        }
    }
}

/**
 * Hľadá dokument v sivotónovom, vzpriamenom obraze nízkeho rozlíšenia (cca 480 × 640).
 *
 * Postup: rozmazanie → Cannyho hrany a Otsuovo prahovanie (dva nezávislé zdroje kandidátov) →
 * kontúry → konvexný obal → aproximácia štvoruholníkom → overenie podľa [DetectionCriteria].
 * Z platných kandidátov sa vyberie ten s najväčšou plochou váženou podporou hrán.
 *
 * Trieda nie je vláknovo bezpečná (opakovane používa pracovné matice).
 */
class DocumentDetector {

    /** Voliteľný záznam dôvodov zamietnutia kandidátov (na ladenie a testy). */
    var log: ((String) -> Unit)? = null

    private val blurred = Mat()
    private val edges = Mat()
    private val binary = Mat()
    private val kernel3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
    private val kernel5 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))

    fun detect(gray: Mat, mode: ScanMode): Detection? {
        val criteria = DetectionCriteria.forMode(mode)
        Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
        // Citlivejší druhý prechod (nižšie prahy Cannyho detektora) v testoch zvyšoval počet falošných
        // nálezov viac, než pomáhal pri bielom papieri na bielom stole; ten prípad rieši až model (F1).
        return pass(gray, criteria, CANNY_LOW, CANNY_HIGH, useOtsu = true)
    }

    private fun pass(gray: Mat, criteria: DetectionCriteria, low: Double, high: Double, useOtsu: Boolean): Detection? {
        val w = gray.cols().toDouble()
        val h = gray.rows().toDouble()

        Imgproc.Canny(blurred, edges, low, high)
        Imgproc.dilate(edges, edges, kernel3)

        val candidates = ArrayList<MatOfPoint>()
        candidates += contours(edges)
        if (useOtsu) {
            Imgproc.threshold(blurred, binary, 0.0, 255.0, Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU)
            Imgproc.morphologyEx(binary, binary, Imgproc.MORPH_CLOSE, kernel5)
            candidates += contours(binary)
        }

        var best: Detection? = null
        var bestScore = 0.0
        for (contour in candidates) {
            val quad = toQuad(contour, w, h, criteria) ?: continue
            val detection = evaluate(quad, gray, w, h, criteria) ?: continue
            // Pri dokumente majú prednosť tvary s pomerom strán blízkym A4.
            val ratioPenalty = if (criteria.preferA4) {
                1.0 - minOf(kotlin.math.abs(quad.sideRatio(w / h) - ImageOps.A4_RATIO) / ImageOps.A4_RATIO, 0.5)
            } else {
                1.0
            }
            val score = detection.areaFraction * minOf(detection.contrast, 60.0) * ratioPenalty
            if (score > bestScore) {
                bestScore = score
                best = detection
            }
        }
        candidates.forEach { it.release() }
        return best
    }

    private fun contours(src: Mat): List<MatOfPoint> {
        val list = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(src, list, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        hierarchy.release()
        val minArea = src.rows() * src.cols() * 0.10
        val (keep, drop) = list.partition { Imgproc.contourArea(it) >= minArea }
        drop.forEach { it.release() }
        return keep
    }

    /** Aproximuje kontúru štvoruholníkom; vráti null, ak to nejde alebo tvar nespĺňa geometrické kritériá. */
    private fun toQuad(contour: MatOfPoint, w: Double, h: Double, c: DetectionCriteria): Quad? {
        val hullIdx = MatOfInt()
        Imgproc.convexHull(contour, hullIdx)
        val pts = contour.toArray()
        val hull = MatOfPoint2f(*hullIdx.toArray().map { pts[it] }.toTypedArray())
        hullIdx.release()
        val peri = Imgproc.arcLength(hull, true)
        var result: Quad? = null
        for (eps in EPSILONS) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull, approx, eps * peri, true)
            val p = approx.toArray()
            approx.release()
            if (p.size == 4) {
                result = Quad.fromUnordered(p.map { Pt(it.x / w, it.y / h) })
                break
            }
            if (p.size < 4) break
        }
        hull.release()
        if (result == null) {
            log?.invoke("kontúra sa nedá aproximovať štvoruholníkom")
            return null
        }

        val aspect = w / h
        val area = result.area()
        val reason = when {
            !result.isConvex() -> "nekonvexný"
            area < c.minArea || area > c.maxArea -> "plocha %.2f".format(area)
            result.sideRatio(aspect) !in c.minSideRatio..c.maxSideRatio -> "pomer strán %.2f".format(result.sideRatio(aspect))
            c.portraitOnly && !result.isPortrait(aspect) -> "na šírku"
            result.angles(aspect).any { it < c.minAngle || it > c.maxAngle } -> "uhly ${result.angles(aspect).map { it.toInt() }}"
            else -> null
        }
        if (reason != null) {
            log?.invoke("zamietnutý $result: $reason")
            return null
        }
        return result
    }

    private fun evaluate(quad: Quad, gray: Mat, w: Double, h: Double, c: DetectionCriteria): Detection? {
        val contrast = minSideContrast(quad, w, h, c.insideBrighter)
        if (contrast < c.minSideContrast) {
            log?.invoke("zamietnutý $quad: kontrast strany %.1f".format(contrast))
            return null
        }

        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val poly = MatOfPoint(*quad.corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
        poly.release()
        // Zmenšenie masky, aby do merania nezasahoval okraj a pozadie.
        Imgproc.erode(mask, mask, kernel5, Point(-1.0, -1.0), 3)

        val inside = Core.mean(gray, mask).`val`[0]
        if (inside < c.minInsideBrightness) {
            log?.invoke("zamietnutý $quad: jas vnútri %.0f".format(inside))
            mask.release()
            return null
        }

        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = org.opencv.core.MatOfDouble()
        val std = org.opencv.core.MatOfDouble()
        Core.meanStdDev(lap, mean, std, mask)
        val sharpness = std.toArray()[0].let { it * it }
        lap.release(); mean.release(); std.release(); mask.release()

        return Detection(quad, contrast, quad.area(), sharpness)
    }

    /**
     * Pre každú stranu porovná jas tesne vnútri a tesne vonku (vzorky po normále strany)
     * a vráti najmenší z mediánov rozdielov. Skutočný okraj papiera má vnútri svetlejšie
     * než vonku po celej dĺžke; vzor látky ani čiara vo vnútri listu túto podmienku nesplnia.
     */
    private fun minSideContrast(quad: Quad, w: Double, h: Double, insideBrighter: Boolean): Double {
        val c = quad.corners.map { Pair(it.x * w, it.y * h) }
        val cx = c.sumOf { it.first } / 4
        val cy = c.sumOf { it.second } / 4
        val maxX = blurred.cols() - 1
        val maxY = blurred.rows() - 1
        var worst = Double.MAX_VALUE
        val diffs = DoubleArray(SAMPLES_PER_SIDE)
        for (i in 0 until 4) {
            val (ax, ay) = c[i]
            val (bx, by) = c[(i + 1) % 4]
            val len = kotlin.math.hypot(bx - ax, by - ay).coerceAtLeast(1e-6)
            var nx = -(by - ay) / len
            var ny = (bx - ax) / len
            // Normála smerom von (od ťažiska).
            val mx = (ax + bx) / 2
            val my = (ay + by) / 2
            if (nx * (mx - cx) + ny * (my - cy) < 0) { nx = -nx; ny = -ny }
            var n = 0
            for (s in 0 until SAMPLES_PER_SIDE) {
                // Vzorky z vnútornej časti strany (bez rohov, kde sa okraje pretínajú).
                val t = 0.1 + 0.8 * (s + 0.5) / SAMPLES_PER_SIDE
                val px = ax + (bx - ax) * t
                val py = ay + (by - ay) * t
                val ix = (px - nx * CONTRAST_OFFSET).toInt()
                val iy = (py - ny * CONTRAST_OFFSET).toInt()
                val ox = (px + nx * CONTRAST_OFFSET).toInt()
                val oy = (py + ny * CONTRAST_OFFSET).toInt()
                if (ox !in 0..maxX || oy !in 0..maxY || ix !in 0..maxX || iy !in 0..maxY) continue
                val d = blurred.get(iy, ix)[0] - blurred.get(oy, ox)[0]
                diffs[n++] = if (insideBrighter) d else kotlin.math.abs(d)
            }
            // Strana pri okraji záberu (dokument presahuje záber) nemá vonkajšie vzorky.
            if (n < SAMPLES_PER_SIDE / 2) return 0.0
            val sorted = diffs.copyOf(n).also { it.sort() }
            worst = minOf(worst, sorted[n / 2])
        }
        return worst
    }

    fun release() {
        listOf(blurred, edges, binary, kernel3, kernel5).forEach { it.release() }
    }

    private companion object {
        const val CANNY_LOW = 20.0
        const val CANNY_HIGH = 60.0
        const val SAMPLES_PER_SIDE = 40
        /** Vzdialenosť vzoriek od strany v pixeloch analyzovaného obrazu. */
        const val CONTRAST_OFFSET = 6.0
        val EPSILONS = doubleArrayOf(0.02, 0.03, 0.04, 0.06)
    }
}
