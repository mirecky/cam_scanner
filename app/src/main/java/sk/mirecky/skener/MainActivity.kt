package sk.mirecky.skener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import sk.mirecky.skener.ui.document.DocumentScreen
import sk.mirecky.skener.ui.editor.EditorScreen
import sk.mirecky.skener.ui.library.LibraryScreen
import sk.mirecky.skener.ui.scanner.ScannerScreen
import sk.mirecky.skener.ui.scanner.ScannerViewModel
import sk.mirecky.skener.ui.theme.SkenerTheme

/**
 * Navigácia: kamera je koreňová obrazovka (aplikácia sa spúšťa rovno do skenovania).
 * Zásobník obrazoviek je uložený ako zoznam reťazcov: "library", "edit:<id>:<strana>" (editor strany),
 * "doc:<id>" (prehľad strán).
 */
class MainActivity : ComponentActivity() {

    private val scanner: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SkenerTheme {
                var stack by rememberSaveable { mutableStateOf(listOf<String>()) }
                fun push(route: String) { stack = stack + route }
                fun pop() { stack = stack.dropLast(1) }
                fun popToScanner() { stack = emptyList() }

                fun openEditor(docId: String, index: Int) {
                    // Z prehľadu strán otvoreného z editora sa vrátime do toho istého editora (nová strana).
                    val prev = stack.getOrNull(stack.size - 2)
                    stack = if (stack.lastOrNull() == "doc:$docId" && prev?.startsWith("edit:$docId:") == true) {
                        stack.dropLast(2) + "edit:$docId:$index"
                    } else {
                        stack + "edit:$docId:$index"
                    }
                }

                val top = stack.lastOrNull()
                when {
                    top == null -> ScannerScreen(
                        viewModel = scanner,
                        onOpenDocument = { openEditor(it, 0) },
                        onOpenLibrary = { push("library") },
                        onRetakeDone = { docId, pageId ->
                            val index = (application as SkenerApp).documents.document(docId)
                                ?.pages?.indexOfFirst { it.id == pageId }?.coerceAtLeast(0) ?: 0
                            openEditor(docId, index)
                        },
                    )
                    top == "library" -> LibraryScreen(
                        onBack = ::pop,
                        onOpenDocument = { openEditor(it, 0) },
                        onScan = {
                            scanner.startNewDocument()
                            popToScanner()
                        },
                    )
                    top.startsWith("edit:") -> {
                        val (docId, index) = top.removePrefix("edit:").split(":").let { it[0] to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
                        // Kľúč podľa položky zásobníka: iná strana = nový editor s vlastným stavom.
                        key(top) {
                        EditorScreen(
                            docId = docId,
                            initialIndex = index,
                            onBack = ::pop,
                            onOpenLibrary = {
                                val at = stack.indexOf("library")
                                stack = if (at >= 0) stack.take(at + 1) else stack + "library"
                            },
                            onOpenOverview = { push("doc:$it") },
                            onAddPages = { id ->
                                scanner.continueDocument(id)
                                popToScanner()
                            },
                            onRetake = { id, pageId ->
                                scanner.retake(id, pageId)
                                popToScanner()
                            },
                        )
                        }
                    }
                    else -> {
                        val docId = top.removePrefix("doc:")
                        DocumentScreen(
                            docId = docId,
                            onBack = ::pop,
                            onAddPages = { id ->
                                scanner.continueDocument(id)
                                popToScanner()
                            },
                            onOpenPage = { openEditor(docId, it) },
                        )
                    }
                }
            }
        }
    }
}
