package sk.mirecky.skener.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import sk.mirecky.skener.R
import sk.mirecky.skener.scanner.Adjust
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.scanner.Quad
import sk.mirecky.skener.scanner.ScanMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Vyhradené miesto pre nasnímanú stranu: kamera zapíše fotografiu do [originalFile]. */
class PageSlot(val docId: String, val pageId: String, val dir: File) {
    val originalFile: File get() = File(dir, "original.jpg")
}

/**
 * Dokumenty a ich strany v internom úložisku aplikácie a automatická aktualizácia PDF
 * v `Dokumenty/Skener` po každej zmene (s krátkym oneskorením, aby sa zmeny zlúčili).
 *
 * Štruktúra: `files/documents/<docId>/document.json` a `.../pages/<pageId>/{original,crop,display}.jpg`.
 */
class DocumentRepository(private val context: Context) {

    private val root = File(context.filesDir, "documents").apply { mkdirs() }
    private val pdfStore = PdfStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pdfJobs = HashMap<String, Job>()

    private val _documents = MutableStateFlow(load())
    /** Dokumenty od najnovšie upraveného. */
    val documents: StateFlow<List<DocumentItem>> = _documents.asStateFlow()

    private val _exporting = MutableStateFlow<Set<String>>(emptySet())
    /** Dokumenty, ktorých PDF sa práve aktualizuje. */
    val exporting: StateFlow<Set<String>> = _exporting.asStateFlow()

    private val ocrEngine = OcrEngine(context)
    /** OCR beží postupne na jednom vlákne (Tesseract nie je vláknovo bezpečný). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val ocrDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var ocrJob: Job? = null

    private val _ocrPending = MutableStateFlow<Set<String>>(emptySet())
    /** Strany (ID), ktoré čakajú na OCR alebo sa práve rozpoznávajú. */
    val ocrPending: StateFlow<Set<String>> = _ocrPending.asStateFlow()

    private val _ocrRevision = MutableStateFlow(0L)
    /** Zvýši sa po každej rozpoznanej strane (podnet pre UI načítať text znova). */
    val ocrRevision: StateFlow<Long> = _ocrRevision.asStateFlow()

    fun document(id: String): DocumentItem? = _documents.value.firstOrNull { it.id == id }

    /** Výsledok OCR strany, ak je aktuálny vzhľadom na jej orez. */
    fun ocr(page: PageItem): OcrResult? =
        OcrResult.read(page.ocrFile)?.takeIf { it.cropVersion == page.cropVersion }

    /** Text celého dokumentu (strany oddelené prázdnym riadkom). */
    fun documentText(docId: String): String =
        document(docId)?.pages.orEmpty().mapNotNull { ocr(it)?.text?.takeIf(String::isNotBlank) }.joinToString("\n\n")

    /**
     * Rozpozná text na stranách, ktoré ho ešte nemajú (alebo majú zastaraný). Po každom dokumente
     * sa aktualizuje PDF, aby obsahovalo textovú vrstvu.
     */
    private fun scheduleOcr() {
        if (ocrJob?.isActive == true) return
        ocrJob = scope.launch(ocrDispatcher) {
            while (true) {
                val todo = _documents.value.flatMap { doc -> doc.pages.map { doc.id to it } }
                    .filter { (_, p) -> ocr(p) == null && p.cropFile.exists() }
                _ocrPending.value = todo.map { it.second.id }.toSet()
                val (docId, page) = todo.firstOrNull() ?: break
                try {
                    val result = ocrEngine.recognize(page.cropFile, page.cropVersion)
                    page.ocrFile.writeText(result.toJson())
                } catch (e: Throwable) {
                    Log.e(TAG, "OCR strany zlyhalo", e)
                    // Prázdny výsledok, aby sa chybná strana neopakovala donekonečna.
                    page.ocrFile.writeText(OcrResult(page.cropVersion, 1, 1, "", emptyList()).toJson())
                }
                _ocrRevision.value += 1
                val docDone = document(docId)?.pages.orEmpty().none { ocr(it) == null }
                if (docDone) schedulePdf(docId)
            }
            _ocrPending.value = emptySet()
        }
    }

    init {
        scheduleOcr()
    }

