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
            val portrait = outH >= outW
            val measured = if (portrait) outH / outW else outW / outH
            if (kotlin.math.abs(measured - snapRatio) / snapRatio <= SNAP_TOLERANCE) {
                // Zachová sa dlhšia strana, kratšia sa dopočíta.
                if (portrait) outW = outH / snapRatio else outH = outW / snapRatio
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

    /** Najväčšia relatívna odchýlka nameraného pomeru strán, pri ktorej sa ešte prispôsobí formátu. */
    private const val SNAP_TOLERANCE = 0.15

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
}
