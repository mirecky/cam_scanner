package sk.mirecky.skener.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

/**
 * Hotové PDF vo verejnom priečinku `Dokumenty/Skener` (MediaStore; aplikácia spravuje len
 * súbory, ktoré sama vytvorila, preto nepotrebuje žiadne povolenie).
 */
internal class PdfStore(private val context: Context) {

    private val resolver get() = context.contentResolver

    /**
     * Zapíše obsah [source] do PDF. Ak existuje [existing], prepíše ho; ak medzitým zmizlo
     * (napr. ho používateľ zmazal), vytvorí nové. Vráti URI zapísaného PDF.
     */
    fun write(name: String, existing: Uri?, source: File): Uri {
        if (existing != null) {
            val written = runCatching {
                resolver.openOutputStream(existing, "wt")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
                rename(existing, name)
            }.onFailure { Log.w(TAG, "Prepísanie PDF zlyhalo, vytváram nové", it) }.getOrNull()
            if (written != null) return written
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: error("MediaStore odmietol vytvoriť PDF")
        try {
            resolver.openOutputStream(uri, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /**
     * Premenuje PDF. Súbor vytvorený touto inštaláciou je v MediaStore; staršie PDF sprístupnené
     * cez povolený priečinok (pozri [listFolder]) sa premenúvajú cez DocumentsContract, pričom
     * sa môže zmeniť ich URI. Vráti platné URI po premenovaní.
     */
    fun rename(uri: Uri, name: String): Uri {
        if (isMedia(uri)) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf") }, null, null)
            return uri
        }
        if (displayName(uri) == "$name.pdf") return uri
        return DocumentsContract.renameDocument(resolver, uri, "$name.pdf") ?: uri
    }

    private fun isMedia(uri: Uri) = uri.authority == MediaStore.AUTHORITY

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    /** PDF súbor v povolenom priečinku. */
    class FolderPdf(val uri: Uri, val name: String, val modified: Long)

    /** Zoznam PDF v priečinku, ku ktorému používateľ povolil prístup (strom SAF). */
    fun listFolder(tree: Uri): List<FolderPdf> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = ArrayList<FolderPdf>()
        resolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (name.startsWith(".")) continue
                if (mime != MIME && !name.endsWith(".pdf", ignoreCase = true)) continue
                out += FolderPdf(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), name, c.getLong(3))
            }
        }
        return out
    }

    /** Veľkosť súboru v bajtoch, alebo null, ak súbor neexistuje. */
    fun size(uri: Uri): Long? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null
        }
    }.getOrNull()

    /** Uloží obrázok strany ako JPEG do `Obrázky/Skener` (zobrazí sa v galérii). */
    fun saveImage(name: String, source: File): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Skener/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) }.getOrNull()
            ?: return false
        return runCatching {
            resolver.openOutputStream(uri, "w")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        }.onFailure {
            Log.w(TAG, "Uloženie obrázka zlyhalo", it)
            resolver.delete(uri, null, null)
        }.isSuccess
    }

    fun delete(uri: Uri) {
        runCatching { if (isMedia(uri)) resolver.delete(uri, null, null) else DocumentsContract.deleteDocument(resolver, uri) }
            .onFailure { Log.w(TAG, "Zmazanie PDF zlyhalo", it) }
    }

    companion object {
        private const val TAG = "PdfStore"
        const val MIME = "application/pdf"
        val RELATIVE_PATH = Environment.DIRECTORY_DOCUMENTS + "/Skener/"
    }
}
