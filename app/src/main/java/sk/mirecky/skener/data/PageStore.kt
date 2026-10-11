package sk.mirecky.skener.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Strana rozpracovaného dokumentu. [cacheKey] sa mení pri každej zmene obsahu súboru,
 * aby sa v náhľadoch nezobrazoval starý obrázok z vyrovnávacej pamäte.
 */
data class Page(val file: File) {
    val cacheKey: String = "${file.path}:${file.lastModified()}:${file.length()}"
}

/**
 * Úložisko strán rozpracovaného dokumentu v internom úložisku aplikácie.
 * Každá strana sa zapisuje hneď po nasnímaní, takže ukončenie aplikácie nespôsobí stratu práce.
 * Poradie strán je dané poradím názvov súborov `page_NNNN.jpg`.
 */
class PageStore(context: Context) {

    private val dir = File(context.filesDir, "session").apply { mkdirs() }
    private val _pages = MutableStateFlow(scan())
    val pages: StateFlow<List<Page>> = _pages.asStateFlow()

    /** Súbor pre ďalšiu stranu; poradové číslo nadväzuje na najvyššie existujúce. */
    fun newPageFile(): File {
        val next = (files().mapNotNull { indexOf(it) }.maxOrNull() ?: 0) + 1
        return File(dir, "%s%04d%s".format(PREFIX, next, SUFFIX))
    }

    /** Volá sa po zapísaní novej strany (alebo inej zmene mimo tejto triedy). */
    fun refresh() {
        _pages.value = scan()
    }

    suspend fun delete(page: Page) = io {
        page.file.delete()
    }

    suspend fun clear() = io {
        files().forEach { it.delete() }
    }

    /** Vymení poradie dvoch strán premenovaním súborov. */
    suspend fun swap(a: Page, b: Page) = io {
        val tmp = File(dir, "swap.tmp")
        a.file.renameTo(tmp)
        b.file.renameTo(a.file)
        tmp.renameTo(b.file)
    }

    /** Otočí stranu o [degrees] (násobok 90) a prepíše súbor otočenými pixelmi. */
    suspend fun rotate(page: Page, degrees: Int) = io {
        val source = ImageDecoder.createSource(page.file)
        // ImageDecoder uplatní orientáciu z EXIF, výsledok je teda „tak, ako to vidí používateľ“.
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val rotated = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height,
            Matrix().apply { postRotate(degrees.toFloat()) }, true,
        )
        val tmp = File(dir, "rotate.tmp")
        tmp.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        bitmap.recycle()
        rotated.recycle()
        tmp.renameTo(page.file)
    }

    private suspend fun io(block: () -> Unit) {
        withContext(Dispatchers.IO) { block() }
        refresh()
    }

    private fun files(): List<File> =
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }

    private fun scan(): List<Page> = files().map(::Page)

    private fun indexOf(file: File): Int? =
        file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toIntOrNull()

    private companion object {
        const val PREFIX = "page_"
        const val SUFFIX = ".jpg"
        const val JPEG_QUALITY = 95
    }
}
