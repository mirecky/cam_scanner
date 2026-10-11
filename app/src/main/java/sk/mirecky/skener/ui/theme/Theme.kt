package sk.mirecky.skener.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Skener používa vždy tmavú schému: obrazovka s kamerou je tmavá a svetlé prvky by rušili náhľad.
 * Farby sa odvodzujú od tapety (dynamické farby systému).
 */
@Composable
fun SkenerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = dynamicDarkColorScheme(LocalContext.current),
        content = content,
    )
}
