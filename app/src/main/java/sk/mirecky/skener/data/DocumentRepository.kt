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

    fun document(id: String): DocumentItem? = _documents.value.firstOrNull { it.id == id }

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
        val page = PageItem(slot.pageId, slot.dir, filter, mode, rotation = 0, quad = quad, version = System.currentTimeMillis())
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

    suspend fun deletePages(docId: String, pageIds: Set<String>) {
        val doc = document(docId) ?: return
        val remaining = doc.pages.filterNot { it.id in pageIds }
        if (remaining.isEmpty()) {
            deleteDocuments(setOf(docId))
            return
        }
        modify(docId) { it.copy(pages = remaining) }
        withContext(Dispatchers.IO) { doc.pages.filter { it.id in pageIds }.forEach { it.dir.deleteRecursively() } }
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
            d.copy(pages = d.pages.map { p -> if (p.id in pageIds) p.copy(rotation = (p.rotation + 90) % 360, version = now) else p })
        }
    }

    suspend fun setFilter(docId: String, pageIds: Set<String>, filter: PageFilter) {
        val doc = document(docId) ?: return
        val targets = doc.pages.filter { it.id in pageIds && it.filter != filter }
        if (targets.isEmpty()) return
        withContext(Dispatchers.Default) { targets.forEach { PageRenderer.applyFilter(it.cropFile, it.displayFile, filter) } }
        val now = System.currentTimeMillis()
        val changed = targets.map { it.id }.toSet()
        modify(docId) { d ->
            d.copy(pages = d.pages.map { p -> if (p.id in changed) p.copy(filter = filter, version = now) else p })
        }
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
            PdfExporter.export(doc.pages, tmp)
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
                PageItem(id, dir, PageFilter.ORIGINAL, ScanMode.DOCUMENT, 0, null, now)
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
    }
}
