package sk.mirecky.skener.scanner

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Meria pohyb v zábere ako priemerný rozdiel jasu dvoch po sebe idúcich zmenšených snímok. */
class MotionMeter {
    private var previous: Mat? = null
    private val small = Mat()

    fun measure(gray: Mat): Double {
        Imgproc.resize(gray, small, Size(SIZE_W, SIZE_H), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        val prev = previous
        val result = if (prev == null) {
            0.0
        } else {
            val diff = Mat()
            Core.absdiff(small, prev, diff)
            Core.mean(diff).`val`[0].also { diff.release() }
        }
        if (prev == null) previous = small.clone() else small.copyTo(prev)
        return result
    }

    fun release() {
        previous?.release()
        small.release()
    }

    private companion object {
        const val SIZE_W = 60.0
        const val SIZE_H = 80.0
    }
}

object ImageOps {

    /**
     * Odtlačok obsahu dokumentu: vyrovnaný a zmenšený obraz s normalizovaným jasom a kontrastom.
     * Slúži na rozlíšenie „ten istý list“ / „iný list“ (pozri [AutoCaptureController]).
     */
    fun fingerprint(gray: Mat, quad: Quad): FloatArray {
        val warped = warp(gray, quad, AutoCaptureController.FP_W, AutoCaptureController.FP_H, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(warped, warped, Size(3.0, 3.0), 0.0)
        val f = Mat()
        warped.convertTo(f, CvType.CV_32F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(f, mean, std)
        val m = mean.toArray()[0]
        val s = max(std.toArray()[0], 1.0)
        val data = FloatArray(AutoCaptureController.FP_W * AutoCaptureController.FP_H)
        f.get(0, 0, data)
        for (i in data.indices) data[i] = ((data[i] - m) / s).toFloat()
        listOf(warped, f, mean, std).forEach { it.release() }
        return data
    }

    /** Pomer strán formátu A4 (dlhšia : kratšia). */
    const val A4_RATIO = 1.41421356

    /**
     * Vyrovná dokument z obrazu [src] (ľubovoľný počet kanálov) podľa štvoruholníka [quad]
     * v normalizovaných súradniciach. Výstupné rozmery zodpovedajú dĺžkam strán štvoruholníka.
     *
     * Ak je zadaný [snapRatio] a nameraný pomer strán sa od neho líši najviac o [SNAP_TOLERANCE],
     * výstup sa prispôsobí presne tomuto pomeru (napr. A4), čím sa odstráni skreslenie z perspektívy.
     *
     * [margin] (podiel kratšej strany) odreže na každej strane pás papiera, aby na hrane neostal
     * tmavý prúžok podkladu ani tieň okraja listu. Odrezanie sa počíta vo vyrovnanej strane,
     * takže je rovnako široké po celom obvode aj pri šikmom zábere; rozmery výstupu sa nemenia.
     */
    fun cropDocument(src: Mat, quad: Quad, snapRatio: Double? = null, margin: Double = 0.0): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        fun d(a: Pt, b: Pt) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        var outW = max(d(quad.tl, quad.tr), d(quad.bl, quad.br))
        var outH = max(d(quad.tl, quad.bl), d(quad.tr, quad.br))
        if (snapRatio != null) {
            // Orientácia z priemerných dĺžok strán (ako v detektore): pri fotení zošikma je
            // bližšia hrana výrazne dlhšia a samotné maximum by list „otočilo“ na šírku.
            val avgW = (d(quad.tl, quad.tr) + d(quad.bl, quad.br)) / 2
            val avgH = (d(quad.tl, quad.bl) + d(quad.tr, quad.br)) / 2
            val portrait = avgH >= avgW
            val measured = if (portrait) avgH / avgW else avgW / avgH
            if (kotlin.math.abs(measured - snapRatio) / snapRatio <= SNAP_TOLERANCE) {
                // Zachová sa najdlhšia nameraná strana (rozlíšenie), druhá sa dopočíta.
                val long = max(outW, outH)
                if (portrait) {
                    outH = long
                    outW = long / snapRatio
                } else {
                    outW = long
                    outH = long / snapRatio
                }
            }
        }
        val ow = outW.roundToInt().coerceAtLeast(1)
        val oh = outH.roundToInt().coerceAtLeast(1)
        // Rohy celého listu sa zobrazia mimo výstupu: vnútro zmenšené o okraj m vyplní celý výstup.
        val m = (minOf(ow, oh) * margin.coerceIn(0.0, 0.2))
        val kx = ow / (ow - 2 * m)
        val ky = oh / (oh - 2 * m)
        val x0 = -m * kx
        val y0 = -m * ky
        val x1 = (ow - m) * kx
        val y1 = (oh - m) * ky
        return warp(src, quad, ow, oh, Imgproc.INTER_CUBIC, Rect4(x0, y0, x1, y1))
    }

    private data class Rect4(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    /**
     * Najväčšia relatívna odchýlka nameraného pomeru strán, pri ktorej sa ešte prispôsobí formátu.
     * Široká, lebo list fotený zošikma má v obraze výrazne skrátenú výšku (A4, A5 aj A6 majú pomer √2).
     */
    private const val SNAP_TOLERANCE = 0.30

    private fun warp(
        src: Mat,
        quad: Quad,
        outW: Int,
        outH: Int,
        interpolation: Int,
        target: Rect4 = Rect4(0.0, 0.0, outW.toDouble(), outH.toDouble()),
    ): Mat {
        val w = src.cols().toDouble()
        val h = src.rows().toDouble()
        val from = MatOfPoint2f(*quad.corners.map { Point(it.x * w, it.y * h) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(target.x0, target.y0), Point(target.x1, target.y0),
            Point(target.x1, target.y1), Point(target.x0, target.y1),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val out = Mat()
        Imgproc.warpPerspective(src, out, m, Size(outW.toDouble(), outH.toDouble()), interpolation)
        listOf(from, to, m).forEach { it.release() }
        return out
    }

    /**
     * Vyplní tmavé plochy v rohoch orezanej strany farbou papiera. Vzniknú, keď má list zahnutý
     * alebo odtrhnutý roh (kramlička): dopočítaný roh leží nad tmavým podkladom. Vyplní sa len
     * tmavá súvislá plocha, ktorá sa dotýka rohu a nezasahuje ďaleko do strany, takže písmo
     * pri okraji ostane. Vráti počet vyplnených rohov.
     */
    fun fillDarkCorners(bgr: Mat): Int {
        val gray = Mat()
        Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
        val w = gray.cols()
        val h = gray.rows()
        // Prah medzi papierom a podkladom: polovica jasu papiera (90. percentil).
        val hist = Mat()
        Imgproc.calcHist(listOf(gray), org.opencv.core.MatOfInt(0), Mat(), hist, org.opencv.core.MatOfInt(256), org.opencv.core.MatOfFloat(0f, 256f))
        var acc = 0.0
        var paper = 255
        for (i in 0 until 256) {
            acc += hist.get(i, 0)[0]
            if (acc >= gray.total() * 0.9) { paper = i; break }
        }
        hist.release()
        val dark = Mat()
        Imgproc.threshold(gray, dark, paper * 0.5, 255.0, Imgproc.THRESH_BINARY_INV)
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        val count = Imgproc.connectedComponentsWithStats(dark, labels, stats, centroids, 8, CvType.CV_32S)
        val probe = (minOf(w, h) * 0.01).toInt().coerceAtLeast(2)
        val cornerPts = listOf(probe to probe, w - 1 - probe to probe, w - 1 - probe to h - 1 - probe, probe to h - 1 - probe)
        val paperMask = Mat()
        Core.bitwise_not(dark, paperMask)
        val paperColor = Core.mean(bgr, paperMask)
        var filled = 0
        val done = HashSet<Int>()
        for ((x, y) in cornerPts) {
            val label = labels.get(y, x)[0].toInt()
            if (label == 0 || label in done) continue
            val bw = stats.get(label, Imgproc.CC_STAT_WIDTH)[0]
            val bh = stats.get(label, Imgproc.CC_STAT_HEIGHT)[0]
            val area = stats.get(label, Imgproc.CC_STAT_AREA)[0]
            // Trojuholník v rohu: najviac 35 % šírky/výšky a zhruba polovica svojho obdĺžnika.
            if (bw > 0.35 * w || bh > 0.35 * h || area < 0.3 * bw * bh) continue
            val mask = Mat()
            Core.compare(labels, org.opencv.core.Scalar(label.toDouble()), mask, Core.CMP_EQ)
            // Mierne rozšírenie, aby neostal tmavý lem na hrane záhybu.
            Imgproc.dilate(mask, mask, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(5.0, 5.0)))
            bgr.setTo(paperColor, mask)
            mask.release()
            done += label
            filled++
        }
        listOf(gray, dark, labels, stats, centroids, paperMask).forEach { it.release() }
        return filled
    }
}
