package sk.mirecky.skener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import sk.mirecky.skener.ui.pages.PagesScreen
import sk.mirecky.skener.ui.scanner.ScannerScreen
import sk.mirecky.skener.ui.theme.SkenerTheme

private enum class Screen { SCANNER, PAGES }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SkenerTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.SCANNER) }
                when (screen) {
                    Screen.SCANNER -> ScannerScreen(onOpenPages = { screen = Screen.PAGES })
                    Screen.PAGES -> PagesScreen(onBack = { screen = Screen.SCANNER })
                }
            }
        }
    }
}
