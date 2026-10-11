package sk.mirecky.skener.ui.document

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import sk.mirecky.skener.R
import sk.mirecky.skener.data.PageItem
import sk.mirecky.skener.scanner.Filters
import sk.mirecky.skener.scanner.PageFilter
import sk.mirecky.skener.ui.common.ZoomablePageImage

/**
 * Strana na celú obrazovku: približovanie, prechod medzi stranami a úprava filtra.
 *
 * Dole je rad filtrov s náhľadmi a ovládanie parametra (−/+). Zmena sa najprv ukáže
 * na zmenšenom náhľade; po krátkej nečinnosti sa strana prepočíta v plnom rozlíšení.
 * [applyTargets] sú strany, na ktoré sa dá filter použiť naraz (vybrané strany; null = všetky).
 */
@Composable
fun PageViewer(
    pages: List<PageItem>,
    initialIndex: Int,
    applyTargets: Set<String>?,
    onClose: () -> Unit,
    onRotate: (PageItem) -> Unit,
    onDelete: (PageItem) -> Unit,
    onApplyFilter: (pageIds: Set<String>, filter: PageFilter, level: Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { pages.size }
    val current = pagerState.currentPage.coerceIn(pages.indices)
    val page = pages[current]

    LaunchedEffect(pages.size) {
        if (pagerState.currentPage > pages.lastIndex) pagerState.scrollToPage(pages.lastIndex)
    }

    // Úprava filtra aktuálnej strany.
    var edit by remember(page.id) { mutableStateOf(page.filter to page.level) }
    val preview = remember(page.id, page.version) { FilterPreview(page) }
    DisposableEffect(preview) { onDispose { preview.release() } }
    var thumbnails by remember(page.id) { mutableStateOf<Map<PageFilter, ImageBitmap>>(emptyMap()) }
    var previewImage by remember(page.id) { mutableStateOf<ImageBitmap?>(null) }
    val committed = page.filter to page.level

    LaunchedEffect(preview) {
        preview.load()
        if (thumbnails.isEmpty()) thumbnails = preview.thumbnails()
    }
    LaunchedEffect(preview, edit) {
        if (edit == committed) {
            previewImage = null
            return@LaunchedEffect
        }
        previewImage = preview.render(edit.first, edit.second)
        // Po chvíli bez ďalšej zmeny sa strana prepočíta v plnom rozlíšení.
        delay(COMMIT_DELAY_MS)
        onApplyFilter(setOf(page.id), edit.first, edit.second)
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
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRotate(page) }) {
                Icon(Icons.Outlined.RotateRight, stringResource(R.string.action_rotate), tint = Color.White)
            }
            IconButton(onClick = { onDelete(page) }) {
                Icon(Icons.Outlined.Delete, stringResource(R.string.action_delete), tint = Color.White)
            }
        }

        HorizontalPager(
            state = pagerState,
            key = { pages[it].id },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { index ->
            val image = previewImage
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (index == current && image != null) {
                    Image(image, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    ZoomablePageImage(
                        pages[index],
                        contentDescription = stringResource(R.string.page_position, index + 1, pages.size),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        FilterStrip(
            thumbnails = thumbnails,
            selected = edit.first,
            onSelect = { f -> edit = f to (if (f == committed.first) committed.second else 0) },
        )
        LevelControl(
            filter = edit.first,
            level = edit.second,
            busy = edit != committed,
            onLevel = { edit = edit.first to it.coerceIn(Filters.LEVELS) },
            applyLabel = if (applyTargets != null) {
                stringResource(R.string.apply_to_selected, applyTargets.size)
            } else {
                stringResource(R.string.apply_to_all)
            },
            onApplyToAll = { onApplyFilter(applyTargets ?: pages.map { it.id }.toSet(), edit.first, edit.second) },
        )
    }
}

@Composable
private fun FilterStrip(thumbnails: Map<PageFilter, ImageBitmap>, selected: PageFilter, onSelect: (PageFilter) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        for (filter in PageFilter.entries) {
            val isSelected = filter == selected
            Column(
                Modifier.width(68.dp).clickable { onSelect(filter) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .size(width = 60.dp, height = 80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.DarkGray)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    val thumb = thumbnails[filter]
                    if (thumb != null) {
                        Image(thumb, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    } else {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(filter.label()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun LevelControl(
    filter: PageFilter,
    level: Int,
    busy: Boolean,
    onLevel: (Int) -> Unit,
    applyLabel: String,
    onApplyToAll: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { onLevel(level - 1) }, enabled = level > Filters.LEVELS.first) {
            Icon(Icons.Filled.Remove, stringResource(R.string.action_less))
        }
        Column(Modifier.weight(1f).clickable { onLevel(0) }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(filter.parameterLabel()), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (level > 0) "+$level" else level.toString(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                if (busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(Modifier.size(14.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }
        }
        FilledTonalIconButton(onClick = { onLevel(level + 1) }, enabled = level < Filters.LEVELS.last) {
            Icon(Icons.Filled.Add, stringResource(R.string.action_more))
        }
    }
    TextButton(onClick = onApplyToAll, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        Icon(Icons.Outlined.DoneAll, null)
        Text("  $applyLabel")
    }
}

/** Oneskorenie prepočtu strany v plnom rozlíšení po poslednej zmene filtra. */
private const val COMMIT_DELAY_MS = 700L

internal fun PageFilter.label() = when (this) {
    PageFilter.BLACK_WHITE -> R.string.filter_bw
    PageFilter.GRAYSCALE -> R.string.filter_gray
    PageFilter.COLOR -> R.string.filter_color
    PageFilter.WHITEBOARD -> R.string.filter_whiteboard
    PageFilter.ORIGINAL -> R.string.filter_original
}

private fun PageFilter.parameterLabel() = when (this) {
    PageFilter.BLACK_WHITE -> R.string.param_ink
    PageFilter.GRAYSCALE -> R.string.param_contrast
    PageFilter.COLOR, PageFilter.WHITEBOARD -> R.string.param_saturation
    PageFilter.ORIGINAL -> R.string.param_brightness
}
