package sk.mirecky.skener.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import sk.mirecky.skener.data.PageItem

/** Náhľad strany; Coil zmenšuje obrázok na veľkosť zobrazenia. */
@Composable
fun PageImage(
    page: PageItem,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(page.displayFile)
            .memoryCacheKey(page.cacheKey)
            .diskCacheKey(page.cacheKey)
            .build(),
        contentDescription = contentDescription,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/**
 * Strana s približovaním dvoma prstami, posúvaním a dvojitým ťuknutím.
 * Kým nie je priblížená, potiahnutie prstom prepína strany v nadradenom HorizontalPager.
 */
@Composable
fun ZoomablePageImage(page: PageItem, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    ZoomableAsyncImage(
        model = ImageRequest.Builder(context)
            .data(page.displayFile)
            .memoryCacheKey(page.cacheKey)
            .diskCacheKey(page.cacheKey)
            .build(),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
