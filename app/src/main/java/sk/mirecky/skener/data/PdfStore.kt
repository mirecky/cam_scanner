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
