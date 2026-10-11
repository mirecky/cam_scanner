package sk.mirecky.skener.ui.pages

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import sk.mirecky.skener.SkenerApp
import sk.mirecky.skener.data.Page

class PagesViewModel(application: Application) : AndroidViewModel(application) {

    private val store = (application as SkenerApp).pageStore

    val pages: StateFlow<List<Page>> = store.pages

    fun delete(page: Page) {
        viewModelScope.launch { store.delete(page) }
    }

    fun rotate(page: Page) {
        viewModelScope.launch { store.rotate(page, 90) }
    }

    /** Posunie stranu o [offset] pozícií (−1 doľava, +1 doprava). */
    fun move(index: Int, offset: Int) {
        val list = pages.value
        val target = index + offset
        if (index !in list.indices || target !in list.indices) return
        viewModelScope.launch { store.swap(list[index], list[target]) }
    }

    fun clear() {
        viewModelScope.launch { store.clear() }
    }
}
