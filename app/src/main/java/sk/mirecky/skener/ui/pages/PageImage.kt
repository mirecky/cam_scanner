package sk.mirecky.skener.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import sk.mirecky.skener.data.Page

/** Obrázok strany; Coil rešpektuje orientáciu z EXIF a zmenšuje obrázok na veľkosť zobrazenia. */
@Composable
fun PageImage(
    page: Page,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(page.file)
            .memoryCacheKey(page.cacheKey)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}
