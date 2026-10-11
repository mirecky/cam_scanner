package sk.mirecky.skener

import android.app.Application
import android.util.Log
import org.opencv.android.OpenCVLoader
import sk.mirecky.skener.data.PageStore

class SkenerApp : Application() {
    /** Jediná inštancia úložiska strán, zdieľaná kamerou aj správou strán. */
    val pageStore: PageStore by lazy { PageStore(this) }

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("SkenerApp", "OpenCV sa nepodarilo načítať")
    }
}
