package sk.mirecky.skener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import sk.mirecky.skener.ui.document.DocumentScreen
import sk.mirecky.skener.ui.library.LibraryScreen
import sk.mirecky.skener.ui.scanner.ScannerScreen
import sk.mirecky.skener.ui.scanner.ScannerViewModel
import sk.mirecky.skener.ui.theme.SkenerTheme

/**
 * Navigácia: kamera je koreňová obrazovka (aplikácia sa spúšťa rovno do skenovania).
 * Zásobník obrazoviek je uložený ako zoznam reťazcov: "library", "doc:<id>".
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

                when (val top = stack.lastOrNull()) {
                    null -> ScannerScreen(
                        viewModel = scanner,
                        onOpenDocument = { push("doc:$it") },
                        onOpenLibrary = { push("library") },
                    )
                    "library" -> LibraryScreen(
                        onBack = ::pop,
                        onOpenDocument = { push("doc:$it") },
                        onScan = {
                            scanner.startNewDocument()
                            popToScanner()
                        },
                    )
                    else -> DocumentScreen(
                        docId = top.removePrefix("doc:"),
                        onBack = ::pop,
                        onAddPages = { id ->
                            scanner.continueDocument(id)
                            popToScanner()
                        },
                    )
                }
            }
        }
    }
}
