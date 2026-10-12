package sk.mirecky.skener.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
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
            val ok = runCatching {
                resolver.openOutputStream(existing, "wt")!!.use { out -> source.inputStream().use { it.copyTo(out) } }
                rename(existing, name)
            }.onFailure { Log.w(TAG, "Prepísanie PDF zlyhalo, vytváram nové", it) }.isSuccess
            if (ok) return existing
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

    fun rename(uri: Uri, name: String) {
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.pdf") }, null, null)
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
        runCatching { resolver.delete(uri, null, null) }
            .onFailure { Log.w(TAG, "Zmazanie PDF zlyhalo", it) }
    }

    companion object {
        private const val TAG = "PdfStore"
        const val MIME = "application/pdf"
        val RELATIVE_PATH = Environment.DIRECTORY_DOCUMENTS + "/Skener/"
    }
}
