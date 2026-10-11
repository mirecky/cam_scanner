package sk.mirecky.skener

import android.app.Application
import sk.mirecky.skener.data.PageStore

class SkenerApp : Application() {
    /** Jediná inštancia úložiska strán, zdieľaná kamerou aj správou strán. */
    val pageStore: PageStore by lazy { PageStore(this) }
}
