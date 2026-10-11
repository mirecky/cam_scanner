package sk.mirecky.skener.ui.scanner

import android.graphics.Rect
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.scanner.Detection
import sk.mirecky.skener.scanner.DocumentDetector
import sk.mirecky.skener.scanner.ImageOps
import sk.mirecky.skener.scanner.MotionMeter
import sk.mirecky.skener.scanner.ScanMode

/** Výsledok analýzy jedného snímku z kamery. */
class AnalysisResult(
    val timeMs: Long,
    val detection: Detection?,
    val motion: Double,
    val fingerprint: FloatArray?,
    /** Pomer šírky a výšky vzpriameného analyzovaného obrazu. */
    val aspect: Double,
    /** Dôvod zamietnutia posledného kandidáta (diagnostika), ak dokument nebol nájdený. */
    val rejection: String?,
)

/**
 * Analyzátor CameraX: z jasovej zložky (Y) snímku vytvorí vzpriamený sivotónový obraz
 * nízkeho rozlíšenia a nájde v ňom dokument. Beží na vlastnom vlákne.
 */
class DocumentAnalyzer(
    private val mode: () -> ScanMode,
    private val onResult: (AnalysisResult) -> Unit,
) : ImageAnalysis.Analyzer {

    private var lastRejection: String? = null
    private val detector = DocumentDetector().apply { log = { lastRejection = it.substringAfter(": ", it) } }
    private val motionMeter = MotionMeter()
    private var rowBuffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            val gray = toUprightGray(image)
            lastRejection = null
            val detection = detector.detect(gray, mode())
            val motion = motionMeter.measure(gray)
            val fingerprint = detection?.let { ImageOps.fingerprint(gray, it.quad) }
            val aspect = gray.cols().toDouble() / gray.rows()
            gray.release()
            onResult(AnalysisResult(System.currentTimeMillis(), detection, motion, fingerprint, aspect, lastRejection))
        } finally {
            image.close()
        }
    }

    private fun toUprightGray(image: ImageProxy): Mat {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val w = image.width
        val h = image.height
        val full = Mat(h, w, CvType.CV_8UC1)
        if (rowBuffer.size < w) rowBuffer = ByteArray(w)
        for (row in 0 until h) {
            buffer.position(row * rowStride)
            buffer.get(rowBuffer, 0, w)
            full.put(row, 0, rowBuffer, 0, w)
        }

        // Výrez, ktorý zodpovedá aj výslednej fotografii (spoločný viewport kamery).
        val crop = Rect(image.cropRect).apply { if (!intersect(0, 0, w, h)) set(0, 0, w, h) }
        val roi = full.submat(crop.top, crop.bottom, crop.left, crop.right)

        val upright = Mat()
        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(roi, upright, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(roi, upright, Core.ROTATE_180)
            270 -> Core.rotate(roi, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> roi.copyTo(upright)
        }
        roi.release()
        full.release()

        if (upright.cols() <= TARGET_WIDTH) return upright
        val scale = TARGET_WIDTH.toDouble() / upright.cols()
        val small = Mat()
        Imgproc.resize(upright, small, Size(TARGET_WIDTH.toDouble(), upright.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA)
        upright.release()
        return small
    }

    private companion object {
        const val TARGET_WIDTH = 480
    }
}