    /** Vyhradí miesto pre novú stranu; ak [docId] je null, nová strana založí nový dokument. */
    fun reservePage(docId: String?): PageSlot {
        val doc = docId ?: newId()
        val page = newId()
        val dir = File(File(File(root, doc), "pages"), page).apply { mkdirs() }
        return PageSlot(doc, page, dir)
    }

    /** Spracuje nasnímanú fotografiu a pridá stranu na koniec dokumentu. */
    suspend fun addPage(slot: PageSlot, quad: Quad?, mode: ScanMode) {
        val filter = PageFilter.defaultFor(mode)
        val page = PageItem(slot.pageId, slot.dir, filter, level = 0, mode = mode, rotation = 0, quad = quad, version = System.currentTimeMillis())
        withContext(Dispatchers.Default) {
            try {
                PageRenderer.renderNew(slot.originalFile, page.cropFile, page.displayFile, quad, mode, filter)
            } catch (e: Exception) {
                Log.e(TAG, "Spracovanie strany zlyhalo, použijem pôvodnú fotografiu", e)
                slot.originalFile.copyTo(page.cropFile, overwrite = true)
                slot.originalFile.copyTo(page.displayFile, overwrite = true)
            }
        }
        modify(slot.docId, create = true) { it.copy(pages = it.pages + page) }
    }

    /** Zahodí vyhradené miesto (snímanie zlyhalo). */
    fun discard(slot: PageSlot) {
        slot.dir.deleteRecursively()
        // Priečinok nového dokumentu sa zmaže, len ak v ňom nie je iná (spracúvaná) strana.
        val docDir = File(root, slot.docId)
        val otherPages = File(docDir, "pages").listFiles().orEmpty()
        if (document(slot.docId) == null && otherPages.isEmpty()) docDir.deleteRecursively()
    }

    /**
     * Odstráni strany z dokumentu. Súbory strán ostanú ešte [UNDO_MS] na disku, aby sa zmazanie
     * dalo vrátiť cez [restorePages]. Ak by dokument ostal prázdny, zmaže sa celý (bez možnosti vrátenia).
     * Vráti odstránené strany s ich pôvodnými pozíciami (pre „Späť“).
     */
    suspend fun deletePages(docId: String, pageIds: Set<String>): List<Pair<Int, PageItem>> {
        val doc = document(docId) ?: return emptyList()
        val remaining = doc.pages.filterNot { it.id in pageIds }
        if (remaining.isEmpty()) {
            deleteDocuments(setOf(docId))
            return emptyList()
        }
        val removed = doc.pages.withIndex().filter { it.value.id in pageIds }.map { it.index to it.value }
        modify(docId) { it.copy(pages = remaining) }
        scope.launch {
            delay(UNDO_MS)
            val stillGone = document(docId)?.pages?.map { it.id }?.toSet() ?: emptySet()
            removed.filter { it.second.id !in stillGone }.forEach { it.second.dir.deleteRecursively() }
        }
        return removed
    }

    /** Vráti zmazané strany na ich pôvodné pozície (ak ich súbory ešte existujú). */
    suspend fun restorePages(docId: String, removed: List<Pair<Int, PageItem>>) {
        val restorable = removed.filter { it.second.displayFile.exists() }.sortedBy { it.first }
        if (restorable.isEmpty()) return
        modify(docId) { d ->
            val list = d.pages.toMutableList()
            restorable.forEach { (index, page) -> if (list.none { it.id == page.id }) list.add(index.coerceIn(0, list.size), page) }
            d.copy(pages = list)
        }
    }

    /** Nastaví nové poradie strán (zoznam ID v novom poradí). */
    suspend fun reorder(docId: String, order: List<String>) {
        modify(docId) { doc ->
            val byId = doc.pages.associateBy { it.id }
            val reordered = order.mapNotNull { byId[it] }
            // Strany, ktoré v zozname chýbajú (napr. pribudli medzitým), ostanú na konci.
            doc.copy(pages = reordered + doc.pages.filter { it.id !in order })
        }
    }

