package sk.mirecky.skener.ui.pages

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
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

/**
 * Obrázok strany s približovaním dvoma prstami, posúvaním a dvojitým ťuknutím.
 * Kým nie je priblížený, potiahnutie prstom prepína strany v nadradenom HorizontalPager.
 */
@Composable
fun ZoomablePageImage(page: Page, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    ZoomableAsyncImage(
        model = ImageRequest.Builder(context)
            .data(page.file)
            .memoryCacheKey(page.cacheKey)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
