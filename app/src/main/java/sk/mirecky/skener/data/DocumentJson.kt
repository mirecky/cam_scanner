package sk.mirecky.skener.data

import org.json.JSONArray
import org.json.JSONObject
import sk.mirecky.skener.scanner.Adjust
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.scanner.Pt
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode
import java.io.File

/** Uloženie metadát dokumentu do súboru `document.json` v priečinku dokumentu. */
internal object DocumentJson {

    private const val FILE = "document.json"

    fun write(doc: DocumentItem) {
        val json = JSONObject()
            .put("name", doc.name)
            .put("createdAt", doc.createdAt)
            .put("updatedAt", doc.updatedAt)
            .put("pdfUri", doc.pdfUri ?: JSONObject.NULL)
            .put("compact", doc.compact)
            .put("pages", JSONArray(doc.pages.map { page ->
                JSONObject()
                    .put("id", page.id)
                    .put("filter", page.filter.name)
                    .put("level", page.level)
                    .put("adjust", JSONArray(listOf(page.adjust.brightness, page.adjust.contrast, page.adjust.sharpness)))
                    .put("mode", page.mode.name)
                    .put("rotation", page.rotation)
                    .put("version", page.version)
                    .put("cropVersion", page.cropVersion)
                    .put("quad", page.quad?.let { q -> JSONArray(q.corners.flatMap { listOf(it.x, it.y) }) } ?: JSONObject.NULL)
            }))
        val tmp = File(doc.dir, "$FILE.tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(File(doc.dir, FILE))
    }

    fun read(dir: File): DocumentItem? = runCatching {
        val json = JSONObject(File(dir, FILE).readText())
        val pagesDir = File(dir, "pages")
        val pages = json.getJSONArray("pages").let { arr ->
            (0 until arr.length()).map { i ->
                val p = arr.getJSONObject(i)
                val id = p.getString("id")
                PageItem(
                    id = id,
                    dir = File(pagesDir, id),
                    filter = enumValueOrNull<PageFilter>(p.optString("filter")) ?: PageFilter.ORIGINAL,
                    level = p.optInt("level", 0),
                    adjust = p.optJSONArray("adjust")?.let { a -> Adjust(a.optInt(0), a.optInt(1), a.optInt(2)) } ?: Adjust.NONE,
                    mode = enumValueOrNull<ScanMode>(p.optString("mode")) ?: ScanMode.DOCUMENT,
                    rotation = p.optInt("rotation", 0),
                    quad = p.optJSONArray("quad")?.let { a ->
                        val v = (0 until 8).map { a.getDouble(it) }
                        Quad(Pt(v[0], v[1]), Pt(v[2], v[3]), Pt(v[4], v[5]), Pt(v[6], v[7]))
                    },
                    version = p.optLong("version", 0),
                    cropVersion = p.optLong("cropVersion", p.optLong("version", 0)),
                )
            }.filter { it.displayFile.exists() }
        }
        DocumentItem(
            id = dir.name,
            dir = dir,
            name = json.getString("name"),
            createdAt = json.getLong("createdAt"),
            updatedAt = json.getLong("updatedAt"),
            pages = pages,
            pdfUri = json.optString("pdfUri").takeIf { it.isNotEmpty() && it != "null" },
            compact = json.optBoolean("compact", false),
        )
    }.getOrNull()

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
