package sk.mirecky.skener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import sk.mirecky.skener.ui.scanner.ScannerScreen
import sk.mirecky.skener.ui.theme.SkenerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SkenerTheme {
                ScannerScreen()
            }
        }
    }
}