    suspend fun rotate(docId: String, pageIds: Set<String>) {
        val doc = document(docId) ?: return
        val targets = doc.pages.filter { it.id in pageIds }
        withContext(Dispatchers.Default) { targets.forEach { PageRenderer.rotateClockwise(it.cropFile, it.displayFile) } }
        val now = System.currentTimeMillis()
        modify(docId) { d ->
            d.copy(pages = d.pages.map { p -> if (p.id in pageIds) p.copy(rotation = (p.rotation + 90) % 360, version = now, cropVersion = now) else p })
        }
    }

    suspend fun setFilter(docId: String, pageIds: Set<String>, filter: PageFilter, level: Int) {
        val doc = document(docId) ?: return
        val targets = doc.pages.filter { it.id in pageIds && (it.filter != filter || it.level != level) }
        if (targets.isEmpty()) return
        withContext(Dispatchers.Default) { targets.forEach { PageRenderer.applyFilter(it.cropFile, it.displayFile, filter, level, it.adjust) } }
        val now = System.currentTimeMillis()
        val changed = targets.map { it.id }.toSet()
        modify(docId) { d ->
            d.copy(pages = d.pages.map { p -> if (p.id in changed) p.copy(filter = filter, level = level, version = now) else p })
        }
    }

    suspend fun setAdjust(docId: String, pageIds: Set<String>, adjust: Adjust) {
        val doc = document(docId) ?: return
        val targets = doc.pages.filter { it.id in pageIds && it.adjust != adjust }
        if (targets.isEmpty()) return
        withContext(Dispatchers.Default) {
            targets.forEach { PageRenderer.applyFilter(it.cropFile, it.displayFile, it.filter, it.level, adjust) }
        }
        val now = System.currentTimeMillis()
        val changed = targets.map { it.id }.toSet()
        modify(docId) { d ->
            d.copy(pages = d.pages.map { p -> if (p.id in changed) p.copy(adjust = adjust, version = now) else p })
        }
    }

    /** Nový orez strany z pôvodnej fotografie podľa [quad] (null = celá fotografia). */
    suspend fun recrop(docId: String, pageId: String, quad: Quad?) {
        val page = document(docId)?.pages?.firstOrNull { it.id == pageId } ?: return
        if (!page.originalFile.exists()) return
        withContext(Dispatchers.Default) {
            PageRenderer.renderNew(
                page.originalFile, page.cropFile, page.displayFile, quad, page.mode,
                page.filter, page.level, page.adjust, page.rotation,
            )
        }
        val now = System.currentTimeMillis()
        modify(docId) { d -> d.copy(pages = d.pages.map { p -> if (p.id == pageId) p.copy(quad = quad, version = now, cropVersion = now) else p }) }
    }

    /**
     * Prefotenie strany: nová fotografia nahradí stranu [pageId] na tom istom mieste
     * a prevezme jej filter, parameter a úpravy.
     */
    suspend fun replacePage(slot: PageSlot, pageId: String, quad: Quad?, mode: ScanMode) {
        val old = document(slot.docId)?.pages?.firstOrNull { it.id == pageId }
        if (old == null) {
            addPage(slot, quad, mode)
            return
        }
        val page = PageItem(
            slot.pageId, slot.dir, old.filter, old.level, old.adjust, mode,
            rotation = 0, quad = quad, version = System.currentTimeMillis(),
        )
        withContext(Dispatchers.Default) {
            try {
                PageRenderer.renderNew(slot.originalFile, page.cropFile, page.displayFile, quad, mode, page.filter, page.level, page.adjust)
            } catch (e: Exception) {
                Log.e(TAG, "Spracovanie prefotenej strany zlyhalo", e)
                slot.originalFile.copyTo(page.cropFile, overwrite = true)
                slot.originalFile.copyTo(page.displayFile, overwrite = true)
            }
        }
        modify(slot.docId) { d -> d.copy(pages = d.pages.map { if (it.id == pageId) page else it }) }
        withContext(Dispatchers.IO) { old.dir.deleteRecursively() }
    }

    suspend fun rename(docId: String, name: String) {
        val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifEmpty { return }
        modify(docId) { it.copy(name = clean) }
    }

