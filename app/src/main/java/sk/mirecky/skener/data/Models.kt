package sk.mirecky.skener.data

import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode
import java.io.File

/** Strana dokumentu. Súbory strany ležia v priečinku [dir]. */
data class PageItem(
    val id: String,
    val dir: File,
    val filter: PageFilter,
    val mode: ScanMode,
    /** Otočenie oproti pôvodnému orezu v stupňoch (0, 90, 180, 270). */
    val rotation: Int,
    /** Obrys dokumentu na pôvodnej fotografii (pre neskoršiu úpravu rohov). */
    val quad: Quad?,
    /** Zvyšuje sa pri každej zmene obrazu strany (kľúč pre vyrovnávaciu pamäť náhľadov). */
    val version: Long,
) {
    /** Pôvodná fotografia z kamery. */
    val originalFile: File get() = File(dir, "original.jpg")
    /** Farebný orez s vyrovnanou perspektívou (základ pre filtre). */
    val cropFile: File get() = File(dir, "crop.jpg")
    /** Výsledná strana po filtri (zobrazuje sa a ide do PDF). */
    val displayFile: File get() = File(dir, "display.jpg")
    val cacheKey: String get() = "${dir.path}:$version"
}

/** Dokument = jedno PDF zložené zo strán. */
data class DocumentItem(
    val id: String,
    val dir: File,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pages: List<PageItem>,
    /** URI výsledného PDF v MediaStore (Dokumenty/Skener), ak už bolo vytvorené. */
    val pdfUri: String?,
)
