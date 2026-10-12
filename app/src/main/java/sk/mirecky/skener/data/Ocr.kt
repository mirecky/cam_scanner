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
                // Opravy sa uplatnia aj na výsledky uložené staršou verziou (sú idempotentné).
                text = OcrLines.fixText(j.getString("text")),
                words = OcrLines.fixWords((0 until arr.length()).map { i ->
                    val w = arr.getJSONArray(i)
                    OcrWord(w.getString(0), w.getInt(1), w.getInt(2), w.getInt(3), w.getInt(4))
                }),
            )
        }.getOrNull()
    }
}

/** Zoskupenie slov do riadkov a oprava typických chýb rozpoznávania. */
internal object OcrLines {

    /**
     * Slová (v poradí čítania od Tesseractu) rozdelí do riadkov: nový riadok začína, keď sa
     * slovo vráti doľava alebo sa zvislo neprekrýva s predchádzajúcim slovom.
     */
    fun group(words: List<OcrWord>): List<List<OcrWord>> {
        val lines = ArrayList<MutableList<OcrWord>>()
        var current: MutableList<OcrWord>? = null
        for (w in words) {
            val prev = current?.lastOrNull()
            val sameLine = prev != null && w.x >= prev.x + prev.w / 2 && overlap(prev, w) > 0.4
            if (!sameLine) current = ArrayList<OcrWord>().also { lines += it }
            current!!.add(w)
        }
        return lines
    }

    private fun overlap(a: OcrWord, b: OcrWord): Float {
        val inter = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
        return inter.toFloat() / minOf(a.h, b.h).coerceAtLeast(1)
    }

    /** Odrážky, ktoré Tesseract číta ako „+“, „«“, „©“ a pod. (len samostatné na začiatku riadku). */
    private val BULLET = Regex("^[+«»©®*°¢•]{1,2}$")
    private val TEXT_BULLET = Regex("(?m)^([ \\t]*)[+«»©®*°¢]{1,2}(?=[ \\t]+\\S)")
    /** Vlnovka „~70“ prečítaná ako pomlčka tesne pred číslom. */
    private val TILDE = Regex("(^|[\\s(])—(?=\\d)")

    fun fixText(text: String): String =
        text.replace(TEXT_BULLET, "$1•").replace(TILDE, "$1~")

    fun fixWords(words: List<OcrWord>): List<OcrWord> {
        val lineStarts = group(words).mapNotNull { it.firstOrNull() }.toHashSet()
        return words.map { w ->
            var t = w.text
            if (w in lineStarts && BULLET.matches(t)) t = "•"
            if (t.length > 1 && t[0] == '—' && t[1].isDigit()) t = "~" + t.substring(1)
            if (t == w.text) w else w.copy(text = t)
        }
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
        val text = OcrLines.fixText(tess.getUTF8Text().orEmpty().trim())
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
        return OcrResult(cropVersion, w, h, text, OcrLines.fixWords(words))
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
