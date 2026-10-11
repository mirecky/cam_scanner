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
    /** Podiel obvodu štvoruholníka, ktorý leží na nájdených hranách (0..1). */
    val edgeSupport: Double,
    /** Podiel plochy záberu, ktorý dokument zaberá (0..1). */
    val areaFraction: Double,
    /** Ostrosť: rozptyl Laplaceovho operátora vo vnútri dokumentu. */
    val sharpness: Double,
)

/** Hraničné hodnoty pre overenie, že nájdený štvoruholník je skutočne dokument (F2). */
data class DetectionCriteria(
    val minArea: Double,
    val maxArea: Double,
    val maxSideRatio: Double,
    val minAngle: Double,
    val maxAngle: Double,
    val minEdgeSupport: Double,
    val minSideSupport: Double,
    /** Minimálny priemerný jas vnútri (papier je svetlý); 0 = nekontroluje sa. */
    val minInsideBrightness: Double,
) {
    companion object {
        fun forMode(mode: ScanMode) = when (mode) {
            ScanMode.DOCUMENT -> DetectionCriteria(
                minArea = 0.15, maxArea = 0.97, maxSideRatio = 2.0,
                minAngle = 50.0, maxAngle = 130.0,
                minEdgeSupport = 0.70, minSideSupport = 0.45,
                minInsideBrightness = 115.0,
            )
            ScanMode.WHITEBOARD -> DetectionCriteria(
                minArea = 0.20, maxArea = 0.98, maxSideRatio = 3.0,
                minAngle = 45.0, maxAngle = 135.0,
                minEdgeSupport = 0.65, minSideSupport = 0.40,
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

    private val blurred = Mat()
    private val edges = Mat()
    private val edgesWide = Mat()
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
        // Rozšírená mapa hrán na meranie podpory hrán (tolerancia niekoľkých pixelov).
        Imgproc.dilate(edges, edgesWide, kernel5)

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
            val score = detection.areaFraction * detection.edgeSupport
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
        result ?: return null

        val aspect = w / h
        if (!result.isConvex()) return null
        val area = result.area()
        if (area < c.minArea || area > c.maxArea) return null
        if (result.sideRatio(aspect) > c.maxSideRatio) return null
        if (result.angles(aspect).any { it < c.minAngle || it > c.maxAngle }) return null
        return result
    }

    private fun evaluate(quad: Quad, gray: Mat, w: Double, h: Double, c: DetectionCriteria): Detection? {
        val corners = quad.corners
        var total = 0
        var hits = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            var sideHits = 0
            for (s in 0 until SAMPLES_PER_SIDE) {
                val t = (s + 0.5) / SAMPLES_PER_SIDE
                val x = ((a.x + (b.x - a.x) * t) * w).toInt().coerceIn(0, edgesWide.cols() - 1)
                val y = ((a.y + (b.y - a.y) * t) * h).toInt().coerceIn(0, edgesWide.rows() - 1)
                if (edgesWide.get(y, x)[0] > 0) sideHits++
            }
            if (sideHits.toDouble() / SAMPLES_PER_SIDE < c.minSideSupport) return null
            hits += sideHits
            total += SAMPLES_PER_SIDE
        }
        val support = hits.toDouble() / total
        if (support < c.minEdgeSupport) return null

        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        val poly = MatOfPoint(*corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        Imgproc.fillConvexPoly(mask, poly, Scalar(255.0))
        poly.release()
        // Zmenšenie masky, aby do merania nezasahoval okraj a pozadie.
        Imgproc.erode(mask, mask, kernel5, Point(-1.0, -1.0), 3)

        val inside = Core.mean(gray, mask).`val`[0]
        if (inside < c.minInsideBrightness) {
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

        return Detection(quad, support, quad.area(), sharpness)
    }

    fun release() {
        listOf(blurred, edges, edgesWide, binary, kernel3, kernel5).forEach { it.release() }
    }

    private companion object {
        const val CANNY_LOW = 20.0
        const val CANNY_HIGH = 60.0
        const val SAMPLES_PER_SIDE = 40
        val EPSILONS = doubleArrayOf(0.02, 0.03, 0.04, 0.06)
    }
}
