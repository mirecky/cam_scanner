package sk.mirecky.skener.ui.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sk.mirecky.skener.R
import sk.mirecky.skener.data.Page

@Composable
fun PagesScreen(onBack: () -> Unit, viewModel: PagesViewModel = viewModel()) {
    val pages by viewModel.pages.collectAsStateWithLifecycle()
    var viewerIndex by rememberSaveable { mutableStateOf<Int?>(null) }

    val index = viewerIndex
    if (index != null && pages.isNotEmpty()) {
        BackHandler { viewerIndex = null }
        PageViewer(
            pages = pages,
            initialIndex = index.coerceIn(pages.indices),
            onClose = { viewerIndex = null },
            onRotate = viewModel::rotate,
            onDelete = viewModel::delete,
            onMove = viewModel::move,
        )
    } else {
        BackHandler(onBack = onBack)
        PageGrid(
            pages = pages,
            onBack = onBack,
            onOpen = { viewerIndex = it },
            onClear = viewModel::clear,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageGrid(
    pages: List<Page>,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onClear: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pluralStringResource(R.plurals.page_count, pages.size, pages.size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (pages.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.action_clear_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (pages.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.pages_empty), style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(16.dp))
                Button(onClick = onBack) { Text(stringResource(R.string.action_back_to_camera)) }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
            ) {
                itemsIndexed(pages, key = { _, page -> page.cacheKey }) { index, page ->
                    PageTile(page, number = index + 1, onClick = { onOpen(index) })
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_title)) },
            text = { Text(stringResource(R.string.clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun PageTile(page: Page, number: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(3f / 4f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        PageImage(page, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Surface(
            shape = RoundedCornerShape(topEnd = 8.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.align(Alignment.BottomStart),
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun PageViewer(
    pages: List<Page>,
    initialIndex: Int,
    onClose: () -> Unit,
    onRotate: (Page) -> Unit,
    onDelete: (Page) -> Unit,
    onMove: (index: Int, offset: Int) -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialIndex) { pages.size }
    val scope = rememberCoroutineScope()
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
            PageImage(
                pages[index],
                contentDescription = stringResource(R.string.page_position, index + 1, pages.size),
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            ViewerAction(Icons.Filled.ChevronLeft, R.string.action_move_left, enabled = current > 0) {
                onMove(current, -1)
                scope.launch { pagerState.scrollToPage(current - 1) }
            }
            ViewerAction(Icons.Outlined.RotateRight, R.string.action_rotate) { onRotate(pages[current]) }
            ViewerAction(Icons.Outlined.Delete, R.string.action_delete) { onDelete(pages[current]) }
            ViewerAction(Icons.Filled.ChevronRight, R.string.action_move_right, enabled = current < pages.lastIndex) {
                onMove(current, 1)
                scope.launch { pagerState.scrollToPage(current + 1) }
            }
        }
    }
}

@Composable
private fun ViewerAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = null, tint = if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
        }
        Text(
            stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
        )
    }
}