    suspend fun deleteDocuments(ids: Set<String>) {
        val removed = mutex.withLock {
            val (gone, kept) = _documents.value.partition { it.id in ids }
            _documents.value = kept
            ids.forEach { pdfJobs.remove(it)?.cancel() }
            gone
        }
        withContext(Dispatchers.IO) {
            removed.forEach { doc ->
                doc.pdfUri?.let { pdfStore.delete(Uri.parse(it)) }
                doc.dir.deleteRecursively()
            }
        }
    }

    /** Zmení dokument, uloží metadáta a naplánuje aktualizáciu PDF. */
    private suspend fun modify(docId: String, create: Boolean = false, change: (DocumentItem) -> DocumentItem) {
        val updated = mutex.withLock {
            val now = System.currentTimeMillis()
            val current = document(docId) ?: if (create) {
                DocumentItem(docId, File(root, docId), defaultName(now), now, now, emptyList(), null)
            } else {
                return
            }
            val next = change(current).copy(updatedAt = now)
            withContext(Dispatchers.IO) {
                next.dir.mkdirs()
                DocumentJson.write(next)
            }
            _documents.update { list -> (listOf(next) + list.filter { it.id != docId }).sortedByDescending { it.updatedAt } }
            next
        }
        schedulePdf(updated.id)
        scheduleOcr()
    }

    private fun schedulePdf(docId: String) {
        synchronized(pdfJobs) {
            pdfJobs.remove(docId)?.cancel()
            pdfJobs[docId] = scope.launch {
                delay(PDF_DEBOUNCE_MS)
                exportPdf(docId)
            }
        }
    }

    private suspend fun exportPdf(docId: String) {
        val doc = document(docId) ?: return
        if (doc.pages.isEmpty()) return
        _exporting.update { it + docId }
        try {
            val tmp = File(context.cacheDir, "$docId.pdf")
            PdfExporter.export(doc.pages, tmp, ocr = ::ocr, font = { context.resources.openRawResource(R.font.onest_regular) })
            val uri = pdfStore.write(doc.name, doc.pdfUri?.let(Uri::parse), tmp)
            tmp.delete()
            if (uri.toString() != doc.pdfUri) {
                mutex.withLock {
                    val current = document(docId) ?: return@withLock
                    val next = current.copy(pdfUri = uri.toString())
                    DocumentJson.write(next)
                    _documents.update { list -> list.map { if (it.id == docId) next else it } }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Aktualizácia PDF zlyhala", e)
        } finally {
            _exporting.update { it - docId }
        }
    }

    private fun load(): List<DocumentItem> {
        migrateLegacySession()
        return root.listFiles { f -> f.isDirectory }.orEmpty()
            .mapNotNull { DocumentJson.read(it) }
            .filter { it.pages.isNotEmpty() }
            .sortedByDescending { it.updatedAt }
    }

    /** Strany z verzií pred zavedením dokumentov (`files/session`) sa presunú do jedného dokumentu. */
    private fun migrateLegacySession() {
        val legacy = File(context.filesDir, "session")
        if (!legacy.isDirectory) return
        val files = legacy.listFiles { f -> f.name.startsWith("page_") && f.name.endsWith(".jpg") }.orEmpty().sortedBy { it.name }
        if (files.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val docId = newId()
            val docDir = File(root, docId)
            val pages = files.mapIndexed { i, f ->
                val id = "${docId}_$i"
                val dir = File(File(docDir, "pages"), id).apply { mkdirs() }
                f.copyTo(File(dir, "crop.jpg"), overwrite = true)
                f.copyTo(File(dir, "display.jpg"), overwrite = true)
                PageItem(id, dir, PageFilter.ORIGINAL, level = 0, mode = ScanMode.DOCUMENT, rotation = 0, quad = null, version = now)
            }
            DocumentJson.write(DocumentItem(docId, docDir, defaultName(now), now, now, pages, null))
        }
        legacy.deleteRecursively()
    }

    private fun newId(): String = "${System.currentTimeMillis()}${(1000..9999).random()}"

    private fun defaultName(time: Long): String =
        "Sken_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.ROOT).format(Date(time))

    private companion object {
        const val TAG = "DocumentRepository"
        const val PDF_DEBOUNCE_MS = 1_500L
        /** Ako dlho sa dá zmazanie strán vrátiť. */
        const val UNDO_MS = 8_000L
    }
}
