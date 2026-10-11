package sk.mirecky.skener.ui.document

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sk.mirecky.skener.R
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.ui.common.ZoomablePageImage

/** Strana na celú obrazovku: približovanie, prechod medzi stranami, filter, otočenie, zmazanie. */
@Composable
fun PageViewer(
    pages: List<PageItem>,
    initialIndex: Int,
    onClose: () -> Unit,
    onRotate: (PageItem) -> Unit,
    onFilter: (PageItem) -> Unit,
    onDelete: (PageItem) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { pages.size }
    val current = pagerState.currentPage.coerceIn(pages.indices)

    // Po zmazaní poslednej strany musí pager ukázať platnú stranu.
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage > pages.lastIndex) pagerState.scrollToPage(pages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, stringResource(R.string.action_close), tint = Color.White)
            }
            Text(
                stringResource(R.string.page_position, current + 1, pages.size),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }

        HorizontalPager(
            state = pagerState,
            key = { pages[it].cacheKey },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { index ->
            ZoomablePageImage(
                pages[index],
                contentDescription = stringResource(R.string.page_position, index + 1, pages.size),
                modifier = Modifier.fillMaxSize(),
            )
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ViewerAction(Icons.Outlined.AutoFixHigh, R.string.action_filter) { onFilter(pages[current]) }
            ViewerAction(Icons.Outlined.RotateRight, R.string.action_rotate) { onRotate(pages[current]) }
            ViewerAction(Icons.Outlined.Delete, R.string.action_delete) { onDelete(pages[current]) }
        }
    }
}

@Composable
private fun ViewerAction(icon: ImageVector, label: Int, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = null, tint = Color.White) }
        Text(stringResource(label), style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}
