package sk.mirecky.skener.data

import android.content.Context
import java.io.File

/**
 * Úložisko strán rozpracovaného dokumentu v internom úložisku aplikácie.
 * Každá strana sa zapisuje hneď po nasnímaní, takže ukončenie aplikácie nespôsobí stratu práce.
 */
class PageStore(context: Context) {

    private val dir = File(context.filesDir, "session").apply { mkdirs() }

    fun pages(): List<File> =
        dir.listFiles { f -> f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }

    /** Súbor pre ďalšiu stranu; poradové číslo nadväzuje na najvyššie existujúce. */
    fun newPageFile(): File {
        val next = (pages().mapNotNull { indexOf(it) }.maxOrNull() ?: 0) + 1
        return File(dir, "%s%04d%s".format(PREFIX, next, SUFFIX))
    }

    private fun indexOf(file: File): Int? =
        file.name.removePrefix(PREFIX).removeSuffix(SUFFIX).toIntOrNull()

    private companion object {
        const val PREFIX = "page_"
        const val SUFFIX = ".jpg"
    }
}
