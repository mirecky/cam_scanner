package sk.mirecky.skener.data

import android.content.Context
import android.util.Log
import com.googlecode.tesseract.android.TessBaseAPI
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import sk.mirecky.skener.scanner.Filters
import java.io.File

/** Rozpoznané slovo s polohou v pixeloch obrazu OCR. */
data class OcrWord(val text: String, val x: Int, val y: Int, val w: Int, val h: Int)

/**
 * Výsledok OCR jednej strany. [width] × [height] sú rozmery obrazu, na ktorom OCR bežalo
 * (pomer strán zodpovedá orezu strany). [cropVersion] viaže výsledok na konkrétny orez.
 */
data class OcrResult(
    val cropVersion: Long,
    val width: Int,
    val height: Int,
    val text: String,
    val words: List<OcrWord>,
) {
    fun toJson(): String = JSONObject()
        .put("cropVersion", cropVersion)
        .put("width", width)
        .put("height", height)
        .put("text", text)
        .put("words", JSONArray(words.map { JSONArray(listOf(it.text, it.x, it.y, it.w, it.h)) }))
        .toString()

    companion object {
        fun read(file: File): OcrResult? = runCatching {
            val j = JSONObject(file.readText())
            val arr = j.getJSONArray("words")
            OcrResult(
                cropVersion = j.getLong("cropVersion"),
                width = j.getInt("width"),
                height = j.getInt("height"),
                text = j.getString("text"),
                words = (0 until arr.length()).map { i ->
                    val w = arr.getJSONArray(i)
                    OcrWord(w.getString(0), w.getInt(1), w.getInt(2), w.getInt(3), w.getInt(4))
                },
            )
        }.getOrNull()
    }
}

/**
 * Offline OCR (Tesseract 5, modely tessdata_best; jazyky podľa modelov pribalených v APK).
 * Nie je vláknovo bezpečný – volať z jedného vlákna (pozri [DocumentRepository]).
 */
internal class OcrEngine(private val context: Context) {

    private var api: TessBaseAPI? = null

    private fun ensureApi(): TessBaseAPI {
        api?.let { return it }
        val root = File(context.filesDir, "ocr")
        val dataDir = File(root, "tessdata").apply { mkdirs() }
        // Jazyky = modely pribalené v APK (assets/tessdata); pridanie jazyka nevyžaduje zmenu kódu.
        val langs = context.assets.list("tessdata").orEmpty()
            .filter { it.endsWith(".traineddata") }
            .map { it.removeSuffix(".traineddata") }
            .sorted()
        check(langs.isNotEmpty()) { "V APK chýbajú jazykové modely OCR" }
        // Modely sa skopírujú z APK pri prvom spustení a znova po aktualizácii aplikácie.
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        val marker = File(dataDir, "version")
        if (marker.takeIf { it.exists() }?.readText() != version || langs.any { !File(dataDir, "$it.traineddata").exists() }) {
            for (lang in langs) {
                val target = File(dataDir, "$lang.traineddata")
                val tmp = File(dataDir, "$lang.tmp")
                context.assets.open("tessdata/$lang.traineddata").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.renameTo(target)) { "Kopírovanie modelu $lang zlyhalo" }
            }
            marker.writeText(version)
        }
        return TessBaseAPI().also {
            check(it.init(root.path, langs.joinToString("+"), TessBaseAPI.OEM_LSTM_ONLY)) { "Tesseract sa nepodarilo inicializovať" }
            it.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            api = it
        }
    }

    /** Rozpozná text na farebnom oreze strany [crop]. */
    fun recognize(crop: File, cropVersion: Long): OcrResult {
        val bgr = Imgcodecs.imread(crop.path, Imgcodecs.IMREAD_COLOR)
        val gray = Filters.ocrInput(bgr)
        bgr.release()
        val input = limitWidth(gray)
        val w = input.cols()
        val h = input.rows()
        val bytes = ByteArray(w * h)
        input.get(0, 0, bytes)
        if (input !== gray) input.release()
        gray.release()

        val tess = ensureApi()
        tess.setImage(bytes, w, h, 1, w)
        val text = tess.getUTF8Text().orEmpty().trim()
        val words = ArrayList<OcrWord>()
        tess.getResultIterator()?.let { it ->
            it.begin()
            do {
                val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                if (!t.isNullOrEmpty() && r != null && r.width() > 0 && r.height() > 0) {
                    words += OcrWord(t, r.left, r.top, r.width(), r.height())
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            it.delete()
        }
        tess.clear()
        Log.d(TAG, "OCR: ${words.size} slov (${w}×$h)")
        return OcrResult(cropVersion, w, h, text, words)
    }

    /** Najviac 300 dpi na šírku A4 – vyššie rozlíšenie OCR nespresní, len spomalí. */
    private fun limitWidth(src: Mat): Mat {
        if (src.cols() <= MAX_WIDTH) return src
        val f = MAX_WIDTH.toDouble() / src.cols()
        return Mat().also { Imgproc.resize(src, it, Size(MAX_WIDTH.toDouble(), src.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA) }
    }

    private companion object {
        const val TAG = "OcrEngine"
        const val MAX_WIDTH = 2480
    }
}
