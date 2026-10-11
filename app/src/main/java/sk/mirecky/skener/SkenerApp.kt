package sk.mirecky.skener

import android.app.Application
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.opencv.android.OpenCVLoader
import sk.mirecky.skener.data.DocumentRepository

class SkenerApp : Application() {
    /** Jediná inštancia úložiska dokumentov, zdieľaná všetkými obrazovkami. */
    val documents: DocumentRepository by lazy { DocumentRepository(this) }

    /** Rozsah pre operácie, ktoré nesmú skončiť odchodom z obrazovky (mazanie, filtre, presuny). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * Spoločný stav hlášok so „Späť“: hláška sa spúšťa v rozsahu aplikácie, takže vydrží
     * aj prechod medzi obrazovkami (napr. zmazanie poslednej zobrazenej strany).
     */
    val snackbar = androidx.compose.material3.SnackbarHostState()

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("SkenerApp", "OpenCV sa nepodarilo načítať")
        PDFBoxResourceLoader.init(this)
    }
}
